package ais.tee.data.security

import ais.tee.data.security.ChatGptPlanSignIn.Result
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real loopback sockets, fake OpenAI transport; time-bounded so a listener bug cannot hang CI. */
class ChatGptPlanSignInTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val records = FakeRecords()
    private val vault = ChatGptPlanCredentialStore(records)
    private val seen = mutableListOf<String>()
    private var nonce = ""
    private var subject = "subject-1"
    private var scope = "openid offline_access resource.invoke chatgpt.tokens.use.direct"
    private val exchange = ChatGptPlanTokenExchange(ChatGptPlanTokenExchange.Transport { request ->
        synchronized(seen) { seen += request.method + " " + request.url.encodedPath }
        when (request.url.encodedPath) {
            "/api/accounts/oauth/token" -> buildJsonObject {
                put("access_token", "access-secret")
                put("refresh_token", "refresh-secret")
                put("id_token", signed(nonce, "oaiapp_test", subject))
                put("token_type", "Bearer")
                put("scope", scope)
                put("expires_in", 3600)
            }.toString()
            "/.well-known/jwks.json" -> jwks()
            "/.well-known/openid-configuration" ->
                """{"revocation_endpoint":"https://auth.openai.com/oauth/revoke"}"""
            "/oauth/revoke" -> ""
            else -> throw AssertionError("Unexpected URL")
        }
    })

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test(timeout = 15_000)
    fun browserCallbackSavesVerifiedAccount() {
        val attempt = ChatGptPlanSignIn(vault, exchange).start()!!
        val result = finishAsync(attempt)
        assertTrue(callback(attempt, "code=one-time&client_id=oaiapp_test").startsWith("HTTP/1.1 200"))
        assertEquals(Result.CONNECTED, result.get(10, TimeUnit.SECONDS))
        val account = vault.accounts().single()
        assertEquals("oaiapp_test", account.clientId)
        assertTrue(account.connected && account.planUsageGranted)
        assertPortClosed(attempt)
    }

    @Test(timeout = 15_000)
    fun identityOnlyGrantConnectsWithoutPlanUsage() {
        scope = "openid offline_access resource.invoke"
        val attempt = ChatGptPlanSignIn(vault, exchange).start()!!
        val result = finishAsync(attempt)
        callback(attempt, "code=one-time&client_id=oaiapp_test")
        assertEquals(Result.CONNECTED_WITHOUT_PLAN, result.get(10, TimeUnit.SECONDS))
        assertFalse(vault.accounts().single().planUsageGranted)
    }

    @Test(timeout = 15_000)
    fun deniedConsentIsCancelledAndSavesNothing() {
        val attempt = ChatGptPlanSignIn(vault, exchange).start()!!
        val result = finishAsync(attempt)
        callback(attempt, "error=access_denied")
        assertEquals(Result.CANCELLED, result.get(10, TimeUnit.SECONDS))
        assertTrue(vault.accounts().isEmpty())
        assertTrue(seen.isEmpty())
    }

    @Test(timeout = 15_000)
    fun closeBeforeFinishFreesPortAndFinishThrows() {
        val attempt = ChatGptPlanSignIn(vault, exchange).start()!!
        attempt.close()
        assertPortClosed(attempt)
        assertThrows(IOException::class.java) { attempt.finish(1_000) }
    }

    @Test(timeout = 15_000)
    fun reauthorizationRejectsDifferentIdentity() {
        val first = ChatGptPlanSignIn(vault, exchange).start()!!
        val firstResult = finishAsync(first)
        callback(first, "code=one-time&client_id=oaiapp_test")
        assertEquals(Result.CONNECTED, firstResult.get(10, TimeUnit.SECONDS))
        vault.disconnectLocally("oaiapp_test")

        subject = "someone-else"
        val again = ChatGptPlanSignIn(vault, exchange).start(vault.accounts().single())!!
        assertFalse(again.authorizationUrl.contains("agent_name_hint"))
        val result = finishAsync(again)
        callback(again, "code=one-time")
        assertEquals(Result.FAILED, result.get(10, TimeUnit.SECONDS))
        assertFalse(vault.accounts().single().connected)
        assertTrue(seen.contains("POST /oauth/revoke"))
    }

    @Test(timeout = 15_000)
    fun unsavedGrantIsRevoked() {
        vault.hostId()
        records.failWrites = true
        val attempt = ChatGptPlanSignIn(vault, exchange).start()!!
        val result = finishAsync(attempt)
        callback(attempt, "code=one-time&client_id=oaiapp_test")
        assertEquals(Result.FAILED, result.get(10, TimeUnit.SECONDS))
        assertTrue(seen.contains("POST /oauth/revoke"))
    }

    @Test
    fun fullVaultOpensNoListener() {
        var opened = 0
        val signIn = ChatGptPlanSignIn(vault, exchange) {
            opened++
            ChatGptPlanLoopback.open()
        }
        repeat(ChatGptPlanCredentialStore.MAX_ACCOUNTS) { fillAccount("oaiapp_$it", "subject-$it") }
        assertEquals(ChatGptPlanCredentialStore.MAX_ACCOUNTS, vault.accounts().size)
        assertNull(signIn.start())
        assertEquals(0, opened)
    }

    private fun fillAccount(clientId: String, subject: String) {
        val pending = ChatGptPlanOAuth.prepare(vault.hostId(), 1455)
        val grantExchange = ChatGptPlanTokenExchange(ChatGptPlanTokenExchange.Transport { request ->
            when (request.url.encodedPath) {
                "/api/accounts/oauth/token" -> buildJsonObject {
                    put("access_token", "a")
                    put("refresh_token", "r")
                    put("id_token", signed(pending.nonce, clientId, subject))
                    put("token_type", "Bearer")
                    put("scope", scope)
                    put("expires_in", 3600)
                }.toString()
                else -> jwks()
            }
        })
        val url = pending.redirectUri + "?state=" + state(pending.authorizationUrl) +
            "&code=c&client_id=" + clientId
        assertTrue(vault.saveVerified(grantExchange.complete(pending, url, nowEpochSeconds = now())))
    }

    private fun finishAsync(attempt: ChatGptPlanSignIn.Attempt): Future<Result> {
        nonce = nonceOf(attempt.authorizationUrl)
        return executor.submit(Callable { attempt.finish(10_000) })
    }

    private fun callback(attempt: ChatGptPlanSignIn.Attempt, query: String): String {
        val port = URI(redirectUri(attempt.authorizationUrl)).port
        val target = "/auth/callback?state=" + state(attempt.authorizationUrl) + "&" + query
        return Socket(LOCALHOST, port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            String(socket.getInputStream().readBytes(), StandardCharsets.UTF_8)
        }
    }

    private fun assertPortClosed(attempt: ChatGptPlanSignIn.Attempt) {
        val port = URI(redirectUri(attempt.authorizationUrl)).port
        assertThrows(IOException::class.java) { Socket(LOCALHOST, port).close() }
    }

    private fun query(url: String, name: String): String =
        URI(url).rawQuery.split("&").map { it.split("=", limit = 2) }
            .first { it[0] == name }.let { URLDecoder.decode(it[1], "UTF-8") }

    private fun state(url: String) = query(url, "state")
    private fun nonceOf(url: String) = query(url, "nonce")
    private fun redirectUri(url: String) = query(url, "redirect_uri")
    private fun now() = System.currentTimeMillis() / 1000

    private fun jwks(): String {
        val publicKey = keyPair.public as RSAPublicKey
        return buildJsonObject {
            put("keys", JsonArray(listOf(buildJsonObject {
                put("kid", "test-key")
                put("kty", "RSA")
                put("use", "sig")
                put("alg", "RS256")
                put("n", b64(unsigned(publicKey.modulus)))
                put("e", b64(unsigned(publicKey.publicExponent)))
            })))
        }.toString()
    }

    private fun signed(nonce: String, clientId: String, subject: String): String {
        val header = buildJsonObject { put("kid", "test-key"); put("alg", "RS256") }
        val claims = buildJsonObject {
            put("iss", "https://auth.openai.com")
            put("aud", clientId)
            put("sub", subject)
            put("nonce", nonce)
            put("iat", now() - 10)
            put("exp", now() + 3600)
        }
        val input = b64(header.toString().toByteArray(StandardCharsets.UTF_8)) + "." +
            b64(claims.toString().toByteArray(StandardCharsets.UTF_8))
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(input.toByteArray(StandardCharsets.US_ASCII))
        return input + "." + b64(signer.sign())
    }

    private fun unsigned(number: BigInteger): ByteArray =
        number.toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()

    private fun b64(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private class FakeRecords : ChatGptPlanCredentialStore.RecordStorage {
        var text: String? = null
        var failWrites = false
        override fun read(): String? = text
        override fun hasStoredData(): Boolean = text != null
        override fun write(value: String): Boolean {
            if (failWrites) return false
            text = value
            return true
        }
    }

    private companion object {
        val LOCALHOST: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}

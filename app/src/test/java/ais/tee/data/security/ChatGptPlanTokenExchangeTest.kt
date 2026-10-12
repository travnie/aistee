package ais.tee.data.security

import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptPlanTokenExchangeTest {
    private val now = 1_800_000_000L
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val jwks = buildJsonObject {
        put("keys", JsonArray(listOf(buildJsonObject {
            put("kid", "test-key")
            put("kty", "RSA")
            put("use", "sig")
            put("alg", "RS256")
            put("n", b64(unsigned(publicKey.modulus)))
            put("e", b64(unsigned(publicKey.publicExponent)))
        })))
    }.toString()

    @Test
    fun exchangesCallbackAndVerifiesSignedIdentity() {
        val pending = pending()
        val called = mutableListOf<String>()
        val exchange = exchange(pending, called)
        val callback = validCallback(pending)
        val grant = exchange.complete(pending, callback, nowEpochSeconds = now)
        assertEquals("subject-1", grant.identity.subject)
        assertEquals(now + 3600, grant.expiresAtEpochSeconds)
        assertTrue(grant.planUsageGranted)
        assertEquals(listOf(
            "https://auth.openai.com/api/accounts/oauth/token",
            "https://auth.openai.com/.well-known/jwks.json",
        ), called)
        assertFalse(grant.toString().contains("access-secret"))
        assertThrows(Exception::class.java) { exchange.complete(pending, callback, nowEpochSeconds = now) }
        assertEquals(2, called.size) // replay does not call the network
    }

    @Test
    fun identityOnlyScopesNeverEnablePlanUsage() {
        val pending = pending()
        val grant = exchange(pending, mutableListOf(), "openid offline_access resource.invoke")
            .complete(pending, validCallback(pending), nowEpochSeconds = now)
        assertFalse(grant.planUsageGranted)
    }

    @Test
    fun directScopeAloneGrantsPlanUsageWithReducedScopeResponse() {
        val pending = pending()
        val grant = exchange(pending, mutableListOf(), "openid offline_access chatgpt.tokens.use.direct")
            .complete(pending, validCallback(pending), nowEpochSeconds = now)
        assertTrue(grant.planUsageGranted)
    }

    @Test
    fun rejectsDifferentAccountAndUnverifiableNonce() {
        val first = pending()
        assertThrows(Exception::class.java) {
            exchange(first, mutableListOf()).complete(
                first, validCallback(first), expectedSubject = "not-this-account", nowEpochSeconds = now)
        }
        val second = pending()
        assertThrows(Exception::class.java) {
            exchange(second, mutableListOf(), wrongNonce = true)
                .complete(second, validCallback(second), nowEpochSeconds = now)
        }
    }

    @Test
    fun alteredCallbackMakesNoNetworkRequestButDoesNotConsumeAttempt() {
        val pending = pending()
        val called = mutableListOf<String>()
        val exchange = exchange(pending, called)
        assertThrows(Exception::class.java) {
            exchange.complete(pending, pending.redirectUri + "?state=wrong&code=secret", nowEpochSeconds = now)
        }
        assertTrue(called.isEmpty())
        assertEquals("subject-1", exchange.complete(pending, validCallback(pending), nowEpochSeconds = now).identity.subject)
    }

    @Test
    fun verifiedGrantsSaveAndReplaceOnlyTheirOwnAccount() {
        val record = FakeVaultRecord()
        val vault = ChatGptPlanCredentialStore(record)
        val host = vault.hostId()
        assertTrue(host.startsWith("urn:uuid:"))
        fun grant(clientId: String, subject: String, suffix: String): ChatGptPlanTokenExchange.Grant {
            val pending = pending()
            return exchange(
                pending, mutableListOf(), clientId = clientId, subject = subject,
                tokenSuffix = suffix,
            ).complete(pending, validCallback(pending, clientId), nowEpochSeconds = now)
        }

        val first = grant("oaiapp_one", "subject-one", "-1")
        val second = grant("oaiapp_two", "subject-two", "-2")
        assertTrue(vault.saveVerified(first))
        assertTrue(vault.saveVerified(second))
        assertEquals(2, vault.accounts().size)
        assertEquals("subject-one", vault.accounts().first { it.clientId == "oaiapp_one" }.subject)
        assertEquals("subject-two", vault.accounts().first { it.clientId == "oaiapp_two" }.subject)
        assertEquals("access-secret-1", vault.credentials("oaiapp_one")!!.accessToken)
        assertEquals("refresh-secret-1", vault.credentials("oaiapp_one")!!.refreshToken)
        assertEquals(first.idToken, vault.credentials("oaiapp_one")!!.idToken)
        assertEquals(first.scopes, vault.credentials("oaiapp_one")!!.scopes)
        assertEquals(first.expiresAtEpochSeconds, vault.credentials("oaiapp_one")!!.expiresAt)
        assertTrue(vault.accounts().first { it.clientId == "oaiapp_one" }.planUsageGranted)

        assertTrue(vault.saveVerified(grant("oaiapp_one", "subject-one", "-new")))
        assertEquals("access-secret-new", vault.credentials("oaiapp_one")!!.accessToken)
        assertEquals("refresh-secret-new", vault.credentials("oaiapp_one")!!.refreshToken)
        assertEquals("access-secret-2", vault.credentials("oaiapp_two")!!.accessToken)
        val unchanged = record.text
        assertFalse(vault.saveVerified(grant("oaiapp_one", "impostor", "-wrong")))
        assertEquals(unchanged, record.text)
        assertEquals("subject-one", vault.accounts().first { it.clientId == "oaiapp_one" }.subject)
        assertEquals(2, ChatGptPlanCredentialStore(record).accounts().size)
    }

    @Test
    fun credentialVaultRefusesThirteenthAccountWithoutMutatingExistingRecord() {
        val record = FakeVaultRecord()
        val vault = ChatGptPlanCredentialStore(record)
        vault.hostId()
        for (number in 1..12) {
            val pending = pending()
            val clientId = "oaiapp_$number"
            val grant = exchange(pending, mutableListOf(), clientId = clientId, subject = "subject-$number")
                .complete(pending, validCallback(pending, clientId), nowEpochSeconds = now)
            assertTrue(vault.saveVerified(grant))
        }
        val snapshot = record.text
        val extra = pending()
        val thirteenth = exchange(extra, mutableListOf(), clientId = "oaiapp_13", subject = "subject-13")
            .complete(extra, validCallback(extra, "oaiapp_13"), nowEpochSeconds = now)
        assertFalse(vault.saveVerified(thirteenth))
        assertEquals(snapshot, record.text)
        assertEquals(12, vault.accounts().size)
    }

    private class FakeVaultRecord : ChatGptPlanCredentialStore.RecordStorage {
        var text: String? = null
        override fun read(): String? = text
        override fun hasStoredData(): Boolean = text != null
        override fun write(value: String): Boolean {
            text = value
            return true
        }
    }

    private fun pending() = ChatGptPlanOAuth.prepare(
        hostId = "urn:uuid:fd74b2d9-fab2-4f33-9d53-ac90b7bc9b46", port = 1455)

    private fun validCallback(
        pending: ChatGptPlanOAuth.Pending,
        clientId: String = "oaiapp_test",
    ): String {
        val state = URI(pending.authorizationUrl).rawQuery.split("&")
            .map { it.split("=", limit = 2) }
            .first { URLDecoder.decode(it[0], "UTF-8") == "state" }
            .let { URLDecoder.decode(it[1], "UTF-8") }
        return pending.redirectUri + "?state=" + state + "&code=one-time-code&client_id=" + clientId
    }

    private fun exchange(
        pending: ChatGptPlanOAuth.Pending,
        called: MutableList<String>,
        scope: String = "openid offline_access resource.invoke chatgpt.tokens.use.direct",
        wrongNonce: Boolean = false,
        clientId: String = "oaiapp_test",
        subject: String = "subject-1",
        tokenSuffix: String = "",
    ): ChatGptPlanTokenExchange {
        return ChatGptPlanTokenExchange(ChatGptPlanTokenExchange.Transport { request: Request ->
            called += request.url.toString()
            when (request.url.encodedPath) {
                "/api/accounts/oauth/token" -> {
                    assertEquals("POST", request.method)
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertTrue(body.contains("code_verifier="))
                    assertTrue(body.contains("client_id=" + clientId))
                    assertTrue(body.contains("grant_type=authorization_code"))
                    buildJsonObject {
                        put("access_token", "access-secret" + tokenSuffix)
                        put("refresh_token", "refresh-secret" + tokenSuffix)
                        put("id_token", signed(if (wrongNonce) "different" else pending.nonce, clientId, subject))
                        put("token_type", "Bearer")
                        put("scope", scope)
                        put("expires_in", 3600)
                    }.toString()
                }
                "/.well-known/jwks.json" -> jwks
                else -> throw AssertionError("Unexpected URL")
            }
        })
    }

    private fun signed(
        nonce: String,
        clientId: String = "oaiapp_test",
        subject: String = "subject-1",
    ): String {
        val header = buildJsonObject { put("kid", "test-key"); put("alg", "RS256") }
        val claims = buildJsonObject {
            put("iss", "https://auth.openai.com")
            put("aud", clientId)
            put("sub", subject)
            put("nonce", nonce)
            put("iat", now - 10)
            put("exp", now + 3600)
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
}

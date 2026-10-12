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

    private fun pending() = ChatGptPlanOAuth.prepare(
        hostId = "urn:uuid:fd74b2d9-fab2-4f33-9d53-ac90b7bc9b46", port = 1455)

    private fun validCallback(pending: ChatGptPlanOAuth.Pending): String {
        val state = URI(pending.authorizationUrl).rawQuery.split("&")
            .map { it.split("=", limit = 2) }
            .first { URLDecoder.decode(it[0], "UTF-8") == "state" }
            .let { URLDecoder.decode(it[1], "UTF-8") }
        return pending.redirectUri + "?state=" + state + "&code=one-time-code&client_id=oaiapp_test"
    }

    private fun exchange(
        pending: ChatGptPlanOAuth.Pending,
        called: MutableList<String>,
        scope: String = "openid offline_access resource.invoke chatgpt.tokens.use.direct",
        wrongNonce: Boolean = false,
    ): ChatGptPlanTokenExchange {
        return ChatGptPlanTokenExchange(ChatGptPlanTokenExchange.Transport { request: Request ->
            called += request.url.toString()
            when (request.url.encodedPath) {
                "/api/accounts/oauth/token" -> {
                    assertEquals("POST", request.method)
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertTrue(body.contains("code_verifier="))
                    assertTrue(body.contains("client_id=oaiapp_test"))
                    assertTrue(body.contains("grant_type=authorization_code"))
                    buildJsonObject {
                        put("access_token", "access-secret")
                        put("refresh_token", "refresh-secret")
                        put("id_token", signed(if (wrongNonce) "different" else pending.nonce))
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

    private fun signed(nonce: String): String {
        val header = buildJsonObject { put("kid", "test-key"); put("alg", "RS256") }
        val claims = buildJsonObject {
            put("iss", "https://auth.openai.com")
            put("aud", "oaiapp_test")
            put("sub", "subject-1")
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

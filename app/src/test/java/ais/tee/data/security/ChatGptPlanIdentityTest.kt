package ais.tee.data.security

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests: crypto fixtures must not depend on Robolectric interceptors. */
class ChatGptPlanIdentityTest {
    private val now = 1_800_000_000L
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = pair.public as RSAPublicKey
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
    fun acceptsSignedTokenWithExpectedClaims() {
        val checked = ChatGptPlanIdentity.verify(signed(claims()), jwks, "oaiapp_test", "nonce-value", now)
        assertEquals("account-a", checked.subject)
        assertEquals("person@example.org", checked.email)
        assertEquals("oaiapp_test", checked.clientId)
        assertFalse(checked.toString().contains("account-a"))
    }

    @Test
    fun acceptsSingleElementArrayAudienceWithoutAzp() {
        val claims = replaceClaim(claims(), "aud", JsonArray(listOf(JsonPrimitive("oaiapp_test"))))
        val checked = ChatGptPlanIdentity.verify(signed(claims), jwks, "oaiapp_test", "nonce-value", now)
        assertEquals("account-a", checked.subject)
    }

    @Test
    fun rejectsWrongNonceAudienceIssuerAndExpiredToken() {
        val token = signed(claims())
        assertTrue(runCatching { ChatGptPlanIdentity.verify(token, jwks, "oaiapp_test", "other", now) }.isFailure)
        assertTrue(runCatching { ChatGptPlanIdentity.verify(token, jwks, "oaiapp_other", "nonce-value", now) }.isFailure)
        assertTrue(runCatching {
            ChatGptPlanIdentity.verify(signed(replaceClaim(claims(), "iss", JsonPrimitive("https://evil.example"))), jwks, "oaiapp_test", "nonce-value", now)
        }.isFailure)
        assertTrue(runCatching {
            ChatGptPlanIdentity.verify(signed(replaceClaim(claims(), "exp", JsonPrimitive(now - 6))), jwks, "oaiapp_test", "nonce-value", now)
        }.isFailure)
    }

    @Test
    fun rejectsTamperedSignatureAndForgedSigningKeyUrl() {
        val valid = signed(claims())
        val tampered = valid.dropLast(2) + if (valid.last() == 'A') "BB" else "AA"
        assertTrue(runCatching { ChatGptPlanIdentity.verify(tampered, jwks, "oaiapp_test", "nonce-value", now) }.isFailure)
        val maliciousHeader = buildJsonObject {
            put("alg", "RS256")
            put("kid", "test-key")
            put("jku", "https://evil.example/keys")
        }
        assertTrue(runCatching {
            ChatGptPlanIdentity.verify(signed(claims(), maliciousHeader), jwks, "oaiapp_test", "nonce-value", now)
        }.isFailure)
        assertTrue(runCatching {
            ChatGptPlanIdentity.verify(signed(claims(), buildJsonObject {
                put("alg", "none")
                put("kid", "test-key")
            }), jwks, "oaiapp_test", "nonce-value", now)
        }.isFailure)
    }

    @Test
    fun rejectsMissingKeyAndFutureIssuedAt() {
        val token = signed(claims())
        assertTrue(runCatching { ChatGptPlanIdentity.verify(token, """{"keys":[]}""", "oaiapp_test", "nonce-value", now) }.isFailure)
        assertTrue(runCatching {
            ChatGptPlanIdentity.verify(signed(replaceClaim(claims(), "iat", JsonPrimitive(now + 10))), jwks, "oaiapp_test", "nonce-value", now)
        }.isFailure)
    }

    private fun claims() = buildJsonObject {
        put("iss", "https://auth.openai.com")
        put("aud", "oaiapp_test")
        put("sub", "account-a")
        put("email", "person@example.org")
        put("nonce", "nonce-value")
        put("iat", now - 10)
        put("exp", now + 100)
    }

    private fun replaceClaim(input: JsonObject, name: String, value: kotlinx.serialization.json.JsonElement) =
        JsonObject(input.toMutableMap().also { it[name] = value })

    private fun signed(
        payload: JsonObject,
        header: JsonObject = buildJsonObject {
            put("alg", "RS256")
            put("kid", "test-key")
        },
    ): String {
        val input = b64(header.toString().toByteArray(StandardCharsets.UTF_8)) + "." +
            b64(payload.toString().toByteArray(StandardCharsets.UTF_8))
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(pair.private)
        signer.update(input.toByteArray(StandardCharsets.US_ASCII))
        return input + "." + b64(signer.sign())
    }

    private fun unsigned(value: BigInteger): ByteArray =
        value.toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()

    private fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

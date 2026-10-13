package ais.tee.data.security

import java.io.IOException
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests use an in-memory record; production encrypts using Android Keystore. */
class ChatGptPlanCredentialStoreTest {
    private class Fake : ChatGptPlanCredentialStore.RecordStorage {
        var text: String? = null
        var unreadable = false
        var refuseWrites = false
        override fun read(): String? = if (unreadable) null else text
        override fun hasStoredData() = text != null || unreadable
        override fun write(value: String): Boolean {
            if (refuseWrites) return false
            text = value
            return true
        }
    }

    @Test
    fun persistsOpaqueHostIdAcrossInstances() {
        val data = Fake()
        val one = ChatGptPlanCredentialStore(data)
        val host = one.hostId()
        assertTrue(host.startsWith("urn:uuid:"))
        assertEquals(host, ChatGptPlanCredentialStore(data).hostId())
        assertTrue(one.accounts().isEmpty())
    }

    @Test
    fun failsClosedWhenKeystoreIsUnavailable() {
        val data = Fake()
        val vault = ChatGptPlanCredentialStore(data)
        val host = vault.hostId()
        data.unreadable = true
        assertThrows(IOException::class.java) { vault.accounts() }
        assertThrows(IOException::class.java) { vault.hostId() }
        data.unreadable = false
        assertEquals(host, vault.hostId())
    }

    @Test
    fun neverClaimsHostIdWasSavedIfEncryptionFailed() {
        val data = Fake().apply { refuseWrites = true }
        assertThrows(IOException::class.java) { ChatGptPlanCredentialStore(data).hostId() }
        assertNull(data.text)
    }

    @Test
    fun localDisconnectOnlyClearsSelectedTokensNotRegistrationOrAnotherAccount() {
        val data = Fake()
        val vault = ChatGptPlanCredentialStore(data)
        val host = vault.hostId()
        data.text = """
            {"version":1,"host":"$host","accounts":[
            {"client_id":"oaiapp_one","sub":"subject-one","email":"one@example.org",
             "tokens":{"id_token":"id1","access_token":"access1","refresh_token":"refresh1",
             "scopes":["chatgpt.tokens.use.direct"],"expires_at":1800000000}},
            {"client_id":"oaiapp_two","sub":"subject-two",
             "tokens":{"id_token":"id2","access_token":"access2","refresh_token":"refresh2",
             "scopes":["openid"],"expires_at":1800000000}}
            ]}
        """.trimIndent()
        assertEquals(2, vault.accounts().size)
        assertTrue(vault.accounts()[0].planUsageGranted)
        assertFalse(vault.accounts()[1].planUsageGranted)
        assertFalse(vault.accounts()[0].toString().contains("subject-one"))
        assertTrue(vault.disconnectLocally("oaiapp_one"))
        assertEquals(host, vault.hostId())
        assertNull(vault.credentials("oaiapp_one"))
        assertEquals("subject-one", vault.accounts()[0].subject)
        assertFalse(vault.accounts()[0].connected)
        assertTrue(vault.accounts()[1].connected)
        assertEquals("refresh2", vault.credentials("oaiapp_two")!!.refreshToken)
        assertFalse(data.text!!.contains("access1"))
        assertTrue(data.text!!.contains("access2"))
    }

    @Test
    fun malformedOrDuplicateRecordsCannotBeLoadedOrOverwritten() {
        val data = Fake()
        val vault = ChatGptPlanCredentialStore(data)
        val host = vault.hostId()
        data.text = """{"version":1,"host":"$host","accounts":[{"client_id":"a","sub":"x"},{"client_id":"a","sub":"y"}]}"""
        assertThrows(IOException::class.java) { vault.accounts() }
        assertThrows(IOException::class.java) { vault.hostId() }
        data.text = """{"version":1,"host":"$host","accounts":[{"client_id":"a","sub":"x","tokens":{"access_token":"incomplete"}}]}"""
        assertThrows(IOException::class.java) { vault.accounts() }
    }

    @Test
    fun failedMutationKeepsExistingRecordUnchanged() {
        val data = Fake()
        val vault = ChatGptPlanCredentialStore(data)
        val host = vault.hostId()
        data.text = """{"version":1,"host":"$host","accounts":[{"client_id":"one","sub":"a",
          "tokens":{"id_token":"id","access_token":"access","refresh_token":"refresh",
          "scopes":["openid"],"expires_at":1800000000}}]}"""
        val original = data.text
        data.refuseWrites = true
        assertFalse(vault.disconnectLocally("one"))
        assertEquals(original, data.text)
    }

    @Test
    fun saveVerifiedStoresIdentityAndAllTokenFields() {
        val data = Fake()
        val vault = ChatGptPlanCredentialStore(data)
        vault.hostId()
        assertTrue(vault.saveVerified(grant("oaiapp_one", "subject-one", "1", setOf("chatgpt.tokens.use.direct"))))
        val account = vault.accounts().single()
        assertEquals("oaiapp_one", account.clientId)
        assertEquals("subject-one", account.subject)
        assertEquals("subject-one@example.org", account.email)
        assertTrue(account.connected)
        assertTrue(account.planUsageGranted)
        assertEquals(1_800_000_100L, account.expiresAt)
        val secret = vault.credentials("oaiapp_one")!!
        assertTrue(secret.idToken.count { it == '.' } == 2)
        assertEquals("access1", secret.accessToken)
        assertEquals("refresh1", secret.refreshToken)
        assertEquals(setOf("chatgpt.tokens.use.direct"), secret.scopes)
        assertEquals(1_800_000_100L, secret.expiresAt)
    }

    @Test
    fun saveVerifiedReplacesOnlyMatchingRegistration() {
        val data = Fake()
        val vault = ChatGptPlanCredentialStore(data)
        vault.hostId()
        assertTrue(vault.saveVerified(grant("oaiapp_one", "subject-one", "1")))
        assertTrue(vault.saveVerified(grant("oaiapp_two", "subject-two", "2")))
        assertTrue(vault.saveVerified(grant("oaiapp_one", "subject-one", "3")))
        assertEquals(listOf("oaiapp_two", "oaiapp_one"), vault.accounts().map { it.clientId })
        assertEquals("access3", vault.credentials("oaiapp_one")!!.accessToken)
        assertEquals("access2", vault.credentials("oaiapp_two")!!.accessToken)
        assertFalse(data.text!!.contains("access1"))
    }

    @Test
    fun saveVerifiedRejectsSubjectChangeAndAccountOverflowWithoutWriting() {
        val data = Fake()
        val vault = ChatGptPlanCredentialStore(data)
        vault.hostId()
        assertTrue(vault.saveVerified(grant("oaiapp_one", "subject-one", "1")))
        var original = data.text
        assertFalse(vault.saveVerified(grant("oaiapp_one", "subject-other", "2")))
        assertEquals(original, data.text)
        for (i in 2..12) assertTrue(vault.saveVerified(grant("oaiapp_$i", "subject-$i", "$i")))
        original = data.text
        assertFalse(vault.saveVerified(grant("oaiapp_13", "subject-13", "13")))
        assertEquals(original, data.text)
        assertEquals(12, vault.accounts().size)
    }

    @Test
    fun saveVerifiedRequiresPersistedHostId() {
        val vault = ChatGptPlanCredentialStore(Fake())
        assertThrows(IllegalArgumentException::class.java) {
            vault.saveVerified(grant("oaiapp_one", "subject-one", "1"))
        }
    }

    private fun grant(
        clientId: String,
        subject: String,
        suffix: String,
        scopes: Set<String> = setOf("openid"),
    ): ChatGptPlanTokenExchange.Grant {
        val payload = buildJsonObject {
            put("iss", "https://auth.openai.com")
            put("aud", clientId)
            put("sub", subject)
            put("email", "$subject@example.org")
            put("nonce", "nonce")
            put("iat", NOW - 10)
            put("exp", NOW + 100)
        }
        val input = b64("""{"alg":"RS256","kid":"test-key"}""".toByteArray(StandardCharsets.UTF_8)) +
            "." + b64(payload.toString().toByteArray(StandardCharsets.UTF_8))
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(keys.private)
            update(input.toByteArray(StandardCharsets.US_ASCII))
        }
        val jwt = input + "." + b64(signer.sign())
        val identity = ChatGptPlanIdentity.Verified.verify(jwt, jwks, clientId, "nonce", NOW)
        return ChatGptPlanTokenExchange.Grant(
            identity, jwt, "access$suffix", "refresh$suffix", scopes, NOW + 100)
    }

    private companion object {
        const val NOW = 1_800_000_000L
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val jwks = (keys.public as RSAPublicKey).let { key ->
            buildJsonObject {
                put("keys", JsonArray(listOf(buildJsonObject {
                    put("kid", "test-key")
                    put("kty", "RSA")
                    put("n", b64(unsigned(key.modulus)))
                    put("e", b64(unsigned(key.publicExponent)))
                })))
            }.toString()
        }

        fun unsigned(value: BigInteger): ByteArray =
            value.toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()

        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

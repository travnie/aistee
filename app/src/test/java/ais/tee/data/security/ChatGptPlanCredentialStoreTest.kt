package ais.tee.data.security

import java.io.IOException
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
}

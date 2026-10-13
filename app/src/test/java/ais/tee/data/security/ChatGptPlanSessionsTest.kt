package ais.tee.data.security

import java.io.IOException
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Refresh protocol, rotation CAS and per-registration coordination; no network. */
class ChatGptPlanSessionsTest {
    private val now = 1_800_000_000L
    private val record = Fake()
    private val vault = ChatGptPlanCredentialStore(record)
    private val requests = mutableListOf<String>()

    @Test
    fun refreshSendsSavedClientIdWithoutScopeOrDynamicClient() {
        val refreshed = exchange(response()).refresh("oaiapp_one", "refresh-old", now)
        val body = requests.single()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("client_id=oaiapp_one"))
        assertTrue(body.contains("refresh_token=refresh-old"))
        assertTrue(body.contains("resource=https%3A%2F%2Fapi.openai.com%2Fv1"))
        assertFalse(body.contains("scope="))
        assertEquals("access-new", refreshed.accessToken)
        assertEquals("refresh-new", refreshed.refreshToken)
        assertNull(refreshed.scopes)
        assertEquals(now + 3600, refreshed.expiresAtEpochSeconds)
        assertFalse(refreshed.toString().contains("access-new"))
        assertThrows(IllegalArgumentException::class.java) {
            exchange(response()).refresh("dynamic_agent_client", "refresh-old", now)
        }
    }

    @Test
    fun unusableTokenCodesRequireReauthorizationButOtherRejectionsDoNot() {
        for (code in listOf("invalid_grant", "refresh_token_reused", "refresh_token_expired")) {
            assertThrows(ChatGptPlanTokenExchange.ReauthorizationRequired::class.java) {
                exchange(rejected(code)).refresh("oaiapp_one", "refresh-old", now)
            }
        }
        val other = assertThrows(IOException::class.java) {
            exchange(rejected("invalid_client")).refresh("oaiapp_one", "refresh-old", now)
        }
        assertFalse(other is ChatGptPlanTokenExchange.ReauthorizationRequired)
        assertThrows(IOException::class.java) {
            exchange("""{"token_type":"Bearer","access_token":"a","expires_in":3600}""")
                .refresh("oaiapp_one", "refresh-old", now)
        }
    }

    @Test
    fun saveRefreshedRotatesTogetherAndRejectsStaleToken() {
        connect(expiresAt = now + 10)
        val refreshed = exchange(response(scope = "openid")).refresh("oaiapp_one", "refresh-old", now)
        assertFalse(vault.saveRefreshed("oaiapp_one", "refresh-other", refreshed))
        assertTrue(vault.saveRefreshed("oaiapp_one", "refresh-old", refreshed))
        val stored = vault.credentials("oaiapp_one")!!
        assertEquals("access-new", stored.accessToken)
        assertEquals("refresh-new", stored.refreshToken)
        assertEquals("id-verified", stored.idToken)
        assertEquals(setOf("openid"), stored.scopes)
        assertEquals("subject-one", vault.accounts().single().subject)
        val snapshot = record.text
        assertFalse(vault.saveRefreshed("oaiapp_one", "refresh-old", refreshed))
        assertEquals(snapshot, record.text)
    }

    @Test
    fun freshCredentialsAreReturnedWithoutNetwork() {
        connect(expiresAt = now + 3600)
        val sessions = ChatGptPlanSessions(vault, exchange(response())) { now }
        assertEquals("access-old", sessions.usable("oaiapp_one")!!.accessToken)
        assertTrue(requests.isEmpty())
        assertNull(sessions.usable("oaiapp_missing"))
    }

    @Test
    fun nearExpiryRefreshesAndKeepsGrantedScopes() {
        connect(expiresAt = now + 60)
        val sessions = ChatGptPlanSessions(vault, exchange(response())) { now }
        val usable = sessions.usable("oaiapp_one")!!
        assertEquals("access-new", usable.accessToken)
        assertEquals(setOf("chatgpt.tokens.use.direct"), usable.scopes)
        assertEquals(1, requests.size)
        assertEquals("access-new", sessions.usable("oaiapp_one")!!.accessToken)
        assertEquals(1, requests.size)
    }

    @Test
    fun unusableTokenClearsSecretsButKeepsRegistration() {
        connect(expiresAt = now - 1)
        val sessions = ChatGptPlanSessions(vault, exchange(rejected("invalid_grant"))) { now }
        assertThrows(ChatGptPlanTokenExchange.ReauthorizationRequired::class.java) {
            sessions.usable("oaiapp_one")
        }
        assertNull(vault.credentials("oaiapp_one"))
        assertEquals("subject-one", vault.accounts().single().subject)
        assertFalse(record.text!!.contains("refresh-old"))
    }

    @Test
    fun staleRejectionDoesNotEraseNewerGrant() {
        connect(expiresAt = now - 1)
        // Re-sign-in lands while the old refresh is in flight; the old token is then rejected.
        val racing = ChatGptPlanTokenExchange(ChatGptPlanTokenExchange.Transport {
            record.text = record.text!!.replace("refresh-old", "refresh-resigned")
                .replace("access-old", "access-resigned")
            throw ChatGptPlanTokenExchange.OAuthError("invalid_grant")
        })
        assertThrows(ChatGptPlanTokenExchange.ReauthorizationRequired::class.java) {
            ChatGptPlanSessions(vault, racing) { now }.usable("oaiapp_one")
        }
        assertEquals("refresh-resigned", vault.credentials("oaiapp_one")!!.refreshToken)
        assertEquals("access-resigned", vault.credentials("oaiapp_one")!!.accessToken)
    }

    @Test
    fun transientFailureKeepsStoredTokens() {
        connect(expiresAt = now - 1)
        val failing = ChatGptPlanTokenExchange(ChatGptPlanTokenExchange.Transport { throw IOException("offline") })
        val snapshot = record.text
        assertThrows(IOException::class.java) {
            ChatGptPlanSessions(vault, failing) { now }.usable("oaiapp_one")
        }
        assertEquals(snapshot, record.text)
        assertEquals("refresh-old", vault.credentials("oaiapp_one")!!.refreshToken)
    }

    @Test
    fun revokeUsesDiscoveredEndpointWithRefreshTokenHint() {
        val seen = mutableListOf<String>()
        revoking(seen, "https://auth.openai.com/oauth/revoke").revoke("oaiapp_one", "refresh-old")
        assertEquals("GET https://auth.openai.com/.well-known/openid-configuration", seen[0])
        assertTrue(seen[1].startsWith("POST https://auth.openai.com/oauth/revoke "))
        assertTrue(seen[1].contains("token=refresh-old"))
        assertTrue(seen[1].contains("token_type_hint=refresh_token"))
        assertTrue(seen[1].contains("client_id=oaiapp_one"))
    }

    @Test
    fun revokeRefusesEndpointOffIssuerOrigin() {
        for (endpoint in listOf("https://evil.example/revoke", "https://auth.openai.com.evil.example/r")) {
            val seen = mutableListOf<String>()
            assertThrows(IOException::class.java) { revoking(seen, endpoint).revoke("oaiapp_one", "refresh-old") }
            assertEquals(1, seen.size)
        }
    }

    @Test
    fun signOutRevokesThenClearsButKeepsRegistration() {
        connect(expiresAt = now + 3600)
        val sessions = ChatGptPlanSessions(vault, revoking(mutableListOf(), "https://auth.openai.com/oauth/revoke"))
        assertEquals(ChatGptPlanSessions.SignOut.REVOKED, sessions.signOut("oaiapp_one"))
        assertNull(vault.credentials("oaiapp_one"))
        assertEquals("subject-one", vault.accounts().single().subject)
        assertEquals(ChatGptPlanSessions.SignOut.NOT_CONNECTED, sessions.signOut("oaiapp_one"))
    }

    @Test
    fun unconfirmedRevocationStillClearsAfterBoundedRetries() {
        connect(expiresAt = now + 3600)
        val sleeps = mutableListOf<Long>()
        val offline = ChatGptPlanTokenExchange(ChatGptPlanTokenExchange.Transport { throw IOException("offline") })
        val result = ChatGptPlanSessions(vault, offline, sleep = { sleeps += it }) { now }.signOut("oaiapp_one")
        assertEquals(ChatGptPlanSessions.SignOut.CLEARED_UNCONFIRMED, result)
        assertEquals(listOf(1_000L, 2_000L), sleeps)
        assertNull(vault.credentials("oaiapp_one"))
        assertFalse(record.text!!.contains("refresh-old"))
    }

    @Test
    fun interruptedBackoffStillClearsLocally() {
        connect(expiresAt = now + 3600)
        val offline = ChatGptPlanTokenExchange(ChatGptPlanTokenExchange.Transport { throw IOException("offline") })
        val result = ChatGptPlanSessions(vault, offline, sleep = { throw InterruptedException() }) { now }
            .signOut("oaiapp_one")
        assertTrue(Thread.interrupted())
        assertEquals(ChatGptPlanSessions.SignOut.CLEARED_UNCONFIRMED, result)
        assertNull(vault.credentials("oaiapp_one"))
    }

    @Test
    fun signOutDoesNotEraseSignInSavedDuringRevocation() {
        connect(expiresAt = now + 3600)
        val racing = ChatGptPlanTokenExchange(ChatGptPlanTokenExchange.Transport { request: Request ->
            if (request.method == "GET") return@Transport """{"revocation_endpoint":"https://auth.openai.com/oauth/revoke"}"""
            record.text = record.text!!.replace("refresh-old", "refresh-resigned")
            ""
        })
        assertEquals(ChatGptPlanSessions.SignOut.REVOKED, ChatGptPlanSessions(vault, racing).signOut("oaiapp_one"))
        assertEquals("refresh-resigned", vault.credentials("oaiapp_one")!!.refreshToken)
    }

    private fun revoking(seen: MutableList<String>, endpoint: String) =
        ChatGptPlanTokenExchange(ChatGptPlanTokenExchange.Transport { request: Request ->
            val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
            seen += "${request.method} ${request.url} $body".trim()
            if (request.method == "GET") """{"revocation_endpoint":"$endpoint"}""" else ""
        })

    private fun connect(expiresAt: Long) {
        val host = vault.hostId()
        record.text = """{"version":1,"host":"$host","accounts":[{"client_id":"oaiapp_one","sub":"subject-one",
          "tokens":{"id_token":"id-verified","access_token":"access-old","refresh_token":"refresh-old",
          "scopes":["chatgpt.tokens.use.direct"],"expires_at":$expiresAt}}]}"""
    }

    private fun response(scope: String? = null) = buildString {
        append("""{"token_type":"Bearer","access_token":"access-new","refresh_token":"refresh-new","expires_in":3600""")
        if (scope != null) append(""","scope":"$scope"""")
        append("}")
    }

    private fun rejected(code: String) = "!$code"

    /** A reply starting with `!` is delivered as an OAuth error with that code. */
    private fun exchange(reply: String) = ChatGptPlanTokenExchange(ChatGptPlanTokenExchange.Transport { request: Request ->
        assertEquals("https://auth.openai.com/api/accounts/oauth/token", request.url.toString())
        requests += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        if (reply.startsWith("!")) throw ChatGptPlanTokenExchange.OAuthError(reply.drop(1))
        reply
    })

    private class Fake : ChatGptPlanCredentialStore.RecordStorage {
        var text: String? = null
        override fun read(): String? = text
        override fun hasStoredData() = text != null
        override fun write(value: String): Boolean {
            text = value
            return true
        }
    }
}

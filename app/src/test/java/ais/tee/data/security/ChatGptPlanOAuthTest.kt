package ais.tee.data.security

import ais.tee.data.security.ChatGptPlanOAuth.Callback
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptPlanOAuthTest {
    private val host = "urn:uuid:fd74b2d9-fab2-4f33-9d53-ac90b7bc9b46"

    @Test
    fun newRegistrationRequestsOfficialScopesAndPkceWithoutASecret() {
        val pending = ChatGptPlanOAuth.prepare(host, 1455)
        val params = query(URI(pending.authorizationUrl).rawQuery)
        assertEquals("https", URI(pending.authorizationUrl).scheme)
        assertEquals("auth.openai.com", URI(pending.authorizationUrl).host)
        assertEquals("/api/accounts/authorize", URI(pending.authorizationUrl).path)
        assertEquals("dynamic_agent_client", params["client_id"])
        assertEquals("Aistee", params["agent_name_hint"])
        assertEquals(host, params["ext_agent_host_id"])
        assertEquals("code", params["response_type"])
        assertEquals("http://127.0.0.1:1455/auth/callback", params["redirect_uri"])
        assertEquals("https://api.openai.com/v1", params["resource"])
        assertEquals("S256", params["code_challenge_method"])
        assertTrue(params["scope"]!!.split(" ").containsAll(
            listOf("openid", "profile", "email", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct"),
        ))
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pending.codeVerifier.toByteArray(StandardCharsets.US_ASCII)),
        )
        assertEquals(expectedChallenge, params["code_challenge"])
        assertNotEquals(params["state"], params["nonce"])
        assertFalse(pending.toString().contains(pending.codeVerifier))
    }

    @Test
    fun returningRegistrationReusesIssuedClientAndChecksCallbackBinding() {
        val pending = ChatGptPlanOAuth.prepare(host, 29111, "oaiapp_saved")
        val params = query(URI(pending.authorizationUrl).rawQuery)
        assertEquals("oaiapp_saved", params["client_id"])
        assertFalse(params.containsKey("agent_name_hint"))
        val valid = pending.redirectUri + "?code=secret-code&state=${params["state"]}"
        val returned = pending.acceptCallback(valid)
        assertTrue(returned is Callback.Code)
        assertEquals("oaiapp_saved", (returned as Callback.Code).clientId)
        assertFalse(returned.toString().contains("secret-code"))
        assertEquals(Callback.Invalid, pending.acceptCallback(valid)) // replay
    }

    @Test
    fun registrationRequiresAnIssuedClientAndExactState() {
        val pending = ChatGptPlanOAuth.prepare(host, 1455)
        val state = query(URI(pending.authorizationUrl).rawQuery)["state"]
        assertEquals(Callback.Invalid, pending.acceptCallback(pending.redirectUri + "?code=x&state=bad&client_id=oaiapp_yes"))
        assertEquals(Callback.Invalid, pending.acceptCallback(pending.redirectUri + "?code=x&state=$state"))
        assertEquals(Callback.Invalid, pending.acceptCallback(pending.redirectUri + "?code=x&state=$state&client_id=dynamic_agent_client"))
        val accepted = pending.acceptCallback(pending.redirectUri + "?code=x&state=$state&client_id=oaiapp_yes")
        assertEquals("oaiapp_yes", (accepted as Callback.Code).clientId)
    }

    @Test
    fun rejectsOtherOriginsDuplicateParametersAndClientSwitches() {
        val pending = ChatGptPlanOAuth.prepare(host, 1455, "oaiapp_saved")
        val state = query(URI(pending.authorizationUrl).rawQuery)["state"]
        val callback = "?state=$state&code=secret"
        assertEquals(Callback.Invalid, pending.acceptCallback("http://localhost:1455/auth/callback$callback"))
        assertEquals(Callback.Invalid, pending.acceptCallback("http://127.0.0.1:1456/auth/callback$callback"))
        assertEquals(Callback.Invalid, pending.acceptCallback("http://127.0.0.1:1455/callback$callback"))
        assertEquals(Callback.Invalid, pending.acceptCallback(pending.redirectUri + callback + "&code=second"))
        assertEquals(Callback.Invalid, pending.acceptCallback(pending.redirectUri + callback + "&client_id=oaiapp_other"))
        assertTrue(pending.acceptCallback(pending.redirectUri + callback) is Callback.Code)
    }

    @Test
    fun deniedConsentConsumesAttemptOnlyWithMatchingState() {
        val pending = ChatGptPlanOAuth.prepare(host, 1455)
        val state = query(URI(pending.authorizationUrl).rawQuery)["state"]
        assertEquals(Callback.Invalid, pending.acceptCallback(pending.redirectUri + "?error=access_denied&state=other"))
        assertEquals(Callback.AccessDenied, pending.acceptCallback(pending.redirectUri + "?error=access_denied&state=$state"))
        assertEquals(Callback.Invalid, pending.acceptCallback(pending.redirectUri + "?error=access_denied&state=$state"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDynamicPlaceholderAsExistingClient() {
        ChatGptPlanOAuth.prepare(host, 1455, "dynamic_agent_client")
    }

    private fun query(raw: String?): Map<String, String> =
        raw!!.split("&").associate { element ->
            val parts = element.split("=", limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }
}

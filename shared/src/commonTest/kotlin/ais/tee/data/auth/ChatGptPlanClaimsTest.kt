package ais.tee.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatGptPlanClaimsTest {
    private val now = 1_800_000_000L
    private fun payload(aud: String = "\"oaiapp_test\"", azp: String = "") =
        """{"iss":"https://auth.openai.com","aud":$aud,"sub":"subject-1","nonce":"nonce","iat":1799999990,"exp":1800000100$azp}"""

    @Test
    fun acceptsStringAndArrayAudienceWithoutRequiringAzp() {
        assertEquals("subject-1", validateChatGptPlanClaims(payload(), "oaiapp_test", "nonce", now).subject)
        assertEquals("subject-1", validateChatGptPlanClaims(payload("[\"oaiapp_test\"]"), "oaiapp_test", "nonce", now).subject)
        assertEquals("subject-1", validateChatGptPlanClaims(payload("[\"oaiapp_test\",\"other\"]", ",\"azp\":\"oaiapp_test\""), "oaiapp_test", "nonce", now).subject)
    }

    @Test
    fun rejectsWrongAuthorizedPartyNonceExpiredAndDifferentClient() {
        assertFails { validateChatGptPlanClaims(payload("[\"oaiapp_test\"]", ",\"azp\":\"other\""), "oaiapp_test", "nonce", now) }
        assertFails { validateChatGptPlanClaims(payload(), "oaiapp_test", "wrong", now) }
        assertFails { validateChatGptPlanClaims(payload(), "other", "nonce", now) }
        assertFails { validateChatGptPlanClaims(payload(), "oaiapp_test", "nonce", now + 500) }
    }

    @Test
    fun verifiedIdentityStringNeverExposesSubject() {
        val identity = validateChatGptPlanClaims(payload(), "oaiapp_test", "nonce", now)
        assertFalse(identity.toString().contains("subject-1"))
    }
}

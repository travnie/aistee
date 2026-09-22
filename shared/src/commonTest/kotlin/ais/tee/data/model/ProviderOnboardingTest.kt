package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderOnboardingTest {
    @Test
    fun signInEntriesStayOnProviderOwnedPages() {
        assertEquals("https://chat.qwen.ai/auth?action=signin", WebAiService.QWEN.onboardingCapabilities().signInUrl)
        assertEquals("https://chat.z.ai/auth", WebAiService.ZAI.onboardingCapabilities().signInUrl)
        assertEquals(WebAiService.COPILOT.url, WebAiService.COPILOT.onboardingCapabilities().signInUrl)
        WebAiService.entries.filterNot { it.onboardingCapabilities().hasIdentityAssistedPath }.forEach {
            assertNull(it.onboardingCapabilities().signInUrl)
        }
    }

    @Test
    fun exposesOnlyVerifiedPreferredIdentityMethods() {
        assertEquals(
            listOf(ProviderIdentityMethod.GOOGLE, ProviderIdentityMethod.GITHUB),
            WebAiService.QWEN.onboardingCapabilities().preferredIdentityMethods
        )
        assertEquals(
            listOf(ProviderIdentityMethod.MICROSOFT),
            WebAiService.COPILOT.onboardingCapabilities().preferredIdentityMethods
        )
        assertEquals(
            listOf(ProviderIdentityMethod.GOOGLE, ProviderIdentityMethod.GITHUB),
            WebAiService.ZAI.onboardingCapabilities().preferredIdentityMethods
        )
    }

    @Test
    fun resolvesSupportedUserPreferenceBeforeProviderFallback() {
        assertEquals(
            ProviderIdentityMethod.GITHUB,
            WebAiService.QWEN.resolveOnboardingIdentityMethod(ProviderIdentityMethod.GITHUB)
        )
        assertEquals(
            ProviderIdentityMethod.GOOGLE,
            WebAiService.ZAI.resolveOnboardingIdentityMethod(ProviderIdentityMethod.GOOGLE)
        )
    }

    @Test
    fun fallsBackToFirstVerifiedProviderMethodWhenPreferenceIsUnavailable() {
        assertEquals(
            ProviderIdentityMethod.GOOGLE,
            WebAiService.QWEN.resolveOnboardingIdentityMethod(ProviderIdentityMethod.MICROSOFT)
        )
        assertEquals(
            ProviderIdentityMethod.MICROSOFT,
            WebAiService.COPILOT.resolveOnboardingIdentityMethod(ProviderIdentityMethod.GOOGLE)
        )
        assertEquals(
            ProviderIdentityMethod.GOOGLE,
            WebAiService.QWEN.resolveOnboardingIdentityMethod(null)
        )
    }

    @Test
    fun unsupportedProvidersResolveNoIdentityMethod() {
        assertNull(
            WebAiService.CLAUDE.resolveOnboardingIdentityMethod(ProviderIdentityMethod.GOOGLE)
        )
    }

    @Test
    fun doesNotInventIdentityPathsForUnsupportedProviders() {
        val verified = setOf(WebAiService.QWEN, WebAiService.COPILOT, WebAiService.ZAI)

        WebAiService.entries.filterNot { it in verified }.forEach { service ->
            val capabilities = service.onboardingCapabilities()
            assertFalse(capabilities.hasIdentityAssistedPath, service.name)
            assertEquals(
                EmbeddedSessionHandoff.UNSUPPORTED,
                capabilities.embeddedSessionHandoff,
                service.name
            )
        }
    }

    @Test
    fun identityMethodsDoNotClaimEmbeddedSessionHandoff() {
        listOf(WebAiService.QWEN, WebAiService.COPILOT, WebAiService.ZAI).forEach { service ->
            val capabilities = service.onboardingCapabilities()
            assertTrue(capabilities.hasIdentityAssistedPath, service.name)
            assertEquals(
                EmbeddedSessionHandoff.UNVERIFIED,
                capabilities.embeddedSessionHandoff,
                service.name
            )
        }
    }
}

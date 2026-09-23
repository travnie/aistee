package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderOnboardingTest {
    private val onboardingV2Providers = setOf(
        WebAiService.QWEN,
        WebAiService.COPILOT,
        WebAiService.ZAI,
        WebAiService.GROK,
        WebAiService.CHARACTER_AI,
        WebAiService.VENICE,
        WebAiService.META_AI
    )

    @Test
    fun signInEntriesStayOnProviderOwnedPages() {
        assertEquals("https://chat.qwen.ai/auth?action=signin", WebAiService.QWEN.onboardingCapabilities().signInUrl)
        assertEquals("https://chat.z.ai/auth", WebAiService.ZAI.onboardingCapabilities().signInUrl)

        onboardingV2Providers
            .filterNot { it == WebAiService.QWEN || it == WebAiService.ZAI }
            .forEach { service ->
                assertEquals(service.url, service.onboardingCapabilities().signInUrl, service.name)
            }

        WebAiService.entries.filterNot { it in onboardingV2Providers }.forEach {
            assertNull(it.onboardingCapabilities().signInUrl)
        }
    }

    @Test
    fun exposesCurrentProviderSignInMethods() {
        val expected = mapOf(
            WebAiService.QWEN to listOf(
                ProviderIdentityMethod.GOOGLE,
                ProviderIdentityMethod.GITHUB
            ),
            WebAiService.COPILOT to listOf(
                ProviderIdentityMethod.MICROSOFT,
                ProviderIdentityMethod.GOOGLE,
                ProviderIdentityMethod.APPLE
            ),
            WebAiService.ZAI to listOf(
                ProviderIdentityMethod.GOOGLE,
                ProviderIdentityMethod.GITHUB
            ),
            WebAiService.GROK to listOf(
                ProviderIdentityMethod.X,
                ProviderIdentityMethod.GOOGLE,
                ProviderIdentityMethod.APPLE,
                ProviderIdentityMethod.EMAIL
            ),
            WebAiService.CHARACTER_AI to listOf(
                ProviderIdentityMethod.GOOGLE,
                ProviderIdentityMethod.APPLE,
                ProviderIdentityMethod.EMAIL
            ),
            WebAiService.VENICE to listOf(
                ProviderIdentityMethod.EMAIL,
                ProviderIdentityMethod.WALLET_CONNECT
            ),
            WebAiService.META_AI to listOf(ProviderIdentityMethod.META_ACCOUNT)
        )

        expected.forEach { (service, methods) ->
            assertEquals(methods, service.onboardingCapabilities().preferredIdentityMethods, service.name)
        }
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
        assertEquals(
            ProviderIdentityMethod.APPLE,
            WebAiService.CHARACTER_AI.resolveOnboardingIdentityMethod(ProviderIdentityMethod.APPLE)
        )
    }

    @Test
    fun fallsBackToFirstProviderMethodWhenPreferenceIsUnavailable() {
        assertEquals(
            ProviderIdentityMethod.GOOGLE,
            WebAiService.QWEN.resolveOnboardingIdentityMethod(ProviderIdentityMethod.MICROSOFT)
        )
        assertEquals(
            ProviderIdentityMethod.MICROSOFT,
            WebAiService.COPILOT.resolveOnboardingIdentityMethod(ProviderIdentityMethod.GITHUB)
        )
        assertEquals(
            ProviderIdentityMethod.X,
            WebAiService.GROK.resolveOnboardingIdentityMethod(null)
        )
        assertEquals(
            ProviderIdentityMethod.META_ACCOUNT,
            WebAiService.META_AI.resolveOnboardingIdentityMethod(ProviderIdentityMethod.GOOGLE)
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
        WebAiService.entries.filterNot { it in onboardingV2Providers }.forEach { service ->
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
    fun providerMethodsDoNotClaimEmbeddedSessionHandoff() {
        onboardingV2Providers.forEach { service ->
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

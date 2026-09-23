package ais.tee.web

import ais.tee.data.model.ProviderIdentityMethod
import ais.tee.data.model.WebAiService
import ais.tee.data.model.onboardingCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderOnboardingNavigationTest {
    @Test
    fun androidNavigationVerificationRemainsProviderScoped() {
        val verified = setOf(WebAiService.QWEN, WebAiService.COPILOT, WebAiService.ZAI)

        WebAiService.entries.forEach { service ->
            assertEquals(
                service in verified,
                ProviderWebRegistry.hasVerifiedTopLevelNavigationPolicy(service)
            )
        }
    }

    @Test
    fun androidNavigationVerificationRemainsIdentityMethodScoped() {
        assertTrue(
            ProviderWebRegistry.isIdentityMethodVerifiedForNavigation(
                WebAiService.QWEN,
                ProviderIdentityMethod.GOOGLE
            )
        )
        assertTrue(
            ProviderWebRegistry.isIdentityMethodVerifiedForNavigation(
                WebAiService.QWEN,
                ProviderIdentityMethod.GITHUB
            )
        )
        assertFalse(
            ProviderWebRegistry.isIdentityMethodVerifiedForNavigation(
                WebAiService.QWEN,
                ProviderIdentityMethod.MICROSOFT
            )
        )
        assertFalse(
            ProviderWebRegistry.isIdentityMethodVerifiedForNavigation(
                WebAiService.COPILOT,
                ProviderIdentityMethod.GOOGLE
            )
        )
    }

    @Test
    fun identityMetadataCannotWidenAndroidAuthHostsWithoutPairVerification() {
        assertTrue(WebAiService.QWEN.onboardingCapabilities().hasIdentityAssistedPath)
        assertFalse(
            ProviderWebRegistry.isIdentityMethodVerifiedForNavigation(
                WebAiService.QWEN,
                ProviderIdentityMethod.MICROSOFT
            )
        )
        assertFalse(
            "login.live.com" in ProviderWebRegistry.topLevelNavigationAuthHosts(WebAiService.QWEN)
        )
    }

    @Test
    fun newlyDescribedProvidersStayOutsideEmbeddedNavigationUntilVerified() {
        listOf(
            WebAiService.GROK,
            WebAiService.CHARACTER_AI,
            WebAiService.VENICE,
            WebAiService.META_AI
        ).forEach { service ->
            assertTrue(service.onboardingCapabilities().hasIdentityAssistedPath)
            assertFalse(ProviderWebRegistry.hasVerifiedTopLevelNavigationPolicy(service))
            assertTrue(ProviderWebRegistry.topLevelNavigationAuthHosts(service).isEmpty())
        }
    }

    @Test
    fun verifiedIdentityMethodsResolveToExpectedAndroidAuthHosts() {
        assertEquals(
            setOf("accounts.google.com", "github.com"),
            ProviderWebRegistry.topLevelNavigationAuthHosts(WebAiService.QWEN)
        )
        assertEquals(
            setOf("login.live.com", "login.microsoftonline.com"),
            ProviderWebRegistry.topLevelNavigationAuthHosts(WebAiService.COPILOT)
        )
        assertEquals(
            setOf("accounts.google.com", "github.com"),
            ProviderWebRegistry.topLevelNavigationAuthHosts(WebAiService.ZAI)
        )
    }
}

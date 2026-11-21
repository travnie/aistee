package ais.tee.data.preferences

import ais.tee.data.model.ProviderIdentityMethod
import ais.tee.data.model.WebAiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebChatPreferencesStoreTest {
    @Test
    fun selectedProviderFallsBackSafelyWhenStoredIdIsUnknown() {
        assertEquals(WebAiService.CHATGPT, resolveWebService("CHATGPT"))
        assertEquals(WebAiService.CLAUDE, resolveWebService("retired-provider"))
        assertEquals(WebAiService.CLAUDE, resolveWebService(null))
    }

    @Test
    fun favoriteIdsIgnoreUnknownProvidersAndKeepCanonicalOrder() {
        assertEquals(
            linkedSetOf(WebAiService.CHATGPT, WebAiService.QWEN),
            resolveFavoriteWebServices(setOf("qwen", "CHATGPT", "retired-provider"))
        )
    }

    @Test
    fun disabledTurnNavigationIdsResolveByProviderAndIgnoreUnknownEntries() {
        assertEquals(
            linkedSetOf(WebAiService.CHATGPT, WebAiService.GEMINI),
            resolveDisabledTurnNavigationServices(
                setOf("gemini", "CHATGPT", "retired-provider")
            )
        )
        assertEquals(emptySet<WebAiService>(), resolveDisabledTurnNavigationServices(emptySet()))
    }

    @Test
    fun recentProviderIdsAreBoundedOrderedAndRejectUnknownValues() {
        assertEquals(emptyList<WebAiService>(), resolveRecentWebServices(null))
        assertEquals(
            listOf(WebAiService.GROK, WebAiService.CHATGPT, WebAiService.GEMINI, WebAiService.KIMI),
            resolveRecentWebServices("GROK,broken,chatgpt,grok,gemini,kimi,qwen")
        )
        val recent = recentWebServicesAfterSelection(
            listOf(WebAiService.CLAUDE, WebAiService.CHATGPT, WebAiService.GROK, WebAiService.QWEN),
            WebAiService.GROK
        )
        assertEquals(
            listOf(WebAiService.GROK, WebAiService.CLAUDE, WebAiService.CHATGPT, WebAiService.QWEN),
            recent
        )
        assertEquals(
            listOf(WebAiService.DEEPSEEK, WebAiService.GROK, WebAiService.CLAUDE, WebAiService.CHATGPT),
            recentWebServicesAfterSelection(recent, WebAiService.DEEPSEEK)
        )
    }

    @Test
    fun preferredIdentityMethodResolvesCaseInsensitivelyAndRejectsUnknownValues() {
        assertEquals(
            ProviderIdentityMethod.GITHUB,
            resolvePreferredIdentityMethod("github")
        )
        assertEquals(
            ProviderIdentityMethod.MICROSOFT,
            resolvePreferredIdentityMethod("MICROSOFT")
        )
        assertNull(resolvePreferredIdentityMethod("password"))
        assertNull(resolvePreferredIdentityMethod(null))
    }
}

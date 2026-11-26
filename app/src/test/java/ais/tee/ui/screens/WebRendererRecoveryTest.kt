package ais.tee.ui.screens

import ais.tee.data.model.WebAiService
import ais.tee.data.model.WebChatGenerationObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRendererRecoveryTest {
    @Test
    fun onlyFreshlyConfirmedTrackedInactiveRendererWaivesPriorityWhenNotVisible() {
        assertFalse(rendererPriorityWaivedWhenNotVisible(true, true, true))
        assertFalse(rendererPriorityWaivedWhenNotVisible(false, true, false))
        assertFalse(rendererPriorityWaivedWhenNotVisible(false, false, true))
        assertTrue(rendererPriorityWaivedWhenNotVisible(false, true, true))
    }

    @Test
    fun uncertainOrGeneratingObservationKeepsRendererProtected() {
        assertTrue(rendererInactivityConfirmedByObservation(WebChatGenerationObservation.IDLE))
        assertTrue(rendererInactivityConfirmedByObservation(WebChatGenerationObservation.COMPLETED))
        assertTrue(rendererInactivityConfirmedByObservation(WebChatGenerationObservation.COMPLETED_WHILE_SELECTED))
        assertFalse(rendererInactivityConfirmedByObservation(WebChatGenerationObservation.GENERATING))
        assertFalse(rendererInactivityConfirmedByObservation(WebChatGenerationObservation.UNKNOWN))
    }

    @Test
    fun lateGeneratingOrUnknownObservationRevokesConfirmedInactivity() {
        assertFalse(rendererInactivityConfirmedAfterObservation(true, WebChatGenerationObservation.GENERATING))
        assertFalse(rendererInactivityConfirmedAfterObservation(true, WebChatGenerationObservation.UNKNOWN))
        assertTrue(rendererInactivityConfirmedAfterObservation(true, WebChatGenerationObservation.IDLE))
        assertFalse(rendererInactivityConfirmedAfterObservation(false, WebChatGenerationObservation.IDLE))
    }

    @Test
    fun lowMemoryRendererTerminationRecreatesLastUrl() {
        assertEquals(
            WebRendererRecoveryAction.RECREATE_LAST_URL,
            webRendererRecoveryAction(didCrash = false, isSelected = true, isWebChatActive = true)
        )
    }

    @Test
    fun hiddenSelectedLowMemoryTerminationDefersUntilWebChatIsActive() {
        assertEquals(
            WebRendererRecoveryAction.DEFER_UNTIL_ACTIVE,
            webRendererRecoveryAction(didCrash = false, isSelected = true, isWebChatActive = false)
        )
    }

    @Test
    fun inactiveLowMemoryTerminationStaysEvictedUntilSelected() {
        assertEquals(
            WebRendererRecoveryAction.EVICT_UNTIL_SELECTED,
            webRendererRecoveryAction(didCrash = false, isSelected = false, isWebChatActive = false)
        )
    }

    @Test
    fun crashedInactiveProviderDoesNotKeepLivePoolSlot() {
        assertEquals(
            listOf(WebAiService.GEMINI),
            webServicesForActivation(
                current = listOf(WebAiService.CLAUDE, WebAiService.GEMINI),
                activationTarget = WebAiService.GEMINI,
                crashedServices = setOf(WebAiService.CLAUDE)
            )
        )
        assertEquals(
            listOf(WebAiService.CLAUDE, WebAiService.GEMINI),
            webServicesForActivation(
                current = listOf(WebAiService.CLAUDE, WebAiService.GEMINI),
                activationTarget = WebAiService.CLAUDE,
                crashedServices = setOf(WebAiService.CLAUDE)
            )
        )
    }

    @Test
    fun explicitConversationRecoveryUsesRecognizedProviderRoutes() {
        val routes = mapOf(
            WebAiService.CHATGPT to "https://chatgpt.com/c/conversation-123",
            WebAiService.CLAUDE to "https://claude.ai/chat/conversation-123",
            WebAiService.GEMINI to "https://gemini.google.com/app/abc123",
            WebAiService.DEEPSEEK to "https://chat.deepseek.com/a/chat/s/conversation-123",
        )
        routes.forEach { (service, url) ->
            assertEquals(url, webRendererCrashConversationUrl(service, url))
            assertNull(webRendererCrashConversationUrl(service, "$url?token=secret"))
            assertNull(webRendererCrashConversationUrl(service, "$url#message"))
            assertNull(webRendererCrashConversationUrl(service, "$url/extra"))
            assertNull(webRendererCrashConversationUrl(service, service.url))
        }
        listOf(
            null,
            "https://chatgpt.com/auth/login",
            "https://chatgpt.com/c/%2e%2e",
            "https://chatgpt.com/c/a%2fb",
            "https://chatgpt.com/c/",
            "https://sub.chatgpt.com/c/abc",
            "https://chatgpt.com.evil.test/c/abc",
            "https://user:secret@chatgpt.com/c/abc",
            "https://claude.ai/chat/abc",
            "http://chatgpt.com/c/abc",
        ).forEach { assertNull(it, webRendererCrashConversationUrl(WebAiService.CHATGPT, it)) }
        assertNull(webRendererCrashConversationUrl(WebAiService.KIMI, "https://www.kimi.com/chat/abc"))
    }

    @Test
    fun rendererCrashRequiresExplicitRetry() {
        assertEquals(
            WebRendererRecoveryAction.REQUIRE_USER_RETRY,
            webRendererRecoveryAction(didCrash = true, isSelected = true, isWebChatActive = false)
        )
    }
}

package ais.tee.ui.screens

import ais.tee.data.model.WebAiService
import ais.tee.data.model.WebChatGenerationObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun rendererCrashRequiresExplicitRetry() {
        assertEquals(
            WebRendererRecoveryAction.REQUIRE_USER_RETRY,
            webRendererRecoveryAction(didCrash = true, isSelected = true, isWebChatActive = false)
        )
    }
}

package ais.tee.ui.screens

import android.content.ComponentCallbacks2
import ais.tee.data.model.WebAiService
import org.junit.Assert.assertEquals
import org.junit.Test

@Suppress("DEPRECATION")
class WebViewMemoryPolicyTest {
    @Test
    fun trimLevelsChooseExpectedEvictionStrength() {
        assertEquals(
            WebViewEvictionMode.NONE,
            webViewEvictionModeForTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        )
        assertEquals(
            WebViewEvictionMode.PRESERVE_GENERATING,
            webViewEvictionModeForTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        )
        assertEquals(
            WebViewEvictionMode.PRESERVE_GENERATING,
            webViewEvictionModeForTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        )
        assertEquals(
            WebViewEvictionMode.SELECTED_ONLY,
            webViewEvictionModeForTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        )
        assertEquals(
            WebViewEvictionMode.SELECTED_ONLY,
            webViewEvictionModeForTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)
        )
    }

    @Test
    fun lowerPressureKeepsSelectedAndGeneratingProviders() {
        val liveServices = listOf(WebAiService.CLAUDE, WebAiService.CHATGPT, WebAiService.GEMINI)
        assertEquals(
            listOf(WebAiService.CLAUDE, WebAiService.CHATGPT),
            webServicesToKeepAfterMemoryPressure(
                liveServices = liveServices,
                selectedService = WebAiService.CHATGPT,
                generatingServices = setOf(WebAiService.CLAUDE),
                mode = WebViewEvictionMode.PRESERVE_GENERATING
            )
        )
    }

    @Test
    fun criticalPressureKeepsOnlySelectedProvider() {
        assertEquals(
            listOf(WebAiService.CHATGPT),
            webServicesToKeepAfterMemoryPressure(
                liveServices = listOf(WebAiService.CLAUDE, WebAiService.CHATGPT),
                selectedService = WebAiService.CHATGPT,
                generatingServices = setOf(WebAiService.CLAUDE),
                mode = WebViewEvictionMode.SELECTED_ONLY
            )
        )
    }
}

package ais.tee.ui.screens

import ais.tee.data.model.WebAiService
import ais.tee.web.ProviderWebRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebChatCodexCloudShortcutTest {
    @Test
    fun registryExposesCodexCloudOnlyForChatGpt() {
        val shortcut = ProviderWebRegistry.toolbarShortcut(WebAiService.CHATGPT)

        assertEquals("codex-cloud", shortcut?.id)
        assertEquals("Codex", shortcut?.label)
        assertEquals("https://chatgpt.com/codex/cloud", shortcut?.url)
        assertNull(ProviderWebRegistry.toolbarShortcut(WebAiService.CLAUDE))
        assertNull(ProviderWebRegistry.toolbarShortcut(WebAiService.GEMINI))
    }
}

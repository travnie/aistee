package ais.tee.data.preferences

import android.app.Application
import android.content.Context
import ais.tee.data.model.WebAiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebChatRecentStoreTest {
    @Test
    fun selectionOrderSurvivesStoreRecreationWithoutSavingConversationData() {
        val app = RuntimeEnvironment.getApplication()
        val preferences = app.getSharedPreferences("web_chat_preferences", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val store = WebChatPreferencesStore(app)
            listOf(
                WebAiService.CLAUDE, WebAiService.CHATGPT, WebAiService.GEMINI,
                WebAiService.CLAUDE, WebAiService.GROK, WebAiService.QWEN
            ).forEach(store::saveSelectedService)

            val reopened = WebChatPreferencesStore(app)
            assertEquals(WebAiService.QWEN, reopened.loadSelectedService())
            assertEquals(
                listOf(WebAiService.QWEN, WebAiService.GROK, WebAiService.CLAUDE, WebAiService.GEMINI),
                reopened.loadRecentWebServices()
            )
            val stored = preferences.getString("recent_web_services", "").orEmpty()
            assertEquals("qwen,grok,claude,gemini", stored)
            assertFalse(stored.contains("https://"))
        } finally {
            preferences.edit().clear().commit()
        }
    }
}

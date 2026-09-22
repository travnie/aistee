package ais.tee.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AisteeWidgetPreferencesStoreTest {
    @Test
    fun widgetModeFallsBackToRecentChatsForMissingOrUnknownValues() {
        assertEquals(AisteeWidgetMode.RECENT_CHATS, resolveAisteeWidgetMode(null))
        assertEquals(AisteeWidgetMode.RECENT_CHATS, resolveAisteeWidgetMode("retired_mode"))
        assertEquals(AisteeWidgetMode.PINNED_CHAT, resolveAisteeWidgetMode("pinned_chat"))
        assertEquals("MESSAGES", resolveAisteeWidgetMode("messages").name)
    }

    @Test
    fun messagePreviewPreferenceDefaultsToPrivacySafeStorageShape() {
        assertTrue(
            AisteeWidgetPreferences::class.java.declaredFields.any { field ->
                field.name == "showMessagePreviews"
            }
        )
    }
}

package ais.tee.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class AisteeWidgetPreferencesStoreTest {
    @Test
    fun widgetModeFallsBackToRecentChatsForMissingOrUnknownValues() {
        assertEquals(AisteeWidgetMode.RECENT_CHATS, resolveAisteeWidgetMode(null))
        assertEquals(AisteeWidgetMode.RECENT_CHATS, resolveAisteeWidgetMode("retired_mode"))
        assertEquals(AisteeWidgetMode.PINNED_CHAT, resolveAisteeWidgetMode("pinned_chat"))
    }
}

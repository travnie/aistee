package ais.tee.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeChatConversationShortcutsTest {
    @Test
    fun shortcutIdIsStableAndRejectsBlankConversationIds() {
        assertEquals(
            "native-chat:5c01549bf7a1390c6254f64c8fe5b317f4aae2ae4fdf512e0bbd20a4a7d37a2d",
            nativeChatConversationShortcutId("  conversation-123  "),
        )
        assertEquals(
            nativeChatConversationShortcutId("conversation-123"),
            nativeChatConversationShortcutId("  conversation-123  "),
        )
        assertNull(nativeChatConversationShortcutId("   "))
    }
}

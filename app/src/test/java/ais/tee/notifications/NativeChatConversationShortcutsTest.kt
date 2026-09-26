package ais.tee.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun shortcutIdResolvesOnlyToAKnownConversation() {
        val conversations = listOf("chat-a", "chat-b")

        assertEquals(
            "chat-b",
            nativeChatConversationIdForShortcut(nativeChatConversationShortcutId("chat-b"), conversations),
        )
        assertNull(nativeChatConversationIdForShortcut(nativeChatConversationShortcutId("deleted"), conversations))
        assertNull(nativeChatConversationIdForShortcut("web_ai", conversations))
        assertNull(nativeChatConversationIdForShortcut(null, conversations))
    }

    @Test
    fun titleAndShareTargetOnlyWhenTitlesMayBeShown() {
        val hidden = nativeChatShortcutPresentation("Trip plan", showConversationTitles = false)
        assertEquals("AI chat", hidden.shortLabel)
        assertFalse(hidden.isShareTarget)

        val shown = nativeChatShortcutPresentation("  Trip   plan  ", showConversationTitles = true)
        assertEquals("Trip plan", shown.shortLabel)
        assertTrue(shown.isShareTarget)

        val blank = nativeChatShortcutPresentation("   ", showConversationTitles = true)
        assertEquals("AI chat", blank.shortLabel)
        assertFalse(blank.isShareTarget)
    }

    @Test
    fun onlyTitledNativeChatShortcutsAreRedacted() {
        val chatShortcut = nativeChatConversationShortcutId("chat-a")!!

        assertTrue(isTitledNativeChatShortcut(chatShortcut, "Trip plan"))
        assertFalse(isTitledNativeChatShortcut(chatShortcut, "AI chat"))
        assertFalse(isTitledNativeChatShortcut("web_ai", "Web AI"))
    }
}

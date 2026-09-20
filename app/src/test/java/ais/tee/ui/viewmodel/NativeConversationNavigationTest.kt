package ais.tee.ui.viewmodel

import ais.tee.data.model.NativeChatArchive
import ais.tee.data.model.NativeChatConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeConversationNavigationTest {
    private val first = NativeChatConversation(
        id = "conversation-1",
        createdAtEpochMs = 1L
    )
    private val second = NativeChatConversation(
        id = "conversation-2",
        createdAtEpochMs = 2L
    )
    private val archive = NativeChatArchive(
        activeConversationId = first.id,
        conversations = listOf(first, second)
    )

    @Test
    fun resolvesExistingConversationAfterTrimming() {
        assertEquals(
            second.id,
            resolveNativeConversationTarget(archive, "  conversation-2  ")
        )
    }

    @Test
    fun rejectsMissingOrBlankConversation() {
        assertNull(resolveNativeConversationTarget(archive, "missing"))
        assertNull(resolveNativeConversationTarget(archive, "   "))
    }
}

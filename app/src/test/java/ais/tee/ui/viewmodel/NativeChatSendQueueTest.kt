package ais.tee.ui.viewmodel

import ais.tee.data.model.AiProvider
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NativeChatArchive
import ais.tee.data.model.NativeChatConversation
import ais.tee.notifications.mergeNativeChatDirectReplyResponses
import ais.tee.notifications.nativeChatReplyIdFromUserMessageId
import ais.tee.notifications.nativeChatReplyUserMessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatSendQueueTest {
    private val conversation = NativeChatConversation(id = "chat", createdAtEpochMs = 1L, draft = "hello")

    @Test
    fun onlyOfflineBackgroundCapableSendsAreQueued() {
        assertTrue(shouldQueueNativeChatSend(isOnline = false, canSendInBackground = true, text = "hello"))
        assertFalse(shouldQueueNativeChatSend(isOnline = true, canSendInBackground = true, text = "hello"))
        assertFalse(shouldQueueNativeChatSend(isOnline = false, canSendInBackground = false, text = "hello"))
        assertFalse(shouldQueueNativeChatSend(isOnline = false, canSendInBackground = true, text = "x".repeat(4_001)))
    }

    @Test
    fun queuedTurnIsAppendedClearsTheDraftAndTitlesTheChat() {
        val queued = conversation.withQueuedMessage("r1", " hello ", now = 5L)
        val message = queued.messages.single()

        assertEquals(nativeChatReplyUserMessageId("r1"), message.id)
        assertEquals("r1", nativeChatReplyIdFromUserMessageId(message.id))
        assertTrue(message.isQueued)
        assertEquals(CHAT_ROLE_USER, message.sender)
        assertEquals("", queued.draft)
        assertEquals("hello", queued.title)
    }

    @Test
    fun cancelRemovesOnlyAStillQueuedTurn() {
        val queued = conversation.withQueuedMessage("r1", "hello", now = 5L)
        val (cancelled, removed) = queued.withoutQueuedMessage(nativeChatReplyUserMessageId("r1"))!!

        assertTrue(cancelled.messages.isEmpty())
        assertEquals("hello", removed.text)
        assertNull(queued.withoutQueuedMessage("unknown"))
    }

    @Test
    fun deliveredAnswersClearTheQueuedMarker() {
        val queued = conversation.withQueuedMessage("r1", "hello", now = 5L)
        val answer = ModelChatMessage(id = "a", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CHATGPT, text = "hi")

        val merged = mergeNativeChatDirectReplyResponses(
            archive = NativeChatArchive(activeConversationId = "chat", conversations = listOf(queued)),
            conversationId = "chat",
            userMessageId = nativeChatReplyUserMessageId("r1"),
            responses = listOf(answer),
            now = 6L,
        )!!.conversations.single()

        assertEquals(listOf(false, false), merged.messages.map { it.isQueued })
        assertEquals("a", merged.messages.last().id)
    }
}

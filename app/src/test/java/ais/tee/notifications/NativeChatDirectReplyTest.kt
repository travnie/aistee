package ais.tee.notifications

import ais.tee.data.model.AiProvider
import ais.tee.data.model.ApiKeyConfig
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NativeChatArchive
import ais.tee.data.model.NativeChatConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatDirectReplyTest {
    private fun conversation(
        provider: AiProvider = AiProvider.CHATGPT,
        messages: List<ModelChatMessage> = listOf(
            ModelChatMessage(
                id = "assistant",
                sender = CHAT_ROLE_ASSISTANT,
                provider = provider,
                text = "Ready",
                timestamp = 1L,
            )
        ),
    ) = NativeChatConversation(
        id = "chat-1",
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        selectedProvider = provider,
        selectedModel = provider.defaultModel,
        messages = messages,
        draft = "keep my draft",
    )

    @Test
    fun retryCannotRecreateReplyAfterHistoryWasCleared() {
        val chat = conversation()
        val archive = NativeChatArchive(activeConversationId = chat.id, conversations = listOf(chat))
        val prepared = requireNotNull(prepareNativeChatDirectReply(archive, chat.id, "retry", "hello", 10L))
        val generated = ModelChatMessage(id = "direct_reply_retry_chatgpt", sender = CHAT_ROLE_ASSISTANT, text = "answer")
        val completed = requireNotNull(mergeNativeChatDirectReplyResponses(prepared.archive, chat.id, prepared.userMessage.id, listOf(generated), 11L))
        // Either initial or completion persistence can fail before a retry is scheduled.
        for (pending in listOf(prepared.archive, completed)) {
            val cleared = pending.copy(conversations = pending.conversations.map {
                it.copy(messages = emptyList(), replyEpoch = 20L)
            })
            assertNull(prepareNativeChatDirectReply(cleared, chat.id, "retry", "hello", 30L, expectedReplyEpoch = 0L))
            assertNull(mergeNativeChatDirectReplyResponses(cleared, chat.id, prepared.userMessage.id, listOf(generated), 30L))
            assertTrue(prepareNativeChatDirectReply(cleared, chat.id, "fresh", "new", 30L, expectedReplyEpoch = 20L) != null)
        }
    }

    @Test
    fun directReplyRequiresConfiguredTransport() {
        val openAiChat = conversation()
        assertFalse(canNativeChatDirectReply(openAiChat, ApiKeyConfig()))
        assertTrue(canNativeChatDirectReply(openAiChat, ApiKeyConfig(openAiKey = "configured")))

        val compare = conversation(provider = AiProvider.ALL)
        assertFalse(canNativeChatDirectReply(compare, ApiKeyConfig()))
        assertTrue(canNativeChatDirectReply(compare, ApiKeyConfig(claudeKey = "configured")))
    }

    @Test
    fun preparingReplyIsIdempotentAndPreservesDraft() {
        val chat = conversation()
        val archive = NativeChatArchive(activeConversationId = chat.id, conversations = listOf(chat))
        val first = requireNotNull(
            prepareNativeChatDirectReply(archive, chat.id, "reply-1", "  hello  ", 10L)
        )
        assertEquals("hello", first.userMessage.text)
        assertEquals("keep my draft", first.conversation.draft)

        val retried = requireNotNull(
            prepareNativeChatDirectReply(first.archive, chat.id, "reply-1", "hello", 20L)
        )
        assertEquals(1, retried.conversation.messages.count { it.id == first.userMessage.id })
    }

    @Test
    fun responseMergeStaysAttachedToItsReplyTurn() {
        val chat = conversation()
        val archive = NativeChatArchive(activeConversationId = chat.id, conversations = listOf(chat))
        val prepared = requireNotNull(
            prepareNativeChatDirectReply(archive, chat.id, "reply-1", "hello", 10L)
        )
        val laterUser = prepared.userMessage.copy(id = "later-user", text = "later", timestamp = 11L)
        val withLaterTurn = prepared.archive.copy(
            conversations = listOf(
                prepared.conversation.copy(messages = prepared.conversation.messages + laterUser)
            )
        )
        val answer = ModelChatMessage(
            id = "direct_reply_reply-1_chatgpt",
            sender = CHAT_ROLE_ASSISTANT,
            provider = AiProvider.CHATGPT,
            text = "answer",
            timestamp = 12L,
        )
        val merged = requireNotNull(
            mergeNativeChatDirectReplyResponses(
                withLaterTurn,
                chat.id,
                prepared.userMessage.id,
                listOf(answer),
                12L,
            )
        )
        assertEquals(
            listOf("assistant", prepared.userMessage.id, answer.id, "later-user"),
            merged.activeConversation!!.messages.map { it.id },
        )
    }
}

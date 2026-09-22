package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeChatMergeTest {
    private val user = ModelChatMessage(id = "user", sender = CHAT_ROLE_USER, text = "hello")
    private val reply = ModelChatMessage(id = "direct_reply", sender = CHAT_ROLE_ASSISTANT, text = "answer")
    private val chat = NativeChatConversation(id = "one", createdAtEpochMs = 1, messages = listOf(user))
    private val base = NativeChatArchive(activeConversationId = chat.id, conversations = listOf(chat))

    @Test
    fun clearEpochRemovesEvenBackgroundTurnsUnseenByTheForeground() {
        val empty = base.copy(conversations = listOf(chat.copy(messages = emptyList())))
        val background = base.copy(conversations = listOf(chat.copy(messages = listOf(user, reply))))
        val cleared = empty.copy(conversations = listOf(empty.conversations.single().copy(replyEpoch = 2L)))
        val merged = mergeNativeChatChanges(empty, cleared, background)
        assertTrue(merged.activeConversation!!.messages.isEmpty())
        assertEquals(2L, merged.activeConversation!!.replyEpoch)
        // A stale foreground draft cannot resurrect the turns either.
        val stale = background.copy(conversations = listOf(background.conversations.single().copy(draft = "draft")))
        assertTrue(mergeNativeChatChanges(background, stale, merged).activeConversation!!.messages.isEmpty())
    }

    @Test
    fun staleForegroundDraftPreservesBackgroundAnswerAndOtherChats() {
        val other = chat.copy(id = "two")
        val background = base.copy(conversations = listOf(chat.copy(messages = listOf(user, reply)), other))
        val foreground = base.copy(conversations = listOf(chat.copy(draft = "typing")))
        val merged = mergeNativeChatChanges(base, foreground, background)
        assertEquals(listOf(user, reply), merged.activeConversation!!.messages)
        assertEquals("typing", merged.activeConversation!!.draft)
        assertEquals(listOf("one", "two"), merged.conversations.map { it.id })
    }

    @Test
    fun preservesResponsePositionAndForegroundStreamingChanges() {
        val later = user.copy(id = "later", text = "next question")
        val partial = reply.copy(id = "stream", text = "par", isPartial = true)
        val initial = base.copy(conversations = listOf(chat.copy(messages = listOf(user, later, partial))))
        val current = initial.copy(conversations = listOf(chat.copy(messages = listOf(user, reply, later, partial))))
        val finished = partial.copy(text = "complete", isPartial = false)
        val incoming = initial.copy(conversations = listOf(chat.copy(messages = listOf(user, later, finished))))
        assertEquals(listOf(user, reply, later, finished), mergeNativeChatChanges(initial, incoming, current).activeConversation!!.messages)
    }

    @Test
    fun intentionalDeletionsDoNotResurrectKnownTurnsOrConversations() {
        val current = base.copy(conversations = listOf(chat.copy(messages = listOf(user, reply))))
        val cleared = base.copy(conversations = listOf(chat.copy(messages = emptyList())))
        assertTrue(mergeNativeChatChanges(base, cleared, current).activeConversation!!.messages.isEmpty())
        assertTrue(mergeNativeChatChanges(base, NativeChatArchive(), current).conversations.isEmpty())
        assertTrue(mergeNativeChatChanges(base, base, NativeChatArchive()).conversations.isEmpty())
    }

    @Test
    fun repeatedStaleSnapshotsDoNotErasePreviouslyMergedBackgroundTurn() {
        val edited = base.copy(conversations = listOf(chat.copy(draft = "a")))
        val current = base.copy(conversations = listOf(chat.copy(messages = listOf(user, reply))))
        val first = mergeNativeChatChanges(base, edited, current)
        val second = mergeNativeChatChanges(edited, edited.copy(conversations = listOf(chat.copy(draft = "ab"))), first)
        assertEquals(listOf(user, reply), second.activeConversation!!.messages)
        assertEquals("ab", second.activeConversation!!.draft)
    }
}

package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeChatStarTest {
    private fun message(id: String, sender: String = CHAT_ROLE_USER, timestamp: Long = 10) = ModelChatMessage(
        id = id,
        sender = sender,
        provider = if (sender == CHAT_ROLE_ASSISTANT) AiProvider.CLAUDE else null,
        text = "text $id",
        timestamp = timestamp,
    )

    private val conversation = NativeChatConversation(
        id = "c",
        createdAtEpochMs = 1,
        messages = listOf(
            message(NATIVE_CHAT_WELCOME_MESSAGE_ID, CHAT_ROLE_ASSISTANT),
            message("u1"),
            message("a1", CHAT_ROLE_ASSISTANT),
            message("u2"),
            message("queued").copy(isQueued = true),
        ),
    )

    @Test
    fun togglingStarsAndUnstarsRealTurns() {
        val starred = conversation.withStarToggled("a1").withStarToggled("u1")
        assertEquals(listOf("a1", "u1"), starred.starredMessageIds)
        // The jump list follows conversation order, not star order.
        assertEquals(listOf("u1", "a1"), starred.starredMessages.map { it.id })

        assertEquals(listOf("u1"), starred.withStarToggled("a1").starredMessageIds)
    }

    @Test
    fun welcomeQueuedAndUnknownMessagesCannotBeStarred() {
        listOf(NATIVE_CHAT_WELCOME_MESSAGE_ID, "queued", "missing").forEach { id ->
            assertSame(conversation, conversation.withStarToggled(id), id)
        }
    }

    @Test
    fun normalizationDropsStarsForMissingMessagesAndDuplicates() {
        val archive = NativeChatArchive(
            activeConversationId = "c",
            conversations = listOf(conversation.copy(starredMessageIds = listOf("u1", "gone", "u1"))),
        )
        assertEquals(listOf("u1"), assertNotNull(archive.normalized()).conversations.single().starredMessageIds)
    }

    @Test
    fun starsSurviveTheCodecAndOlderArchivesDecodeWithoutThem() {
        val archive = NativeChatArchive(
            activeConversationId = "c",
            conversations = listOf(conversation.withStarToggled("a1")),
        )
        val decoded = assertNotNull(NativeChatArchiveCodec.decode(NativeChatArchiveCodec.encode(archive)))
        assertEquals(listOf("a1"), decoded.conversations.single().starredMessageIds)

        val legacy = """{"version":1,"activeConversationId":"c","conversations":[{"id":"c","createdAtEpochMs":1}]}"""
        assertTrue(assertNotNull(NativeChatArchiveCodec.decode(legacy.encodeToByteArray())).conversations.single().starredMessageIds.isEmpty())
    }

    @Test
    fun foregroundStarChangesSurviveAMergeWithAWorkerReply() {
        val base = NativeChatArchive(activeConversationId = "c", conversations = listOf(conversation))
        val incoming = base.copy(conversations = listOf(conversation.withStarToggled("u1")))
        val current = base.copy(
            conversations = listOf(conversation.copy(messages = conversation.messages + message("worker", CHAT_ROLE_ASSISTANT)))
        )

        val merged = mergeNativeChatChanges(base, incoming, current).conversations.single()

        assertEquals(listOf("u1"), merged.starredMessageIds)
        assertTrue(merged.messages.any { it.id == "worker" })
    }

    @Test
    fun branchesCarryStarsForTheMessagesTheyCopy() {
        val source = conversation.withStarToggled("u1").withStarToggled("u2")
        var next = 0
        val branch = assertNotNull(source.forkAt("a1", newConversationId = "b", nowEpochMs = 99) { "m${next++}" })

        // u2 comes after the branch point, so only u1's copy stays starred.
        assertEquals(listOf("fork-m0/u1"), branch.starredMessageIds)
        assertEquals(listOf("u1", "u2"), source.starredMessageIds)
    }

    @Test
    fun notesAreOneTrimmedClippedLineOnStarredMessagesOnly() {
        val starred = conversation.withStarToggled("u1")
        assertEquals(mapOf("u1" to "check this later"), starred.withStarNote("u1", "  check\n this\tlater ").starNotes)
        assertEquals(MAX_NATIVE_STAR_NOTE_CHARS, starred.withStarNote("u1", "x".repeat(500)).starNotes.getValue("u1").length)
        assertSame(starred, starred.withStarNote("a1", "not starred"))
        assertTrue(starred.withStarNote("u1", "keep").withStarNote("u1", "  ").starNotes.isEmpty())
    }

    @Test
    fun unstarringDropsTheNote() {
        val noted = conversation.withStarToggled("u1").withStarNote("u1", "keep")
        assertTrue(noted.withStarToggled("u1").starNotes.isEmpty())
    }

    @Test
    fun normalizationKeepsNotesOnlyForRetainedStars() {
        val archive = NativeChatArchive(
            activeConversationId = "c",
            conversations = listOf(
                conversation.copy(
                    starredMessageIds = listOf("u1", "gone"),
                    starNotes = mapOf("u1" to " a\nb ", "gone" to "x", "a1" to "unstarred"),
                )
            ),
        )
        assertEquals(mapOf("u1" to "a b"), assertNotNull(archive.normalized()).conversations.single().starNotes)
    }

    @Test
    fun notesSurviveTheCodecAndOlderArchivesDecodeWithoutThem() {
        val archive = NativeChatArchive(
            activeConversationId = "c",
            conversations = listOf(conversation.withStarToggled("a1").withStarNote("a1", "good answer")),
        )
        val decoded = assertNotNull(NativeChatArchiveCodec.decode(NativeChatArchiveCodec.encode(archive)))
        assertEquals(mapOf("a1" to "good answer"), decoded.conversations.single().starNotes)

        val legacy = """{"version":1,"activeConversationId":"c","conversations":[{"id":"c","createdAtEpochMs":1}]}"""
        assertTrue(assertNotNull(NativeChatArchiveCodec.decode(legacy.encodeToByteArray())).conversations.single().starNotes.isEmpty())
    }

    @Test
    fun foregroundNoteEditsSurviveAMergeWithAWorkerReply() {
        val starred = conversation.withStarToggled("u1")
        val base = NativeChatArchive(activeConversationId = "c", conversations = listOf(starred))
        val incoming = base.copy(conversations = listOf(starred.withStarNote("u1", "remember")))
        val current = base.copy(
            conversations = listOf(starred.copy(messages = starred.messages + message("worker", CHAT_ROLE_ASSISTANT)))
        )

        val merged = mergeNativeChatChanges(base, incoming, current).conversations.single()

        assertEquals(mapOf("u1" to "remember"), merged.starNotes)
        assertTrue(merged.messages.any { it.id == "worker" })
    }

    @Test
    fun branchesCarryNotesForTheMessagesTheyCopy() {
        val source = conversation.withStarToggled("u1").withStarToggled("u2")
            .withStarNote("u1", "first").withStarNote("u2", "second")
        var next = 0
        val branch = assertNotNull(source.forkAt("a1", newConversationId = "b", nowEpochMs = 99) { "m${next++}" })

        assertEquals(mapOf("fork-m0/u1" to "first"), branch.starNotes)
        assertEquals(mapOf("u1" to "first", "u2" to "second"), source.starNotes)
    }

    @Test
    fun notesAreRedactedFromToString() {
        val noted = conversation.withStarToggled("u1").withStarNote("u1", "secret plan")
        assertTrue("secret plan" !in noted.toString())
    }
}

package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeChatForkTest {
    private fun user(id: String, text: String = id) =
        ModelChatMessage(id = id, sender = CHAT_ROLE_USER, text = text, timestamp = 10)

    private fun reply(id: String, provider: AiProvider, model: String? = provider.defaultModel) =
        ModelChatMessage(
            id = id,
            sender = CHAT_ROLE_ASSISTANT,
            provider = provider,
            modelName = model,
            text = "reply $id",
            timestamp = 20,
        )

    private val welcome = ModelChatMessage(
        id = NATIVE_CHAT_WELCOME_MESSAGE_ID,
        sender = CHAT_ROLE_ASSISTANT,
        provider = AiProvider.ALL,
        text = "Welcome",
    )

    private fun compareConversation() = NativeChatConversation(
        id = "source",
        title = "Trip plan",
        createdAtEpochMs = 1,
        updatedAtEpochMs = 2,
        messages = listOf(
            welcome,
            user("u1"),
            reply("a1-claude", AiProvider.CLAUDE),
            reply("a1-gemini", AiProvider.GEMINI),
            user("u2"),
            reply("a2-claude", AiProvider.CLAUDE),
            reply("a2-gemini", AiProvider.GEMINI, model = "gemini-custom-preview"),
            reply("a2-openai", AiProvider.CHATGPT),
            user("u3"),
        ),
        draft = "unsent",
        selectedProvider = AiProvider.ALL,
        selectedModel = "all",
        replyEpoch = 7,
    )

    private fun fork(source: NativeChatConversation, messageId: String): NativeChatConversation? {
        var next = 0
        return source.forkAt(messageId, newConversationId = "branch", nowEpochMs = 99) { "m${next++}" }
    }

    @Test
    fun branchKeepsEarlierTurnsAndOnlyTheChosenReplyOfItsTurn() {
        val branch = assertNotNull(fork(compareConversation(), "a2-gemini"))

        assertEquals(
            listOf("Welcome", "u1", "reply a1-claude", "reply a1-gemini", "u2", "reply a2-gemini"),
            branch.messages.map { it.text }
        )
        assertEquals("branch", branch.id)
        assertEquals(99, branch.createdAtEpochMs)
        assertEquals(99, branch.updatedAtEpochMs)
        assertEquals("", branch.draft)
        assertEquals(0, branch.replyEpoch)
        assertEquals(
            NativeChatForkOrigin("source", "a2-gemini", inheritedMessageCount = 6, forkedAtEpochMs = 99),
            branch.forkedFrom
        )
        // Original timestamps are kept so the branch reads like the source.
        assertEquals(20, branch.messages.last().timestamp)
    }

    @Test
    fun branchGivesCopiedMessagesFreshIdsButKeepsTheWelcomeId() {
        val source = compareConversation()
        val branch = assertNotNull(fork(source, "a1-claude"))

        assertEquals(NATIVE_CHAT_WELCOME_MESSAGE_ID, branch.messages.first().id)
        val copiedIds = branch.messages.drop(1).map { it.id }
        assertEquals(listOf("m0", "m1"), copiedIds)
        assertTrue(copiedIds.none { id -> source.messages.any { it.id == id } })
    }

    @Test
    fun branchFromCompareContinuesWithTheReplyProvider() {
        val source = compareConversation()

        val claude = assertNotNull(fork(source, "a2-claude"))
        assertEquals(AiProvider.CLAUDE, claude.selectedProvider)
        assertEquals(AiProvider.CLAUDE.defaultModel, claude.selectedModel)

        // An unknown or resolved model id falls back to the provider default in compare mode.
        val gemini = assertNotNull(fork(source, "a2-gemini"))
        assertEquals(AiProvider.GEMINI, gemini.selectedProvider)
        assertEquals(AiProvider.GEMINI.defaultModel, gemini.selectedModel)
    }

    @Test
    fun branchFromSingleProviderChatKeepsTheChosenModelAndMode() {
        val source = NativeChatConversation(
            id = "source",
            createdAtEpochMs = 1,
            messages = listOf(user("u1"), reply("a1", AiProvider.CHATGPT, model = "gpt-5.6-2026-09-01")),
            selectedProvider = AiProvider.CHATGPT,
            selectedModel = "gpt-5.6-luna",
            apiProcessingMode = ApiProcessingMode.FLEX,
        )

        val branch = assertNotNull(fork(source, "a1"))

        assertEquals("gpt-5.6-luna", branch.selectedModel)
        assertEquals(AiProvider.CHATGPT.normalizeApiProcessingMode(ApiProcessingMode.FLEX), branch.apiProcessingMode)
    }

    @Test
    fun branchDropsQueuedTurns() {
        val source = NativeChatConversation(
            id = "source",
            createdAtEpochMs = 1,
            messages = listOf(
                user("u1"),
                reply("a1", AiProvider.CLAUDE),
                user("u-queued").copy(isQueued = true),
            ),
        )

        val branch = assertNotNull(fork(source, "a1"))

        assertTrue(branch.messages.none { it.isQueued })
        assertEquals(listOf("u1", "reply a1"), branch.messages.map { it.text })
    }

    @Test
    fun onlyCompletedAssistantRepliesCanBeBranched() {
        val source = compareConversation().let { conversation ->
            conversation.copy(
                messages = conversation.messages + listOf(
                    reply("partial", AiProvider.CLAUDE).copy(isPartial = true),
                    reply("error", AiProvider.CLAUDE).copy(isError = true),
                    reply("simulated", AiProvider.CLAUDE).copy(isSimulated = true),
                )
            )
        }

        listOf(NATIVE_CHAT_WELCOME_MESSAGE_ID, "u1", "partial", "error", "simulated", "missing").forEach { id ->
            assertFalse(source.canForkAt(id), id)
            assertNull(fork(source, id), id)
        }
        assertTrue(source.canForkAt("a1-claude"))
    }

    @Test
    fun branchTitleDoesNotStackPrefixes() {
        val branch = assertNotNull(fork(compareConversation(), "a1-claude"))
        assertEquals("Branch: Trip plan", branch.title)

        val nested = assertNotNull(fork(branch.copy(id = "branch"), branch.messages.last().id))
        assertEquals("Branch: Trip plan", nested.title)
    }

    @Test
    fun forkOriginSurvivesTheCodecAndOlderArchivesStillDecode() {
        val branch = assertNotNull(fork(compareConversation(), "a1-claude"))
        val archive = NativeChatArchive(activeConversationId = branch.id, conversations = listOf(branch))

        val decoded = assertNotNull(NativeChatArchiveCodec.decode(NativeChatArchiveCodec.encode(archive)))
        assertEquals(branch.forkedFrom, decoded.conversations.single().forkedFrom)

        val legacy = """{"version":1,"activeConversationId":"c","conversations":[{"id":"c","createdAtEpochMs":1}]}"""
        val legacyDecoded = assertNotNull(NativeChatArchiveCodec.decode(legacy.encodeToByteArray()))
        assertNull(legacyDecoded.conversations.single().forkedFrom)
    }

    @Test
    fun branchSurvivesAMergeWithConcurrentWorkerChangesToTheSource() {
        val source = compareConversation()
        val base = NativeChatArchive(activeConversationId = source.id, conversations = listOf(source))
        val branch = assertNotNull(fork(source, "a1-claude"))
        val incoming = base.copy(activeConversationId = branch.id, conversations = listOf(branch, source))
        val workerReply = reply("worker", AiProvider.CLAUDE)
        val current = base.copy(conversations = listOf(source.copy(messages = source.messages + workerReply)))

        val merged = mergeNativeChatChanges(base, incoming, current)

        assertEquals(branch.id, merged.activeConversationId)
        assertEquals(branch, merged.conversations.first { it.id == branch.id })
        assertTrue(merged.conversations.first { it.id == source.id }.messages.any { it.id == "worker" })
    }

    @Test
    fun debugStringsRedactForkIdentifiers() {
        val branch = assertNotNull(fork(compareConversation(), "a1-claude"))
        val text = branch.toString() + branch.forkedFrom.toString()
        assertFalse(text.contains("=source"))
        assertFalse(text.contains("a1-claude"))
        assertFalse(text.contains("Trip plan"))
    }
}

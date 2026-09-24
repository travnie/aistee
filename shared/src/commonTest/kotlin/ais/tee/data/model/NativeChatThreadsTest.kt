package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeChatThreadsTest {
    @Test
    fun titleUsesFirstLineLikeTextWithoutGrowingForever() {
        assertEquals("New conversation", nativeConversationTitle("   \n  "))
        assertEquals("Hello world from Aistee", nativeConversationTitle("  Hello   world\nfrom Aistee  "))

        val longTitle = nativeConversationTitle("x".repeat(200))
        assertEquals(56, longTitle.length)
        assertTrue(longTitle.endsWith("…"))
    }

    @Test
    fun normalizationDropsDuplicateIdsAndRepairsActiveConversation() {
        val older = NativeChatConversation(
            id = " older ",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2
        )
        val newer = NativeChatConversation(
            id = "newer",
            title = "  ",
            createdAtEpochMs = 3,
            updatedAtEpochMs = 4,
            selectedProvider = AiProvider.GEMINI,
            selectedModel = ""
        )
        val normalized = assertNotNull(
            NativeChatArchive(
                activeConversationId = "missing",
                conversations = listOf(older, newer, older.copy(title = "duplicate"))
            ).normalized()
        )
        val activeConversation = assertNotNull(normalized.activeConversation)

        assertEquals(2, normalized.conversations.size)
        assertEquals("newer", normalized.activeConversationId)
        assertEquals(DEFAULT_NATIVE_CONVERSATION_TITLE, activeConversation.title)
        assertEquals(AiProvider.GEMINI.defaultModel, activeConversation.selectedModel)
        assertEquals("older", normalized.conversations.first().id)
    }

    @Test
    fun normalizationKeepsSupportedApiModeAndDropsUnsupportedMode() {
        val openAi = NativeChatConversation(
            id = "openai",
            createdAtEpochMs = 1,
            selectedProvider = AiProvider.CHATGPT,
            selectedModel = AiProvider.CHATGPT.defaultModel,
            apiMode = NativeApiMode.FLEX,
        )
        val claude = NativeChatConversation(
            id = "claude",
            createdAtEpochMs = 2,
            selectedProvider = AiProvider.CLAUDE,
            selectedModel = AiProvider.CLAUDE.defaultModel,
            apiMode = NativeApiMode.FLEX,
        )
        val normalized = assertNotNull(
            NativeChatArchive(
                activeConversationId = openAi.id,
                conversations = listOf(openAi, claude),
            ).normalized()
        )

        assertEquals(NativeApiMode.FLEX, normalized.conversations.first { it.id == "openai" }.apiMode)
        assertEquals(NativeApiMode.AUTO, normalized.conversations.first { it.id == "claude" }.apiMode)
    }

    @Test
    fun codecDoesNotPersistProviderReplayState() {
        val conversation = NativeChatConversation(
            id = "c1",
            title = "Test",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
            messages = listOf(
                ModelChatMessage(
                    id = "m1",
                    sender = CHAT_ROLE_ASSISTANT,
                    provider = AiProvider.CHATGPT,
                    text = "answer",
                    providerReplayState = "provider-secret-handle"
                )
            )
        )
        val archive = NativeChatArchive(
            activeConversationId = conversation.id,
            conversations = listOf(conversation)
        )
        val encoded = NativeChatArchiveCodec.encode(archive)
        val decoded = assertNotNull(NativeChatArchiveCodec.decode(encoded))
        val restoredConversation = assertNotNull(decoded.activeConversation)
        val restoredMessage = restoredConversation.messages.single()

        assertEquals("answer", restoredMessage.text)
        assertNull(restoredMessage.providerReplayState)
    }

    @Test
    fun codecPersistsConversationDraft() {
        val conversation = NativeChatConversation(
            id = "c1",
            createdAtEpochMs = 1,
            draft = "half-written prompt\nwith details"
        )
        val archive = NativeChatArchive(
            activeConversationId = conversation.id,
            conversations = listOf(conversation)
        )

        val decoded = assertNotNull(NativeChatArchiveCodec.decode(NativeChatArchiveCodec.encode(archive)))

        assertEquals("half-written prompt\nwith details", assertNotNull(decoded.activeConversation).draft)
    }

    @Test
    fun unsupportedArchiveVersionIsRejected() {
        val archive = NativeChatArchive(
            version = NATIVE_CHAT_ARCHIVE_VERSION + 1,
            activeConversationId = "c1",
            conversations = listOf(
                NativeChatConversation(
                    id = "c1",
                    createdAtEpochMs = 1
                )
            )
        )

        assertNull(NativeChatArchiveCodec.decode(NativeChatArchiveCodec.encode(archive)))
    }
}

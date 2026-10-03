package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class NativeChatSourceAssetTest {
    private companion object {
        const val SOURCE_ID = "private-library-asset-id"
    }

    @Test
    fun sourceIdsAndStagedDraftSourcesSurviveLocalArchiveRoundTrip() {
        val user = ModelChatMessage(
            id = "u1",
            sender = CHAT_ROLE_USER,
            text = "Use these notes",
            sourceAssetIds = listOf(SOURCE_ID),
        )
        val conversation = NativeChatConversation(
            id = "c1",
            createdAtEpochMs = 1,
            messages = listOf(user),
            draft = "unsent excerpt",
            draftSourceAssetIds = listOf(SOURCE_ID),
        )

        val decoded = assertNotNull(
            NativeChatArchiveCodec.decode(
                NativeChatArchiveCodec.encode(
                    NativeChatArchive(
                        activeConversationId = conversation.id,
                        conversations = listOf(conversation),
                    ),
                ),
            ),
        )

        assertEquals(listOf(SOURCE_ID), decoded.activeConversation?.messages?.single()?.sourceAssetIds)
        assertEquals(listOf(SOURCE_ID), decoded.activeConversation?.draftSourceAssetIds)
    }

    @Test
    fun localSourceIdsAreNeitherExportedNorPrinted() {
        val message = ModelChatMessage(
            id = "u1",
            sender = CHAT_ROLE_USER,
            text = "Use these notes",
            sourceAssetIds = listOf(SOURCE_ID),
        )
        val markdown = assertNotNull(renderChatMarkdown(listOf(message)))

        assertFalse(SOURCE_ID in markdown)
        assertFalse(SOURCE_ID in message.toString())
        assertFalse(
            SOURCE_ID in NativeChatConversation(
                id = "c1",
                createdAtEpochMs = 1,
                messages = listOf(message),
                draftSourceAssetIds = listOf(SOURCE_ID),
            ).toString(),
        )
    }
}

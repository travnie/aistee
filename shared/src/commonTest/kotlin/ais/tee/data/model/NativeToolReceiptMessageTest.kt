package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class NativeToolReceiptMessageTest {
    private val receipt = NativeToolReceipt(
        toolName = "docbench_inspect_text",
        inputScope = "SECRET_SCOPE",
        decision = CapabilityDecision.ALLOW,
        destination = "SECRET_DESTINATION",
        outcome = "SECRET_OUTCOME",
    )
    private val reply = ModelChatMessage(
        id = "m1",
        sender = CHAT_ROLE_ASSISTANT,
        provider = AiProvider.CLAUDE,
        text = "Done",
        toolReceipts = listOf(receipt),
    )

    @Test
    fun receiptsStayWithTheStoredReply() {
        val conversation = NativeChatConversation(
            id = "c1",
            createdAtEpochMs = 1,
            messages = listOf(ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = "inspect"), reply),
        )
        val encoded = NativeChatArchiveCodec.encode(
            NativeChatArchive(activeConversationId = conversation.id, conversations = listOf(conversation))
        )
        val decoded = assertNotNull(NativeChatArchiveCodec.decode(encoded))

        assertEquals(listOf(receipt), decoded.activeConversation?.messages?.last()?.toolReceipts)
    }

    @Test
    fun receiptsAreNeitherExportedNorPrinted() {
        val markdown = assertNotNull(
            renderChatMarkdown(listOf(ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = "inspect"), reply))
        )

        assertFalse("SECRET_SCOPE" in markdown)
        assertFalse("SECRET_DESTINATION" in markdown)
        assertFalse("SECRET_OUTCOME" in markdown)
        assertFalse("SECRET_SCOPE" in reply.toString())
        assertFalse("SECRET_DESTINATION" in reply.toString())
        assertFalse("SECRET_OUTCOME" in reply.toString())
        assertFalse("SECRET_SCOPE" in receipt.toString())
    }
}

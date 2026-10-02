package ais.tee.ui.viewmodel

import ais.tee.data.model.AiProvider
import ais.tee.data.model.CapabilityDecision
import ais.tee.data.model.NativeToolReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingToolReceiptTest {
    private val receipt = NativeToolReceipt(
        toolName = "codebench_generate_qr",
        inputScope = "Model-selected inline chat text",
        decision = CapabilityDecision.ALLOW,
        destination = "Project Library",
        outcome = "Artifact saved",
    )

    @Test
    fun firstCompletedToolCallCreatesPartialReplyWithReceipt() {
        val messages = upsertStreamingToolReceipt(
            messages = emptyList(),
            messageId = "stream-user-1-gemini",
            provider = AiProvider.GEMINI,
            model = "gemini-test",
            receipt = receipt,
        )

        assertEquals(1, messages.size)
        assertTrue(messages.single().isPartial)
        assertEquals(listOf(receipt), messages.single().toolReceipts)
    }

    @Test
    fun laterReceiptStaysOnTheSamePartialReply() {
        val first = upsertStreamingToolReceipt(
            messages = emptyList(),
            messageId = "stream-user-1-gemini",
            provider = AiProvider.GEMINI,
            model = "gemini-test",
            receipt = receipt,
        )
        val second = receipt.copy(toolName = "docbench_inspect_text", destination = "Current model turn")

        val messages = upsertStreamingToolReceipt(
            messages = first,
            messageId = "stream-user-1-gemini",
            provider = AiProvider.GEMINI,
            model = "gemini-test",
            receipt = second,
        )

        assertEquals(listOf(receipt, second), messages.single().toolReceipts)
    }
}

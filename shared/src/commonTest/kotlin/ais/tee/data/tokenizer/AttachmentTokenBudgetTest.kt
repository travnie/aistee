package ais.tee.data.tokenizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentTokenBudgetTest {
    private fun plan(context: Int, prompt: Int, reserve: Int, vararg tokens: Int) =
        planAttachmentTokenBudget(
            encodingLabel = "o200k_base",
            contextTokens = context,
            promptTokens = prompt,
            reservedOutputTokens = reserve,
            attachments = tokens.mapIndexed { index, value -> AttachmentTokens("file$index", value) },
        )

    @Test
    fun everythingFits() {
        val budget = plan(1_000, 100, 100, 300, 400)
        assertTrue(budget.fitsCompletely)
        assertEquals(100, budget.readablePercent)
        assertEquals(800, budget.availableTokens)
    }

    @Test
    fun firstOverflowingFileIsCutAndLaterOnesExcluded() {
        val budget = plan(1_000, 100, 100, 500, 500, 200)
        assertEquals(listOf(500, 300, 0), budget.allowances.map { it.includedTokens })
        assertTrue(budget.allowances[0].isComplete)
        assertFalse(budget.allowances[1].isComplete)
        assertTrue(budget.allowances[2].isExcluded)
        assertEquals(66, budget.readablePercent)
        assertFalse(budget.fitsCompletely)
    }

    @Test
    fun promptLargerThanContextLeavesNoRoom() {
        val budget = plan(1_000, 1_500, 100, 10)
        assertEquals(0, budget.availableTokens)
        assertEquals(0, budget.readablePercent)
    }

    @Test
    fun noAttachmentsIsFullyReadable() {
        assertEquals(100, plan(10, 0, 0).readablePercent)
    }

    @Test
    fun emptyAttachmentIsNotExcluded() {
        val budget = plan(0, 0, 0, 0)
        assertFalse(budget.allowances.single().isExcluded)
        assertEquals(100, budget.readablePercent)
    }

    @Test
    fun negativeInputsAreClamped() {
        val budget = plan(-5, -5, -5, -10, 5)
        assertEquals(0, budget.contextTokens)
        assertEquals(listOf(0, 5), budget.allowances.map { it.attachment.tokens })
        assertEquals(listOf(0, 0), budget.allowances.map { it.includedTokens })
        assertEquals(0, budget.readablePercent)
    }

    @Test
    fun hugePromptAndReserveDoNotWrapAround() {
        val budget = plan(10, Int.MAX_VALUE, Int.MAX_VALUE, 5)
        assertEquals(0, budget.availableTokens)
        assertEquals(0, budget.readablePercent)
    }

    @Test
    fun hugeCountsDoNotOverflowPercent() {
        val budget = plan(Int.MAX_VALUE, 0, 0, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(3L * Int.MAX_VALUE, budget.attachmentTokens)
        assertEquals(33, budget.readablePercent)
    }
}

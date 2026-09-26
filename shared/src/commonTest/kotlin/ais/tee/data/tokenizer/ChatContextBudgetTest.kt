package ais.tee.data.tokenizer

import ais.tee.data.model.AiProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatContextBudgetTest {
    /** One token per character keeps the arithmetic readable. */
    private val counter = object : TokenCounter {
        override val encodingLabel = "test"
        override fun count(text: String): Int = text.length
    }

    @Test
    fun onlyPublishedWindowsAreKnown() {
        assertEquals(200_000, knownChatContextWindowTokens(AiProvider.CLAUDE, "claude-haiku-4-5-20251001"))
        assertEquals(1_000_000, knownChatContextWindowTokens(AiProvider.CLAUDE, "claude-sonnet-5"))
        assertNull(knownChatContextWindowTokens(AiProvider.CHATGPT, "gpt-5.6"))
        assertNull(knownChatContextWindowTokens(AiProvider.ALL, "all"))
    }

    @Test
    fun fittingContextHasNoWarning() {
        val budget = planChatContextBudget(counter, 100, "hi", "system", listOf("a", "b"), reservedOutputTokens = 10)
        assertTrue(budget.fitsCompletely)
        assertNull(chatContextWarning(budget))
    }

    @Test
    fun oversizedHistoryReportsReadableShare() {
        // 100 - 10 prompt - 20 reserve = 70 free; 10 system + 90 history = 100 context tokens.
        val budget = planChatContextBudget(
            counter,
            contextTokens = 100,
            prompt = "p".repeat(10),
            systemInstruction = "s".repeat(10),
            history = listOf("h".repeat(40), "h".repeat(50)),
            reservedOutputTokens = 20,
        )
        val warning = chatContextWarning(budget)!!

        assertEquals(70, warning.readablePercent)
        assertEquals(
            "The model can read about 70% of this chat's context (counted with test, not the provider's own count).",
            warning.message,
        )
    }

    @Test
    fun blankSystemInstructionAndEmptyHistoryAreSkipped() {
        val budget = planChatContextBudget(counter, 10, "prompt", " ", emptyList(), reservedOutputTokens = 0)
        assertTrue(budget.allowances.isEmpty())
        assertNull(chatContextWarning(budget))
    }
}

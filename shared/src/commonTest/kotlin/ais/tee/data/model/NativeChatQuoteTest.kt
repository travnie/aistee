package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeChatQuoteTest {
    @Test
    fun quotesEveryLineAndLeavesAFreshLineBelow() {
        assertEquals("> one\n>\n> two\n\n", quoteIntoNativeChatDraft("one\r\n\r\ntwo", draft = "", maxChars = 100))
    }

    @Test
    fun trimsSurroundingBlankLinesAndTrailingSpaces() {
        assertEquals("> one\n\n", quoteIntoNativeChatDraft("\n  \none   \n\n", draft = "", maxChars = 100))
    }

    @Test
    fun keepsIndentationAndNestsExistingQuotes() {
        assertEquals(">   code\n> > earlier\n\n", quoteIntoNativeChatDraft("  code\n> earlier", draft = "", maxChars = 100))
    }

    @Test
    fun appendsBelowAnExistingDraft() {
        assertEquals(
            "My thought\n\n> quoted\n\n",
            quoteIntoNativeChatDraft("quoted", draft = "My thought \n\n", maxChars = 100),
        )
    }

    @Test
    fun blankOrOversizedQuotesAreRejected() {
        assertNull(quoteIntoNativeChatDraft(" \n\t\n", draft = "keep", maxChars = 100))
        assertNull(quoteIntoNativeChatDraft("x".repeat(20), draft = "", maxChars = 10))
    }
}

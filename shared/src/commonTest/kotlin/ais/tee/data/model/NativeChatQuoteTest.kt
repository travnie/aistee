package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun appendedTextLandsBelowTheDraftUnquoted() {
        assertEquals("line one\n  indented\n", appendToNativeChatDraft("\r\nline one\r\n  indented  \n\n", draft = "", maxChars = 100))
        assertEquals("My note\n\n> kept\n", appendToNativeChatDraft("> kept", draft = "My note  \n", maxChars = 100))
    }

    @Test
    fun blankOrOversizedAppendsAreRejected() {
        assertNull(appendToNativeChatDraft(" \n\t\n", draft = "keep", maxChars = 100))
        assertNull(appendToNativeChatDraft("x".repeat(20), draft = "", maxChars = 10))
    }

    @Test
    fun onlyTextAssetsCanBeInserted() {
        fun asset(type: String) = ProjectLibraryAsset(
            id = "a", projectId = DEFAULT_PROJECT_ID, title = "t", mediaType = type,
            fileName = "a.bin", sizeBytes = 1, createdAtEpochMs = 1,
        )
        assertTrue(asset("text/markdown").canInsertIntoDraft())
        assertTrue(asset("Text/Plain").canInsertIntoDraft())
        assertFalse(asset("image/svg+xml").canInsertIntoDraft())
    }

    @Test
    fun blankOrOversizedQuotesAreRejected() {
        assertNull(quoteIntoNativeChatDraft(" \n\t\n", draft = "keep", maxChars = 100))
        assertNull(quoteIntoNativeChatDraft("x".repeat(20), draft = "", maxChars = 10))
    }
}

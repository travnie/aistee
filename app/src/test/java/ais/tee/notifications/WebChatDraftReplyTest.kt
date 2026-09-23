package ais.tee.notifications

import ais.tee.data.preferences.WebChatDraftStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebChatDraftReplyTest {
    @Test
    fun acceptsExactNonBlankDraftWithinBound() {
        val text = "  draft reply\nwith spacing  "
        assertEquals(text, WebChatDraftReply.normalizeDraftReplyText(text))
    }

    @Test
    fun rejectsBlankAndOversizedDrafts() {
        assertNull(WebChatDraftReply.normalizeDraftReplyText("   "))
        assertNull(
            WebChatDraftReply.normalizeDraftReplyText(
                "x".repeat(WebChatDraftStore.MAX_DRAFT_CHARS + 1)
            )
        )
    }
}

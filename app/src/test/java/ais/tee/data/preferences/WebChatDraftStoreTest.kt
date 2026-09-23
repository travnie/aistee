package ais.tee.data.preferences

import ais.tee.data.model.WebAiService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebChatDraftStoreTest {
    @Test
    fun stagesPreservesAndConsumesDraftWithoutLeakingText() {
        val root = tempRoot()
        try {
            val store = WebChatDraftStore(root)
            val text = "  keep my spacing\nsecond line  "
            val staged = requireNotNull(store.stage(WebAiService.CLAUDE, text))

            assertEquals(text, store.peek(WebAiService.CLAUDE)?.text)
            assertFalse(staged.toString().contains(text))
            assertFalse(store.consume(WebAiService.CLAUDE, "stale-id"))
            assertEquals(staged.id, store.peek(WebAiService.CLAUDE)?.id)
            assertTrue(store.consume(WebAiService.CLAUDE, staged.id))
            assertNull(store.peek(WebAiService.CLAUDE))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun replacingProviderDraftInvalidatesOlderId() {
        val root = tempRoot()
        try {
            val store = WebChatDraftStore(root)
            val first = requireNotNull(store.stage(WebAiService.CHATGPT, "first"))
            val second = requireNotNull(store.stage(WebAiService.CHATGPT, "second"))

            assertFalse(store.consume(WebAiService.CHATGPT, first.id))
            assertEquals(second.id, store.peek(WebAiService.CHATGPT)?.id)
            assertEquals("second", store.peek(WebAiService.CHATGPT)?.text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsBlankAndOversizedDrafts() {
        val root = tempRoot()
        try {
            val store = WebChatDraftStore(root)
            assertNull(store.stage(WebAiService.GEMINI, "   "))
            assertNull(
                store.stage(
                    WebAiService.GEMINI,
                    "x".repeat(WebChatDraftStore.MAX_DRAFT_CHARS + 1)
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "aistee-web-draft-${System.nanoTime()}").apply {
            check(mkdirs())
        }
}

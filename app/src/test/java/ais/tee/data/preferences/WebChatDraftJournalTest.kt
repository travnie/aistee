package ais.tee.data.preferences

import ais.tee.data.model.WebAiService
import org.junit.Assert.*
import org.junit.Test

class WebChatDraftJournalTest {
    private class Fake : WebDraftJournalPersistence {
        var record: String? = null
        var canWrite = true
        override fun read() = record
        override fun hasStoredData() = record != null
        override fun write(text: String): Boolean {
            if (!canWrite) return false
            record = text
            return true
        }
        override fun clear(): Boolean { record = null; return true }
    }

    @Test fun restoresOlderTextWithoutLosingCurrentDraft() {
        val data = Fake()
        var now = 1_000_000L
        val journal = WebChatDraftJournal(data) { now }
        val provider = WebAiService.CHATGPT
        assertTrue(journal.save(provider, "original long text " + "a".repeat(100)))
        assertTrue(journal.checkpoint(provider))
        val saved = journal.load(provider).page.versions.single().id
        now += 5_000
        assertTrue(journal.save(provider, "replacement content"))
        assertTrue(journal.restore(provider, saved))
        val recovered = journal.load(provider).page
        assertTrue(recovered.current.startsWith("original"))
        assertTrue(recovered.versions.any { it.text == "replacement content" })
        assertEquals("", journal.load(WebAiService.CLAUDE).page.current)
    }

    @Test fun autosavesAndLimitsHistory() {
        val data = Fake()
        var now = 1_000_000L
        val journal = WebChatDraftJournal(data) { now }
        repeat(16) { index ->
            assertTrue(journal.save(WebAiService.CHATGPT, "long " + index + "x".repeat(110)))
            now += 125_000
        }
        val snapshot = journal.load(WebAiService.CHATGPT)
        assertEquals(8, snapshot.page.versions.size)
        assertTrue(snapshot.readable)
    }

    @Test fun corruptCiphertextCannotBeOverwrittenByAutosave() {
        val fake = Fake().apply { record = "{bad" }
        val journal = WebChatDraftJournal(fake)
        assertFalse(journal.load(WebAiService.CHATGPT).readable)
        assertFalse(journal.save(WebAiService.CHATGPT, "valuable"))
        assertEquals("{bad", fake.record)
        assertTrue(journal.reset())
        assertTrue(journal.save(WebAiService.CHATGPT, "new"))
    }

    @Test fun failedDiskWriteRetainsPreviousDraft() {
        val fake = Fake()
        val journal = WebChatDraftJournal(fake)
        assertTrue(journal.save(WebAiService.CHATGPT, "safe old text"))
        fake.canWrite = false
        assertFalse(journal.save(WebAiService.CHATGPT, "unsaved new text"))
        assertEquals("safe old text", journal.load(WebAiService.CHATGPT).page.current)
    }

    @Test fun oversizeInputNeverTruncatesOrChangesStorage() {
        val data = Fake()
        val journal = WebChatDraftJournal(data)
        assertTrue(journal.save(WebAiService.CHATGPT, "original"))
        val raw = data.record
        assertFalse(journal.save(WebAiService.CHATGPT, "x".repeat(48_001)))
        assertFalse(journal.save(WebAiService.CHATGPT, "界".repeat(48_000)))
        assertEquals(raw, data.record)
    }
}

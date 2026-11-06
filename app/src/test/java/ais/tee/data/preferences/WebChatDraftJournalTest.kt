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

    @Test fun delayedAutosaveCannotUndoNewerCheckpoint() {
        val data = Fake()
        val first = WebChatDraftJournal(data)
        val second = WebChatDraftJournal(data)
        val oldTicket = first.nextWriteTicket()
        val newTicket = second.nextWriteTicket()
        val checkpointText = "newer checkpoint"
        assertTrue(second.checkpoint(WebAiService.CHATGPT, checkpointText, newTicket))
        assertTrue(first.save(WebAiService.CHATGPT, "stale autosave", oldTicket))
        val stored = first.load(WebAiService.CHATGPT).page
        assertEquals(checkpointText, stored.current)
        assertEquals(checkpointText, stored.versions.single().text)
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
        val replacement = "replacement content"
        assertTrue(journal.save(provider, replacement))
        assertTrue(journal.restore(provider, saved))
        val recovered = journal.load(provider).page
        assertTrue(recovered.current.startsWith("original long text"))
        assertTrue(recovered.versions.any { it.text == replacement })
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
        assertEquals((7..14).map { "long $it" + "x".repeat(110) }, snapshot.page.versions.map { it.text })
        assertTrue(snapshot.readable)
    }

    @Test fun corruptCiphertextCannotBeOverwrittenByAutosave() {
        val corruptRecord = "{bad"
        val fake = Fake().apply { record = corruptRecord }
        val journal = WebChatDraftJournal(fake)
        assertFalse(journal.load(WebAiService.CHATGPT).readable)
        assertFalse(journal.save(WebAiService.CHATGPT, "valuable"))
        assertEquals(corruptRecord, fake.record)
        assertTrue(journal.reset())
        assertTrue(journal.save(WebAiService.CHATGPT, "new"))
    }

    @Test fun failedDiskWriteRetainsPreviousDraft() {
        val fake = Fake()
        val journal = WebChatDraftJournal(fake)
        val safeText = "safe old text"
        assertTrue(journal.save(WebAiService.CHATGPT, safeText))
        fake.canWrite = false
        assertFalse(journal.save(WebAiService.CHATGPT, "unsaved new text"))
        assertEquals(safeText, journal.load(WebAiService.CHATGPT).page.current)
    }

    @Test fun oversizeInputNeverTruncatesOrChangesStorage() {
        val data = Fake()
        val journal = WebChatDraftJournal(data)
        assertTrue(journal.save(WebAiService.CHATGPT, "original"))
        val raw = data.record
        assertFalse(journal.save(WebAiService.CHATGPT, "x".repeat(48_001)))
        assertFalse(journal.save(WebAiService.CHATGPT, "界".repeat(48_001)))
        assertEquals(raw, data.record)
    }
    @Test fun restoringOldestPointKeepsUnpersistedEditorChanges() {
        val data = Fake()
        var now = 1_000_000L
        val journal = WebChatDraftJournal(data) { now }
        val service = WebAiService.CHATGPT
        repeat(8) { index ->
            assertTrue(journal.save(service, "checkpoint-$index-" + "a".repeat(100)))
            assertTrue(journal.checkpoint(service))
            now += 1_000L
        }
        val oldest = journal.load(service).page.versions.first()
        val unsaved = "Editor changes never flushed separately " + "x".repeat(110)
        assertTrue(journal.restore(service, oldest.id, unsaved))
        val restored = journal.load(service).page
        assertEquals(oldest.text, restored.current)
        assertTrue(restored.versions.any { it.text == unsaved })
    }

    @Test fun oversizedCombinedHistoryPrunesVersionsNotCurrentDrafts() {
        val data = Fake()
        val journal = WebChatDraftJournal(data)
        val providers = WebAiService.entries.take(8)
        assertTrue(providers.size >= 7)
        providers.forEachIndexed { serviceIndex, service ->
            repeat(9) { revision ->
                val text = "draft-$serviceIndex-$revision-" + "x".repeat(46_000)
                assertTrue(journal.save(service, text))
                assertTrue(journal.checkpoint(service))
            }
        }
        val all = providers.map { journal.load(it) }
        assertTrue(all.all { it.readable })
        assertTrue(all.all { it.page.current.isNotBlank() })
        assertTrue(all.sumOf { it.page.versions.size } < providers.size * 8)
        assertTrue((data.record?.length ?: 0) <= 3_000_000)
    }

    @Test fun manualCheckpointIncludesLatestUnsavedTextInOneWrite() {
        val journal = WebChatDraftJournal(Fake())
        val source = "Not yet autosaved, but worth keeping"
        assertTrue(journal.checkpoint(WebAiService.CHATGPT, source))
        val page = journal.load(WebAiService.CHATGPT).page
        assertEquals(source, page.current)
        assertEquals(source, page.versions.single().text)
    }

}

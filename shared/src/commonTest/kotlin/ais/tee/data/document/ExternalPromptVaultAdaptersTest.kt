package ais.tee.data.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalPromptVaultAdaptersTest {
    @Test
    fun envelopeAdapterMapsTitleAndContentVariantsInOrder() {
        val raw = """
            {"version": 1, "exportedAt": "2026-11-25T00:00:00.000Z", "prompts": [
              {"id": "a", "title": "Review", "content": "Review this diff", "tags": ["code"], "createdAt": 1},
              {"name": "Summary", "text": "Summarize", "category": "Work"},
              {"title": "Prompt field", "prompt": "Explain"}
            ]}
        """.trimIndent()
        val import = requireNotNull(ExternalPromptVaultAdapters.decode(raw))
        assertEquals("prompt-list-envelope", import.adapterId)
        assertEquals(0, import.skipped)
        assertEquals(listOf("Review", "Summary", "Prompt field"), import.prompts.map { it.title })
        assertEquals(listOf("Review this diff", "Summarize", "Explain"), import.prompts.map { it.text })
        assertTrue(import.prompts.all { it.version == 1 })
    }

    @Test
    fun bareArrayAdapterImportsPromptObjects() {
        val raw = """[{"title": "One", "content": "first"}, {"name": "Two", "prompt": "second"}]"""
        val import = requireNotNull(ExternalPromptVaultAdapters.decode(raw))
        assertEquals("bare-prompt-array", import.adapterId)
        assertEquals(listOf("One" to "first", "Two" to "second"), import.prompts.map { it.title to it.text })
    }

    @Test
    fun invalidEntriesAreDroppedAndCounted() {
        val raw = """{"prompts": [
          {"title": "Good", "content": "text"},
          {"title": " padded ", "content": "text"},
          {"title": "", "content": "text"},
          {"title": "tab\there", "content": "text"},
          {"title": "No text"},
          {"content": "no title"},
          "not an object",
          {"title": 7, "content": "non-string title"}
        ]}"""
        val import = requireNotNull(ExternalPromptVaultAdapters.decode(raw))
        assertEquals(listOf("Good"), import.prompts.map { it.title })
        assertEquals(7, import.skipped)
    }

    @Test
    fun entriesBeyondTheBackupLimitAreSkippedNotImported() {
        val entries = List(PortablePromptBackup.MAX_PROMPTS + 3) { index ->
            """{"title": "P$index", "content": "t"}"""
        }
        val raw = """{"prompts": [${entries.joinToString(",")}]}"""
        val import = requireNotNull(ExternalPromptVaultAdapters.decode(raw))
        assertEquals(PortablePromptBackup.MAX_PROMPTS, import.prompts.size)
        assertEquals(3, import.skipped)
        assertEquals("P0", import.prompts.first().title)
        assertEquals("P${PortablePromptBackup.MAX_PROMPTS - 1}", import.prompts.last().title)
    }

    @Test
    fun aisteeFormatsAndUnrelatedJsonAreNeverReinterpreted() {
        val single = requireNotNull(PortablePrompt.fromLibrary("One", "1").toDocument(4_096)).text
        assertNull(ExternalPromptVaultAdapters.decode(single))
        val backup = requireNotNull(
            PortablePromptBackup.of(listOf(PortablePrompt.fromLibrary("One", "1"))).toDocument(4_096)
        ).text
        assertNull(ExternalPromptVaultAdapters.decode(backup))
        val wrongVersion = backup.replace("\"version\":1", "\"version\":2")
        assertNull(ExternalPromptVaultAdapters.decode(wrongVersion))
        assertNull(ExternalPromptVaultAdapters.decode("""{"kind": "${PortablePromptBackup.KIND}", "prompts": [{"title": "T", "text": "x"}]}"""))
        assertNull(ExternalPromptVaultAdapters.decode("""{"messages": [{"role": "user", "text": "hi"}]}"""))
        assertNull(ExternalPromptVaultAdapters.decode("""{"prompts": "nope"}"""))
        assertNull(ExternalPromptVaultAdapters.decode("""{"prompts": []}"""))
        assertNull(ExternalPromptVaultAdapters.decode("""[{"title": " ", "content": " "}]"""))
        assertNull(ExternalPromptVaultAdapters.decode("not json"))
        assertNull(ExternalPromptVaultAdapters.decode("""["just strings"]"""))
    }

    @Test
    fun oversizedFilesAreRejected() {
        val raw = "[{\"title\": \"Big\", \"content\": \"" + "x".repeat(8 * 1024 * 1024) + "\"}]"
        assertNull(ExternalPromptVaultAdapters.decode(raw))
    }

    @Test
    fun unicodeAndEscapesSurviveTheMapping() {
        val raw = """{"prompts": [{"title": "Żółć", "content": "line1\nline2 \"quoted\""}]}"""
        val import = assertNotNull(ExternalPromptVaultAdapters.decode(raw))
        assertEquals("Żółć" to "line1\nline2 \"quoted\"", import.prompts.single().let { it.title to it.text })
    }

    @Test
    fun adapterRegistryIsOrderedAndLabeled() {
        assertEquals(listOf("prompt-list-envelope", "bare-prompt-array"), ExternalPromptVaultAdapters.adapters.map { it.id })
        assertTrue(ExternalPromptVaultAdapters.adapters.all { it.label.isNotBlank() })
    }
}

package ais.tee.data.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortablePromptTest {
    @Test
    fun portablePromptPreservesTextAndRejectsInvalidArchives() {
        val text = "Keep  spaces\r\nKeep \"quotes\" and Unicode: ż."
        val raw = requireNotNull(PortablePrompt.fromLibrary("My prompt", text).toDocument(4_096)).text
        val restored = requireNotNull(PortablePrompt.decode(raw))
        assertEquals("My prompt", restored.title)
        assertEquals(text, restored.text)
        assertNull(PortablePrompt.decode(raw.replace("\"version\":1", "\"version\":2")))
        assertNull(PortablePrompt.decode(raw.replace("My prompt", " ")))
        assertNull(PortablePrompt.decode("{}"))
    }

    @Test
    fun portablePromptRejectsJsonEscapingThatExceedsFileLimit() {
        val text = "\"".repeat(64)
        assertNull(PortablePrompt.fromLibrary("Large prompt", text).toDocument(128))
    }

    private fun backupOf(vararg prompts: Pair<String, String>) =
        PortablePromptBackup.of(prompts.map { (title, text) -> PortablePrompt.fromLibrary(title, text) })

    @Test
    fun backupRoundTripsPromptsInOrder() {
        val raw = requireNotNull(backupOf("B" to "second\r\n", "A" to "first ż").toDocument(4_096)).text
        val restored = requireNotNull(PortablePromptBackup.decode(raw))
        assertEquals(listOf("B" to "second\r\n", "A" to "first ż"), restored.prompts.map { it.title to it.text })
    }

    @Test
    fun backupDropsNonPortablePromptsOnExportAndNeverWritesAnEmptyFile() {
        val backup = backupOf("Good" to "text", " padded " to "text", "Blank" to " ")
        assertEquals(listOf("Good"), backup.prompts.map { it.title })
        assertNull(backupOf("Blank" to " ").toDocument(4_096))
        assertNull(backupOf("Good" to "\"".repeat(64)).toDocument(128))
    }

    @Test
    fun backupDecodeIsAllOrNothingAndFormatsStayDistinct() {
        val raw = requireNotNull(backupOf("One" to "1", "Two" to "2").toDocument(4_096)).text
        assertNull(PortablePromptBackup.decode(raw.replace("\"title\":\"Two\"", "\"title\":\" \"")))
        assertNull(PortablePromptBackup.decode(raw.replace(PortablePromptBackup.KIND, "other")))
        assertNull(PortablePromptBackup.decode(raw.replace("{\"version\":1,\"kind\"", "{\"version\":2,\"kind\"")))
        assertNull(PortablePromptBackup.decode("{\"version\":1,\"kind\":\"${PortablePromptBackup.KIND}\",\"prompts\":[]}"))
        assertNull(PortablePrompt.decode(raw))
        val single = requireNotNull(PortablePrompt.fromLibrary("One", "1").toDocument(4_096)).text
        assertNull(PortablePromptBackup.decode(single))
    }

    @Test
    fun backupRejectsMoreThanTheDocumentedPromptCount() {
        val prompts = List(PortablePromptBackup.MAX_PROMPTS + 1) { PortablePrompt.fromLibrary("P$it", "t") }
        assertNull(PortablePromptBackup.of(prompts).toDocument(Int.MAX_VALUE))
        val overLimit = prompts.joinToString(",") { "{\"version\":1,\"title\":\"${it.title}\",\"text\":\"t\"}" }
        assertNull(
            PortablePromptBackup.decode("{\"version\":1,\"kind\":\"${PortablePromptBackup.KIND}\",\"prompts\":[$overLimit]}")
        )
        assertEquals(
            PortablePromptBackup.MAX_PROMPTS,
            requireNotNull(
                PortablePromptBackup.decode(
                    requireNotNull(PortablePromptBackup.of(prompts.dropLast(1)).toDocument(Int.MAX_VALUE)).text
                )
            ).prompts.size,
        )
    }
}

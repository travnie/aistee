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
}

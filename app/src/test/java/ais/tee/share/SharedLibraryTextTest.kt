package ais.tee.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLibraryTextTest {
    @Test
    fun blankTextIsRejected() {
        assertNull(sharedLibraryText("  \n ", "text/plain", subject = null, fileName = null))
    }

    @Test
    fun subjectWinsAsTitle() {
        val shared = sharedLibraryText("body", "text/plain", subject = " Article ", fileName = "notes.md")!!
        assertEquals("Article", shared.title)
    }

    @Test
    fun markdownFileKeepsMarkdownFormatAndNameAsTitle() {
        val shared = sharedLibraryText("# Heading\ntext", "application/octet-stream", null, "prompt.v2.md")!!
        assertEquals("prompt.v2", shared.title)
        assertEquals("text/markdown", shared.mediaType)
        assertEquals("md", shared.extension)
    }

    @Test
    fun plainTextUsesFirstLineWithoutHeadingMarks() {
        val shared = sharedLibraryText("\n\n## Idea list\nmore", "text/plain", null, null)!!
        assertEquals("Idea list", shared.title)
        assertEquals("text/plain", shared.mediaType)
        assertEquals("txt", shared.extension)
    }

    @Test
    fun longTitleIsCapped() {
        val shared = sharedLibraryText("x".repeat(300), null, null, null)!!
        assertEquals(80, shared.title.length)
    }

    @Test
    fun markdownMimeTypeIsRespected() {
        assertEquals("md", sharedLibraryText("a", "text/markdown", null, null)!!.extension)
    }
}

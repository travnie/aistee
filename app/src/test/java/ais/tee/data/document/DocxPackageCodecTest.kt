package ais.tee.data.document

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxPackageCodecTest {
    @Test
    fun packageRoundTripPreservesPortableStructure() {
        val source = DocbenchStructuredDocument(
            listOf(
                DocbenchStructuredBlock("Title", headingLevel = 1, bookmarks = listOf("title")),
                DocbenchStructuredBlock("Body\nline two")
            )
        )

        val encoded = DocxPackageCodec.encode(source)
        val decoded = DocxPackageCodec.decode(encoded)

        assertEquals(source, decoded)
        val names = zipEntryNames(encoded)
        assertTrue("[Content_Types].xml" in names)
        assertTrue("word/document.xml" in names)
        assertTrue("word/styles.xml" in names)
        assertTrue("word/_rels/document.xml.rels" in names)
    }

    @Test
    fun unsafeZipEntryIsRejected() {
        val bytes = zipOf("../word/document.xml" to "<x/>")

        assertThrows(IllegalArgumentException::class.java) {
            DocxPackageCodec.decode(bytes)
        }
    }

    @Test
    fun directoryEntriesDoNotBreakDecoding() {
        val source = DocbenchStructuredDocument(
            listOf(DocbenchStructuredBlock("Hello"))
        )
        val generated = DocxPackageCodec.encode(source)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { target ->
            target.putNextEntry(ZipEntry("word/"))
            target.closeEntry()
            ZipInputStream(ByteArrayInputStream(generated)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    target.putNextEntry(ZipEntry(entry.name))
                    input.copyTo(target)
                    target.closeEntry()
                    input.closeEntry()
                }
            }
        }

        assertEquals(source, DocxPackageCodec.decode(output.toByteArray()))
    }

    @Test
    fun duplicatePackageEntriesAreRejected() {
        val bytes = zipOf(
            "word/document.xml" to "<x/>",
            "word/document.xml" to "<x/>"
        )

        assertThrows(IllegalArgumentException::class.java) {
            DocxPackageCodec.decode(bytes)
        }
    }

    @Test
    fun packageWithoutMainDocumentIsRejected() {
        val bytes = zipOf("[Content_Types].xml" to "<Types/>")

        assertThrows(IllegalArgumentException::class.java) {
            DocxPackageCodec.decode(bytes)
        }
    }

    private fun zipEntryNames(bytes: ByteArray): Set<String> {
        val result = linkedSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result += entry.name
                zip.closeEntry()
            }
        }
        return result
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}

package ais.tee.data.document

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object DocxPackageCodec {
    const val DOCX_MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val MAX_DOCX_PACKAGE_BYTES = 16 * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 24 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 128
    private const val BUFFER_SIZE = 16 * 1024

    fun decode(bytes: ByteArray): DocbenchStructuredDocument {
        require(bytes.size <= MAX_DOCX_PACKAGE_BYTES) {
            "DOCX package exceeds the 16 MiB limit."
        }
        var documentXml: String? = null
        var stylesXml: String? = null
        var entryCount = 0
        var totalUncompressed = 0L
        val seenEntries = mutableSetOf<String>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) {
                    throw IllegalArgumentException("DOCX contains too many package entries.")
                }
                val normalizedName = normalizeEntryName(entry.name)
                val onBytesRead: (Int) -> Unit = { count ->
                    totalUncompressed += count
                    if (totalUncompressed > MAX_UNCOMPRESSED_BYTES) {
                        throw IllegalArgumentException("DOCX expands beyond the supported limit.")
                    }
                }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                require(seenEntries.add(normalizedName)) {
                    "DOCX contains duplicate package entry: $normalizedName"
                }
                when (normalizedName) {
                    "word/document.xml" ->
                        documentXml = readEntryBounded(zip, onBytesRead).decodeToString()
                    "word/styles.xml" ->
                        stylesXml = readEntryBounded(zip, onBytesRead).decodeToString()
                    else -> drainEntryBounded(zip, onBytesRead)
                }
                zip.closeEntry()
            }
        }

        val mainXml = documentXml
            ?: throw IllegalArgumentException("DOCX does not contain word/document.xml.")
        val headingStyles = stylesXml?.let(DocxWordprocessingMl::decodeHeadingStyles).orEmpty()
        return DocxWordprocessingMl.decodeDocument(mainXml, headingStyles)
    }

    fun encode(document: DocbenchStructuredDocument): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            writeEntry(zip, "[Content_Types].xml", contentTypesXml())
            writeEntry(zip, "_rels/.rels", packageRelationshipsXml())
            writeEntry(zip, "word/document.xml", DocxWordprocessingMl.encodeDocument(document))
            writeEntry(zip, "word/styles.xml", DocxWordprocessingMl.encodeStyles())
            writeEntry(zip, "word/_rels/document.xml.rels", documentRelationshipsXml())
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_DOCX_PACKAGE_BYTES) {
            "Generated DOCX package exceeds the 16 MiB limit."
        }
        return bytes
    }

    private fun readEntryBounded(
        zip: ZipInputStream,
        onBytesRead: (Int) -> Unit
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var entryBytes = 0
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            entryBytes += count
            if (entryBytes > MAX_ENTRY_BYTES) {
                throw IllegalArgumentException("DOCX entry exceeds the 8 MiB limit.")
            }
            onBytesRead(count)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun drainEntryBounded(
        zip: ZipInputStream,
        onBytesRead: (Int) -> Unit
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            onBytesRead(count)
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        val bytes = content.encodeToByteArray()
        require(bytes.size <= MAX_ENTRY_BYTES) { "Generated DOCX entry is too large." }
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun normalizeEntryName(name: String): String {
        val normalized = name.replace('\\', '/').trim('/')
        require(
            normalized.isNotBlank() &&
                normalized.split('/').none { it == ".." || it.isEmpty() }
        ) {
            "DOCX contains an unsafe package entry name."
        }
        return normalized
    }

    private fun contentTypesXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
            """</Types>"""

    private fun packageRelationshipsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
            """</Relationships>"""

    private fun documentRelationshipsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            """</Relationships>"""
}

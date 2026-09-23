package ais.tee.data.document

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlException
import nl.adaptivity.xmlutil.xmlStreaming

internal const val WORDPROCESSINGML_NS =
    "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val MAX_DOCX_XML_CHARS = 8 * 1024 * 1024
private const val MAX_DOCX_STYLES = 2_048
private val HEADING_STYLE_PATTERN = Regex("""(?i)^heading\\s*([1-9])$""")

/** Text/outline subset of WordprocessingML used by the Android DOCX package adapter. */
object DocxWordprocessingMl {
    fun decodeDocument(
        xml: String,
        headingStyleLevels: Map<String, Int> = emptyMap()
    ): DocbenchStructuredDocument {
        require(xml.length <= MAX_DOCX_XML_CHARS) { "DOCX document XML exceeds the 8 MiB limit." }
        rejectDtd(xml)
        val reader = xmlStreaming.newReader(xml, expandEntities = false)
        val blocks = mutableListOf<DocbenchStructuredBlock>()
        val outline = mutableListOf<DocbenchOutlineEntry>()
        var paragraph: ParagraphBuilder? = null
        var insideText = false

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> if (reader.namespaceURI == WORDPROCESSINGML_NS) {
                        when (reader.localName) {
                            "p" -> {
                                if (paragraph != null) {
                                    throw IllegalArgumentException("Nested Word paragraphs are not supported.")
                                }
                                if (blocks.size >= MAX_DOCBENCH_STRUCTURED_BLOCKS) {
                                    throw IllegalArgumentException("DOCX has too many paragraphs.")
                                }
                                paragraph = ParagraphBuilder()
                            }
                            "pStyle" -> paragraph?.let { current ->
                                val styleId = reader.getAttributeValue(WORDPROCESSINGML_NS, "val")
                                    ?: reader.getAttributeValue(null, "val")
                                current.headingLevel = styleId
                                    ?.let(headingStyleLevels::get)
                                    ?: styleId?.headingLevelFromStyleName()
                            }
                            "bookmarkStart" -> paragraph?.let { current ->
                                val name = reader.getAttributeValue(WORDPROCESSINGML_NS, "name")
                                    ?: reader.getAttributeValue(null, "name")
                                if (
                                    !name.isNullOrBlank() &&
                                    name != "_GoBack" &&
                                    current.bookmarks.size < MAX_DOCBENCH_BOOKMARKS_PER_BLOCK
                                ) {
                                    require(name.length <= MAX_DOCBENCH_BOOKMARK_NAME_CHARS) {
                                        "DOCX bookmark name exceeds the supported limit."
                                    }
                                    current.bookmarks += name
                                }
                            }
                            "t" -> if (paragraph != null) insideText = true
                            "tab" -> paragraph?.text?.append('\t')
                            "br", "cr" -> paragraph?.text?.append('\n')
                        }
                    }
                    EventType.END_ELEMENT -> if (reader.namespaceURI == WORDPROCESSINGML_NS) {
                        when (reader.localName) {
                            "t" -> insideText = false
                            "p" -> {
                                val completed = paragraph
                                    ?: throw IllegalArgumentException("Unexpected Word paragraph end.")
                                val block = completed.build()
                                val blockIndex = blocks.size
                                blocks += block
                                completed.headingLevel?.let { level ->
                                    if (block.text.isNotBlank()) {
                                        outline += DocbenchOutlineEntry(
                                            title = block.text.take(MAX_DOCBENCH_OUTLINE_TITLE_CHARS),
                                            level = level,
                                            blockIndex = blockIndex
                                        )
                                    }
                                }
                                paragraph = null
                                insideText = false
                            }
                        }
                    }
                    EventType.TEXT,
                    EventType.CDSECT -> if (insideText) paragraph?.text?.append(reader.text)
                    EventType.ENTITY_REF -> if (insideText) {
                        if (!reader.isKnownEntity) {
                            throw IllegalArgumentException("DOCX contains an undeclared XML entity.")
                        }
                        paragraph?.text?.append(reader.text)
                    }
                    EventType.DOCDECL -> throw IllegalArgumentException(
                        "DOCX WordprocessingML must not contain a DTD."
                    )
                    else -> Unit
                }
            }
        } catch (error: XmlException) {
            throw IllegalArgumentException(error.message ?: "Invalid DOCX document XML.", error)
        } finally {
            reader.close()
        }
        if (paragraph != null) throw IllegalArgumentException("Unclosed Word paragraph.")
        return DocbenchStructuredDocument(blocks = blocks, outline = outline)
    }

    fun decodeHeadingStyles(xml: String): Map<String, Int> {
        require(xml.length <= MAX_DOCX_XML_CHARS) { "DOCX styles XML exceeds the 8 MiB limit." }
        rejectDtd(xml)
        val reader = xmlStreaming.newReader(xml, expandEntities = false)
        val result = linkedMapOf<String, Int>()
        var style: StyleBuilder? = null

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> if (reader.namespaceURI == WORDPROCESSINGML_NS) {
                        when (reader.localName) {
                            "style" -> {
                                if (result.size >= MAX_DOCX_STYLES) {
                                    throw IllegalArgumentException("DOCX has too many styles.")
                                }
                                val type = reader.getAttributeValue(WORDPROCESSINGML_NS, "type")
                                    ?: reader.getAttributeValue(null, "type")
                                val id = reader.getAttributeValue(WORDPROCESSINGML_NS, "styleId")
                                    ?: reader.getAttributeValue(null, "styleId")
                                style = if (type == "paragraph" && !id.isNullOrBlank()) {
                                    StyleBuilder(id)
                                } else {
                                    null
                                }
                            }
                            "name" -> style?.name =
                                reader.getAttributeValue(WORDPROCESSINGML_NS, "val")
                                    ?: reader.getAttributeValue(null, "val")
                            "outlineLvl" -> style?.outlineLevel =
                                (reader.getAttributeValue(WORDPROCESSINGML_NS, "val")
                                    ?: reader.getAttributeValue(null, "val"))
                                    ?.toIntOrNull()
                                    ?.takeIf { it in 0..8 }
                                    ?.plus(1)
                        }
                    }
                    EventType.END_ELEMENT -> if (
                        reader.namespaceURI == WORDPROCESSINGML_NS &&
                        reader.localName == "style"
                    ) {
                        style?.let { current ->
                            val level = current.outlineLevel
                                ?: current.name?.headingLevelFromStyleName()
                                ?: current.id.headingLevelFromStyleName()
                            if (level != null) result[current.id] = level
                        }
                        style = null
                    }
                    EventType.DOCDECL -> throw IllegalArgumentException(
                        "DOCX styles XML must not contain a DTD."
                    )
                    else -> Unit
                }
            }
        } catch (error: XmlException) {
            throw IllegalArgumentException(error.message ?: "Invalid DOCX styles XML.", error)
        } finally {
            reader.close()
        }
        return result
    }

    fun encodeDocument(document: DocbenchStructuredDocument): String {
        require(document.blocks.size <= MAX_DOCBENCH_STRUCTURED_BLOCKS) {
            "Document has too many blocks for DOCX export."
        }
        val usedBookmarkNames = mutableSetOf<String>()
        var bookmarkId = 1

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:document xmlns:w="$WORDPROCESSINGML_NS">""")
            append("<w:body>")
            val outlineByBlock = document.outline.groupBy(DocbenchOutlineEntry::blockIndex)
            document.blocks.forEachIndexed { blockIndex, block ->
                append("<w:p>")
                outlineByBlock[blockIndex]
                    ?.minByOrNull(DocbenchOutlineEntry::level)
                    ?.let { entry ->
                        append("""<w:pPr><w:pStyle w:val="Heading${entry.level}"/></w:pPr>""")
                    }

                val bookmarkIds = mutableListOf<Int>()
                block.bookmarks.forEach { rawName ->
                    val name = uniqueBookmarkName(rawName, bookmarkId, usedBookmarkNames)
                    val id = bookmarkId++
                    bookmarkIds += id
                    append("""<w:bookmarkStart w:id="$id" w:name="${escapeXmlAttribute(name)}"/>""")
                }

                appendParagraphText(block.text)

                bookmarkIds.asReversed().forEach { id ->
                    append("""<w:bookmarkEnd w:id="$id"/>""")
                }
                append("</w:p>")
            }
            append("<w:sectPr/>")
            append("</w:body></w:document>")
        }
    }

    fun encodeStyles(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:styles xmlns:w="$WORDPROCESSINGML_NS">""")
        append("""<w:style w:type="paragraph" w:default="1" w:styleId="Normal">""")
        append("""<w:name w:val="Normal"/></w:style>""")
        for (level in 1..9) {
            append("""<w:style w:type="paragraph" w:styleId="Heading$level">""")
            append("""<w:name w:val="heading $level"/>""")
            append("""<w:basedOn w:val="Normal"/>""")
            append("""<w:pPr><w:outlineLvl w:val="${level - 1}"/></w:pPr>""")
            append("</w:style>")
        }
        append("</w:styles>")
    }

    private fun StringBuilder.appendParagraphText(text: String) {
        if (text.isEmpty()) return
        append("<w:r>")
        var segmentStart = 0
        text.forEachIndexed { index, char ->
            if (char == '\n' || char == '\t') {
                appendTextSegment(text.substring(segmentStart, index))
                append(if (char == '\t') "<w:tab/>" else "<w:br/>")
                segmentStart = index + 1
            }
        }
        appendTextSegment(text.substring(segmentStart))
        append("</w:r>")
    }

    private fun StringBuilder.appendTextSegment(segment: String) {
        if (segment.isEmpty()) return
        require(hasOnlyXml10Characters(segment)) {
            "DOCX text contains a character outside the XML 1.0 repertoire."
        }
        append("""<w:t xml:space="preserve">""")
        append(escapeXmlText(segment))
        append("</w:t>")
    }

    private fun uniqueBookmarkName(
        rawName: String,
        fallbackIndex: Int,
        used: MutableSet<String>
    ): String {
        val base = sanitizeBookmarkName(rawName, fallbackIndex)
        if (used.add(base)) return base
        var suffix = 2
        while (true) {
            val suffixText = "_$suffix"
            val candidate = base.take((40 - suffixText.length).coerceAtLeast(1)) + suffixText
            if (used.add(candidate)) return candidate
            suffix++
        }
    }

    internal fun sanitizeBookmarkName(rawName: String, fallbackIndex: Int): String {
        val cleaned = buildString {
            rawName.trim().forEach { char ->
                append(if (char.isLetterOrDigit() || char == '_') char else '_')
            }
        }
        val prefixed = when {
            cleaned.isEmpty() -> "bookmark_$fallbackIndex"
            cleaned.first().isLetter() || cleaned.first() == '_' -> cleaned
            else -> "_$cleaned"
        }
        return prefixed.take(40)
    }

    private fun escapeXmlText(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(char)
            }
        }
    }

    private fun escapeXmlAttribute(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }

    private fun rejectDtd(xml: String) {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) {
            "DOCX WordprocessingML DTD/DOCTYPE declarations are not supported."
        }
    }

    private fun String.headingLevelFromStyleName(): Int? =
        HEADING_STYLE_PATTERN.matchEntire(trim())?.groupValues?.get(1)?.toIntOrNull()

    private fun hasOnlyXml10Characters(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val first = value[index].code
            val codePoint = when {
                first in 0xD800..0xDBFF -> {
                    if (index + 1 >= value.length) return false
                    val second = value[index + 1].code
                    if (second !in 0xDC00..0xDFFF) return false
                    index++
                    0x10000 + ((first - 0xD800) shl 10) + (second - 0xDC00)
                }
                first in 0xDC00..0xDFFF -> return false
                else -> first
            }
            if (
                codePoint != 0x9 &&
                codePoint != 0xA &&
                codePoint != 0xD &&
                codePoint !in 0x20..0xD7FF &&
                codePoint !in 0xE000..0xFFFD &&
                codePoint !in 0x10000..0x10FFFF
            ) return false
            index++
        }
        return true
    }

    private class ParagraphBuilder {
        val text = StringBuilder()
        val bookmarks = mutableListOf<String>()
        var headingLevel: Int? = null

        fun build(): DocbenchStructuredBlock = DocbenchStructuredBlock(
            text = text.toString(),
            bookmarks = bookmarks.toList()
        )
    }

    private data class StyleBuilder(
        val id: String,
        var name: String? = null,
        var outlineLevel: Int? = null
    )
}

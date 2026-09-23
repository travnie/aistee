package ais.tee.data.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocxWordprocessingMlTest {
    @Test
    fun roundTripPreservesTextHeadingsBookmarksBreaksAndTabs() {
        val source = DocbenchStructuredDocument(
            listOf(
                DocbenchStructuredBlock(
                    text = "Chapter 1",
                    headingLevel = 1,
                    bookmarks = listOf("chapter-one")
                ),
                DocbenchStructuredBlock(
                    text = "first line\nsecond\tpart",
                    bookmarks = listOf("body mark")
                ),
                DocbenchStructuredBlock(text = "")
            )
        )

        val xml = DocxWordprocessingMl.encodeDocument(source)
        val styles = DocxWordprocessingMl.decodeHeadingStyles(
            DocxWordprocessingMl.encodeStyles()
        )
        val decoded = DocxWordprocessingMl.decodeDocument(xml, styles)

        assertEquals(
            listOf(
                source.blocks[0].copy(bookmarks = listOf("chapter_one")),
                source.blocks[1].copy(bookmarks = listOf("body_mark")),
                source.blocks[2]
            ),
            decoded.blocks
        )
        assertTrue(xml.contains("w:pStyle w:val=\"Heading1\""))
        assertTrue(xml.contains("<w:br/>"))
        assertTrue(xml.contains("<w:tab/>"))
    }

    @Test
    fun customParagraphStyleOutlineLevelBecomesHeading() {
        val styles = """
            <w:styles xmlns:w="$WORDPROCESSINGML_NS">
              <w:style w:type="paragraph" w:styleId="ChapterTitle">
                <w:name w:val="Chapter title"/>
                <w:pPr><w:outlineLvl w:val="1"/></w:pPr>
              </w:style>
            </w:styles>
        """.trimIndent()
        val document = """
            <w:document xmlns:w="$WORDPROCESSINGML_NS"><w:body>
              <w:p><w:pPr><w:pStyle w:val="ChapterTitle"/></w:pPr><w:r><w:t>Two</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent()

        val headingStyles = DocxWordprocessingMl.decodeHeadingStyles(styles)
        val decoded = DocxWordprocessingMl.decodeDocument(document, headingStyles)

        assertEquals(2, decoded.blocks.single().headingLevel)
        assertEquals("Two", decoded.blocks.single().text)
    }

    @Test
    fun headingStyleNameIsRecognizedWithoutOutlineLevel() {
        val styles = """
            <w:styles xmlns:w="$WORDPROCESSINGML_NS">
              <w:style w:type="paragraph" w:styleId="CustomHeading">
                <w:name w:val="heading 3"/>
              </w:style>
            </w:styles>
        """.trimIndent()
        val document = """
            <w:document xmlns:w="$WORDPROCESSINGML_NS"><w:body>
              <w:p><w:pPr><w:pStyle w:val="CustomHeading"/></w:pPr><w:r><w:t>Three</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent()

        val decoded = DocxWordprocessingMl.decodeDocument(
            document,
            DocxWordprocessingMl.decodeHeadingStyles(styles)
        )

        assertEquals(3, decoded.blocks.single().headingLevel)
    }

    @Test
    fun bookmarkNamesAreSanitizedAndMadeUniqueForWord() {
        val source = DocbenchStructuredDocument(
            listOf(
                DocbenchStructuredBlock("A", bookmarks = listOf("123 bad name", "123 bad name"))
            )
        )

        val xml = DocxWordprocessingMl.encodeDocument(source)

        assertTrue(xml.contains("w:name=\"_123_bad_name\""))
        assertTrue(xml.contains("w:name=\"_123_bad_name_2\""))
    }

    @Test
    fun dtdIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DocxWordprocessingMl.decodeDocument(
                """<!DOCTYPE x [<!ENTITY e "boom">]><w:document xmlns:w="$WORDPROCESSINGML_NS"><w:body/></w:document>"""
            )
        }
    }
}

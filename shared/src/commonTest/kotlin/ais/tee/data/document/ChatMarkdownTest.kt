package ais.tee.data.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ChatMarkdownTest {
    @Test
    fun headingsParagraphsAndInlineStyles() {
        val blocks = parseChatMarkdown(
            "# Title\n\nSome **bold**, *italic*, `code` and ~~gone~~ text."
        )!!

        assertEquals(ChatMarkdownBlock.Heading(1, listOf(ChatMarkdownSpan("Title"))), blocks[0])
        val paragraph = assertIs<ChatMarkdownBlock.Paragraph>(blocks[1])
        assertEquals(
            listOf(
                ChatMarkdownSpan("Some "),
                ChatMarkdownSpan("bold", bold = true),
                ChatMarkdownSpan(", "),
                ChatMarkdownSpan("italic", italic = true),
                ChatMarkdownSpan(", "),
                ChatMarkdownSpan("code", code = true),
                ChatMarkdownSpan(" and "),
                ChatMarkdownSpan("gone", strikethrough = true),
                ChatMarkdownSpan(" text."),
            ),
            paragraph.spans,
        )
    }

    @Test
    fun linesInsideAParagraphStayOnSeparateLines() {
        val paragraph = assertIs<ChatMarkdownBlock.Paragraph>(parseChatMarkdown("first\nsecond")!!.single())
        assertEquals("first\nsecond", paragraph.spans.joinToString("") { it.text })
    }

    @Test
    fun codeFenceKeepsLanguageAndBody() {
        val block = parseChatMarkdown("```kotlin\nval x = 1\nprintln(x)\n```")!!.single()
        assertEquals(ChatMarkdownBlock.CodeBlock("kotlin", "val x = 1\nprintln(x)"), block)
    }

    @Test
    fun listsKeepOrderStartAndNesting() {
        val blocks = parseChatMarkdown("3. three\n4. four\n\n- a\n  - nested\n- [x] done")!!
        val ordered = assertIs<ChatMarkdownBlock.ListBlock>(blocks[0])
        assertEquals(true, ordered.ordered)
        assertEquals(3, ordered.startNumber)
        assertEquals(2, ordered.items.size)

        val bullets = assertIs<ChatMarkdownBlock.ListBlock>(blocks[1])
        assertEquals(false, bullets.ordered)
        assertIs<ChatMarkdownBlock.ListBlock>(bullets.items[0][1])
        val checked = assertIs<ChatMarkdownBlock.Paragraph>(bullets.items[1][0])
        assertEquals("☑ done", checked.spans.joinToString("") { it.text })
    }

    @Test
    fun tablesUseTheSharedTableModel() {
        val block = parseChatMarkdown("| A | B |\n|---|--:|\n| 1 | 2 |")!!.single()
        val table = assertIs<ChatMarkdownBlock.Table>(block).table
        assertEquals(listOf("A", "B"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
        assertEquals(MarkdownTableAlignment.RIGHT, table.alignments[1])
    }

    @Test
    fun onlyWebLinksAreLinkedAndHtmlStaysLiteral() {
        val spans = assertIs<ChatMarkdownBlock.Paragraph>(
            parseChatMarkdown("[site](https://example.com) [x](javascript:alert(1)) <b>raw</b>")!!.single()
        ).spans

        assertEquals(ChatMarkdownSpan("site", link = "https://example.com"), spans.first())
        assertEquals(listOf("https://example.com"), spans.mapNotNull { it.link })
        assertEquals(true, spans.joinToString("") { it.text }.contains("<b>raw</b>"))
    }

    @Test
    fun quotesRulesAndEscapes() {
        val blocks = parseChatMarkdown("> quoted\n\n---\n\n\\*not italic\\*")!!
        val quote = assertIs<ChatMarkdownBlock.Quote>(blocks[0])
        assertEquals("quoted", assertIs<ChatMarkdownBlock.Paragraph>(quote.blocks.single()).spans.single().text)
        assertEquals(ChatMarkdownBlock.Rule, blocks[1])
        assertEquals("*not italic*", assertIs<ChatMarkdownBlock.Paragraph>(blocks[2]).spans.joinToString("") { it.text })
    }

    @Test
    fun oversizedInputFallsBackToPlainText() {
        assertNull(parseChatMarkdown("a".repeat(MAX_CHAT_MARKDOWN_CHARS + 1)))
    }
}

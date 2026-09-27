package ais.tee.data.document

import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.renderChatMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMathTest {
    private fun spans(text: String) = assertIs<ChatMarkdownBlock.Paragraph>(parseChatMarkdown(text)!!.single()).spans

    @Test
    fun inlineMathSurvivesMarkdownEmphasisAndEscapes() {
        assertEquals(
            listOf(ChatMarkdownSpan("Area "), ChatMarkdownSpan("a_1 * b_2 \\{x\\}", math = true), ChatMarkdownSpan(".")),
            spans("Area \$a_1 * b_2 \\{x\\}\$."),
        )
        assertEquals(
            listOf(ChatMarkdownSpan("so "), ChatMarkdownSpan("x^2", math = true)),
            spans("so \\(x^2\\)"),
        )
    }

    @Test
    fun displayMathSplitsTheParagraph() {
        val blocks = parseChatMarkdown("Energy:\n\$\$E = mc^2\$\$\nand\n\\[\n\\int_0^1 x\\,dx\n\\]")!!
        assertEquals(
            listOf(
                ChatMarkdownBlock.Paragraph(listOf(ChatMarkdownSpan("Energy:"))),
                ChatMarkdownBlock.Math("E = mc^2"),
                ChatMarkdownBlock.Paragraph(listOf(ChatMarkdownSpan("and"))),
                ChatMarkdownBlock.Math("\\int_0^1 x\\,dx"),
            ),
            blocks,
        )
    }

    @Test
    fun pricesCodeAndEscapedDollarsStayText() {
        listOf("\$5 and \$10", "costs \$5.", "\$ x \$", "between \$5-\$10 today", "a \$5 b\n\nc\$ d").forEach { text ->
            assertTrue(parseChatMarkdown(text)!!.flatMap { (it as ChatMarkdownBlock.Paragraph).spans }.none { it.math }, text)
        }
        assertEquals(listOf(ChatMarkdownSpan("\$x\$", code = true)), spans("`\$x\$`"))
        assertEquals(listOf(ChatMarkdownSpan("\$x\$")), spans("\\\$x\\\$"))
        assertEquals(
            ChatMarkdownBlock.CodeBlock(null, "\$\$a\$\$ and \$b\$"),
            parseChatMarkdown("```\n\$\$a\$\$ and \$b\$\n```")!!.single(),
        )
    }

    @Test
    fun tablesKeepTheirMathSource() {
        val table = assertIs<ChatMarkdownBlock.Table>(parseChatMarkdown("| f | g |\n|---|---|\n| \$x^2\$ | \$a|b\$ |")!!.single())
        assertEquals("\$x^2\$", table.table.rows.single().first())
    }

    @Test
    fun convertsCommonTex() {
        assertEquals(
            listOf(ChatMathRun("x", ChatMathStyle.ITALIC), ChatMathRun("2", scripts = "^"), ChatMathRun("+1")),
            chatMathRuns("x^2+1"),
        )
        assertEquals("α≤β", chatMathRuns("\\alpha \\le \\beta")!!.joinToString("") { it.text })
        assertEquals("(a+b)/2", chatMathRuns("\\frac{a+b}{2}")!!.joinToString("") { it.text })
        assertEquals("√(x+1)", chatMathRuns("\\sqrt{x+1}")!!.joinToString("") { it.text })
        assertEquals("sin(x)", chatMathRuns("\\sin(x)")!!.joinToString("") { it.text })
        assertEquals("ℝ", chatMathRuns("\\mathbb{R}")!!.joinToString("") { it.text })
        assertEquals("if x>0", chatMathRuns("\\text{if } x>0")!!.joinToString("") { it.text })
        assertEquals(
            listOf("∑" to "", "i" to "_", "=1" to "_", "n" to "^"),
            chatMathRuns("\\sum_{i=1}^{n}")!!.map { it.text to it.scripts },
        )
        assertEquals("a=1\nb=2", chatMathRuns("\\begin{aligned} a&=1 \\\\ b&=2 \\end{aligned}")!!.joinToString("") { it.text }.replace(" ", ""))
    }

    @Test
    fun unsupportedOrHostileTexFallsBack() {
        assertNull(chatMathRuns("\\begin{pmatrix} 1 & 0 \\end{pmatrix}"))
        assertNull(chatMathRuns("\\unknowncommand{x}"))
        assertNull(chatMathRuns("{x"))
        assertNull(chatMathRuns("{".repeat(100) + "x" + "}".repeat(100)))
        assertNull(chatMathRuns("x".repeat(MAX_CHAT_MATH_CHARS + 1)))
    }

    @Test
    fun exportKeepsTheTexSource() {
        val text = "Area \$\\pi r^2\$ and \$\$\\frac{a}{b}\$\$"
        val exported = renderChatMarkdown(
            listOf(
                ModelChatMessage(id = "u", sender = CHAT_ROLE_USER, text = "area?"),
                ModelChatMessage(id = "m", sender = CHAT_ROLE_ASSISTANT, text = text),
            )
        )!!
        assertTrue(text in exported)
    }

    @Test
    fun unmatchedDelimitersStayCheapInLongAnswers() {
        val text = "a \$5 ` \$\$ \\( ".repeat(20_000)
        val math = extractChatMath(text)
        assertEquals(text, math.restore(math.text))
    }
}

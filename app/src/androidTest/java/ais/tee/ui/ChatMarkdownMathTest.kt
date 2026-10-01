package ais.tee.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.document.ChatMarkdownBlock
import ais.tee.data.document.parseChatMarkdown
import ais.tee.ui.screens.chatMarkdownAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import ais.tee.ui.screens.ChatMarkdownContent
import ais.tee.ui.screens.prepareChatMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMarkdownMathTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inlineAndDisplayMathRenderAndUnknownTexStaysReadable() {
        val blocks = checkNotNull(
            parseChatMarkdown(
                "Euler: \$e^{i\\pi}+1=0\$ costs \$5.\n\n\$\$\\frac{a}{b} \\le \\sqrt{2}\$\$\n\n\\(\\begin{pmatrix}1\\end{pmatrix}\\)"
            )
        )
        val markdown = prepareChatMarkdown(blocks)
        composeRule.setContent { ChatMarkdownContent(markdown = markdown, color = Color.Black) }

        composeRule.onNodeWithText("Euler: eiπ+1=0 costs \$5.").assertIsDisplayed()
        composeRule.onNode(hasText("a/b≤√2") and hasAnyAncestor(hasTestTag("chat_markdown_math"))).assertIsDisplayed()
        composeRule.onNodeWithText("\\begin{pmatrix}1\\end{pmatrix}").assertIsDisplayed()
    }

    @Test
    fun displayMathCopiesItsTexSource() {
        val markdown = prepareChatMarkdown(checkNotNull(parseChatMarkdown("\$\$\\frac{a}{b}\$\$")))
        val copied = mutableListOf<String>()
        composeRule.setContent {
            ChatMarkdownContent(markdown = markdown, color = Color.Black, onCopySource = { copied += it })
        }

        composeRule.onNodeWithTag("btn_copy_math_tex").performClick()

        assertEquals(listOf("\\frac{a}{b}"), copied)
    }

    @Test
    fun inlineMathCopiesItsTexSourceOnlyWhenCopyIsOffered() {
        val spans = (checkNotNull(parseChatMarkdown("Euler: \$e^{i\\pi}+1=0\$ done")).single() as ChatMarkdownBlock.Paragraph).spans
        val copied = mutableListOf<String>()

        val copyable = chatMarkdownAnnotatedString(spans, Color.Blue, Color.Gray, onCopyMath = { copied += it })
        val link = copyable.getLinkAnnotations(0, copyable.length).single()
        assertEquals("eiπ+1=0", copyable.text.substring(link.start, link.end))
        (link.item as LinkAnnotation.Clickable).linkInteractionListener?.onClick(link.item)
        assertEquals(listOf("e^{i\\pi}+1=0"), copied)

        val plain = chatMarkdownAnnotatedString(spans, Color.Blue, Color.Gray)
        assertTrue(plain.getLinkAnnotations(0, plain.length).isEmpty())
    }
}

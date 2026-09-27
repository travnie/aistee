package ais.tee.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.document.parseChatMarkdown
import ais.tee.ui.screens.ChatMarkdownContent
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
        composeRule.setContent { ChatMarkdownContent(blocks = blocks, color = Color.Black) }

        composeRule.onNodeWithText("Euler: eiπ+1=0 costs \$5.").assertIsDisplayed()
        composeRule.onNode(hasText("a/b≤√2") and hasAnyAncestor(hasTestTag("chat_markdown_math"))).assertIsDisplayed()
        composeRule.onNodeWithText("\\begin{pmatrix}1\\end{pmatrix}").assertIsDisplayed()
    }
}

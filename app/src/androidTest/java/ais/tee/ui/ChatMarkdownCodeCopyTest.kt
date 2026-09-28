package ais.tee.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.document.parseChatMarkdown
import ais.tee.ui.screens.ChatMarkdownContent
import ais.tee.ui.screens.prepareChatMarkdown
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMarkdownCodeCopyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val markdown = prepareChatMarkdown(
        checkNotNull(parseChatMarkdown("Run this:\n\n```kotlin\nprintln(\"hi\")\n```\n"))
    )

    @Test
    fun codeBlockCopiesItsSourceWithoutFences() {
        val copied = mutableListOf<String>()
        composeRule.setContent {
            ChatMarkdownContent(markdown = markdown, color = Color.Black, onCopyCode = { copied += it })
        }

        composeRule.onNodeWithText("kotlin").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_copy_code_block").performClick()

        assertEquals(listOf("println(\"hi\")"), copied)
    }

    @Test
    fun noCopyButtonWithoutACallback() {
        composeRule.setContent { ChatMarkdownContent(markdown = markdown, color = Color.Black) }

        composeRule.onAllNodesWithTag("btn_copy_code_block").assertCountEquals(0)
    }
}

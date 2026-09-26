package ais.tee.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.document.MarkdownTable
import ais.tee.data.model.AiProvider
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.ModelChatMessage
import ais.tee.ui.screens.ChatMessageItem
import ais.tee.ui.screens.MarkdownTableScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownTableScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val message = ModelChatMessage(
        id = "table-message",
        sender = CHAT_ROLE_ASSISTANT,
        provider = AiProvider.CHATGPT,
        text = """
            Results:

            | Planet | Moons |
            | :----- | ----: |
            | Mars | 2 |
            | Jupiter | 95 |
        """.trimIndent(),
    )

    @Test
    fun viewTableOpensTheTableScreenAndCopiesNeutralizedCsv() {
        var copiedCsv: String? = null
        composeRule.setContent {
            var table by remember { mutableStateOf<MarkdownTable?>(null) }
            ChatMessageItem(
                message = message,
                maxBubbleWidth = 400.dp,
                canOpenMarkdown = true,
                onCopyText = {},
                onOpenMarkdown = {},
                onRetryPrompt = {},
                onViewTable = { table = it },
            )
            table?.let { current ->
                MarkdownTableScreen(
                    table = current,
                    title = "Table",
                    onDismiss = { table = null },
                    onCopyCsv = { copiedCsv = it },
                    onExportCsv = {},
                    onSaveToLibrary = null,
                )
            }
        }

        composeRule.onNodeWithTag("btn_view_table_table-message").performClick()
        composeRule.onNodeWithTag("markdown_table_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("markdown_table_header").assertIsDisplayed()
        composeRule.onNode(
            hasText("Jupiter") and hasAnyAncestor(hasTestTag("markdown_table_screen"))
        ).assertIsDisplayed()

        composeRule.onNodeWithTag("btn_markdown_table_actions").performClick()
        composeRule.onNodeWithTag("btn_markdown_table_copy_csv").performClick()
        composeRule.runOnIdle {
            assertEquals("Planet,Moons\r\nMars,2\r\nJupiter,95\r\n", copiedCsv)
        }

        composeRule.onNodeWithTag("btn_markdown_table_close").performClick()
        composeRule.onNodeWithTag("markdown_table_screen").assertDoesNotExist()
    }

    @Test
    fun completedAnswerRendersMarkdownWithAnInlineTable() {
        composeRule.setContent {
            ChatMessageItem(
                message = message.copy(text = "# Heading\n\nSome **bold** text.\n\n" + message.text),
                maxBubbleWidth = 400.dp,
                canOpenMarkdown = true,
                onCopyText = {},
                onOpenMarkdown = {},
                onRetryPrompt = {},
            )
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag("chat_markdown_content")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("Heading")).assertIsDisplayed()
        composeRule.onNode(hasText("Some bold text.")).assertIsDisplayed()
        composeRule.onNode(hasTestTag("chat_markdown_table")).assertIsDisplayed()
    }
}

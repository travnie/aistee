package ais.tee.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ModelChatMessage
import ais.tee.ui.screens.ChatMessageItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TOGGLE_TAG = "btn_expand_message_u1"
private const val SHOW_MORE = "Show more"
private const val LAST_LINE = "line 40"

@RunWith(AndroidJUnit4::class)
class LongUserMessageCollapseTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setMessage(text: String) {
        composeRule.setContent {
            // Scrollable like the production list, so the expanded toggle can be scrolled into view.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ChatMessageItem(
                    message = ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = text),
                    maxBubbleWidth = 360.dp,
                    canOpenMarkdown = false,
                    onCopyText = {},
                    onOpenMarkdown = {},
                    onRetryPrompt = {},
                )
            }
        }
    }

    @Test
    fun longPromptCollapsesAndExpands() {
        setMessage((1..40).joinToString("\n") { "line $it" })

        composeRule.onNodeWithText(SHOW_MORE).assertIsDisplayed()
        // Collapsed semantics expose only the preview, not the hidden tail.
        composeRule.onNodeWithText(LAST_LINE, substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(TOGGLE_TAG).performClick()
        composeRule.onNodeWithTag(TOGGLE_TAG).performScrollTo()
        composeRule.onNodeWithText("Show less").assertIsDisplayed()
        composeRule.onNodeWithText(LAST_LINE, substring = true).assertExists()
        composeRule.onNodeWithTag(TOGGLE_TAG).performClick()
        composeRule.onNodeWithTag(TOGGLE_TAG).performScrollTo()
        composeRule.onNodeWithText(SHOW_MORE).assertIsDisplayed()
    }

    @Test
    fun shortPromptHasNoToggle() {
        setMessage("Just a short question?")

        composeRule.onAllNodesWithTag(TOGGLE_TAG).assertCountEquals(0)
    }
}

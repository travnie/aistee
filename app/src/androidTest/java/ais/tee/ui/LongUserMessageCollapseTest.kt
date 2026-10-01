package ais.tee.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ModelChatMessage
import ais.tee.ui.screens.ChatMessageItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LongUserMessageCollapseTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setMessage(text: String) {
        composeRule.setContent {
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

    @Test
    fun longPromptCollapsesAndExpands() {
        setMessage((1..40).joinToString("\n") { "line $it" })

        composeRule.onNodeWithText("Show more").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_expand_message_u1").performClick()
        composeRule.onNodeWithText("Show less").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_expand_message_u1").performClick()
        composeRule.onNodeWithText("Show more").assertIsDisplayed()
    }

    @Test
    fun shortPromptHasNoToggle() {
        setMessage("Just a short question?")

        composeRule.onAllNodesWithTag("btn_expand_message_u1").assertCountEquals(0)
    }
}

package ais.tee.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.tokenizer.ChatContextWarning
import ais.tee.ui.screens.ChatContextWarningBanner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatContextWarningBannerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun warningShowsLabelledShareAndActions() {
        val warning = ChatContextWarning(readablePercent = 62, encodingLabel = "o200k")
        val clicks = mutableListOf<String>()
        composeRule.setContent {
            ChatContextWarningBanner(
                message = warning.message,
                onSendAnyway = { clicks += "send" },
                onNewChat = { clicks += "new" },
                onDismiss = { clicks += "dismiss" },
            )
        }

        composeRule.onNodeWithTag("chat_context_warning").assertIsDisplayed()
        composeRule.onNodeWithText(warning.message).assertIsDisplayed()
        composeRule.onNodeWithTag("btn_chat_context_send_anyway").performClick()
        composeRule.onNodeWithTag("btn_chat_context_new_chat").performClick()
        composeRule.onNodeWithTag("btn_chat_context_dismiss").performClick()

        assertEquals(listOf("send", "new", "dismiss"), clicks)
    }
}

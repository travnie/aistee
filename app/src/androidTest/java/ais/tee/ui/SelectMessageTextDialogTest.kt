package ais.tee.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.ui.screens.SelectMessageTextDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectMessageTextDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun quotesAndCopiesTheSelectionOrTheWholeMessage() {
        val quoted = mutableListOf<String>()
        val copied = mutableListOf<String>()
        composeRule.setContent {
            SelectMessageTextDialog(
                messageId = "a1",
                text = "Hello **world**",
                onDismiss = {},
                onCopy = { copied += it },
                onQuote = { quoted += it },
            )
        }

        composeRule.onNodeWithText("Quote all").performClick()
        composeRule.onNodeWithTag("select_text_field").performTextInputSelection(TextRange(8, 13))
        composeRule.onNodeWithText("Quote selection").performClick()
        composeRule.onNodeWithTag("btn_copy_selection").performClick()

        assertEquals(listOf("Hello **world**", "world"), quoted)
        assertEquals(listOf("world"), copied)
    }
}

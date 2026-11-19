package ais.tee.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ais.tee.R
import ais.tee.data.model.ProviderIdentityMethod
import ais.tee.data.model.WebAiService
import ais.tee.ui.screens.WebProviderSignInDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebProviderSignInDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chatGptOffersManualRecovery() = verifyManualRecovery(WebAiService.CHATGPT)

    @Test
    fun claudeOffersManualRecovery() = verifyManualRecovery(WebAiService.CLAUDE)

    @Test
    fun geminiOffersGoogleBrowserAdvice() = verifyManualRecovery(WebAiService.GEMINI)

    private fun verifyManualRecovery(service: WebAiService) {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        var browserClicks = 0
        composeRule.setContent {
            WebProviderSignInDialog(
                service = service,
                preferredMethod = null,
                canOpenInApp = false,
                onChooseMethod = { error("Unexpected identity selection") },
                onOpenInApp = { error("Unexpected embedded launch") },
                onOpenInBrowser = { browserClicks++ },
                onDismiss = {},
            )
        }

        composeRule.onNodeWithTag("web_sign_in_app").assertIsNotEnabled()
        ProviderIdentityMethod.entries.forEach { method ->
            composeRule.onNodeWithTag("web_sign_in_method_${method.name}").assertDoesNotExist()
        }
        composeRule.onNodeWithText(resources.getString(R.string.web_sign_in_manual_hint)).assertExists()
        composeRule.onNodeWithText(resources.getString(R.string.web_sign_in_app_hint)).assertDoesNotExist()
        val googleHint = composeRule.onNodeWithText(resources.getString(R.string.web_sign_in_google_hint))
        if (service == WebAiService.GEMINI) googleHint.assertExists() else googleHint.assertDoesNotExist()
        composeRule.onNodeWithTag("web_sign_in_browser").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, browserClicks) }
    }
}

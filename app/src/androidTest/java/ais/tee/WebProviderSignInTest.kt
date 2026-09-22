package ais.tee

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.model.ProviderIdentityMethod
import ais.tee.data.model.WebAiService
import ais.tee.data.preferences.WebChatPreferencesStore
import ais.tee.ui.viewmodel.StudioViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebProviderSignInTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private lateinit var preferences: WebChatPreferencesStore
    private var originalPreference: ProviderIdentityMethod? = null

    @Before
    fun setUp() {
        preferences = WebChatPreferencesStore(composeRule.activity)
        originalPreference = preferences.loadPreferredIdentityMethod()
        preferences.savePreferredIdentityMethod(null)
    }

    @After
    fun tearDown() {
        preferences.savePreferredIdentityMethod(originalPreference)
    }

    @Test
    fun explicitPreferenceSurvivesReopeningAndAnotherProviderFallback() {
        select(WebAiService.QWEN)
        openHelp()
        composeRule.onNodeWithTag("web_sign_in_method_GITHUB").performClick()
        assertEquals(ProviderIdentityMethod.GITHUB, preferences.loadPreferredIdentityMethod())
        closeHelp()
        openHelp()
        composeRule.onNodeWithTag("web_sign_in_method_GITHUB").assertIsSelected()
        closeHelp()

        select(WebAiService.COPILOT)
        openHelp()
        composeRule.onNodeWithTag("web_sign_in_method_MICROSOFT").assertIsSelected()
        assertEquals(ProviderIdentityMethod.GITHUB, preferences.loadPreferredIdentityMethod())
        closeHelp()
        select(WebAiService.ZAI)
        openHelp()
        composeRule.onNodeWithTag("web_sign_in_method_GITHUB").assertIsSelected()
    }

    @Test
    fun providerChangeClosesHelpInsteadOfRetargetingAnOpenAction() {
        select(WebAiService.QWEN)
        openHelp()
        select(WebAiService.COPILOT)
        composeRule.onNodeWithTag("web_sign_in_dialog").assertDoesNotExist()
        openHelp()
        composeRule.onNodeWithTag("web_sign_in_method_GITHUB").assertDoesNotExist()
        composeRule.onNodeWithTag("web_sign_in_method_MICROSOFT").assertIsDisplayed()
    }

    private fun select(service: WebAiService) {
        composeRule.runOnIdle {
            ViewModelProvider(composeRule.activity)[StudioViewModel::class.java].selectWebService(service)
        }
        composeRule.waitForIdle()
    }

    private fun openHelp() {
        composeRule.onNodeWithTag("btn_web_more").performClick()
        composeRule.onNodeWithTag("btn_web_sign_in_help").performClick()
        composeRule.onNodeWithTag("web_sign_in_dialog").assertIsDisplayed()
    }

    private fun closeHelp() {
        composeRule.onNodeWithTag("web_sign_in_close").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("web_sign_in_dialog").fetchSemanticsNodes().isEmpty()
        }
    }
}

package ais.tee

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import ais.tee.data.model.ProviderIdentityMethod
import ais.tee.data.model.WebAiService
import ais.tee.data.preferences.WebChatPreferencesStore
import ais.tee.ui.viewmodel.StudioViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource

@RunWith(AndroidJUnit4::class)
class WebProviderSignInTest {
    // Apply before Activity creation; provider navigation stays offline.
    @get:Rule(order = 0)
    val offlineWeb = object : ExternalResource() {
        private val executor = Executor { it.run() }

        override fun before() {
            assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
            val ready = CountDownLatch(1)
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig.Builder().addProxyRule("127.0.0.1:9").build(),
                executor,
                { ready.countDown() },
            )
            assertTrue("WebView proxy not applied", ready.await(10, TimeUnit.SECONDS))
        }

        override fun after() {
            val ready = CountDownLatch(1)
            ProxyController.getInstance().clearProxyOverride(executor) { ready.countDown() }
            assertTrue("WebView proxy not cleared", ready.await(10, TimeUnit.SECONDS))
        }
    }

    @get:Rule(order = 1)
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
    fun unverifiedProviderKeepsEmbeddedSignInDisabled() {
        select(WebAiService.GROK)
        openHelp()
        composeRule.onNodeWithTag("web_sign_in_app").assertIsNotEnabled()
        assertTrue(composeRule.onAllNodesWithTag("web_sign_in_browser").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun primaryProvidersExposeSignInHelpFromHub() {
        listOf(WebAiService.CHATGPT, WebAiService.CLAUDE, WebAiService.GEMINI).forEach { service ->
            select(service)
            openHelp()
            composeRule.onNodeWithTag("web_sign_in_browser").performScrollTo().assertIsDisplayed()
            closeHelp()
        }
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
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("web_sign_in_dialog").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("web_sign_in_close").fetchSemanticsNodes().isNotEmpty()
        }
        // Assert visible dialog content, not the displaced container semantics during transitions.
        composeRule.onNodeWithTag("web_sign_in_close").assertIsDisplayed()
    }

    private fun closeHelp() {
        composeRule.onNodeWithTag("web_sign_in_close").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("web_sign_in_dialog").fetchSemanticsNodes().isEmpty()
        }
    }
}

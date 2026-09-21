package ais.tee

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import ais.tee.data.model.WebAiService
import ais.tee.ui.viewmodel.StudioViewModel
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebChatFindTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun findNavigatesLoadedTextAndClearsWithoutReplacingTheWebView() {
        val webView = loadConversationFixture()
        openFind()
        composeRule.onNodeWithTag("web_find_query").performTextReplacement("nebula")
        awaitResult("1 / 3")
        composeRule.onNodeWithTag("btn_web_find_next").performClick()
        awaitResult("2 / 3")
        composeRule.onNodeWithTag("btn_web_find_previous").performClick()
        awaitResult("1 / 3")

        composeRule.onNodeWithTag("web_find_query").performTextReplacement("missing phrase")
        awaitResult("No matches")
        composeRule.onNodeWithTag("btn_web_find_next").assertIsNotEnabled()
        composeRule.onNodeWithTag("web_find_query").performTextReplacement("")
        composeRule.onNodeWithTag("btn_web_find_previous").assertIsNotEnabled()
        composeRule.onNodeWithTag("btn_web_find_close").performClick()
        composeRule.onNodeWithTag("web_find_query").assertDoesNotExist()
        composeRule.runOnIdle { assertSame(webView, visibleWebView(composeRule.activity.window.decorView)) }
    }

    @Test
    fun changingProviderClosesFindAndDoesNotCarryTheQuery() {
        loadConversationFixture()
        openFind()
        composeRule.onNodeWithTag("web_find_query").performTextReplacement("nebula")
        awaitResult("1 / 3")
        switchProvider("claude")
        composeRule.onNodeWithTag("web_find_query").assertDoesNotExist()
        switchProvider("chatgpt")
        composeRule.onNodeWithTag("web_find_query").assertDoesNotExist()
        openFind()
        composeRule.onNodeWithTag("web_find_query").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        )
        composeRule.onNodeWithTag("btn_web_find_next").assertIsNotEnabled()
    }

    @Test
    fun documentNavigationClosesFind() {
        val webView = loadConversationFixture()
        openFind()
        composeRule.onNodeWithTag("web_find_query").performTextReplacement("nebula")
        awaitResult("1 / 3")
        composeRule.runOnIdle {
            webView.loadDataWithBaseURL("https://chatgpt.com/next", "<p>Different conversation</p>", "text/html", "UTF-8", null)
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("web_find_query").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun sameDocumentConversationNavigationClosesFind() {
        val webView = loadConversationFixture()
        openFind()
        composeRule.onNodeWithTag("web_find_query").performTextReplacement("nebula")
        awaitResult("1 / 3")
        composeRule.runOnIdle {
            webView.evaluateJavascript(
                "history.pushState({}, '', '/another-chat'); document.body.innerHTML = '<p>New chat</p>';",
                null,
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("web_find_query").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun externallySelectedWarmProviderClosesFind() {
        switchProvider("claude")
        loadConversationFixture()
        openFind()
        composeRule.onNodeWithTag("web_find_query").performTextReplacement("nebula")
        awaitResult("1 / 3")
        // Incoming shares change the ViewModel selection without using the drawer callback.
        composeRule.runOnIdle {
            ViewModelProvider(composeRule.activity)[StudioViewModel::class.java]
                .selectWebService(WebAiService.CLAUDE)
        }
        composeRule.onNodeWithTag("web_find_query").assertDoesNotExist()
        openFind()
        composeRule.onNodeWithTag("web_find_query").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        )
    }

    private fun loadConversationFixture(): WebView {
        switchProvider("chatgpt")
        lateinit var webView: WebView
        composeRule.runOnIdle {
            webView = requireNotNull(visibleWebView(composeRule.activity.window.decorView))
            webView.stopLoading()
            webView.loadDataWithBaseURL(
                "https://chatgpt.com/aistee-find-fixture",
                "<html><body><p>nebula one</p><p>nebula two</p><p>nebula three</p></body></html>",
                "text/html", "UTF-8", null,
            )
        }
        val loaded = AtomicBoolean(false)
        composeRule.waitUntil(5_000) {
            composeRule.runOnIdle {
                webView.evaluateJavascript("document.body.innerText") { result ->
                    loaded.set(result.contains("nebula three") && webView.progress == 100)
                }
            }
            loaded.get()
        }
        return webView
    }

    private fun openFind() {
        composeRule.onNodeWithTag("btn_web_more").performClick()
        composeRule.onNodeWithTag("btn_web_find").performClick()
        composeRule.onNodeWithTag("web_find_query").assertIsDisplayed()
    }

    private fun awaitResult(text: String) {
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("web_find_result").assertTextContains(text) }.isSuccess
        }
    }

    private fun switchProvider(id: String) {
        if (composeRule.onAllNodesWithTag("btn_web_provider_drawer").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("btn_web_provider_drawer").performClick()
        }
        composeRule.onNodeWithTag("tab_web_service_$id").performClick()
        composeRule.waitForIdle()
    }

    private fun visibleWebView(view: View): WebView? {
        if (view is WebView && view.width > 0 && view.height > 0) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                visibleWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}

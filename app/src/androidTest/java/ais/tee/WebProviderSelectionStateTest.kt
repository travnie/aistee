package ais.tee

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import ais.tee.data.model.WebAiService
import ais.tee.ui.viewmodel.StudioViewModel
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebProviderSelectionStateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun retainedProviderPageFinishUsesCurrentSelection() {
        switchProvider("chatgpt")
        val fixtureUrl = "https://chatgpt.com/aistee-selection-fixture"
        val chatGpt = composeRule.runOnIdle {
            requireNotNull(visibleWebView(composeRule.activity.window.decorView)).also { webView ->
                webView.stopLoading()
                webView.loadDataWithBaseURL(
                    fixtureUrl,
                    "<html><body><p>Selection fixture</p></body></html>",
                    "text/html", "UTF-8", fixtureUrl,
                )
            }
        }
        val fixtureReady = AtomicReference<String?>(null)
        composeRule.waitUntil(5_000) {
            composeRule.runOnIdle {
                chatGpt.evaluateJavascript("document.body.innerText === 'Selection fixture'") {
                    fixtureReady.set(it)
                }
            }
            fixtureReady.get() == "true"
        }
        composeRule.runOnIdle {
            assertEquals(fixtureUrl, chatGpt.url)
            chatGpt.webViewClient.onPageFinished(chatGpt, chatGpt.url)
        }
        awaitTrackerSelection(chatGpt, selected = true)

        val callbackSelection = AtomicReference<String?>(null)
        composeRule.runOnIdle {
            // Incoming shares update the ViewModel synchronously, before Compose receives
            // the new selection. Invoke the real retained client in that same UI turn.
            ViewModelProvider(composeRule.activity)[StudioViewModel::class.java]
                .selectWebService(WebAiService.CLAUDE)
            chatGpt.webViewClient.onPageFinished(chatGpt, chatGpt.url)
            // Queue this snapshot immediately after the client's installation script,
            // before later recomposition effects can correct a stale tracker value.
            chatGpt.evaluateJavascript("window.__llmbenchGenerationTracker?.selected") {
                callbackSelection.set(it)
            }
        }
        composeRule.waitUntil(5_000) { callbackSelection.get() != null }
        assertEquals("Page-finished callback must observe the new ViewModel selection", "false", callbackSelection.get())
        awaitTrackerSelection(chatGpt, selected = false)

        switchProvider("chatgpt")
        composeRule.runOnIdle {
            assertSame(chatGpt, visibleWebView(composeRule.activity.window.decorView))
            chatGpt.webViewClient.onPageFinished(chatGpt, chatGpt.url)
        }
        awaitTrackerSelection(chatGpt, selected = true)
    }

    private fun awaitTrackerSelection(webView: WebView, selected: Boolean) {
        val result = AtomicReference<String?>(null)
        composeRule.waitUntil(5_000) {
            composeRule.runOnIdle {
                webView.evaluateJavascript(
                    "window.__llmbenchGenerationTracker?.selected",
                ) { value -> result.set(value) }
            }
            result.get() == selected.toString()
        }
    }

    private fun switchProvider(id: String) {
        composeRule.onNodeWithTag("btn_web_provider_drawer").performClick()
        composeRule.onNodeWithTag("tab_web_service_$id").performClick()
        composeRule.waitForIdle()
    }

    private fun visibleWebView(view: View): WebView? {
        if (view is WebView && view.isShown && view.width > 0 && view.height > 0) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                visibleWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}

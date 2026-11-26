package ais.tee

import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebRendererCrashRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun reopenCreatesReplacementAtConversation() {
        val old = crashConversation()
        compose.onNodeWithText("Reopen conversation").assertIsDisplayed().performClick()
        awaitReplacement(old, CONVERSATION)
    }

    @Test
    fun homeCreatesReplacementAtProviderHome() {
        val old = crashConversation()
        compose.onNodeWithText("Open provider home").assertIsDisplayed().performClick()
        awaitReplacement(old, "https://chatgpt.com")
    }

    private fun crashConversation(): WebView {
        if (compose.onAllNodesWithTag("btn_web_provider_drawer").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("btn_web_provider_drawer").performClick()
        }
        compose.onNodeWithTag("tab_web_service_chatgpt").performClick()
        compose.waitForIdle()
        val old = compose.runOnIdle {
            requireNotNull(visibleWebView(compose.activity.window.decorView)).also {
                it.stopLoading()
                it.loadDataWithBaseURL(
                    CONVERSATION, "<html><body>Recovery fixture</body></html>",
                    "text/html", "UTF-8", CONVERSATION,
                )
            }
        }
        val ready = AtomicBoolean(false)
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                old.evaluateJavascript("document.body.innerText === 'Recovery fixture'") {
                    ready.set(it == "true" && old.url == CONVERSATION && old.progress == 100)
                }
            }
            ready.get()
        }
        compose.runOnIdle {
            // Inject the platform callback, not a native renderer crash; no shared-process fallout.
            old.webViewClient.onPageFinished(old, old.url)
            assertTrue(old.webViewClient.onRenderProcessGone(old, syntheticCrash()))
        }
        compose.onNodeWithText("Reopen conversation").assertIsDisplayed()
        return old
    }

    @Suppress("DEPRECATION")
    private fun syntheticCrash() = object : RenderProcessGoneDetail() {
        override fun didCrash() = true
        override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_IMPORTANT
    }

    private fun awaitReplacement(old: WebView, expected: String) {
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                val current = visibleWebView(compose.activity.window.decorView)
                current != null && current !== old &&
                    listOf(current.originalUrl, current.url).any { it?.trimEnd('/') == expected }
            }
        }
        compose.runOnIdle {
            val current = requireNotNull(visibleWebView(compose.activity.window.decorView))
            assertNotSame(old, current)
            current.stopLoading()
        }
        compose.onNodeWithText("Reopen conversation").assertDoesNotExist()
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

    private companion object {
        const val CONVERSATION = "https://chatgpt.com/c/aistee-recovery-fixture"
    }
}

package ais.tee

import android.view.View
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import android.view.ViewGroup
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import ais.tee.data.model.WebAiService
import ais.tee.ui.viewmodel.StudioViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebUserTurnTimelineTest {
    private companion object {
        const val MORE_MENU = "btn_web_more"
        const val NAVIGATION_TOGGLE = "toggle_web_turn_navigation"
    }
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun choosingLoadedTurnScrollsExistingRendererWithoutChangingDraft() {
        val webView = loadConversationFixture()
        openTimeline()
        composeRule.onNodeWithTag("web_timeline_count").assertTextContains("20 loaded user messages")
        saveTimelineScreenshot()
        composeRule.onNodeWithTag("web_timeline_turn_3").performClick()
        awaitClosed()
        composeRule.runOnIdle { assertSame(webView, visibleWebView(composeRule.activity.window.decorView)) }
        val verified = AtomicBoolean(false)
        composeRule.waitUntil(5_000) {
            composeRule.runOnIdle {
                webView.evaluateJavascript(
                    "(function(){var r=document.querySelectorAll('[data-message-author-role=\"user\"]')[3].getBoundingClientRect();" +
                        "return Math.abs((r.top+r.bottom)/2-window.innerHeight/2)<2 && " +
                        "document.getElementById('composer').value === 'Keep this draft';})()"
                ) { verified.set(it == "true") }
            }
            verified.get()
        }
    }

    @Test
    fun navigationClosesTimelineAndReloadCannotReuseItsTargets() {
        val webView = loadConversationFixture()
        openTimeline()
        composeRule.runOnIdle {
            webView.evaluateJavascript("history.pushState({}, '', '/c/new-chat'); document.body.innerHTML = '<p>New chat</p>';", null)
        }
        awaitClosed()
        openTimeline()
        composeRule.onNodeWithText("No user messages detected on this page.").assertIsDisplayed()
    }

    @Test
    fun switchingProvidersDiscardsTimelineAndRespectsProviderOptOut() {
        loadConversationFixture()
        openTimeline()
        composeRule.runOnIdle {
            ViewModelProvider(composeRule.activity)[StudioViewModel::class.java]
                .selectWebService(WebAiService.CLAUDE)
        }
        awaitClosed()
        switchProvider("chatgpt")
        composeRule.onNodeWithTag(MORE_MENU).performClick()
        composeRule.onNodeWithTag(NAVIGATION_TOGGLE).performClick()
        composeRule.onNodeWithTag(MORE_MENU).performClick()
        composeRule.onNodeWithTag(NAVIGATION_TOGGLE).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("btn_web_timeline").fetchSemanticsNodes().isEmpty())
        // Restore shared smoke preference.
        composeRule.onNodeWithTag(NAVIGATION_TOGGLE).performClick()
    }

    @Test
    fun realMessageMutationsDiscardTargetsWithoutScrolling() {
        listOf(
            "document.querySelector('[data-message-author-role=\"user\"]').textContent='Recycled message';",
            "document.querySelector('[data-message-author-role=\"user\"]').setAttribute('data-message-id','replacement');"
        ).forEach { mutation ->
            val webView = loadConversationFixture()
            openTimeline()
            val before = composeRule.runOnIdle { webView.scrollY }
            composeRule.runOnIdle { webView.evaluateJavascript(mutation, null) }
            awaitClosed()
            composeRule.runOnIdle { assertEquals(before, webView.scrollY) }
        }
    }

    private fun saveTimelineScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "timeline-smoke.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AisteeSmoke")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use {
            check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }

    private fun openTimeline() {
        composeRule.onNodeWithTag(MORE_MENU).performClick()
        composeRule.onNodeWithTag("btn_web_timeline").performClick()
        composeRule.waitUntil(10_000) {
            runCatching {
                composeRule.onNodeWithTag("web_timeline_count").assertIsDisplayed()
            }.isSuccess || runCatching {
                composeRule.onNodeWithText("No user messages detected on this page.").assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun awaitClosed() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("web_timeline_dialog").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun loadConversationFixture(): WebView {
        switchProvider("chatgpt")
        val webView = composeRule.runOnIdle {
            val view = requireNotNull(visibleWebView(composeRule.activity.window.decorView))
            view.stopLoading()
            view.loadDataWithBaseURL(
                "https://chatgpt.com/c/aistee-timeline-fixture",
                "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body>" + (1..20).joinToString("") {
                    "<div data-message-author-role=\"user\" style=\"height:180px;margin-bottom:80px\">Private message $it</div>"
                } + "<textarea id=\"composer\">Keep this draft</textarea></body></html>",
                "text/html", "UTF-8", null,
            )
            view
        }
        val loaded = AtomicBoolean(false)
        composeRule.waitUntil(5_000) {
            composeRule.runOnIdle {
                webView.evaluateJavascript("document.querySelectorAll('[data-message-author-role=\"user\"]').length") { result ->
                    loaded.set(result == "20" && webView.progress == 100)
                }
            }
            loaded.get()
        }
        return webView
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

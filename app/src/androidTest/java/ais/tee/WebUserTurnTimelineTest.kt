package ais.tee

import android.view.View
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import ais.tee.data.model.WebAiService
import ais.tee.web.WebUserTurnJumpResult
import ais.tee.web.WebUserTurnTimelineSession
import ais.tee.ui.viewmodel.StudioViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebUserTurnTimelineTest {
    private companion object {
        const val FIXTURE_URL = "https://chatgpt.com/c/aistee-timeline-fixture"
        const val RELOADED_FIXTURE_URL = "https://chatgpt.com/c/reloaded-timeline-fixture"
        const val MORE_MENU = "btn_web_more"
        const val NAVIGATION_TOGGLE = "toggle_web_turn_navigation"
    }
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private var fixtureView: WebView? = null

    @Test
    fun revokedRendererLeaseNeverReadsDestroyedWebView() {
        composeRule.runOnIdle {
            val webView = WebView(composeRule.activity)
            webView.destroy()
            val session = WebUserTurnTimelineSession(
                webView, WebAiService.CHATGPT,
                isCurrentWebView = { false },
                isRetainedWebView = { false }
            )
            session.read { assertNull(it) }
            session.jump(0) { assertEquals(WebUserTurnJumpResult.OFF_PROVIDER, it) }
            session.checkCurrent { assertFalse(it) }
            session.close()
        }
    }

    @Test
    fun retainedRendererCleanupRunsWhileNotForeground() {
        val webView = loadConversationFixture()
        val loaded = AtomicBoolean(false)
        val foreground = AtomicBoolean(true)
        val retained = AtomicBoolean(true)
        lateinit var session: WebUserTurnTimelineSession
        composeRule.runOnIdle {
            session = WebUserTurnTimelineSession(
                webView, WebAiService.CHATGPT,
                isCurrentWebView = { foreground.get() },
                isRetainedWebView = { retained.get() }
            )
            session.read { loaded.set(it != null) }
        }
        composeRule.waitUntil(5_000) { loaded.get() }
        foreground.set(false)
        composeRule.runOnIdle { session.close() }
        val cleared = AtomicBoolean(false)
        composeRule.waitUntil(5_000) {
            composeRule.runOnIdle {
                webView.evaluateJavascript(
                    "typeof window.__aisteeTurnTimeline === 'undefined'"
                ) { cleared.set(it == "true") }
            }
            cleared.get()
        }
    }

    @Test
    fun choosingLoadedTurnScrollsExistingRendererWithoutChangingDraft() {
        val webView = loadConversationFixture()
        openTimeline()
        composeRule.onNodeWithTag("web_timeline_count").assertTextEquals("20 loaded user messages.")
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
        composeRule.runOnIdle { webView.loadUrl(RELOADED_FIXTURE_URL) }
        val reloaded = AtomicBoolean(false)
        composeRule.waitUntil(5_000) {
            composeRule.runOnIdle {
                webView.evaluateJavascript(
                    "location.href === '$RELOADED_FIXTURE_URL' && typeof window.__aisteeTurnTimeline === 'undefined'"
                ) { reloaded.set(it == "true" && webView.progress == 100 && webView.url == RELOADED_FIXTURE_URL) }
            }
            reloaded.get()
        }
        openTimeline()
        composeRule.onNodeWithText("No user messages detected on this page.").assertIsDisplayed()
    }

    @Test
    fun switchingProvidersDiscardsTimelineAndRespectsProviderOptOut() {
        switchProvider("claude")
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
            "document.querySelector('[data-message-author-role=\"user\"]').setAttribute('data-message-id','replacement');",
            "document.body.appendChild(document.querySelector('[data-message-author-role=\"user\"]'));"
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
            installFixtureClient(view)
            view.stopLoading()
            view.loadUrl(FIXTURE_URL)
            view
        }
        val loaded = AtomicBoolean(false)
        composeRule.waitUntil(5_000) {
            composeRule.runOnIdle {
                webView.evaluateJavascript(
                    "document.querySelectorAll('[data-message-author-role=\"user\"]').length === 20 && location.href === '$FIXTURE_URL'"
                ) { result ->
                    loaded.set(result == "true" && webView.progress == 100 && webView.url == FIXTURE_URL)
                }
            }
            loaded.get()
        }
        composeRule.runOnIdle { assertEquals(FIXTURE_URL, webView.url) }
        return webView
    }

    // Normal navigation + local responses. App lifecycle/navigation callbacks remain active.
    private fun installFixtureClient(view: WebView) {
        if (fixtureView === view) return
        fixtureView = view
        val delegate = view.webViewClient
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val html = when (request?.url?.toString()) {
                    FIXTURE_URL -> "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body>" +
                        (1..20).joinToString("") {
                            "<div data-message-author-role=\"user\" style=\"height:180px;margin-bottom:80px\">Private message $it</div>"
                        } + "<textarea id=\"composer\">Keep this draft</textarea></body></html>"
                    RELOADED_FIXTURE_URL -> "<html><body><p>New chat</p></body></html>"
                    else -> return delegate.shouldInterceptRequest(view, request)
                }
                return WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) =
                delegate.onPageStarted(view, url, favicon)
            override fun onPageFinished(view: WebView?, url: String?) = delegate.onPageFinished(view, url)
            override fun onPageCommitVisible(view: WebView?, url: String?) = delegate.onPageCommitVisible(view, url)
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) =
                delegate.doUpdateVisitedHistory(view, url, isReload)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                delegate.shouldOverrideUrlLoading(view, request)
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) =
                delegate.onReceivedError(view, request, error)
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) =
                delegate.onReceivedSslError(view, handler, error)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean =
                delegate.onRenderProcessGone(view, detail)
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
        if (view is WebView && view.isShown && view.width > 0 && view.height > 0) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                visibleWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}

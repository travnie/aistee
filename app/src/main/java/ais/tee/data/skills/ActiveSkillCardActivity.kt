package ais.tee.data.skills

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import ais.tee.data.preferences.ActiveSkillsPreferencesStore
import ais.tee.security.bindScreenPrivacy
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Shows one skill card full screen in the `:skills` process (`docs/skills-runtime.md`), with the
 * same sandbox settings, CSP and no network as a skill run. Cookies are cleared and refused
 * before the card loads, or it does not open. The app lock does not run in this process, so the
 * card never stays in the back stack or recents: leaving it closes it.
 */
class ActiveSkillCardActivity : ComponentActivity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindScreenPrivacy()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().take(ACTIVE_SKILL_MAX_CARD_TITLE_CHARS)
        val html = intent.getStringExtra(EXTRA_HTML)
        if (
            html == null ||
            html.length > ACTIVE_SKILL_MAX_OUTPUT_BYTES ||
            !ActiveSkillProcess.isDataDirectoryIsolated ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            closeWith("This card cannot be shown safely.")
            return
        }
        lifecycleScope.launch {
            if (!disableActiveSkillProcessCookies()) {
                closeWith("This card cannot be shown safely.")
            } else {
                show(title, html)
            }
        }
    }

    override fun onDestroy() {
        webView?.apply {
            webViewClient = WebViewClient()
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun show(title: String, html: String) {
        val basePath = "/cards/${UUID.randomUUID()}/"
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(basePath) { path ->
                if (path != "index.html") {
                    activeSkillDenied(404)
                } else {
                    WebResourceResponse(
                        "text/html",
                        "utf-8",
                        200,
                        "OK",
                        mapOf(
                            "Cache-Control" to "no-store",
                            "X-Content-Type-Options" to "nosniff",
                            "Content-Security-Policy" to ACTIVE_SKILL_CONTENT_SECURITY_POLICY,
                        ),
                        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
                    )
                }
            }
            .build()
        val view = WebView(this).also { webView = it }
        configureActiveSkillWebView(view)
        view.webViewClient = CardClient(assetLoader)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                fitsSystemWindows = true
                addView(
                    TextView(this@ActiveSkillCardActivity).apply {
                        text = title
                        textSize = 18f
                        val padding = (16 * resources.displayMetrics.density).toInt()
                        setPadding(padding, padding, padding, padding / 2)
                    }
                )
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        )
        view.loadUrl("$ACTIVE_SKILL_ORIGIN${basePath}index.html")
    }

    private fun closeWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private inner class CardClient(private val assetLoader: WebViewAssetLoader) : WebViewClient() {
        /** A card cannot navigate anywhere, main frame or subframe. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        /** Only the card page itself is served; everything else, network included, is denied. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            if (request.method != "GET") return activeSkillDenied(405)
            return assetLoader.shouldInterceptRequest(request.url) ?: activeSkillDenied(403)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            closeWith("The card stopped unexpectedly.")
            return true
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HTML = "html"

        /** Opens [card] only while active skills are on. Call from the app process. */
        internal fun open(context: Context, card: ActiveSkillCard) {
            if (!ActiveSkillsPreferencesStore(context).isEnabled()) {
                Toast.makeText(context, "Turn on active skills to open skill cards.", Toast.LENGTH_SHORT).show()
                return
            }
            context.startActivity(
                Intent(context, ActiveSkillCardActivity::class.java)
                    .putExtra(EXTRA_TITLE, card.title)
                    .putExtra(EXTRA_HTML, card.html)
                    .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }
}

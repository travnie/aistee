package ais.tee.data.skills

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Files of one skill version. Only paths under `scripts/` are ever served to the sandbox. */
internal class ActiveSkillBundle(
    val skillName: String,
    /** Lowercase hex SHA-256 of the whole bundle; also scopes the served path. */
    val digest: String,
    val files: Map<String, ByteArray>,
) {
    init {
        require(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' }) { "digest must be SHA-256 hex" }
    }

    override fun toString(): String = "ActiveSkillBundle(skill=<redacted>, files=${files.size})"
}

internal const val ACTIVE_SKILL_ENTRY_FILE = "scripts/index.html"

/** Shared by every skill: paths are not origins, so nothing here is isolated per skill by origin. */
internal const val ACTIVE_SKILL_ORIGIN = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}"

internal fun activeSkillBasePath(digest: String): String = "/skills/$digest/"

/** Maps a path relative to the skill's base path to a bundle file, or null outside `scripts/`. */
internal fun activeSkillBundlePath(relativePath: String): String? {
    val relative = relativePath.ifEmpty { "index.html" }
    if (relative.endsWith('/') || '%' in relative || '\\' in relative) return null
    if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
    return "scripts/$relative"
}

/** V1 has no network: only the skill's own files, no workers, frames, forms or plugins. */
internal const val ACTIVE_SKILL_CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data:; font-src 'self'; connect-src 'self'; media-src 'none'; worker-src 'none'; " +
        "frame-src 'none'; child-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"

/**
 * Runs before any skill script in every frame: removes WebRTC (a network path CSP cannot close),
 * service workers and `document.cookie`. Defence in depth only; cookies are disabled for the whole
 * skills process by [ActiveSkillService].
 */
private const val DOCUMENT_START_SCRIPT = """(() => {
  const lock = (target, name, descriptor) => {
    try { Object.defineProperty(target, name, Object.assign({ configurable: false }, descriptor)); } catch (e) {}
  };
  for (const name of ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel',
      'RTCSessionDescription', 'RTCIceCandidate']) {
    lock(window, name, { value: undefined, writable: false });
  }
  lock(Navigator.prototype, 'serviceWorker', { get: () => undefined });
  lock(Document.prototype, 'cookie', { get: () => '', set: () => {} });
})();"""

/**
 * Sandbox settings shared by skill runs and cards: scripts but no JavaScript interface, DOM
 * storage, file/content access, popups or third-party cookies, plus [DOCUMENT_START_SCRIPT].
 * Callers must check [WebViewFeature.DOCUMENT_START_SCRIPT] first.
 */
@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
internal fun configureActiveSkillWebView(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        allowFileAccess = false
        allowContentAccess = false
        domStorageEnabled = false
        @Suppress("DEPRECATION")
        databaseEnabled = false
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_NO_CACHE
        safeBrowsingEnabled = true
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    WebViewCompat.addDocumentStartJavaScript(webView, DOCUMENT_START_SCRIPT, setOf("*"))
}

/**
 * Runs one active skill call in a throwaway, hidden WebView (`docs/skills-runtime.md`). Only used
 * inside the dedicated skills process ([ActiveSkillService]): no JavaScript interface, no DOM
 * storage, file/content access, popups or network; files served by [WebViewAssetLoader] for the
 * current digest only; the result delivered once through an origin-checked web message; and the
 * WebView destroyed after the result, an error or [timeoutMs]. Fails closed when the required
 * WebView features are missing.
 */
internal class ActiveSkillWebViewRunner(
    context: Context,
    private val timeoutMs: Long = ACTIVE_SKILL_TIMEOUT_MS,
) {
    private val appContext = context.applicationContext

    suspend fun run(bundle: ActiveSkillBundle, requestJson: String): ActiveSkillHostMessage {
        if (ACTIVE_SKILL_ENTRY_FILE !in bundle.files) {
            return ActiveSkillHostMessage.Failed("The skill has no $ACTIVE_SKILL_ENTRY_FILE.")
        }
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            return ActiveSkillHostMessage.Failed("This device's WebView cannot run skills safely.")
        }
        return withContext(Dispatchers.Main) { runOnMain(bundle, requestJson) }
    }

    @SuppressLint("RequiresFeature")
    private suspend fun runOnMain(bundle: ActiveSkillBundle, requestJson: String): ActiveSkillHostMessage {
        val callId = UUID.randomUUID().toString()
        val result = CompletableDeferred<ActiveSkillHostMessage>()
        val pageLoaded = CompletableDeferred<Unit>()
        val basePath = activeSkillBasePath(bundle.digest)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(basePath, BundlePathHandler(bundle))
            .build()
        val webView = WebView(appContext)
        try {
            configureActiveSkillWebView(webView)
            WebViewCompat.addWebMessageListener(
                webView,
                ACTIVE_SKILL_HOST_OBJECT,
                setOf(ACTIVE_SKILL_ORIGIN),
            ) { _, message, sourceOrigin, isMainFrame, _ ->
                val accepted = acceptActiveSkillHostMessage(
                    payload = message.data,
                    isMainFrame = isMainFrame,
                    sourceOrigin = sourceOrigin.originString(),
                    expectedOrigin = ACTIVE_SKILL_ORIGIN,
                    expectedCallId = callId,
                )
                if (accepted != ActiveSkillHostMessage.Rejected) result.complete(accepted)
            }
            webView.webViewClient = SandboxClient(assetLoader, pageLoaded, result)

            val outcome = withTimeoutOrNull(timeoutMs) {
                webView.loadUrl("$ACTIVE_SKILL_ORIGIN${basePath}index.html")
                pageLoaded.await()
                if (!result.isCompleted) webView.evaluateJavascript(startScript(callId, requestJson), null)
                result.await()
            }
            return outcome ?: ActiveSkillHostMessage.Failed("The skill did not finish within ${timeoutMs / 1000} s.")
        } finally {
            webView.webViewClient = WebViewClient()
            webView.stopLoading()
            webView.destroy()
        }
    }

    /**
     * Script that calls `window.aistee_skill_run` once with [requestJson] and posts exactly one
     * `{callId, output}` or `{callId, error}` message tagged with the one-use [callId]; a Promise
     * result is awaited in the page, not by `evaluateJavascript`.
     */
    private fun startScript(callId: String, requestJson: String): String {
        // requestJson comes from activeSkillRequestJson, so it is a valid JS object literal.
        return """(() => {
  const host = window.$ACTIVE_SKILL_HOST_OBJECT;
  const send = (value) => host.postMessage(JSON.stringify(Object.assign({ callId: '$callId' }, value)));
  Promise.resolve()
    .then(() => {
      if (typeof window.aistee_skill_run !== 'function') throw new Error('aistee_skill_run is not defined');
      return window.aistee_skill_run(JSON.stringify($requestJson));
    })
    .then(r => {
      const text = typeof r === 'string' ? r : JSON.stringify(r);
      send(text !== undefined && text.length <= ${ACTIVE_SKILL_MAX_OUTPUT_BYTES}
        ? { output: text }
        : { error: 'output missing or larger than ${ACTIVE_SKILL_MAX_OUTPUT_BYTES / 1024} KiB' });
    }, e => send({ error: String(e && e.message || e).slice(0, 500) }));
})();"""
    }

    private class BundlePathHandler(private val bundle: ActiveSkillBundle) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse {
            val file = activeSkillBundlePath(path) ?: return activeSkillDenied(404)
            val bytes = bundle.files[file] ?: return activeSkillDenied(404)
            val headers = buildMap {
                put("Cache-Control", "no-store")
                put("X-Content-Type-Options", "nosniff")
                if (file.endsWith(".html")) put("Content-Security-Policy", ACTIVE_SKILL_CONTENT_SECURITY_POLICY)
            }
            return WebResourceResponse(mimeTypeFor(file), "utf-8", 200, "OK", headers, ByteArrayInputStream(bytes))
        }
    }

    private class SandboxClient(
        private val assetLoader: WebViewAssetLoader,
        private val pageLoaded: CompletableDeferred<Unit>,
        private val result: CompletableDeferred<ActiveSkillHostMessage>,
    ) : WebViewClient() {
        /** Every navigation after the initial load, main frame or subframe, is cancelled. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        override fun onPageFinished(view: WebView, url: String) {
            pageLoaded.complete(Unit)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            result.complete(ActiveSkillHostMessage.Failed("The skill stopped unexpectedly."))
            pageLoaded.complete(Unit)
            return true
        }

        /** Only the current bundle is served; everything else, network included, is denied. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            if (request.method != "GET") return activeSkillDenied(405)
            return assetLoader.shouldInterceptRequest(request.url) ?: activeSkillDenied(403)
        }
    }
}

private fun Uri.originString(): String? = scheme?.let { scheme -> authority?.let { "$scheme://$it" } }

internal fun activeSkillDenied(status: Int): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    status,
    when (status) {
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Forbidden"
    },
    mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(ByteArray(0)),
)

private fun mimeTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "text/html"
    "js", "mjs" -> "text/javascript"
    "css" -> "text/css"
    "json" -> "application/json"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "woff2" -> "font/woff2"
    "txt", "md" -> "text/plain"
    else -> "application/octet-stream"
}

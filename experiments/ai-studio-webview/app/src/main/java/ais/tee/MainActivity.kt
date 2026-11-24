package ais.tee

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.app.Activity
import android.provider.OpenableColumns
import android.util.Base64
import android.view.WindowManager
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private var pendingAcceptTypes = emptyList<String>()
    private var pendingMultiple = false
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        val data = result.data
        val uris = if (result.resultCode == Activity.RESULT_OK && data != null) {
            val clip = data.clipData
            (if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            else listOfNotNull(data.data))
                .distinct().filter { it.scheme == "content" && matchesAcceptType(it, pendingAcceptTypes) }
                .let { if (pendingMultiple) it else it.take(1) }
        } else emptyList()
        pendingAcceptTypes = emptyList()
        callback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    private data class PendingExport(val bytes: ByteArray)
    private var pendingExport: PendingExport? = null
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val export = pendingExport
        pendingExport = null
        val uri = result.data?.data
        if (export != null && result.resultCode == Activity.RESULT_OK && uri?.scheme == "content") {
            Thread {
                val saved = runCatching {
                    contentResolver.openOutputStream(uri, "w")?.use { it.write(export.bytes) }
                        ?: error("Cannot open document")
                }.isSuccess
                runOnUiThread {
                    Toast.makeText(this, if (saved) "Export saved" else "Export failed", Toast.LENGTH_SHORT).show()
                }
            }.start()
        } else if (export != null) {
            Toast.makeText(this, "Export cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerWorkbenchBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(webView, "AisteeNative", setOf("https://$ASSET_HOST")) { _, message, origin, mainFrame, _ ->
            if (!mainFrame || origin.toString() != "https://$ASSET_HOST") return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > MAX_EXPORT_BYTES * 2) return@addWebMessageListener
            runCatching {
                val request = JSONObject(raw)
                when (request.getString("type")) {
                    "privacy" -> {
                        if (request.getBoolean("enabled")) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    "export" -> startExport(request)
                }
            }.onFailure {
                Toast.makeText(this, "Native request failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startExport(request: JSONObject) {
        check(pendingExport == null) { "Export already pending" }
        val name = request.getString("name")
        require(name.isNotBlank() && name.length <= 200 && !name.contains('/') && !name.contains('\\'))
        require(name.none { it.isISOControl() })
        val mime = guessMimeType(name)
        val bytes = Base64.decode(request.getString("base64"), Base64.DEFAULT)
        require(bytes.size <= MAX_EXPORT_BYTES)
        pendingExport = PendingExport(bytes)
        try {
            exportLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, name)
            })
        } catch (error: Exception) {
            pendingExport = null
            throw error
        }
    }

    private fun matchesAcceptType(uri: Uri, types: List<String>): Boolean {
        if (types.isEmpty() || "*/*" in types) return true
        return runCatching {
            val mime = contentResolver.getType(uri).orEmpty().lowercase()
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0).orEmpty().lowercase() else ""
            }.orEmpty()
            types.any { accept ->
                when {
                    accept.startsWith(".") -> name.endsWith(accept)
                    accept.endsWith("/*") -> mime.startsWith(accept.substringBefore('/') + "/")
                    else -> accept == mime
                }
            }
        }.getOrDefault(false)
    }

    private fun pickerMimeType(accept: String): String? = when (accept) {
        ".md", ".txt", ".yaml", ".yml", ".m3u", ".m3u8" -> "text/*"
        ".json" -> "application/json"
        ".zip" -> "application/zip"
        else -> if (accept.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(accept.drop(1))
            else accept.takeIf { it.contains('/') }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fail closed until the trusted workbench reports its privacy preference.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webViewClient = AisteeWebViewClient()
            webChromeClient = AisteeWebChromeClient()
        }

        registerWorkbenchBridge()
        rootContainer.addView(webView)
        setContentView(rootContainer)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(ENTRY_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) {
            webView.saveState(outState)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun openExternalUrl(uri: Uri) {
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme != "https" && scheme != "http") return
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Ignore when no external browser is available.
        }
    }

    private inner class AisteeWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val uri = request.url
            if (uri.scheme?.lowercase() != "https" || uri.host?.lowercase() != ASSET_HOST) {
                return null
            }

            val rawPath = uri.path.orEmpty().trimStart('/')
            if (rawPath.contains("..") || rawPath.contains('\\')) {
                return WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    403,
                    "Forbidden",
                    emptyMap(),
                    ByteArrayInputStream(ByteArray(0))
                )
            }

            val targetPath = if (rawPath.isEmpty()) "index.html" else rawPath
            return tryOpenAsset(targetPath)
                ?: if (!targetPath.substringAfterLast('/').contains('.')) {
                    tryOpenAsset("index.html")
                } else {
                    null
                }
                ?: WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    404,
                    "Not Found",
                    emptyMap(),
                    ByteArrayInputStream(ByteArray(0))
                )
        }

        private fun tryOpenAsset(relativePath: String): WebResourceResponse? {
            return try {
                val stream = assets.open("$ASSET_DIR/$relativePath")
                val mimeType = guessMimeType(relativePath)
                val encoding = if (mimeType.startsWith("text/") ||
                    mimeType == "application/javascript" ||
                    mimeType == "application/json" ||
                    mimeType == "image/svg+xml"
                ) {
                    "UTF-8"
                } else {
                    null
                }
                WebResourceResponse(mimeType, encoding, stream)
            } catch (_: FileNotFoundException) {
                null
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase() ?: return true
            val host = uri.host?.lowercase()

            if (scheme == "https" && host == ASSET_HOST) {
                return false
            }

            if (scheme == "https" || scheme == "http") {
                openExternalUrl(uri)
            }
            return true
        }
    }

    private inner class AisteeWebChromeClient : WebChromeClient() {

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            if (pendingFileCallback != null) {
                filePathCallback.onReceiveValue(null)
                return true
            }
            pendingFileCallback = filePathCallback
            pendingMultiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
            pendingAcceptTypes = fileChooserParams.acceptTypes.orEmpty()
                .flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val mimeTypes = pendingAcceptTypes.mapNotNull(::pickerMimeType).distinct()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeTypes.singleOrNull() ?: "*/*"
                if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, pendingMultiple)
            }
            return try {
                filePickerLauncher.launch(intent)
                true
            } catch (_: Exception) {
                pendingFileCallback = null
                pendingAcceptTypes = emptyList()
                filePathCallback.onReceiveValue(null)
                true
            }
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            if (!isUserGesture || resultMsg == null) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

            val tempWebView = WebView(this@MainActivity).apply {
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        openExternalUrl(request.url)
                        v.destroy()
                        return true
                    }
                }
            }
            transport.webView = tempWebView
            resultMsg.sendToTarget()
            return true
        }
    }

    private fun guessMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "wasm" -> "application/wasm"
            "txt" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }
    }

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val ASSET_DIR = "web"
        private const val MAX_EXPORT_BYTES = 8 * 1024 * 1024
        private const val ENTRY_URL = "https://$ASSET_HOST/index.html"
    }
}

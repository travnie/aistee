package ais.tee.ui.screens

import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ais.tee.data.model.WebAiService
import ais.tee.web.providerNavigationUrlMatches

internal enum class WebPageLoadFailure {
    CONNECTION, TIMEOUT, SECURE_CONNECTION, OTHER
}

// One live WebView. URLs stay in memory; never included in error copy or diagnostics.
internal class WebPageLoadState(private val service: WebAiService) {
    var failure by mutableStateOf<WebPageLoadFailure?>(null)
        private set
    var retryUrl by mutableStateOf<String?>(null)
        private set
    private var documentUrl: String? = null
    private var documentCommitted = false

    fun onPageStarted(url: String?) {
        documentUrl = url
        documentCommitted = false
        failure = null
        retryUrl = null
    }

    fun onRedirect(request: WebResourceRequest) {
        if (!documentCommitted && request.isForMainFrame && request.isRedirect) {
            documentUrl = request.url.toString()
        }
    }

    fun onPageCommitVisible(url: String?) {
        if (failure == null && matchesDocument(url)) documentCommitted = true
    }

    fun onReceivedSslError(url: String?): Boolean {
        if (documentCommitted || !matchesDocument(url)) return false
        failure = WebPageLoadFailure.SECURE_CONNECTION
        // SSL callback has no request method. Home only; no implicit form replay.
        retryUrl = null
        return true
    }

    fun onReceivedError(request: WebResourceRequest?, errorCode: Int?): Boolean {
        if (documentCommitted || request == null || errorCode == null || !request.isForMainFrame) return false
        val failedUrl = request.url.toString()
        if (!matchesDocument(failedUrl)) return false
        failure = when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_IO -> WebPageLoadFailure.CONNECTION
            WebViewClient.ERROR_TIMEOUT -> WebPageLoadFailure.TIMEOUT
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> WebPageLoadFailure.SECURE_CONNECTION
            else -> WebPageLoadFailure.OTHER
        }
        // Explicit GET only. Never resubmit failed POSTs or external navigation.
        retryUrl = failedUrl.takeIf { request.method.equals("GET", ignoreCase = true) }
        return true
    }

    private fun matchesDocument(url: String?) =
        url != null && url.substringBefore('#') == documentUrl?.substringBefore('#') &&
            providerNavigationUrlMatches(service, url)
}

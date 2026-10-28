package ais.tee.ui.screens

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import ais.tee.data.model.WebAiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebPageLoadRecoveryTest {
    private val page = "https://chatgpt.com/c/local-fixture"

    @Test
    fun mainDocumentConnectionFailureOffersExplicitGetRetry() {
        val state = WebPageLoadState(WebAiService.CHATGPT)
        state.onPageStarted(page)

        assertTrue(state.onReceivedError(request(page), WebViewClient.ERROR_CONNECT))
        assertEquals(WebPageLoadFailure.CONNECTION, state.failure)
        assertEquals(page, state.retryUrl)
    }

    @Test
    fun brokenSubresourcesAndLatePreviousDocumentErrorsDoNotHideCurrentChat() {
        val state = WebPageLoadState(WebAiService.CHATGPT)
        state.onPageStarted(page)

        assertFalse(state.onReceivedError(request(page, mainFrame = false), WebViewClient.ERROR_TIMEOUT))
        assertFalse(state.onReceivedError(request("https://chatgpt.com/old"), WebViewClient.ERROR_CONNECT))
        assertNull(state.failure)
        assertNull(state.retryUrl)
    }

    @Test
    fun nextNavigationClearsFailure() {
        val state = WebPageLoadState(WebAiService.CHATGPT)
        state.onPageStarted(page)
        state.onReceivedError(request(page), WebViewClient.ERROR_TIMEOUT)
        assertEquals(WebPageLoadFailure.TIMEOUT, state.failure)

        state.onPageStarted("https://chatgpt.com/")
        assertNull(state.failure)
        assertNull(state.retryUrl)
    }

    @Test
    fun failedFormsAndOffProviderPagesNeverOfferReplay() {
        val state = WebPageLoadState(WebAiService.CHATGPT)
        state.onPageStarted(page)
        state.onReceivedError(request(page, method = "POST"), WebViewClient.ERROR_CONNECT)
        assertNull(state.retryUrl)
        assertEquals(WebPageLoadFailure.CONNECTION, state.failure)

        val external = "https://example.org/"
        state.onPageStarted(external)
        assertFalse(state.onReceivedError(request(external), WebViewClient.ERROR_CONNECT))
        assertFalse(state.onReceivedSslError(external))
        assertNull(state.failure)
        assertNull(state.retryUrl)
    }

    @Test
    fun secureConnectionFailureUsesDedicatedCategory() {
        val state = WebPageLoadState(WebAiService.CHATGPT)
        state.onPageStarted(page)
        state.onReceivedError(request(page), WebViewClient.ERROR_FAILED_SSL_HANDSHAKE)
        assertEquals(WebPageLoadFailure.SECURE_CONNECTION, state.failure)
    }

    @Test
    fun fragmentDifferenceAndHiddenProviderStateStayIsolated() {
        val chatGpt = WebPageLoadState(WebAiService.CHATGPT)
        val claude = WebPageLoadState(WebAiService.CLAUDE)
        chatGpt.onPageStarted("$page#latest")
        claude.onPageStarted("https://claude.ai/")

        assertTrue(chatGpt.onReceivedError(request(page), WebViewClient.ERROR_HOST_LOOKUP))
        assertEquals(WebPageLoadFailure.CONNECTION, chatGpt.failure)
        assertNull(claude.failure)
    }

    @Test
    fun acceptedMainFrameRedirectFailureUsesTheRedirectTarget() {
        val state = WebPageLoadState(WebAiService.CHATGPT)
        state.onPageStarted(page)
        val redirected = "https://chatgpt.com/auth/login"
        state.onRedirect(request(redirected, redirect = true))
        assertTrue(state.onReceivedError(request(redirected), WebViewClient.ERROR_TIMEOUT))
        assertEquals(redirected, state.retryUrl)
    }

    @Test
    fun committedDocumentIgnoresLateErrorsAndSubresourceCertificateFailures() {
        val state = WebPageLoadState(WebAiService.CHATGPT)
        state.onPageStarted(page)
        state.onPageCommitVisible(page)
        assertFalse(state.onReceivedError(request(page), WebViewClient.ERROR_CONNECT))
        assertFalse(state.onReceivedSslError(page))
        assertNull(state.failure)
    }

    @Test
    fun mainDocumentCertificateFailureOffersHomeWithoutGuessingRequestMethod() {
        val state = WebPageLoadState(WebAiService.CHATGPT)
        state.onPageStarted(page)
        assertFalse(state.onReceivedSslError("https://cdn.example.org/image.png"))
        assertTrue(state.onReceivedSslError(page))
        assertEquals(WebPageLoadFailure.SECURE_CONNECTION, state.failure)
        assertNull(state.retryUrl)
    }

    private fun request(url: String, mainFrame: Boolean = true, method: String = "GET", redirect: Boolean = false) =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame() = mainFrame
            override fun isRedirect() = redirect
            override fun hasGesture() = false
            override fun getMethod() = method
            override fun getRequestHeaders() = mutableMapOf<String, String>()
        }

}

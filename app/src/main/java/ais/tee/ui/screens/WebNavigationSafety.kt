package ais.tee.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import ais.tee.data.model.WebAiService
import ais.tee.web.shouldLoadHttpsInProviderWebView

internal const val WEBVIEW_LOG_TAG = "AisteeWeb"

/** Routes a top-level WebView navigation without granting implicit app-launch authority. */
internal fun handleMainFrameNavigation(
    context: Context,
    service: WebAiService,
    navigation: WebResourceRequest,
    onExternalIntentRequested: (Uri) -> Unit,
    onExternalNavigationFailed: () -> Unit
): Boolean {
    if (!navigation.isForMainFrame) return false

    val uri = navigation.url
    return when (uri.scheme?.lowercase()) {
        "https" -> {
            if (shouldLoadHttpsInProviderWebView(service, uri.toString())) return false
            if (navigation.hasGesture()) {
                reportExternalNavigationLaunch(
                    launched = openExternalUri(context, uri),
                    onFailure = onExternalNavigationFailed
                )
            }
            true
        }
        "http", "mailto", "tel", "sms" -> {
            if (navigation.hasGesture()) {
                reportExternalNavigationLaunch(
                    launched = openExternalUri(context, uri),
                    onFailure = onExternalNavigationFailed
                )
            }
            true
        }
        "intent" -> {
            if (navigation.hasGesture()) {
                onExternalIntentRequested(uri)
            }
            true
        }
        else -> true
    }
}

internal fun reportExternalNavigationLaunch(
    launched: Boolean,
    onFailure: () -> Unit
) {
    if (!launched) onFailure()
}

/** Delegates an explicitly allowed external URI to a browsable system handler. */
internal fun openExternalUri(context: Context, uri: Uri): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    val error = runCatching { context.startActivity(intent) }.exceptionOrNull() ?: return true
    when (error) {
        is ActivityNotFoundException -> Log.w(WEBVIEW_LOG_TAG, "External URI handler unavailable")
        is SecurityException -> Log.w(WEBVIEW_LOG_TAG, "External URI launch rejected")
        else -> throw error
    }
    return false
}

/** Launches a user-confirmed intent URI or falls back to validated HTTPS. */
internal fun openExternalIntentUri(context: Context, uri: Uri) {
    val parsedIntent = runCatching {
        Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
    }.getOrNull() ?: return
    val fallbackUri = validatedHttpsFallback(parsedIntent)
    val targetPackage = parsedIntent.`package` ?: parsedIntent.component?.packageName

    if (!targetPackage.isNullOrBlank()) {
        val launchIntent = sanitizeExternalIntent(parsedIntent, targetPackage)
        if (launchIntent != null) try {
            context.startActivity(launchIntent)
            return
        } catch (error: ActivityNotFoundException) {
            Log.w(WEBVIEW_LOG_TAG, "Intent target unavailable; trying HTTPS fallback", error)
        } catch (error: SecurityException) {
            Log.w(WEBVIEW_LOG_TAG, "Intent target rejected; trying HTTPS fallback", error)
        }
    }

    fallbackUri?.let { openExternalUri(context, it) }
}

/** Reduces an intent URI to a safe browsable ACTION_VIEW handoff. */
private fun sanitizeExternalIntent(intent: Intent, targetPackage: String): Intent? {
    val data = intent.data ?: return null
    val scheme = data.scheme?.lowercase() ?: return null
    if (scheme in setOf("file", "content", "android.resource", "javascript", "data")) return null

    return Intent(Intent.ACTION_VIEW, data).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        setPackage(targetPackage)
    }
}

/** Returns an intent browser fallback only when it is HTTPS. */
private fun validatedHttpsFallback(intent: Intent): Uri? {
    val fallbackUri = intent.getStringExtra("browser_fallback_url")?.let(Uri::parse) ?: return null
    return fallbackUri.takeIf { it.scheme.equals("https", ignoreCase = true) }
}

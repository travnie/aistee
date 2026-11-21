package ais.tee.ui.screens

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import java.net.URI

internal enum class BrowserTabResult { LAUNCHED, UNAVAILABLE, CANCELLED, FAILED }

// Provider-owned entry URLs only. Browser cookies stay in the browser.
internal fun openProviderBrowserTab(context: Context, uri: Uri, onResult: (BrowserTabResult) -> Unit) {
    if (!isBrowserTabEntry(uri)) {
        onResult(BrowserTabResult.FAILED)
        return
    }
    try {
        // AndroidX checks the default VIEW handler before the supplied candidates.
        val defaultPackage = CustomTabsClient.getPackageName(context, emptyList())
        if (defaultPackage != null) {
            onResult(browserTabLaunchResult(launchProviderBrowserTab(context, uri, defaultPackage)))
            return
        }

        // Resolve a generic HTTPS URL, not the provider URL (which may resolve to an app link).
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val candidates = context.packageManager.queryIntentActivities(browserIntent, 0)
            .associate { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
        val compatible = compatibleBrowserPackages(candidates.keys) { packageName ->
            CustomTabsClient.getPackageName(context, listOf(packageName), true) == packageName
        }
        when (compatible.size) {
            0 -> onResult(BrowserTabResult.UNAVAILABLE)
            1 -> onResult(browserTabLaunchResult(launchProviderBrowserTab(context, uri, compatible.single())))
            else -> {
                val activity = context.browserTabActivity()
                if (activity == null) {
                    onResult(BrowserTabResult.UNAVAILABLE)
                    return
                }
                showProviderBrowserPicker(
                    activity,
                    compatible.map { it to "${candidates[it]} ($it)" },
                    launch = { launchProviderBrowserTab(activity, uri, it) },
                    onResult = onResult,
                )
            }
        }
    } catch (_: ActivityNotFoundException) {
        onResult(BrowserTabResult.FAILED)
    } catch (_: SecurityException) {
        onResult(BrowserTabResult.FAILED)
    }
}

private fun browserTabLaunchResult(launched: Boolean) =
    if (launched) BrowserTabResult.LAUNCHED else BrowserTabResult.FAILED

internal fun showProviderBrowserPicker(
    activity: Activity,
    candidates: List<Pair<String, String>>,
    launch: (String) -> Boolean,
    onResult: (BrowserTabResult) -> Unit,
) {
    AlertDialog.Builder(activity)
        .setTitle("Choose browser")
        .setItems(candidates.map { it.second }.toTypedArray()) { _, index ->
            onResult(browserTabLaunchResult(launch(candidates[index].first)))
        }
        .setNegativeButton(android.R.string.cancel) { _, _ -> onResult(BrowserTabResult.CANCELLED) }
        .setOnCancelListener { onResult(BrowserTabResult.CANCELLED) }
        .show()
}

internal fun compatibleBrowserPackages(
    packages: Collection<String>,
    supportsTabs: (String) -> Boolean,
): List<String> = packages.distinct().sorted().filter(supportsTabs)

private tailrec fun Context.browserTabActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.browserTabActivity()
    else -> null
}

private fun launchProviderBrowserTab(context: Context, uri: Uri, browserPackage: String): Boolean {
    val intent = providerBrowserTabIntent(uri, browserPackage) ?: return false
    if (context.browserTabActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

internal fun providerBrowserTabIntent(uri: Uri, browserPackage: String): Intent? {
    if (!isBrowserTabEntry(uri) || browserPackage.isBlank()) return null
    return CustomTabsIntent.Builder()
        .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
        .build().intent.apply {
            data = uri
            setPackage(browserPackage)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
}

internal fun isBrowserTabEntry(uri: Uri): Boolean {
    val parsed = runCatching { URI(uri.toString()) }.getOrNull() ?: return false
    return parsed.scheme.equals("https", ignoreCase = true) &&
        !parsed.host.isNullOrBlank() && parsed.userInfo == null &&
        (parsed.port == -1 || parsed.port == 443)
}

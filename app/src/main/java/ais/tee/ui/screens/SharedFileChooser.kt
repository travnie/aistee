package ais.tee.ui.screens

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebChromeClient
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID

internal fun fileChooserAcceptsMimeType(
    acceptTypes: Array<String>,
    actualMimeType: String?,
    displayName: String? = null
): Boolean {
    val accepted = acceptTypes
        .flatMap { it.split(',') }
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
    if (accepted.isEmpty() || "*/*" in accepted) return true

    val normalizedName = displayName?.trim()?.lowercase()
    val actual = actualMimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { '/' in it }
    val actualType = actual?.substringBefore('/')
    val actualSubtype = actual?.substringAfter('/', missingDelimiterValue = "")
        ?.takeIf(String::isNotEmpty)
    return accepted.any { candidate ->
        if (candidate.startsWith('.')) {
            return@any normalizedName?.endsWith(candidate) == true
        }
        val parts = candidate.split('/', limit = 2)
        parts.size == 2 && actualType != null && actualSubtype != null &&
            (parts[0] == "*" || parts[0] == actualType) &&
            (parts[1] == "*" || parts[1] == actualSubtype)
    }
}

internal fun fileChooserModeAllowsStagedUpload(mode: Int): Boolean =
    mode == WebChromeClient.FileChooserParams.MODE_OPEN ||
        mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

internal fun sharedUploadMimeType(
    resolverMimeType: String?,
    mimeTypeHint: String?
): String? {
    val normalizedResolver = resolverMimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
    val resolverIsGeneric = normalizedResolver.isNullOrEmpty() ||
        normalizedResolver?.contains('*') == true ||
        normalizedResolver == "application/octet-stream" ||
        normalizedResolver == "binary/octet-stream"
    return if (resolverIsGeneric) mimeTypeHint ?: resolverMimeType else resolverMimeType
}

private fun sharedUriDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

internal fun sharedUrisForFileChooser(
    context: Context,
    params: WebChromeClient.FileChooserParams,
    uriStrings: List<String>,
    mimeTypeHint: String?
): List<Uri> {
    if (params.isCaptureEnabled || !fileChooserModeAllowsStagedUpload(params.mode)) return emptyList()
    val matching = uriStrings.asSequence()
        .map(Uri::parse)
        .filter { isAllowedUploadUri(context, it) }
        .filter { uri ->
            val resolverMimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            fileChooserAcceptsMimeType(
                acceptTypes = params.acceptTypes,
                actualMimeType = sharedUploadMimeType(resolverMimeType, mimeTypeHint),
                displayName = sharedUriDisplayName(context, uri)
            )
        }
        .toList()
    return if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
        matching
    } else {
        matching.take(1)
    }
}

/** Accepts only externally granted content URIs for provider uploads. */
internal fun isAllowedUploadUri(context: Context, uri: Uri): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    val authority = uri.authority ?: return false
    val appAuthorityPrefix = context.packageName.lowercase()
    val normalizedAuthority = authority.lowercase()
    if (normalizedAuthority == appAuthorityPrefix || normalizedAuthority.startsWith("$appAuthorityPrefix.")) return false
    return true
}

/** Unique registry key per launch; cancelled owners cannot receive a late result. Main thread only. */
internal class WebFilePickerSlot<T : Any>(private val registry: ActivityResultRegistry) {
    private data class Launch<T>(
        val key: String,
        val request: T,
        val launcher: ActivityResultLauncher<Intent>
    )

    private var active: Launch<T>? = null

    fun launch(intent: Intent, request: T, onResult: (T, ActivityResult) -> Unit) {
        check(active == null)
        val key = "aistee-web-picker-${UUID.randomUUID()}"
        val launcher = registry.register(key, ActivityResultContracts.StartActivityForResult()) { result ->
            val item = active?.takeIf { it.key == key } ?: return@register
            active = null
            // Registry finishes dispatch after invoking the callback.
            Handler(Looper.getMainLooper()).post { item.launcher.unregister() }
            onResult(item.request, result)
        }
        active = Launch(key, request, launcher)
        try {
            launcher.launch(intent)
        } catch (error: Exception) {
            active = null
            launcher.unregister()
            throw error
        }
    }

    fun cancelIf(predicate: (T) -> Boolean): T? {
        val item = active?.takeIf { predicate(it.request) } ?: return null
        active = null
        item.launcher.unregister()
        return item.request
    }
}

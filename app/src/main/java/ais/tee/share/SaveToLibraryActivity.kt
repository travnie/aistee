package ais.tee.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import ais.tee.data.model.DEFAULT_PROJECT_ID
import ais.tee.data.preferences.ProjectLibraryStore
import ais.tee.security.AppLockExempt
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_TITLE_CHARS = 80
private const val FALLBACK_TITLE = "Shared text"

internal data class SharedLibraryText(
    val title: String,
    val mediaType: String,
    val extension: String,
    val text: String,
) {
    override fun toString(): String =
        "SharedLibraryText(title=<redacted>, mediaType=$mediaType, extension=$extension, text=<redacted>)"
}

/**
 * Picks title and format for shared text. Markdown is kept as Markdown (by MIME type or file name);
 * everything else is stored as plain text. Blank text is rejected.
 */
internal fun sharedLibraryText(
    text: String,
    mimeType: String?,
    subject: String?,
    fileName: String?,
): SharedLibraryText? {
    if (text.isBlank()) return null
    val baseName = fileName?.substringBeforeLast('.', fileName)?.trim()?.takeIf { it.isNotEmpty() }
    val markdown = mimeType?.lowercase() == "text/markdown" ||
        fileName?.lowercase()?.let { it.endsWith(".md") || it.endsWith(".markdown") } == true
    val title = subject?.trim()?.takeIf { it.isNotEmpty() }
        ?: baseName
        ?: text.lineSequence()
            .map { it.trim().trimStart('#').trim() }
            .firstOrNull { it.isNotEmpty() }
        ?: FALLBACK_TITLE
    return SharedLibraryText(
        title = title.take(MAX_TITLE_CHARS),
        mediaType = if (markdown) "text/markdown" else "text/plain",
        extension = if (markdown) "md" else "txt",
        text = text,
    )
}

/**
 * Write-only share target: saves shared text or a text file to the Project Library Inbox without
 * opening a chat. It never shows Aistee content, so it is exempt from App lock.
 */
class SaveToLibraryActivity : ComponentActivity(), AppLockExempt {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val request = intent
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) { save(applicationContext, request) }
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun save(context: Context, intent: Intent): String {
        if (intent.action != Intent.ACTION_SEND) return "Nothing to save."
        val streamUri = intent.streamUri()
        val fileName = streamUri?.let { displayName(context, it) }
        val text = if (streamUri != null) {
            readText(context, streamUri) ?: return "Only UTF-8 text up to 8 MB can be saved."
        } else {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        }
        val shared = sharedLibraryText(
            text = text,
            mimeType = intent.type,
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
            fileName = fileName,
        ) ?: return "Nothing to save."
        val saved = ProjectLibraryStore(context.noBackupFilesDir).saveTextAsset(
            projectId = DEFAULT_PROJECT_ID,
            title = shared.title,
            mediaType = shared.mediaType,
            extension = shared.extension,
            text = shared.text,
        )
        return if (saved != null) "Saved to Aistee Library (Inbox)." else "Could not save to Aistee Library."
    }

    /** Only content URIs granted by the sender; file and other schemes are ignored. */
    private fun Intent.streamUri(): Uri? {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }
        return uri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun readText(context: Context, uri: Uri): String? = runCatching {
        val limit = ProjectLibraryStore.MAX_ASSET_BYTES
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > limit) return@runCatching null
            }
            output.toByteArray().decodeToString(throwOnInvalidSequence = true)
        }
    }.getOrNull()
}

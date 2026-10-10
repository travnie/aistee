package ais.tee.data.document

import android.content.Context
import android.net.Uri
import ais.tee.data.skills.sha256Hex

internal typealias OpenedMarkdownDocument = OpenedTextDocument

/** Markdown-specific naming adapter over the single bounded UTF-8 document I/O implementation. */
internal object MarkdownDocumentFileAccess {
    const val MAX_DOCUMENT_BYTES: Int = TextDocumentFileAccess.MAX_DOCUMENT_BYTES
    const val MAX_DISPLAY_NAME_CHARS: Int = TextDocumentFileAccess.MAX_DISPLAY_NAME_CHARS
    private const val FALLBACK_NAME = "document.md"

    suspend fun import(context: Context, uri: Uri): OpenedMarkdownDocument =
        TextDocumentFileAccess.import(context, uri, FALLBACK_NAME)

    suspend fun export(context: Context, uri: Uri, document: TextDocument) =
        TextDocumentFileAccess.export(context, uri, document)

    /** Hash the exact UTF-8 bytes, including original BOM and line-ending choice. */
    fun sourceDigest(document: TextDocument): String = sha256Hex(TextDocumentCodec.encodeUtf8(document))

    fun displayName(context: Context, uri: Uri, fallback: String = FALLBACK_NAME): String =
        TextDocumentFileAccess.displayName(context, uri, markdownFallback(fallback))

    internal fun resolveDisplayName(providerName: String?, fallback: String = FALLBACK_NAME): String =
        TextDocumentFileAccess.resolveDisplayName(providerName, markdownFallback(fallback))

    internal fun normalizeDisplayName(name: String, fallback: String = FALLBACK_NAME): String =
        TextDocumentFileAccess.normalizeDisplayName(name, markdownFallback(fallback))

    private fun markdownFallback(fallback: String): String = fallback.trim().ifBlank { FALLBACK_NAME }
}

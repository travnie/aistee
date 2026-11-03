package ais.tee.data.preferences

import android.content.Context
import ais.tee.data.model.WebAiService
import ais.tee.data.security.EncryptedJsonStore
import ais.tee.web.providerUrlMatches
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_BOOKMARKS = 80
private const val MAX_URL = 2_048
private const val MAX_RECORD = 180_000

/** Explicit user-created references only. No provider page text, cookies or history scraping. */
@Serializable
internal data class WebChatBookmark(
    val id: String,
    val providerId: String,
    val url: String,
    val title: String,
    val folder: String,
)

internal data class WebChatBookmarkSnapshot(
    val bookmarks: List<WebChatBookmark>,
    val readable: Boolean = true,
)

internal sealed interface WebChatBookmarkSaveResult {
    data class Saved(val bookmark: WebChatBookmark) : WebChatBookmarkSaveResult
    data class Rejected(val reason: String) : WebChatBookmarkSaveResult
}

/** Only bookmark a stable-looking, provider-owned HTTPS path. Never save queries or fragments. */
internal fun safeWebChatBookmarkUrl(service: WebAiService, raw: String?): String? {
    if (raw == null || raw.length !in 1..MAX_URL || raw.any(Char::isISOControl)) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (!uri.isAbsolute || !uri.scheme.equals("https", true) ||
        uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        uri.port !in listOf(-1, 443) || uri.rawPath.isNullOrBlank() || uri.rawPath == "/" ||
        uri.rawPath.contains("/../") || uri.rawPath.contains("/./")
    ) return null
    if (uri.path.split('/').any { it == "." || it == ".." }) return null
    if (!providerUrlMatches(service, raw)) return null
    val last = uri.path.trimEnd('/').substringAfterLast('/').lowercase(Locale.ROOT)
    if (last in setOf("login", "logout", "signin", "signup", "oauth", "auth", "settings")) return null
    return raw
}

internal fun cleanWebBookmarkTitle(raw: String): String? =
    raw.trim().takeIf { it.length in 1..80 && it.none(Char::isISOControl) }

internal fun cleanWebBookmarkFolder(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val segments = trimmed.split('/')
    if (segments.size !in 1..3 || trimmed.length > 96) return null
    if (segments.any { it != it.trim() || it.length !in 1..32 ||
            it == "." || it == ".." || it.any(Char::isISOControl) }) return null
    return trimmed
}

internal interface WebChatBookmarkPersistence {
    fun read(): String?
    fun hasStoredData(): Boolean
    fun write(value: String): Boolean
    fun clear(): Boolean
}

/** Keystore-encrypted, excluded from Android backup. Call from an IO dispatcher. */
internal class WebChatBookmarkStore(private val persistence: WebChatBookmarkPersistence) {
    constructor(context: Context) : this(encryptedPersistence(context))

    fun load(): WebChatBookmarkSnapshot = synchronized(lock) { readSnapshot() }

    fun save(service: WebAiService, url: String?, title: String, folder: String): WebChatBookmarkSaveResult =
        synchronized(lock) {
            val snapshot = readSnapshot()
            if (!snapshot.readable) return@synchronized WebChatBookmarkSaveResult.Rejected("Storage unavailable")
            val safeUrl = safeWebChatBookmarkUrl(service, url)
                ?: return@synchronized WebChatBookmarkSaveResult.Rejected("This page has no safe bookmark address")
            val safeTitle = cleanWebBookmarkTitle(title)
                ?: return@synchronized WebChatBookmarkSaveResult.Rejected("Enter a title (1–80 characters)")
            val safeFolder = cleanWebBookmarkFolder(folder)
                ?: return@synchronized WebChatBookmarkSaveResult.Rejected("Use up to three folder levels")
            val existing = snapshot.bookmarks.firstOrNull { it.providerId == service.id && it.url == safeUrl }
            if (existing == null && snapshot.bookmarks.size >= MAX_BOOKMARKS)
                return@synchronized WebChatBookmarkSaveResult.Rejected("Bookmark limit reached ($MAX_BOOKMARKS)")
            val record = WebChatBookmark(existing?.id ?: UUID.randomUUID().toString(),
                service.id, safeUrl, safeTitle, safeFolder)
            val updated = snapshot.bookmarks.filterNot { it.id == record.id } + record
            if (write(updated)) WebChatBookmarkSaveResult.Saved(record)
            else WebChatBookmarkSaveResult.Rejected("Could not save encrypted bookmark")
        }

    fun remove(id: String): Boolean = synchronized(lock) {
        val snapshot = readSnapshot()
        snapshot.readable && snapshot.bookmarks.any { it.id == id } &&
            write(snapshot.bookmarks.filterNot { it.id == id })
    }

    /** Only after explicit user confirmation when data cannot be decrypted. */
    fun reset(): Boolean = synchronized(lock) { runCatching { persistence.clear() }.getOrDefault(false) }

    private fun readSnapshot(): WebChatBookmarkSnapshot = runCatching {
        val raw = persistence.read() ?: return@runCatching WebChatBookmarkSnapshot(
            emptyList(), readable = !persistence.hasStoredData()
        )
        require(raw.length <= MAX_RECORD)
        val record = Json.decodeFromString<Archive>(raw)
        require(record.version == 1 && record.bookmarks.size <= MAX_BOOKMARKS)
        require(record.bookmarks.map { it.id }.toSet().size == record.bookmarks.size)
        require(record.bookmarks.map { it.providerId to it.url }.toSet().size == record.bookmarks.size)
        require(record.bookmarks.all { bookmark ->
            val service = WebAiService.entries.firstOrNull { it.id == bookmark.providerId }
            bookmark.id.isNotBlank() && bookmark.id.length <= 40 &&
                cleanWebBookmarkTitle(bookmark.title) == bookmark.title &&
                cleanWebBookmarkFolder(bookmark.folder) == bookmark.folder &&
                service != null && safeWebChatBookmarkUrl(service, bookmark.url) == bookmark.url
        })
        WebChatBookmarkSnapshot(record.bookmarks)
    }.getOrElse { WebChatBookmarkSnapshot(emptyList(), readable = false) }

    private fun write(bookmarks: List<WebChatBookmark>): Boolean = runCatching {
        val raw = Json.encodeToString(Archive(1, bookmarks))
        raw.length <= MAX_RECORD && persistence.write(raw)
    }.getOrDefault(false)

    @Serializable
    private data class Archive(val version: Int, val bookmarks: List<WebChatBookmark>)

    private companion object {
        val lock = Any()
        fun encryptedPersistence(context: Context): WebChatBookmarkPersistence {
            val encrypted = EncryptedJsonStore(context, "encrypted_web_bookmarks", "aistee-web-bookmarks")
            return object : WebChatBookmarkPersistence {
                override fun read() = encrypted.read()
                override fun hasStoredData() = encrypted.hasStoredData()
                override fun write(value: String) = encrypted.write(value)
                override fun clear() = encrypted.clear()
            }
        }
    }
}

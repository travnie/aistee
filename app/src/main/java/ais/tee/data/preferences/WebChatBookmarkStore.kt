package ais.tee.data.preferences

import android.content.Context
import ais.tee.data.model.WebAiService
import ais.tee.data.security.EncryptedJsonStore
import ais.tee.web.providerUrlMatches
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_BOOKMARKS = 80
private const val MAX_URL = 2_048
internal const val WEB_BOOKMARK_BACKUP_MAX_CHARS = 180_000
private const val MAX_RECORD = WEB_BOOKMARK_BACKUP_MAX_CHARS

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

internal sealed interface WebChatBookmarkImportResult {
    data class Imported(val added: Int, val skipped: Int) : WebChatBookmarkImportResult
    data class Rejected(val reason: String) : WebChatBookmarkImportResult
}

/** Guard SAF reads before parsing untrusted JSON. */
internal fun readWebChatBookmarkBackup(input: InputStream): String? = runCatching {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    InputStreamReader(input, decoder).use { reader ->
        val content = StringBuilder()
        val buffer = CharArray(4_096)
        while (true) {
            val count = reader.read(buffer)
            if (count == -1) break
            require(content.length + count <= WEB_BOOKMARK_BACKUP_MAX_CHARS)
            content.append(buffer, 0, count)
        }
        content.toString()
    }
}.getOrNull()

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

/** Comparison only: preserve original provider URL spelling on disk. */
internal fun webBookmarkIdentityKey(providerId: String, url: String): Pair<String, String> {
    val uri = URI(url)
    return providerId to "https://${uri.host.trimEnd('.').lowercase(Locale.ROOT)}${uri.rawPath}"
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
            val key = webBookmarkIdentityKey(service.id, safeUrl)
            val existing = snapshot.bookmarks.firstOrNull {
                webBookmarkIdentityKey(it.providerId, it.url) == key
            }
            if (existing == null && snapshot.bookmarks.size >= MAX_BOOKMARKS)
                return@synchronized WebChatBookmarkSaveResult.Rejected("Bookmark limit reached ($MAX_BOOKMARKS)")
            val record = WebChatBookmark(existing?.id ?: UUID.randomUUID().toString(),
                service.id, existing?.url ?: safeUrl, safeTitle, safeFolder)
            val updated = snapshot.bookmarks.filterNot { it.id == record.id } + record
            if (write(updated)) WebChatBookmarkSaveResult.Saved(record)
            else WebChatBookmarkSaveResult.Rejected("Could not save encrypted bookmark")
        }

    fun remove(id: String): Boolean = synchronized(lock) {
        val snapshot = readSnapshot()
        snapshot.readable && snapshot.bookmarks.any { it.id == id } &&
            write(snapshot.bookmarks.filterNot { it.id == id })
    }

    /** User-initiated plaintext backup; caller must disclose that provider links are included. */
    fun exportPortableArchive(): String? = synchronized(lock) {
        val snapshot = readSnapshot()
        if (!snapshot.readable) null
        else Json.encodeToString(Archive(version = 1, bookmarks = snapshot.bookmarks))
    }

    /** Merge only. Invalid archives and unreadable local storage never overwrite existing data. */
    fun importPortableArchive(raw: String): WebChatBookmarkImportResult = synchronized(lock) {
        val archive = runCatching { decodeArchive(raw) }.getOrNull()
            ?: return@synchronized WebChatBookmarkImportResult.Rejected("Invalid bookmark backup")
        val snapshot = readSnapshot()
        if (!snapshot.readable) {
            return@synchronized WebChatBookmarkImportResult.Rejected("Encrypted bookmarks unavailable")
        }
        val importedKeys = archive.bookmarks.map { webBookmarkIdentityKey(it.providerId, it.url) }
        if (importedKeys.toSet().size != importedKeys.size) {
            return@synchronized WebChatBookmarkImportResult.Rejected("Duplicate bookmark addresses")
        }
        val localKeys = snapshot.bookmarks.map { webBookmarkIdentityKey(it.providerId, it.url) }.toSet()
        val additions = archive.bookmarks.filterNot {
            webBookmarkIdentityKey(it.providerId, it.url) in localKeys
        }
            .map { it.copy(id = UUID.randomUUID().toString()) }
        if (snapshot.bookmarks.size + additions.size > MAX_BOOKMARKS) {
            return@synchronized WebChatBookmarkImportResult.Rejected("Bookmark limit reached ($MAX_BOOKMARKS)")
        }
        val skipped = archive.bookmarks.size - additions.size
        if (additions.isEmpty() || write(snapshot.bookmarks + additions)) {
            WebChatBookmarkImportResult.Imported(added = additions.size, skipped = skipped)
        } else {
            WebChatBookmarkImportResult.Rejected("Could not save encrypted bookmarks")
        }
    }

    /** Only after explicit user confirmation when data cannot be decrypted. */
    fun reset(): Boolean = synchronized(lock) { runCatching { persistence.clear() }.getOrDefault(false) }

    private fun readSnapshot(): WebChatBookmarkSnapshot = runCatching {
        val raw = persistence.read() ?: return@runCatching WebChatBookmarkSnapshot(
            emptyList(), readable = !persistence.hasStoredData()
        )
        WebChatBookmarkSnapshot(decodeArchive(raw).bookmarks)
    }.getOrElse { WebChatBookmarkSnapshot(emptyList(), readable = false) }

    private fun decodeArchive(raw: String): Archive {
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
        return record
    }

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

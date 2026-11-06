package ais.tee.data.preferences

import android.content.Context
import ais.tee.data.model.WebAiService
import ais.tee.data.security.EncryptedJsonStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_JOURNAL_TEXT_CHARS = 48_000
private const val MAX_JOURNAL_TEXT_BYTES = 192_000
private const val MAX_JOURNAL_RECORD_CHARS = 3_000_000
private const val MAX_JOURNAL_REVISIONS = 8
private const val AUTO_REVISION_MS = 120_000L

/** Entirely Aistee-owned text. Never reads a provider DOM, form, or session. */
@Serializable
internal data class WebDraftVersion(
    val id: String,
    val text: String,
    val savedAtEpochMs: Long,
) {
    override fun toString(): String = "WebDraftVersion(id=<redacted>, text=<redacted>)"
}

@Serializable
internal data class WebDraftPage(
    val serviceId: String,
    val current: String = "",
    val versions: List<WebDraftVersion> = emptyList(),
    val lastVersionEpochMs: Long = 0L,
) {
    override fun toString(): String =
        "WebDraftPage(serviceId=$serviceId, current=<redacted>, versions=${versions.size})"
}

internal data class WebDraftJournalSnapshot(
    val page: WebDraftPage,
    val readable: Boolean = true,
)

internal interface WebDraftJournalPersistence {
    fun read(): String?
    fun hasStoredData(): Boolean
    fun write(text: String): Boolean
    fun clear(): Boolean
}

/** Keystore-backed composer journal. No backup and no provider-owned content inspection. */
internal class WebChatDraftJournal(
    private val persistence: WebDraftJournalPersistence,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(encryptedPersistence(context))

    /** Reserve on caller thread before IO dispatch. */
    fun nextWriteTicket(): Long = issuedWriteTicket.incrementAndGet()

    fun load(service: WebAiService): WebDraftJournalSnapshot = synchronized(lock) {
        val archive = readArchive()
        WebDraftJournalSnapshot(
            page = archive.pages.firstOrNull { it.serviceId == service.id }
                ?: WebDraftPage(service.id),
            readable = archive.readable,
        )
    }

    /** Latest text autosaves; significant older text periodically becomes a restore point. */
    fun save(service: WebAiService, text: String, ticket: Long = nextWriteTicket()): Boolean = synchronized(lock) {
        if (superseded(service, ticket)) return@synchronized true
        if (!validText(text)) return@synchronized false
        val archive = readArchive()
        if (!archive.readable) return@synchronized false
        val previous = archive.pages.firstOrNull { it.serviceId == service.id }
            ?: WebDraftPage(service.id)
        if (previous.current == text) {
            acceptedTickets[service.id] = ticket
            return@synchronized true
        }
        val now = clock()
        val shouldVersion = previous.current.length >= 80 &&
            now - previous.lastVersionEpochMs >= AUTO_REVISION_MS
        val updated = if (shouldVersion) {
            previous.copy(
                current = text,
                versions = addVersion(previous.versions, previous.current, now),
                lastVersionEpochMs = now,
            )
        } else previous.copy(current = text)
        writeAndRecord(service, ticket, archive.pages, updated)
    }

    /** Capture the editor's latest text and its restore point in one atomic journal write. */
    fun checkpoint(service: WebAiService, pendingText: String? = null, ticket: Long = nextWriteTicket()): Boolean = synchronized(lock) {
        if (superseded(service, ticket)) return@synchronized true
        val archive = readArchive()
        if (!archive.readable) return@synchronized false
        val page = archive.pages.firstOrNull { it.serviceId == service.id }
            ?: WebDraftPage(service.id)
        val latest = pendingText ?: page.current
        if (!validText(latest) || latest.isBlank()) return@synchronized false
        val now = clock()
        writeAndRecord(service, ticket, archive.pages, page.copy(
            current = latest,
            versions = addVersion(page.versions, latest, now),
            lastVersionEpochMs = now,
        ))
    }

    /** Read the requested version before rotation; keep any unpersisted editor changes. */
    fun restore(service: WebAiService, versionId: String, pendingText: String? = null, ticket: Long = nextWriteTicket()): Boolean = synchronized(lock) {
        if (superseded(service, ticket)) return@synchronized true
        val archive = readArchive()
        if (!archive.readable) return@synchronized false
        val page = archive.pages.firstOrNull { it.serviceId == service.id }
            ?: return@synchronized false
        val version = page.versions.firstOrNull { it.id == versionId }
            ?: return@synchronized false
        val current = pendingText ?: page.current
        if (!validText(current)) return@synchronized false
        if (page.current == version.text && current == version.text) {
            acceptedTickets[service.id] = ticket
            return@synchronized true
        }
        val now = clock()
        writeAndRecord(service, ticket, archive.pages, page.copy(
            current = version.text,
            versions = if (current.isBlank()) page.versions
                else addVersion(page.versions, current, now),
            lastVersionEpochMs = now,
        ))
    }

    private fun superseded(service: WebAiService, ticket: Long): Boolean =
        ticket < (acceptedTickets[service.id] ?: Long.MIN_VALUE)

    private fun writeAndRecord(
        service: WebAiService,
        ticket: Long,
        pages: List<WebDraftPage>,
        updated: WebDraftPage,
    ): Boolean {
        val ok = write(pages, updated)
        if (ok) acceptedTickets[service.id] = ticket
        return ok
    }

    /** Reset unreadable encrypted data only after an explicit user confirmation. */
    fun reset(): Boolean = synchronized(lock) {
        runCatching { persistence.clear() }.getOrDefault(false)
    }

    private fun addVersion(
        existing: List<WebDraftVersion>,
        text: String,
        now: Long,
    ): List<WebDraftVersion> {
        if (text.isBlank() || existing.lastOrNull()?.text == text) return existing
        return (existing + WebDraftVersion(UUID.randomUUID().toString(), text, now))
            .takeLast(MAX_JOURNAL_REVISIONS)
    }

    /** Prune oldest restore points across providers, never their current drafts. */
    private fun write(pages: List<WebDraftPage>, updated: WebDraftPage): Boolean =
        runCatching {
            val next = (pages.filterNot { it.serviceId == updated.serviceId } + updated).toMutableList()
            while (true) {
                val json = Json.encodeToString(Archive(version = 1, pages = next))
                if (json.length <= MAX_JOURNAL_RECORD_CHARS) return@runCatching persistence.write(json)
                val oldest = next.indices.filter { next[it].versions.isNotEmpty() }
                    .minByOrNull { next[it].versions.first().savedAtEpochMs }
                    ?: return@runCatching false
                next[oldest] = next[oldest].copy(versions = next[oldest].versions.drop(1))
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }.getOrDefault(false)

    private fun readArchive(): ReadArchive = runCatching {
        val raw = persistence.read() ?: return@runCatching ReadArchive(
            pages = emptyList(), readable = !persistence.hasStoredData()
        )
        require(raw.length <= MAX_JOURNAL_RECORD_CHARS)
        val archive = Json.decodeFromString<Archive>(raw)
        require(archive.version == 1)
        require(archive.pages.size <= WebAiService.entries.size)
        require(archive.pages.map { it.serviceId }.toSet().size == archive.pages.size)
        require(archive.pages.all { page ->
            WebAiService.entries.any { it.id == page.serviceId } &&
                validText(page.current) && page.lastVersionEpochMs >= 0 &&
                page.versions.size <= MAX_JOURNAL_REVISIONS &&
                page.versions.map { it.id }.toSet().size == page.versions.size &&
                page.versions.all { v ->
                    v.id.length in 1..40 && v.savedAtEpochMs >= 0 &&
                        validText(v.text) && v.text.isNotBlank()
                }
        })
        ReadArchive(archive.pages)
    }.getOrElse { ReadArchive(emptyList(), readable = false) }

    private fun validText(text: String): Boolean =
        text.length <= MAX_JOURNAL_TEXT_CHARS &&
            text.toByteArray(Charsets.UTF_8).size <= MAX_JOURNAL_TEXT_BYTES

    @Serializable
    private data class Archive(val version: Int, val pages: List<WebDraftPage>)

    private data class ReadArchive(
        val pages: List<WebDraftPage>,
        val readable: Boolean = true,
    )

    private companion object {
        val lock = Any()
        val issuedWriteTicket = AtomicLong(0L)
        val acceptedTickets = mutableMapOf<String, Long>()
        fun encryptedPersistence(context: Context): WebDraftJournalPersistence {
            val storage = EncryptedJsonStore(
                context,
                "encrypted_web_draft_journal",
                "aistee-web-draft-journal",
            )
            return object : WebDraftJournalPersistence {
                override fun read() = storage.read()
                override fun hasStoredData() = storage.hasStoredData()
                override fun write(text: String) = storage.write(text)
                override fun clear() = storage.clear()
            }
        }
    }
}

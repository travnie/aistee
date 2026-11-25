package ais.tee.data.document

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_EXTERNAL_VAULT_CHARS = 8 * 1024 * 1024
private val EXTERNAL_TITLE_KEYS = listOf("title", "name")
private val EXTERNAL_TEXT_KEYS = listOf("content", "text", "prompt")

/** One recognized external vault: portable prompts to merge plus how many entries were dropped. */
class ExternalPromptVaultImport private constructor(
    val adapterId: String,
    val adapterLabel: String,
    val prompts: List<PortablePrompt>,
    val skipped: Int,
) {
    init {
        require(prompts.isNotEmpty()) { "External vault import must contain at least one prompt" }
        require(skipped >= 0) { "Skipped count must not be negative" }
    }

    override fun toString(): String =
        "ExternalPromptVaultImport(adapter=$adapterId, prompts=${prompts.size}, skipped=$skipped)"

    companion object {
        internal fun create(
            adapterId: String,
            adapterLabel: String,
            prompts: List<PortablePrompt>,
            skipped: Int,
        ): ExternalPromptVaultImport? {
            if (prompts.isEmpty()) return null
            return ExternalPromptVaultImport(adapterId, adapterLabel, prompts, skipped)
        }
    }
}

/**
 * Import-only adapter for an external prompt-vault file shape. External files are untrusted data:
 * only bounded title/text pairs become Library prompts, everything else is dropped and counted.
 * Importing never executes code, connects anywhere or grants provider/session access.
 */
interface ExternalPromptVaultAdapter {
    val id: String
    val label: String

    /** Null when the file is not this shape or yields no portable prompt. Never throws. */
    fun decode(raw: String): ExternalPromptVaultImport?
}

/**
 * Registry of external prompt-vault import adapters. Aistee's own single-prompt and
 * [PortablePromptBackup] formats are decoded before these adapters run; a file carrying the
 * Aistee backup kind is never reinterpreted as an external vault.
 */
object ExternalPromptVaultAdapters {
    val adapters: List<ExternalPromptVaultAdapter> = listOf(
        PromptListEnvelopeAdapter,
        BarePromptArrayAdapter,
    )

    /** First matching adapter wins. */
    fun decode(raw: String): ExternalPromptVaultImport? =
        adapters.firstNotNullOfOrNull { adapter -> adapter.decode(raw) }
}

/** `{"prompts": [{...}, ...], ...}` envelope used by common browser-extension vault exports. */
private object PromptListEnvelopeAdapter : ExternalPromptVaultAdapter {
    override val id: String = "prompt-list-envelope"
    override val label: String = "external prompt list"

    override fun decode(raw: String): ExternalPromptVaultImport? = runCatching {
        if (raw.length > MAX_EXTERNAL_VAULT_CHARS) return@runCatching null
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
        if (root["kind"]?.stringContent() == PortablePromptBackup.KIND) return@runCatching null
        val entries = root["prompts"] as? JsonArray ?: return@runCatching null
        buildImport(this, entries)
    }.getOrNull()
}

/** Bare top-level `[{"title": ..., "content": ...}, ...]` array. */
private object BarePromptArrayAdapter : ExternalPromptVaultAdapter {
    override val id: String = "bare-prompt-array"
    override val label: String = "prompt array"

    override fun decode(raw: String): ExternalPromptVaultImport? = runCatching {
        if (raw.length > MAX_EXTERNAL_VAULT_CHARS) return@runCatching null
        val entries = Json.parseToJsonElement(raw) as? JsonArray ?: return@runCatching null
        buildImport(this, entries)
    }.getOrNull()
}

private fun buildImport(
    adapter: ExternalPromptVaultAdapter,
    entries: JsonArray,
): ExternalPromptVaultImport? {
    var skipped = (entries.size - PortablePromptBackup.MAX_PROMPTS).coerceAtLeast(0)
    val prompts = mutableListOf<PortablePrompt>()
    for (entry in entries.take(PortablePromptBackup.MAX_PROMPTS)) {
        val mapped = mapExternalEntry(entry)
        if (mapped == null) skipped++ else prompts += mapped
    }
    return ExternalPromptVaultImport.create(adapter.id, adapter.label, prompts, skipped)
}

/** Only string title/text fields are read; ids, tags, timestamps and folder data are ignored. */
private fun mapExternalEntry(entry: JsonElement): PortablePrompt? {
    val obj = entry as? JsonObject ?: return null
    val title = EXTERNAL_TITLE_KEYS.firstNotNullOfOrNull { key -> obj[key]?.stringContent() }
    val text = EXTERNAL_TEXT_KEYS.firstNotNullOfOrNull { key -> obj[key]?.stringContent() }
    if (title == null || text == null) return null
    return PortablePrompt.fromLibrary(title, text).takeIf { it.isValid() }
}

private fun JsonElement?.stringContent(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

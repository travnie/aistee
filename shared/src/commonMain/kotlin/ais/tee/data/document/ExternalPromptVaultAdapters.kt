package ais.tee.data.document

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Cheap pre-parse guard in UTF-16 code units; the per-entry UTF-8 byte cap below and the
// file-level byte cap enforced at document read keep the real limits in bytes.
private const val MAX_EXTERNAL_VAULT_CHARS = 8 * 1024 * 1024
private const val MAX_EXTERNAL_PROMPT_TEXT_UTF8_BYTES = 8 * 1024 * 1024
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
        ): ExternalPromptVaultImport =
            ExternalPromptVaultImport(adapterId, adapterLabel, prompts, skipped)
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

    /** Null when the parsed document is not this shape or yields no portable prompt. */
    fun decode(root: JsonElement): ExternalPromptVaultImport?
}

/**
 * Registry of external prompt-vault import adapters. Aistee's own single-prompt and
 * [PortablePromptBackup] formats are decoded before these adapters run; a file carrying the
 * Aistee backup kind is never reinterpreted as an external vault. The file is parsed once here
 * and each adapter only inspects the tree.
 */
object ExternalPromptVaultAdapters {
    val adapters: List<ExternalPromptVaultAdapter> = listOf(
        PromptListEnvelopeAdapter,
        BarePromptArrayAdapter,
    )

    /** First matching adapter wins. Strict JSON only; oversized files are rejected up front. */
    fun decode(raw: String): ExternalPromptVaultImport? {
        if (raw.length > MAX_EXTERNAL_VAULT_CHARS) return null
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
        return adapters.firstNotNullOfOrNull { adapter -> adapter.decode(root) }
    }
}

/** `{"prompts": [{...}, ...], ...}` envelope used by common browser-extension vault exports. */
private object PromptListEnvelopeAdapter : ExternalPromptVaultAdapter {
    override val id: String = "prompt-list-envelope"
    override val label: String = "external prompt list"

    override fun decode(root: JsonElement): ExternalPromptVaultImport? {
        val obj = root as? JsonObject ?: return null
        if (obj["kind"]?.stringContent() == PortablePromptBackup.KIND) return null
        val entries = obj["prompts"] as? JsonArray ?: return null
        return buildImport(this, entries)
    }
}

/** Bare top-level `[{"title": ..., "content": ...}, ...]` array. */
private object BarePromptArrayAdapter : ExternalPromptVaultAdapter {
    override val id: String = "bare-prompt-array"
    override val label: String = "prompt array"

    override fun decode(root: JsonElement): ExternalPromptVaultImport? {
        val entries = root as? JsonArray ?: return null
        return buildImport(this, entries)
    }
}

private fun buildImport(
    adapter: ExternalPromptVaultAdapter,
    entries: JsonArray,
): ExternalPromptVaultImport? {
    // A recognized vault shape with zero entries is not a vault; a shape whose entries all fail
    // validation still returns a result so the caller can report the rejected count.
    if (entries.isEmpty()) return null
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
    // Real-world exports often pad names; trimming stays adapter-side, Library rules unchanged.
    val title = EXTERNAL_TITLE_KEYS.firstNotNullOfOrNull { key -> obj[key]?.stringContent()?.trim() }
    val text = EXTERNAL_TEXT_KEYS.firstNotNullOfOrNull { key -> obj[key]?.stringContent() }
    // A lone surrogate would survive isValid but break strict UTF-8 asset encoding downstream,
    // and one over-limit body would abort the rest of the import; drop the entry instead.
    if (title == null || text == null ||
        hasUnpairedSurrogate(title) || hasUnpairedSurrogate(text) ||
        text.encodeToByteArray().size > MAX_EXTERNAL_PROMPT_TEXT_UTF8_BYTES
    ) return null
    return PortablePrompt.fromLibrary(title, text).takeIf { it.isValid() }
}

private fun hasUnpairedSurrogate(value: String): Boolean {
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c.isHighSurrogate() -> {
                if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return true
                i += 2
            }
            c.isLowSurrogate() -> return true
            else -> i++
        }
    }
    return false
}

private fun JsonElement?.stringContent(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

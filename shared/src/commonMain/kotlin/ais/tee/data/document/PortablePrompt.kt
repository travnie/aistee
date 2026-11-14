package ais.tee.data.document

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One local Library prompt. Project paths and provider data stay out of the archive. */
@Serializable
class PortablePrompt private constructor(val version: Int, val title: String, val text: String) {
    internal fun isValid(): Boolean =
        version == 1 && title.isNotBlank() && title == title.trim() &&
            title.length <= 120 && title.none { it in '\u0000'..'\u001f' || it in '\u007f'..'\u009f' } && text.isNotBlank()

    fun toDocument(maxUtf8Bytes: Int): TextDocument? {
        if (!isValid()) return null
        val raw = Json.encodeToString(this)
        if (raw.encodeToByteArray().size > maxUtf8Bytes) return null
        return TextDocument(raw, hadUtf8Bom = false, lineEndings = TextDocumentCodec.detectLineEndings(raw))
    }

    override fun toString(): String = "PortablePrompt(version=$version, content=<redacted>)"

    companion object {
        fun fromLibrary(title: String, text: String): PortablePrompt = PortablePrompt(1, title, text)

        fun decode(raw: String): PortablePrompt? =
            runCatching { Json.decodeFromString<PortablePrompt>(raw).takeIf { it.isValid() } }.getOrNull()
    }
}

/** Many Library prompts in one file. Per-prompt rules unchanged; decode is all-or-nothing. */
@Serializable
class PortablePromptBackup private constructor(
    val version: Int,
    val kind: String,
    val prompts: List<PortablePrompt>,
) {
    private fun isValid(): Boolean =
        version == 1 && kind == KIND && prompts.size in 1..MAX_PROMPTS && prompts.all { it.isValid() }

    fun toDocument(maxUtf8Bytes: Int): TextDocument? {
        if (!isValid()) return null
        val raw = Json.encodeToString(this)
        if (raw.encodeToByteArray().size > maxUtf8Bytes) return null
        return TextDocument(raw, hadUtf8Bom = false, lineEndings = TextDocumentCodec.detectLineEndings(raw))
    }

    override fun toString(): String = "PortablePromptBackup(version=$version, prompts=${prompts.size})"

    companion object {
        const val KIND = "aistee-prompt-vault"
        const val MAX_PROMPTS = 500

        /** Non-portable prompts dropped; caller reports the difference. */
        fun of(prompts: List<PortablePrompt>): PortablePromptBackup =
            PortablePromptBackup(1, KIND, prompts.filter { it.isValid() })

        fun decode(raw: String): PortablePromptBackup? =
            runCatching { Json.decodeFromString<PortablePromptBackup>(raw).takeIf { it.isValid() } }.getOrNull()
    }
}

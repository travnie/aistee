package ais.tee.data.document

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One local Library prompt. Project paths and provider data stay out of the archive. */
@Serializable
internal class PortablePrompt private constructor(val version: Int, val title: String, val text: String) {
    private fun isValid(): Boolean =
        version == 1 && title.isNotBlank() && title == title.trim() &&
            title.length <= 120 && title.none(Char::isISOControl) && text.isNotBlank()

    fun toDocument(): TextDocument? {
        if (!isValid()) return null
        val raw = Json.encodeToString(this)
        if (raw.encodeToByteArray().size > TextDocumentFileAccess.MAX_DOCUMENT_BYTES) return null
        return TextDocument(raw, hadUtf8Bom = false, lineEndings = TextDocumentCodec.detectLineEndings(raw))
    }

    override fun toString(): String = "PortablePrompt(version=$version, content=<redacted>)"

    companion object {
        fun fromLibrary(title: String, text: String): PortablePrompt = PortablePrompt(1, title, text)

        fun decode(raw: String): PortablePrompt? =
            runCatching { Json.decodeFromString<PortablePrompt>(raw).takeIf { it.isValid() } }.getOrNull()
    }
}

package ais.tee.data.document

/**
 * Detects a supported structured-text syntax from portable document metadata.
 *
 * Detection deliberately does not sniff document contents. File names and MIME types are treated as
 * independent hints; conflicting specific hints return null instead of selecting the wrong parser.
 * Generic, malformed or unknown MIME types do not override a recognized file extension.
 */
fun detectStructuredTextFormat(
    displayName: String?,
    mimeType: String? = null
): StructuredTextFormat? {
    val fromName = structuredTextFormatFromDisplayName(displayName)
    val fromMime = structuredTextFormatFromMimeType(mimeType)
    return when {
        fromName != null && fromMime != null && fromName != fromMime -> null
        fromName != null -> fromName
        else -> fromMime
    }
}

/** Validates a document only when its metadata identifies a supported structured-text syntax. */
fun validateStructuredTextDocument(
    text: String,
    displayName: String?,
    mimeType: String? = null
): StructuredTextValidationResult? =
    detectStructuredTextFormat(displayName, mimeType)
        ?.let { format -> StructuredTextDiagnostics.validate(text, format) }

private fun structuredTextFormatFromDisplayName(displayName: String?): StructuredTextFormat? {
    val normalized = displayName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val extension = normalized.substringAfterLast('.', missingDelimiterValue = "")
    return when (extension) {
        "json" -> StructuredTextFormat.JSON
        "json5" -> StructuredTextFormat.JSON5
        "yaml", "yml" -> StructuredTextFormat.YAML
        "xml" -> StructuredTextFormat.XML
        else -> null
    }
}

private fun structuredTextFormatFromMimeType(mimeType: String?): StructuredTextFormat? {
    val mime = parseMimeType(mimeType) ?: return null
    val normalized = "${mime.type}/${mime.subtype}"
    return when {
        normalized == "application/json" ||
            normalized == "text/json" ||
            mime.subtype.hasStructuredSuffix("json") -> StructuredTextFormat.JSON
        normalized == "application/json5" ||
            normalized == "text/json5" ||
            mime.subtype.hasStructuredSuffix("json5") -> StructuredTextFormat.JSON5
        normalized in YAML_MIME_TYPES -> StructuredTextFormat.YAML
        normalized == "application/xml" ||
            normalized == "text/xml" ||
            mime.subtype.hasStructuredSuffix("xml") -> StructuredTextFormat.XML
        else -> null
    }
}

private fun parseMimeType(mimeType: String?): ParsedMimeType? {
    val raw = mimeType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val parameterStart = raw.indexOf(';')
    val mediaType = if (parameterStart < 0) raw else raw.substring(0, parameterStart).trim()
    if (parameterStart >= 0 && !hasValidMimeParameters(raw, parameterStart)) return null
    if (mediaType.count { char -> char == '/' } != 1) return null

    val type = mediaType.substringBefore('/')
    val subtype = mediaType.substringAfter('/')
    if (!type.isMimeToken() || !subtype.isMimeToken()) return null
    return ParsedMimeType(type, subtype)
}

private fun hasValidMimeParameters(source: String, start: Int): Boolean {
    var index = start
    while (index < source.length) {
        index = source.parseMimeParameter(index) ?: return false
    }
    return true
}

private fun String.parseMimeParameter(start: Int): Int? {
    if (getOrNull(start) != ';') return null
    var index = skipMimeWhitespace(start + 1)

    val nameEnd = scanMimeToken(index)
    if (nameEnd == index) return null
    index = skipMimeWhitespace(nameEnd)
    if (getOrNull(index) != '=') return null
    index = skipMimeWhitespace(index + 1)

    index = parseMimeValue(index) ?: return null
    index = skipMimeWhitespace(index)
    return if (index == length || this[index] == ';') index else null
}

private fun String.parseMimeValue(start: Int): Int? {
    if (start >= length) return null
    if (this[start] == '"') return skipQuotedMimeValue(start)
    val end = scanMimeToken(start)
    return end.takeIf { it > start }
}

private fun String.scanMimeToken(start: Int): Int {
    var index = start
    while (index < length && this[index].isMimeTokenChar()) index++
    return index
}

private fun String.skipQuotedMimeValue(start: Int): Int? {
    var index = start + 1
    while (index < length) {
        val char = this[index]
        when {
            char == '"' -> return index + 1
            char == '\\' -> {
                val escaped = getOrNull(index + 1) ?: return null
                if (escaped.isInvalidMimeQuotedChar()) return null
                index += 2
            }
            char.isInvalidMimeQuotedChar() -> return null
            else -> index++
        }
    }
    return null
}

private fun Char.isInvalidMimeQuotedChar(): Boolean =
    (code < 0x20 && this != '\t') || code == 0x7F

private fun String.skipMimeWhitespace(start: Int): Int {
    var index = start
    while (index < length && this[index] in MIME_WHITESPACE) index++
    return index
}

private fun String.hasStructuredSuffix(suffix: String): Boolean {
    val marker = "+$suffix"
    return endsWith(marker) && length > marker.length
}

private fun String.isMimeToken(): Boolean = isNotEmpty() && all(Char::isMimeTokenChar)

private fun Char.isMimeTokenChar(): Boolean =
    this in 'a'..'z' ||
        this in '0'..'9' ||
        this in MIME_TOKEN_PUNCTUATION

private data class ParsedMimeType(
    val type: String,
    val subtype: String
)

private const val MIME_TOKEN_PUNCTUATION = "!#$%&'*+-.^_`|~"
private val MIME_WHITESPACE = setOf(' ', '\t')

private val YAML_MIME_TYPES = setOf(
    "application/yaml",
    "text/yaml",
    "application/x-yaml",
    "text/x-yaml"
)

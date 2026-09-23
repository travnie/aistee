package ais.tee.data.document

import it.krzeminski.snakeyaml.engine.kmp.api.DumpSettings
import it.krzeminski.snakeyaml.engine.kmp.api.LoadSettings
import it.krzeminski.snakeyaml.engine.kmp.api.StreamDataWriter
import it.krzeminski.snakeyaml.engine.kmp.emitter.Emitter
import it.krzeminski.snakeyaml.engine.kmp.events.AliasEvent
import it.krzeminski.snakeyaml.engine.kmp.events.Event
import it.krzeminski.snakeyaml.engine.kmp.events.NodeEvent
import it.krzeminski.snakeyaml.engine.kmp.exceptions.YamlEngineException
import it.krzeminski.snakeyaml.engine.kmp.parser.ParserImpl
import it.krzeminski.snakeyaml.engine.kmp.scanner.StreamReader
import li.songe.json5.Json5
import li.songe.json5.Json5ParseException
import li.songe.json5.Json5SyntaxKind
import li.songe.json5.Json5SyntaxTokens
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlException
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming

/** Structured text syntaxes currently validated by the portable document core. */
enum class StructuredTextFormat {
    JSON,
    JSON5,
    YAML,
    XML
}

data class StructuredTextValidationResult(
    val format: StructuredTextFormat,
    val errorMessage: String? = null
) {
    val isValid: Boolean
        get() = errorMessage == null
}

data class StructuredTextFormatResult(
    val text: String,
    val changed: Boolean,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean
        get() = errorMessage == null
}

/**
 * Syntax-aware validation for portable document tooling.
 *
 * This deliberately stays separate from BOM/EOL/NUL diagnostics. Invalid source is never repaired
 * heuristically, and formatting is exposed only where the current shared parser can round-trip the
 * syntax without knowingly weakening fidelity.
 */
object StructuredTextDiagnostics {
    private const val MAX_JSON_NESTING = 128
    private const val MAX_JSON_FORMATTED_CHARS = 8 * 1024 * 1024
    private const val MAX_JSON5_CHARS = 3 * 1024 * 1024
    private const val MAX_JSON5_NESTING = 128
    private const val MAX_YAML_CODE_POINTS = 3 * 1024 * 1024
    private const val MAX_YAML_FORMATTED_CHARS = 8 * 1024 * 1024
    private const val MAX_XML_CHARS = 3 * 1024 * 1024
    private const val MAX_XML_NESTING = 128

    fun validate(text: String, format: StructuredTextFormat): StructuredTextValidationResult =
        when (format) {
            StructuredTextFormat.JSON -> validateJson(text)
            StructuredTextFormat.JSON5 -> validateJson5(text)
            StructuredTextFormat.YAML -> validateYaml(text)
            StructuredTextFormat.XML -> validateXml(text)
        }

    /**
     * Pretty-prints strict JSON only after a fidelity gate. Duplicate object keys are valid syntax
     * but are not formatted until a caller explicitly resolves their ambiguous semantics.
     */
    fun formatJson(text: String): StructuredTextFormatResult {
        val inspection = StrictJsonParser(text).inspect()
        inspection.errorMessage?.let { error ->
            return StructuredTextFormatResult(
                text = text,
                changed = false,
                errorMessage = error
            )
        }
        if (inspection.hasDuplicateObjectKeys) {
            return StructuredTextFormatResult(
                text = text,
                changed = false,
                errorMessage = "Formatting blocked: duplicate JSON object keys must remain byte-visible."
            )
        }

        val formatted = try {
            JsonLexicalFormatter(text).format()
        } catch (_: JsonFormattingLimitExceededException) {
            return StructuredTextFormatResult(
                text = text,
                changed = false,
                errorMessage = "Formatting blocked: formatted JSON would exceed the 8 MiB safety limit."
            )
        }
        return StructuredTextFormatResult(
            text = formatted,
            changed = formatted != text
        )
    }

    /**
     * Formats valid JSON5 through a source-aware lossless AST. Comments, duplicate keys and raw
     * literals such as hexadecimal numbers remain represented instead of being collapsed through
     * JsonElement.
     */
    fun formatJson5(text: String): StructuredTextFormatResult {
        json5InputError(text)?.let { error ->
            return StructuredTextFormatResult(
                text = text,
                changed = false,
                errorMessage = error
            )
        }
        val syntax = Json5.scanSyntax(text)
        json5ResourceError(syntax)?.let { error ->
            return StructuredTextFormatResult(
                text = text,
                changed = false,
                errorMessage = error
            )
        }
        if (json5FormattedSizeUpperBound(syntax) > MAX_JSON_FORMATTED_CHARS.toLong()) {
            return StructuredTextFormatResult(
                text = text,
                changed = false,
                errorMessage = "Formatting blocked: JSON5 expansion could exceed the 8 MiB safety limit."
            )
        }

        val formatted = try {
            Json5.format(text)
        } catch (error: Json5ParseException) {
            return StructuredTextFormatResult(
                text = text,
                changed = false,
                errorMessage = error.message ?: "Invalid JSON5."
            )
        }
        return StructuredTextFormatResult(
            text = formatted,
            changed = formatted != text
        )
    }


    /**
     * Re-emits valid YAML through SnakeYAML's event pipeline. Parsing events rather than loading
     * values keeps anchors, aliases, tags, scalar styles and comments source-visible while
     * normalizing layout. The bounded writer prevents formatting expansion from allocating
     * unbounded output.
     */
    fun formatYaml(text: String): StructuredTextFormatResult {
        val validation = validateYaml(text)
        if (!validation.isValid) {
            return StructuredTextFormatResult(
                text = text,
                changed = false,
                errorMessage = validation.errorMessage ?: "Invalid YAML."
            )
        }

        return try {
            val loadSettings = LoadSettings(
                label = "Aistee document",
                codePointLimit = MAX_YAML_CODE_POINTS,
                parseComments = true
            )
            val parser = ParserImpl(loadSettings, StreamReader(loadSettings, text))
            val writer = BoundedYamlWriter(MAX_YAML_FORMATTED_CHARS)
            val emitter = Emitter(
                DumpSettings(
                    dumpComments = true,
                    bestLineBreak = "\n",
                    isSplitLines = false,
                    indent = 2
                ),
                writer
            )
            while (parser.hasNext()) {
                emitter.emit(parser.next())
            }
            val formatted = writer.toString()
            StructuredTextFormatResult(
                text = formatted,
                changed = formatted != text
            )
        } catch (_: YamlFormattingLimitExceededException) {
            StructuredTextFormatResult(
                text = text,
                changed = false,
                errorMessage = "Formatting blocked: formatted YAML would exceed the 8 MiB safety limit."
            )
        } catch (error: YamlEngineException) {
            StructuredTextFormatResult(
                text = text,
                changed = false,
                errorMessage = error.message ?: "Could not format YAML."
            )
        }
    }

    private fun validateJson(text: String): StructuredTextValidationResult {
        val inspection = StrictJsonParser(text).inspect()
        return StructuredTextValidationResult(
            format = StructuredTextFormat.JSON,
            errorMessage = inspection.errorMessage
        )
    }

    private fun validateJson5(text: String): StructuredTextValidationResult {
        json5InputError(text)?.let { error ->
            return StructuredTextValidationResult(
                format = StructuredTextFormat.JSON5,
                errorMessage = error
            )
        }
        val syntax = Json5.scanSyntax(text)
        json5ResourceError(syntax)?.let { error ->
            return StructuredTextValidationResult(
                format = StructuredTextFormat.JSON5,
                errorMessage = error
            )
        }
        val diagnostic = Json5.parseToDocument(text).diagnostics.firstOrNull()
        return StructuredTextValidationResult(
            format = StructuredTextFormat.JSON5,
            errorMessage = diagnostic?.let {
                "${it.message} at index ${it.range.start}."
            }
        )
    }

    private fun json5InputError(text: String): String? =
        if (text.length > MAX_JSON5_CHARS) {
            "JSON5 input exceeds the supported 3 Mi character limit."
        } else {
            null
        }

    /**
     * Uses JSON5's tolerant lexer before building an AST. That keeps pathological nesting from
     * reaching the parser/formatter and gives the formatter a conservative expansion bound.
     */
    private fun json5ResourceError(syntax: Json5SyntaxTokens): String? {
        var depth = 0
        for (index in syntax.indices) {
            when (syntax.kindAt(index)) {
                Json5SyntaxKind.LeftBrace,
                Json5SyntaxKind.LeftBracket -> {
                    depth++
                    if (depth > MAX_JSON5_NESTING) {
                        return "JSON5 nesting exceeds the supported limit of $MAX_JSON5_NESTING."
                    }
                }
                Json5SyntaxKind.RightBrace,
                Json5SyntaxKind.RightBracket -> if (depth > 0) depth--
                else -> Unit
            }
        }
        return null
    }

    /**
     * Default Json5 formatting preserves raw token spelling and inserts at most one line break plus
     * two indentation characters per nesting level between emitted tokens. Use that worst case to
     * reject potentially explosive pretty-printing before Json5.format allocates its output buffer.
     */
    private fun json5FormattedSizeUpperBound(syntax: Json5SyntaxTokens): Long {
        if (syntax.isEmpty()) return 0L
        var payloadChars = 0L
        var depth = 0
        var maxDepth = 0
        for (index in syntax.indices) {
            payloadChars += (syntax.endAt(index) - syntax.startAt(index)).toLong()
            when (syntax.kindAt(index)) {
                Json5SyntaxKind.LeftBrace,
                Json5SyntaxKind.LeftBracket -> {
                    depth++
                    if (depth > maxDepth) maxDepth = depth
                }
                Json5SyntaxKind.RightBrace,
                Json5SyntaxKind.RightBracket -> if (depth > 0) depth--
                else -> Unit
            }
        }
        val gaps = (syntax.size - 1).coerceAtLeast(0).toLong()
        val maxWhitespacePerGap = 2L + (2L * maxDepth)
        return payloadChars + gaps * maxWhitespacePerGap + 2L
    }

    /**
     * Parses YAML events only. This validates syntax without forcing documents into Kotaml's
     * narrower YamlNode model or constructing application objects. Anchors are tracked per document
     * so aliases still have to refer to a previously defined anchor. StreamReader enforces the
     * bounded code-point limit before imported input can consume unbounded parser memory.
     */
    private fun validateYaml(text: String): StructuredTextValidationResult {
        return try {
            val settings = LoadSettings(
                label = "Aistee document",
                codePointLimit = MAX_YAML_CODE_POINTS
            )
            val parser = ParserImpl(settings, StreamReader(settings, text))
            val anchors = mutableSetOf<Any>()
            while (parser.hasNext()) {
                val event = parser.next()
                when {
                    event.eventId == Event.ID.DocumentStart -> anchors.clear()
                    event is AliasEvent -> {
                        val anchor = event.anchor
                        if (anchor == null || anchor !in anchors) {
                            return StructuredTextValidationResult(
                                format = StructuredTextFormat.YAML,
                                errorMessage = "YAML alias references an undefined anchor."
                            )
                        }
                    }
                    event is NodeEvent -> event.anchor?.let(anchors::add)
                }
            }
            StructuredTextValidationResult(StructuredTextFormat.YAML)
        } catch (error: YamlEngineException) {
            StructuredTextValidationResult(
                format = StructuredTextFormat.YAML,
                errorMessage = error.message ?: "Invalid YAML."
            )
        }
    }

    /**
     * Validates XML well-formedness through the portable pull parser without building a document tree.
     * A lexical preflight rejects DTD/DOCTYPE declarations before the parser is constructed, so JVM
     * parser implementations never get an opportunity to resolve external subsets. Entity expansion
     * remains disabled during parsing. XML Schema or DTD validation is intentionally out of scope.
     */
    private fun validateXml(text: String): StructuredTextValidationResult {
        validateXmlInput(text)?.let { return it }
        XmlLexicalPreflight(text).inspect()?.let { return invalidXml(it) }

        return try {
            val reader = xmlStreaming.newReader(text, expandEntities = false)
            try {
                validateXmlReader(reader)
            } finally {
                reader.close()
            }
        } catch (error: XmlException) {
            invalidXml(error.message ?: "Invalid XML.")
        } catch (error: IllegalStateException) {
            invalidXml(error.message ?: "Invalid XML.")
        }
    }

    private fun validateXmlInput(text: String): StructuredTextValidationResult? = when {
        text.length > MAX_XML_CHARS ->
            invalidXml("XML input exceeds the supported 3 Mi character limit.")
        text.isBlank() -> invalidXml("XML input is empty.")
        !hasOnlyXml10Characters(text) ->
            invalidXml("XML contains a character outside the supported XML 1.0 repertoire.")
        else -> null
    }

    private fun validateXmlReader(reader: XmlReader): StructuredTextValidationResult {
        var depth = 0
        var rootElements = 0
        while (reader.hasNext()) {
            val event = reader.next()
            when (event) {
                EventType.START_ELEMENT -> {
                    validateXmlStartElement(reader)?.let { return it }
                    if (depth == 0) rootElements++
                    depth++
                    if (depth > MAX_XML_NESTING) {
                        return invalidXml(
                            "XML nesting exceeds the supported limit of $MAX_XML_NESTING."
                        )
                    }
                }
                EventType.END_ELEMENT -> depth--
                EventType.ENTITY_REF -> validateXmlEntity(reader)?.let { return it }
                EventType.DOCDECL -> return invalidXml(
                    "XML DOCTYPE/DTD declarations are not supported by portable validation."
                )
                else -> validateXmlTextEvent(reader, event)?.let { return it }
            }
        }

        return when {
            depth != 0 -> invalidXml("XML element nesting is unbalanced.")
            rootElements != 1 -> invalidXml("XML must contain exactly one root element.")
            else -> StructuredTextValidationResult(StructuredTextFormat.XML)
        }
    }

    private fun validateXmlStartElement(reader: XmlReader): StructuredTextValidationResult? {
        if (hasDuplicateXmlAttributes(reader)) {
            return invalidXml("XML element contains duplicate attributes.")
        }
        for (index in 0 until reader.attributeCount) {
            if (!hasOnlyXml10Characters(reader.getAttributeValue(index))) {
                return invalidXml(
                    "XML attribute contains a character outside the supported XML 1.0 repertoire."
                )
            }
        }
        return null
    }

    private fun validateXmlEntity(reader: XmlReader): StructuredTextValidationResult? {
        if (!reader.isKnownEntity) return invalidXml("XML references an undeclared entity.")
        return if (hasOnlyXml10Characters(reader.text)) {
            null
        } else {
            invalidXml("XML entity resolves to a character outside the supported XML 1.0 repertoire.")
        }
    }

    private fun validateXmlTextEvent(
        reader: XmlReader,
        event: EventType
    ): StructuredTextValidationResult? =
        if (event.isTextElement && !hasOnlyXml10Characters(reader.text)) {
            invalidXml("XML contains a character outside the supported XML 1.0 repertoire.")
        } else {
            null
        }

    private fun hasDuplicateXmlAttributes(reader: XmlReader): Boolean {
        val expandedNames = mutableSetOf<Pair<String, String>>()
        for (index in 0 until reader.attributeCount) {
            val expandedName = reader.getAttributeNamespace(index) to reader.getAttributeLocalName(index)
            if (!expandedNames.add(expandedName)) return true
        }
        return false
    }

    /**
     * Performs only security-sensitive checks that must happen before an XML parser is created.
     * Malformed markup that does not match these narrow checks is deliberately left to xmlutil.
     */
    private class XmlLexicalPreflight(private val source: String) {
        private var index = 0

        fun inspect(): String? {
            while (index < source.length) {
                val markupStart = source.indexOf('<', index)
                if (markupStart < 0) return null
                index = markupStart
                when {
                    source.startsWith("<!--", index) -> skipDelimited("-->")
                    source.startsWith("<![CDATA[", index) -> skipDelimited("]]>")
                    source.startsWith("<?", index) -> skipDelimited("?>")
                    source.startsWith("<!DOCTYPE", index) -> return DOCTYPE_ERROR
                    source.startsWith("</", index) || source.startsWith("<!", index) -> skipMarkup()
                    else -> inspectStartTag()?.let { return it }
                }
            }
            return null
        }

        private fun inspectStartTag(): String? {
            val tagEnd = findTagEnd(index + 1)
            if (tagEnd < 0) {
                index = source.length
                return null
            }

            var cursor = index + 1
            while (cursor < tagEnd && !source[cursor].isXmlWhitespace() && source[cursor] != '/') {
                cursor++
            }
            val rawAttributeNames = mutableSetOf<String>()

            while (cursor < tagEnd) {
                cursor = skipXmlWhitespace(cursor, tagEnd)
                if (cursor >= tagEnd || source[cursor] == '/') break

                val nameStart = cursor
                while (
                    cursor < tagEnd &&
                    !source[cursor].isXmlWhitespace() &&
                    source[cursor] != '=' &&
                    source[cursor] != '/'
                ) {
                    cursor++
                }
                if (nameStart == cursor) break
                val rawName = source.substring(nameStart, cursor)
                if (!rawAttributeNames.add(rawName)) return DUPLICATE_ATTRIBUTE_ERROR

                cursor = skipXmlWhitespace(cursor, tagEnd)
                if (cursor >= tagEnd || source[cursor] != '=') break
                cursor++
                cursor = skipXmlWhitespace(cursor, tagEnd)
                if (cursor >= tagEnd || source[cursor] !in XML_ATTRIBUTE_QUOTES) break
                val quote = source[cursor++]
                val closingQuote = source.indexOf(quote, cursor)
                if (closingQuote < 0 || closingQuote > tagEnd) break
                cursor = closingQuote + 1
            }

            index = tagEnd + 1
            return null
        }

        private fun findTagEnd(start: Int): Int {
            var cursor = start
            var quote: Char? = null
            while (cursor < source.length) {
                val char = source[cursor]
                when {
                    quote != null && char == quote -> quote = null
                    quote == null && char in XML_ATTRIBUTE_QUOTES -> quote = char
                    quote == null && char == '>' -> return cursor
                }
                cursor++
            }
            return -1
        }

        private fun skipDelimited(delimiter: String) {
            val end = source.indexOf(delimiter, index + 2)
            index = if (end < 0) source.length else end + delimiter.length
        }

        private fun skipMarkup() {
            val end = source.indexOf('>', index + 2)
            index = if (end < 0) source.length else end + 1
        }

        private fun skipXmlWhitespace(start: Int, end: Int): Int {
            var cursor = start
            while (cursor < end && source[cursor].isXmlWhitespace()) cursor++
            return cursor
        }

        private fun Char.isXmlWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'

        companion object {
            private const val DOCTYPE_ERROR =
                "XML DOCTYPE/DTD declarations are not supported by portable validation."
            private const val DUPLICATE_ATTRIBUTE_ERROR =
                "XML element contains duplicate attributes or namespace declarations."
            private val XML_ATTRIBUTE_QUOTES = setOf('\'', '"')
        }
    }

    private fun hasOnlyXml10Characters(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val first = value[index].code
            val codePoint = when {
                first in 0xD800..0xDBFF -> {
                    if (index + 1 >= value.length) return false
                    val second = value[index + 1].code
                    if (second !in 0xDC00..0xDFFF) return false
                    index++
                    0x10000 + ((first - 0xD800) shl 10) + (second - 0xDC00)
                }
                first in 0xDC00..0xDFFF -> return false
                else -> first
            }
            if (!isXml10CodePoint(codePoint)) return false
            index++
        }
        return true
    }

    private fun isXml10CodePoint(codePoint: Int): Boolean =
        codePoint == 0x9 ||
            codePoint == 0xA ||
            codePoint == 0xD ||
            codePoint in 0x20..0xD7FF ||
            codePoint in 0xE000..0xFFFD ||
            codePoint in 0x10000..0x10FFFF

    private fun invalidXml(message: String): StructuredTextValidationResult =
        StructuredTextValidationResult(
            format = StructuredTextFormat.XML,
            errorMessage = message
        )

    private data class StrictJsonInspection(
        val errorMessage: String? = null,
        val hasDuplicateObjectKeys: Boolean = false
    )

    /** RFC-style syntax gate that preserves lexical source instead of normalizing while parsing. */
    private class StrictJsonParser(private val source: String) {
        private var index = 0
        private var hasDuplicateObjectKeys = false

        fun inspect(): StrictJsonInspection {
            skipWhitespace()
            if (index == source.length) return failureAt("JSON input is empty")
            parseValue(depth = 0)?.let { error ->
                return StrictJsonInspection(errorMessage = error)
            }
            skipWhitespace()
            if (index != source.length) return failureAt("Unexpected trailing JSON content")
            return StrictJsonInspection(hasDuplicateObjectKeys = hasDuplicateObjectKeys)
        }

        private fun parseValue(depth: Int): String? {
            if (depth > MAX_JSON_NESTING) {
                return errorAt("JSON nesting exceeds the supported limit of $MAX_JSON_NESTING")
            }
            if (index >= source.length) return errorAt("Expected a JSON value")
            return when (source[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString().errorMessage
                't' -> parseLiteral("true")
                'f' -> parseLiteral("false")
                'n' -> parseLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> errorAt("Invalid JSON value")
            }
        }

        private fun parseObject(depth: Int): String? {
            index++
            skipWhitespace()
            if (consume('}')) return null
            val keys = mutableSetOf<String>()

            while (true) {
                if (index >= source.length || source[index] != '"') {
                    return errorAt("Expected a quoted JSON object key")
                }
                val key = parseString()
                key.errorMessage?.let { return it }
                if (!keys.add(requireNotNull(key.value))) hasDuplicateObjectKeys = true

                skipWhitespace()
                if (!consume(':')) return errorAt("Expected ':' after JSON object key")
                skipWhitespace()
                parseValue(depth + 1)?.let { return it }
                skipWhitespace()

                when {
                    consume('}') -> return null
                    consume(',') -> {
                        skipWhitespace()
                        if (index < source.length && source[index] == '}') {
                            return errorAt("Trailing commas are not valid JSON")
                        }
                    }
                    else -> return errorAt("Expected ',' or '}' in JSON object")
                }
            }
        }

        private fun parseArray(depth: Int): String? {
            index++
            skipWhitespace()
            if (consume(']')) return null

            while (true) {
                parseValue(depth + 1)?.let { return it }
                skipWhitespace()
                when {
                    consume(']') -> return null
                    consume(',') -> {
                        skipWhitespace()
                        if (index < source.length && source[index] == ']') {
                            return errorAt("Trailing commas are not valid JSON")
                        }
                    }
                    else -> return errorAt("Expected ',' or ']' in JSON array")
                }
            }
        }

        private fun parseLiteral(expected: String): String? {
            if (!source.regionMatches(index, expected, 0, expected.length)) {
                return errorAt("Invalid JSON literal")
            }
            index += expected.length
            return null
        }

        private fun parseNumber(): String? {
            consume('-')
            parseIntegerPart()?.let { return it }
            parseFractionPart()?.let { return it }
            return parseExponentPart()
        }

        private fun parseIntegerPart(): String? {
            if (index >= source.length) return errorAt("Incomplete JSON number")
            return when (source[index]) {
                '0' -> {
                    index++
                    if (index < source.length && source[index] in '0'..'9') {
                        errorAt("Leading zeroes are not valid JSON numbers")
                    } else {
                        null
                    }
                }
                in '1'..'9' -> {
                    consumeAsciiDigits()
                    null
                }
                else -> errorAt("Invalid JSON number")
            }
        }

        private fun parseFractionPart(): String? {
            if (!consume('.')) return null
            if (index >= source.length || source[index] !in '0'..'9') {
                return errorAt("JSON fraction requires at least one digit")
            }
            consumeAsciiDigits()
            return null
        }

        private fun parseExponentPart(): String? {
            if (index >= source.length || source[index] !in setOf('e', 'E')) return null
            index++
            if (index < source.length && source[index] in setOf('+', '-')) index++
            if (index >= source.length || source[index] !in '0'..'9') {
                return errorAt("JSON exponent requires at least one digit")
            }
            consumeAsciiDigits()
            return null
        }

        private fun consumeAsciiDigits() {
            while (index < source.length && source[index] in '0'..'9') index++
        }

        private fun parseString(): ParsedJsonString {
            index++
            val decoded = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                when {
                    char == '"' -> return ParsedJsonString(decoded.toString())
                    char == '\\' -> parseEscape(decoded)?.let { return stringFailure(it) }
                    char.code < 0x20 -> return stringFailure(
                        "Unescaped control character in JSON string"
                    )
                    else -> decoded.append(char)
                }
            }
            return stringFailure("Unterminated JSON string")
        }

        private fun parseEscape(decoded: StringBuilder): String? {
            if (index >= source.length) return "Incomplete JSON escape"
            return when (val escaped = source[index++]) {
                '"', '\\', '/' -> appendEscape(decoded, escaped)
                'b' -> appendEscape(decoded, '\b')
                'f' -> appendEscape(decoded, '\u000C')
                'n' -> appendEscape(decoded, '\n')
                'r' -> appendEscape(decoded, '\r')
                't' -> appendEscape(decoded, '\t')
                'u' -> parseUnicodeEscape(decoded)
                else -> "Invalid JSON escape"
            }
        }

        private fun appendEscape(decoded: StringBuilder, char: Char): String? {
            decoded.append(char)
            return null
        }

        private fun parseUnicodeEscape(decoded: StringBuilder): String? {
            if (index + 4 > source.length) return "Incomplete JSON Unicode escape"
            val digits = source.substring(index, index + 4)
            if (!digits.all(::isJsonHexDigit)) return "Invalid JSON Unicode escape"
            decoded.append(digits.toInt(16).toChar())
            index += 4
            return null
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in JSON_WHITESPACE) index++
        }

        private fun consume(expected: Char): Boolean {
            if (index >= source.length || source[index] != expected) return false
            index++
            return true
        }

        private fun errorAt(message: String): String = "$message at offset $index."

        private fun failureAt(message: String): StrictJsonInspection =
            StrictJsonInspection(errorMessage = errorAt(message))

        private fun stringFailure(message: String): ParsedJsonString =
            ParsedJsonString(errorMessage = errorAt(message))
    }

    /** Formats structural whitespace only, preserving string, number and literal lexemes verbatim. */
    private class JsonLexicalFormatter(private val source: String) {
        private val output = StringBuilder(minOf(source.length, MAX_JSON_FORMATTED_CHARS))
        private val nonEmptyScopes = mutableListOf<Boolean>()
        private var indent = 0
        private var inString = false
        private var escaped = false

        fun format(): String {
            source.forEachIndexed(::appendCharacter)
            return output.toString()
        }

        private fun appendCharacter(index: Int, char: Char) {
            if (inString) {
                appendStringCharacter(char)
                return
            }
            when (char) {
                '"' -> startString()
                in JSON_WHITESPACE -> Unit
                '{', '[' -> appendOpening(index, char)
                '}', ']' -> appendClosing(char)
                ',' -> appendComma()
                ':' -> appendChecked(": ")
                else -> appendChecked(char)
            }
        }

        private fun startString() {
            inString = true
            appendChecked('"')
        }

        private fun appendStringCharacter(char: Char) {
            appendChecked(char)
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
        }

        private fun appendOpening(index: Int, char: Char) {
            appendChecked(char)
            val closing = if (char == '{') '}' else ']'
            val nonEmpty = nextNonWhitespace(index + 1) != closing
            nonEmptyScopes.add(nonEmpty)
            if (!nonEmpty) return
            indent++
            appendChecked('\n')
            appendIndent()
        }

        private fun appendClosing(char: Char) {
            val nonEmpty = nonEmptyScopes.removeLast()
            if (nonEmpty) {
                indent--
                appendChecked('\n')
                appendIndent()
            }
            appendChecked(char)
        }

        private fun appendComma() {
            appendChecked(',')
            appendChecked('\n')
            appendIndent()
        }

        private fun appendIndent() {
            val spaces = indent * 2
            ensureOutputCapacity(spaces)
            repeat(spaces) { output.append(' ') }
        }

        private fun appendChecked(char: Char) {
            ensureOutputCapacity(1)
            output.append(char)
        }

        private fun appendChecked(text: String) {
            ensureOutputCapacity(text.length)
            output.append(text)
        }

        private fun ensureOutputCapacity(additionalChars: Int) {
            if (additionalChars > MAX_JSON_FORMATTED_CHARS - output.length) {
                throw JsonFormattingLimitExceededException()
            }
        }

        private fun nextNonWhitespace(start: Int): Char? {
            var cursor = start
            while (cursor < source.length && source[cursor] in JSON_WHITESPACE) cursor++
            return source.getOrNull(cursor)
        }
    }

    private data class ParsedJsonString(
        val value: String? = null,
        val errorMessage: String? = null
    )


    private class BoundedYamlWriter(
        private val maxChars: Int
    ) : StreamDataWriter {
        private val output = StringBuilder(minOf(maxChars, 64 * 1024))

        override fun write(str: String) {
            append(str, 0, str.length)
        }

        override fun write(str: String, off: Int, len: Int) {
            append(str, off, len)
        }

        private fun append(str: String, off: Int, len: Int) {
            require(off >= 0 && len >= 0 && off <= str.length - len) {
                "Invalid YAML writer range."
            }
            if (len > maxChars - output.length) {
                throw YamlFormattingLimitExceededException()
            }
            output.append(str, off, off + len)
        }

        override fun toString(): String = output.toString()
    }

    private class YamlFormattingLimitExceededException : RuntimeException()

    private class JsonFormattingLimitExceededException : RuntimeException()

    private val JSON_WHITESPACE = setOf(' ', '\t', '\r', '\n')

    private fun isJsonHexDigit(char: Char): Boolean =
        char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
}

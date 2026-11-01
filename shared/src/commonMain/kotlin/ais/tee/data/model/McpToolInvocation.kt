package ais.tee.data.model

import kotlinx.serialization.json.*

private const val STRUCTURED_CONTENT_FIELD = "structuredContent"
const val MAX_MCP_ARGUMENT_CHARS = 8_192
private val jsonLiteral = Regex("^(true|false|null|-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?)$")

/** Existing catalog and capability contract; manual, per-call consent only. */
fun McpToolCatalog.callDecision(
    nativeName: String,
    argumentsJson: String,
    invocationMode: BenchToolInvocationMode,
    approved: Boolean,
): CapabilityDecision {
    val definition = loadDefinition(nativeName) ?: return CapabilityDecision.DENY
    if (parseMcpArguments(argumentsJson) == null || hasParameterHeaders(definition.inputSchema)) {
        return CapabilityDecision.DENY
    }
    if (invocationMode != BenchToolInvocationMode.EXPLICIT_USER_ACTION) {
        return CapabilityDecision.REQUIRES_USER_INTERACTION
    }
    return if (approved) CapabilityDecision.ALLOW else CapabilityDecision.ASK
}

/** Bounded strict JSON object. Never silently truncate an argument or accept bare literals. */
fun parseMcpArguments(raw: String): JsonObject? {
    if (raw.length > MAX_MCP_ARGUMENT_CHARS || !hasBoundedMcpArgumentDepth(raw)) return null
    val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    return parsed.takeIf { validJsonLiterals(it) }
}

private fun validJsonLiterals(value: JsonElement): Boolean = when (value) {
    is JsonObject -> value.values.all(::validJsonLiterals)
    is JsonArray -> value.all(::validJsonLiterals)
    is JsonPrimitive -> value.isString || jsonLiteral.matches(value.content)
}

// Parameter-header schemas blocked here; JSON examples/defaults are data, not schema nodes.
private fun hasParameterHeaders(value: JsonElement): Boolean {
    val schema = value as? JsonObject ?: return false
    if ("x-mcp-header" in schema) return true
    return schema.any { (key, child) ->
        when (key) {
            "properties", "patternProperties", "\$defs", "definitions", "dependentSchemas", "dependencies" ->
                (child as? JsonObject)?.values?.any(::hasParameterHeaders) == true
            "prefixItems", "allOf", "anyOf", "oneOf" ->
                (child as? JsonArray)?.any(::hasParameterHeaders) == true
            "items", "additionalItems", "contentSchema", "additionalProperties", "unevaluatedProperties", "unevaluatedItems", "contains",
            "not", "if", "then", "else", "propertyNames" -> hasParameterHeaders(child)
            else -> false
        }
    }
}

private fun hasBoundedMcpArgumentDepth(raw: String): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in raw) {
        if (quoted) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> quoted = false
            }
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > 24) return false
            '}', ']' -> if (--depth < 0) return false
        }
    }
    return !quoted && depth == 0
}

/** Plain, bounded output only. No resource fetching, HTML, model instructions or opaque state. */
fun parseMcpToolResult(call: NativeToolCall, result: JsonObject): NativeToolResult? = runCatching {
    require((result["resultType"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "complete")
    val content = if ("content" in result) result["content"] as? JsonArray ?: error("Invalid tool content")
        else if (STRUCTURED_CONTENT_FIELD in result) JsonArray(emptyList()) else error("Missing tool content")
    require(content.size <= 128)
    val error = result["isError"]?.let {
        (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull
            ?: error("Invalid tool status")
    } ?: false
    val text = buildString {
        content.forEach { item ->
            val block = item as? JsonObject ?: error("Invalid tool content")
            if ((block["type"] as? JsonPrimitive)?.content == "text") {
                val value = (block["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: error("Invalid tool text")
                append(value.take(MAX_NATIVE_TOOL_RESULT_CHARS - length))
            } else append("[Non-text content omitted]".take(MAX_NATIVE_TOOL_RESULT_CHARS - length))
            if (length < MAX_NATIVE_TOOL_RESULT_CHARS) append('\n')
        }
        result[STRUCTURED_CONTENT_FIELD]?.let {
            append(it.toString().take(MAX_NATIVE_TOOL_RESULT_CHARS - length))
        }
    }.ifBlank { "[Empty tool result]" }
    NativeToolResult(call.callId, call.name, text, error)
}.getOrNull()

package ais.tee.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val headerSuffix = Regex("^[!#$%&'*+.^_\u0060|~0-9A-Za-z-]{1,128}$")
private const val MAX_HEADER_BINDINGS = 32
private const val MAX_HEADER_BYTES = 16_384
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

internal data class McpParameterHeader(val name: String, val path: List<String>, val type: String)

/** Only statically reachable properties; never interpret examples or defaults as schemas. */
internal fun parseMcpParameterHeaders(schema: JsonObject): List<McpParameterHeader>? = runCatching {
    // Keep existing validation/limits for tools without parameter-header annotations.
    if (!schema.toString().contains("\"x-mcp-header\"")) return@runCatching emptyList()
    val result = mutableListOf<McpParameterHeader>()
    val names = mutableSetOf<String>()
    var visited = 0
    fun walk(node: JsonElement, path: List<String>?, depth: Int) {
        require(depth <= 16 && ++visited <= 1024)
        val obj = node as? JsonObject ?: return
        if ("x-mcp-header" in obj) {
            require(path != null && path.isNotEmpty() && "\$ref" !in obj)
            val suffix = (obj["x-mcp-header"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("Invalid parameter header")
            require(headerSuffix.matches(suffix) && names.add(suffix.lowercase()))
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(type in setOf("string", "integer", "boolean"))
            require(result.size < MAX_HEADER_BINDINGS)
            result += McpParameterHeader("Mcp-Param-$suffix", requireNotNull(path), requireNotNull(type))
        }
        obj.forEach { (key, child) ->
            when (key) {
                "properties" -> (child as? JsonObject)?.forEach { (name, value) ->
                    walk(value, if ("\$ref" in obj) null else path?.plus(name), depth + 1)
                }
                "patternProperties", "\$defs", "definitions", "dependentSchemas", "dependencies" ->
                    (child as? JsonObject)?.values?.forEach { walk(it, null, depth + 1) }
                "prefixItems", "allOf", "anyOf", "oneOf" ->
                    (child as? JsonArray)?.forEach { walk(it, null, depth + 1) }
                "items", "additionalItems" -> {
                    if (child is JsonArray) child.forEach { walk(it, null, depth + 1) }
                    else walk(child, null, depth + 1)
                }
                "contentSchema", "additionalProperties", "unevaluatedProperties", "unevaluatedItems",
                "contains", "not", "if", "then", "else", "propertyNames" -> walk(child, null, depth + 1)
            }
        }
    }
    walk(schema, emptyList(), 0)
    result.toList()
}.getOrNull()

/** Mirrors exact reviewed values. Missing/null paths omitted; wrong leaf types fail closed. */
internal fun extractMcpParameterHeaders(
    bindings: List<McpParameterHeader>,
    arguments: JsonObject,
): Map<String, String>? = runCatching {
    val result = linkedMapOf<String, String>()
    var bytes = 0
    for (binding in bindings) {
        var current: JsonElement = arguments
        var missing = false
        for (key in binding.path) {
            val next = (current as? JsonObject)?.get(key)
            if (next == null || next == JsonNull) {
                missing = true
                break
            }
            current = next
        }
        if (missing) continue
        val value = current as? JsonPrimitive ?: error("Invalid header value")
        val text = when (binding.type) {
            "string" -> value.content.takeIf { value.isString }
            "boolean" -> value.content.takeIf { !value.isString && it in setOf("true", "false") }
            "integer" -> if (!value.isString) canonicalSafeInteger(value.content) else null
            else -> null
        } ?: error("Invalid header type")
        val utf8 = text.encodeToByteArray(throwOnInvalidSequence = true)
        // Upper bound including Base64 expansion, sentinel, name and separator.
        bytes += binding.name.length + 16 + (utf8.size + 2) / 3 * 4
        require(bytes <= MAX_HEADER_BYTES)
        result[binding.name] = text
    }
    result.toMap()
}.getOrNull()

private val jsonNumber = Regex("(-?)(0|[1-9][0-9]*)(?:\\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?")

/** Exact decimal normalization; no floating-point rounding at the safe-integer boundary. */
private fun canonicalSafeInteger(raw: String): String? {
    val parts = jsonNumber.matchEntire(raw)?.groupValues ?: return null
    val fraction = parts[3]
    val digits = (parts[2] + fraction).trimStart('0')
    if (digits.isEmpty()) return "0"
    val exponent = if (parts[4].isEmpty()) 0 else parts[4].toIntOrNull() ?: return null
    val shift = exponent.toLong() - fraction.length
    val normalized = if (shift >= 0) {
        if (digits.length + shift > 16) return null
        digits + "0".repeat(shift.toInt())
    } else {
        val trim = -shift
        if (trim >= digits.length || digits.takeLast(trim.toInt()).any { it != '0' }) return null
        digits.dropLast(trim.toInt())
    }
    val number = (parts[1] + normalized).toLongOrNull() ?: return null
    return number.takeIf { it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER }?.toString()
}

package ais.tee.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val MAX_MCP_LIST_RESPONSE_CHARS = 256_000
private const val MAX_MCP_TOOLS = 128
private const val MAX_MCP_TOOL_NAME_CHARS = 256
private const val MAX_MCP_DESCRIPTION_CHARS = 1_024
private const val MAX_MCP_SCHEMA_CHARS = 32_000
private const val MAX_MCP_CURSOR_CHARS = 2_048
private val MCP_SERVER_ID = Regex("^[A-Za-z0-9_-]{1,20}$")
private val MCP_DISCOVERY_ID = Regex("^[a-f0-9]{32}$")

/** Search metadata only. Server-authored text is untrusted and must not be used as instructions. */
data class McpToolSummary(
    val nativeName: String,
    val remoteName: String,
    val title: String,
    val description: String,
) {
    override fun toString(): String =
        "McpToolSummary(nativeName=$nativeName, remoteName=<redacted>, metadata=<redacted>)"
}

/** Session-scoped binding. Never cache or replay across a new discovery session. */
data class McpToolTarget(val serverId: String, val discoveryId: String, val remoteName: String) {
    override fun toString(): String = "McpToolTarget(serverId=$serverId, target=<redacted>)"
}

/**
 * Bounded adapter for MCP tools/list responses. This is not a transport, permission grant,
 * executable tool registry or persisted server configuration. It only maps a discovered catalog
 * onto the existing NativeToolDefinition contract when a caller explicitly selects a tool.
 *
 * Aliases are scoped to this catalog snapshot, not stable IDs. Execution must resolve the target
 * again against the same live session and pass the maintained capability/consent boundary.
 */
class McpToolCatalog private constructor(
    val serverId: String,
    private val discoveryId: String,
    private val entries: List<Entry>,
) {
    private data class Entry(
        val summary: McpToolSummary,
        val inputSchema: JsonObject,
    )

    val count: Int get() = entries.size

    /** Does not expose input schemas to the model until loadDefinition is requested. */
    fun browse(query: String = "", limit: Int = 20): List<McpToolSummary> {
        require(limit in 1..MAX_MCP_TOOLS)
        val term = query.trim().lowercase()
        return entries.asSequence().map { it.summary }.filter { summary ->
            term.isEmpty() || listOf(summary.remoteName, summary.title, summary.description)
                .any { it.contains(term, ignoreCase = true) }
        }.take(limit).toList()
    }

    fun loadDefinition(nativeName: String): NativeToolDefinition? {
        val entry = entries.firstOrNull { it.summary.nativeName == nativeName } ?: return null
        return NativeToolDefinition(
            name = nativeName,
            description = entry.summary.description.ifBlank { entry.summary.title },
            inputSchema = entry.inputSchema,
        )
    }

    fun resolveTarget(nativeName: String): McpToolTarget? =
        entries.firstOrNull { it.summary.nativeName == nativeName }?.let {
            McpToolTarget(serverId, discoveryId, it.summary.remoteName)
        }

    /** Check at dispatch time against the current authenticated catalog, never a stale one. */
    fun isCurrent(target: McpToolTarget): Boolean =
        target.serverId == serverId && target.discoveryId == discoveryId &&
            entries.any { it.summary.remoteName == target.remoteName }

    override fun toString(): String = "McpToolCatalog(serverId=$serverId, count=$count)"

    companion object {
        /** A partial paginated catalog is never exposed as complete. */
        fun fromToolsListResponse(
            serverId: String,
            discoveryId: String,
            rawResponse: String,
        ): McpToolCatalog? = fromToolsListResponses(serverId, discoveryId, listOf(rawResponse))

        /**
         * Parse a complete, ordered sequence of JSON-RPC tools/list pages. The transport must
         * request each nextCursor from the same authenticated session and verify request IDs.
         * Reject truncated catalogs, repeated tools, invalid schemas and oversized responses.
         */
        fun fromToolsListResponses(
            serverId: String,
            discoveryId: String,
            rawResponses: List<String>,
        ): McpToolCatalog? = runCatching {
                require(MCP_SERVER_ID.matches(serverId))
                // Transport must create a new cryptographically random 128-bit ID per discovery.
                require(MCP_DISCOVERY_ID.matches(discoveryId))
                require(rawResponses.isNotEmpty() && rawResponses.size <= 16)
                val responseIds = mutableSetOf<String>()
                val names = mutableSetOf<String>()
                val entries = mutableListOf<Entry>()
                rawResponses.forEachIndexed { pageIndex, raw ->
                    require(raw.length <= MAX_MCP_LIST_RESPONSE_CHARS)
                    val envelope = Json.parseToJsonElement(raw) as? JsonObject
                        ?: error("Invalid JSON-RPC envelope")
                    require(envelope["jsonrpc"].stringValue() == "2.0")
                    val requestId = envelope["id"] as? JsonPrimitive ?: error("Missing JSON-RPC id")
                    val rawId = requestId.content
                    require(if (requestId.isString) rawId.isNotBlank() && rawId.length <= 128
                        else rawId.toLongOrNull() != null)
                    require(responseIds.add((if (requestId.isString) "s:" else "n:") + rawId))
                    require(envelope["error"] == null)
                    val result = envelope["result"] as? JsonObject ?: error("Missing result")
                    val tools = result["tools"] as? JsonArray ?: error("Missing tools")
                    require(entries.size + tools.size <= MAX_MCP_TOOLS)
                    val nextValue = result["nextCursor"]
                    val nextCursor = nextValue?.stringValue()
                    require(nextValue == null ||
                        (nextCursor != null && nextCursor.isNotBlank() &&
                            nextCursor.length <= MAX_MCP_CURSOR_CHARS))
                    require((pageIndex < rawResponses.lastIndex) == (nextCursor != null))
                    tools.forEach { value ->
                        val tool = value as? JsonObject ?: error("Invalid tool")
                        val remoteName = tool["name"].stringValue()
                            ?.takeIf { it.isNotBlank() && it.length <= MAX_MCP_TOOL_NAME_CHARS }
                            ?: error("Missing tool name")
                        require(names.add(remoteName))
                        fun optionalLabel(key: String): String {
                            val field = tool[key]
                            val text = if (field == null) null
                                else field.stringValue() ?: error("Invalid $key")
                            require(text == null || text.length <= MAX_MCP_DESCRIPTION_CHARS)
                            return text?.takeIf { it.isNotBlank() } ?: remoteName
                        }
                        val description = optionalLabel("description")
                        val title = optionalLabel("title")
                        val schema = tool["inputSchema"] as? JsonObject ?: error("Missing input schema")
                        require(schema.toString().length <= MAX_MCP_SCHEMA_CHARS)
                        require(schema["type"].stringValue() == "object")
                        validateMcpSchema(schema)
                        entries += Entry(
                            summary = McpToolSummary(
                                nativeName = "mcp_" + serverId + "_" + discoveryId + "_" + entries.size,
                                remoteName = remoteName,
                                title = title,
                                description = description,
                            ),
                            inputSchema = schema,
                        )
                    }
                }
                McpToolCatalog(serverId, discoveryId, entries)
            }.getOrNull()
    }
}

private fun kotlinx.serialization.json.JsonElement?.stringValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull


/**
 * Validate common JSON Schema structural keywords recursively before exposing a tool.
 * Unknown extension keywords are annotations; provider-specific schema conversion remains
 * the provider adapter's responsibility. Never treat remote schema as permission to execute.
 */
private fun validateMcpSchema(schema: JsonObject) {
    var visited = 0
    val types = setOf("array", "boolean", "integer", "null", "number", "object", "string")
    fun walk(node: JsonElement, depth: Int) {
        require(depth <= 16 && ++visited <= 1024)
        if (node is JsonPrimitive) {
            require(!node.isString && node.booleanOrNull != null)
            return
        }
        val fields = node as? JsonObject ?: error("Invalid schema")
        fields.forEach { (key, value) ->
            when (key) {
                "type" -> {
                    val variants = if (value is JsonArray)
                        value.map { it.stringValue() ?: error("Invalid type") }
                    else listOf(value.stringValue() ?: error("Invalid type"))
                    require(variants.isNotEmpty() && variants.distinct().size == variants.size &&
                        variants.all { it in types })
                }
                "properties", "patternProperties", "\$defs", "definitions",
                "dependentSchemas" -> {
                    val values = value as? JsonObject ?: error("Invalid property schema")
                    values.values.forEach { walk(it, depth + 1) }
                }
                "items", "additionalProperties", "unevaluatedProperties", "unevaluatedItems",
                "contains", "not", "if", "then", "else", "propertyNames" ->
                    walk(value, depth + 1)
                "prefixItems", "allOf", "anyOf", "oneOf" -> {
                    val parts = value as? JsonArray ?: error("Invalid schema group")
                    if (key != "prefixItems") require(parts.isNotEmpty())
                    parts.forEach { walk(it, depth + 1) }
                }
                "required" -> {
                    val fields = value as? JsonArray ?: error("Invalid required fields")
                    val names = fields.map { it.stringValue() ?: error("Invalid field name") }
                    require(names.distinct().size == names.size)
                }
                "enum" -> require(value is JsonArray && value.isNotEmpty())
                "minItems", "maxItems", "minProperties", "maxProperties", "minLength",
                "maxLength", "minContains", "maxContains" -> {
                    val n = value as? JsonPrimitive ?: error("Invalid integer bound")
                    require(!n.isString && (n.content.toLongOrNull() ?: -1) >= 0)
                }
                "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf" -> {
                    val n = value as? JsonPrimitive ?: error("Invalid numeric bound")
                    val parsed = n.content.toDoubleOrNull() ?: error("Invalid numeric bound")
                    require(!n.isString && parsed.isFinite() && (key != "multipleOf" || parsed > 0))
                }
                "uniqueItems", "readOnly", "writeOnly", "deprecated" -> {
                    val b = value as? JsonPrimitive ?: error("Invalid boolean")
                    require(!b.isString && b.booleanOrNull != null)
                }
                "title", "description", "\$ref", "\$id", "\$schema", "format", "pattern" ->
                    require(value.stringValue() != null)
            }
        }
    }
    walk(schema, 0)
}

package ais.tee.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val MAX_MCP_LIST_RESPONSE_CHARS = 256_000
private const val MAX_MCP_TOOLS = 128
private const val MAX_MCP_TOOL_NAME_CHARS = 256
private const val MAX_MCP_DESCRIPTION_CHARS = 1_024
private const val MAX_MCP_SCHEMA_CHARS = 32_000
private const val MAX_MCP_CURSOR_CHARS = 2_048
private val MCP_SERVER_ID = Regex("^[A-Za-z0-9_-]{1,24}$")

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
data class McpToolTarget(val serverId: String, val remoteName: String) {
    override fun toString(): String = "McpToolTarget(serverId=$serverId, remoteName=<redacted>)"
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
            McpToolTarget(serverId, it.summary.remoteName)
        }

    override fun toString(): String = "McpToolCatalog(serverId=$serverId, count=$count)"

    companion object {
        /** A partial paginated catalog is never exposed as complete. */
        fun fromToolsListResponse(serverId: String, rawResponse: String): McpToolCatalog? =
            fromToolsListResponses(serverId, listOf(rawResponse))

        /**
         * Parse a complete, ordered sequence of JSON-RPC tools/list pages. The transport must
         * request each nextCursor from the same authenticated session and verify request IDs.
         * Reject truncated catalogs, repeated tools, invalid schemas and oversized responses.
         */
        fun fromToolsListResponses(serverId: String, rawResponses: List<String>): McpToolCatalog? =
            runCatching {
                require(MCP_SERVER_ID.matches(serverId))
                require(rawResponses.isNotEmpty() && rawResponses.size <= 16)
                val names = mutableSetOf<String>()
                val entries = mutableListOf<Entry>()
                rawResponses.forEachIndexed { pageIndex, raw ->
                    require(raw.length <= MAX_MCP_LIST_RESPONSE_CHARS)
                    val envelope = Json.parseToJsonElement(raw) as? JsonObject
                        ?: error("Invalid JSON-RPC envelope")
                    require(envelope["jsonrpc"].stringValue() == "2.0")
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
                        val description = tool["description"]?.stringValue() ?: remoteName
                        require(description.isNotBlank() && description.length <= MAX_MCP_DESCRIPTION_CHARS)
                        val title = tool["title"]?.stringValue() ?: remoteName
                        require(title.isNotBlank() && title.length <= MAX_MCP_DESCRIPTION_CHARS)
                        val schema = tool["inputSchema"] as? JsonObject ?: error("Missing input schema")
                        require(schema.toString().length <= MAX_MCP_SCHEMA_CHARS)
                        require(schema["type"].stringValue() == "object")
                        entries += Entry(
                            summary = McpToolSummary(
                                nativeName = "mcp_" + serverId + "_" + entries.size,
                                remoteName = remoteName,
                                title = title,
                                description = description,
                            ),
                            inputSchema = schema,
                        )
                    }
                }
                McpToolCatalog(serverId, entries)
            }.getOrNull()
    }
}

private fun kotlinx.serialization.json.JsonElement?.stringValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

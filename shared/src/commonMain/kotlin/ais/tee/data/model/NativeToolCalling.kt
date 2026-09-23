package ais.tee.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val NATIVE_TOOL_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
private const val MAX_NATIVE_TOOL_DESCRIPTION_CHARS = 1_024
const val MAX_NATIVE_TOOL_RESULT_CHARS = 40_000
const val MAX_NATIVE_TOOL_ROUNDS = 8
const val MAX_NATIVE_TOOL_CALLS_PER_ROUND = 8

/**
 * Provider-neutral client tool contract.
 *
 * Schemas are kept as JSON objects so provider adapters can serialize them without owning a second
 * tool model. Aistee still decides whether a concrete tool may execute; a model call never grants
 * permission by itself.
 */
data class NativeToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
) {
    init {
        require(NATIVE_TOOL_NAME_REGEX.matches(name)) {
            "Tool name must use 1-64 letters, digits, underscores or hyphens"
        }
        require(description.isNotBlank() && description.length <= MAX_NATIVE_TOOL_DESCRIPTION_CHARS) {
            "Tool description must be 1-$MAX_NATIVE_TOOL_DESCRIPTION_CHARS characters"
        }
        require(inputSchema["type"]?.jsonPrimitive?.content == "object") {
            "Tool input schema must describe a JSON object"
        }
    }

    override fun toString(): String =
        "NativeToolDefinition(name=$name, description=<redacted>, inputSchema=<redacted>)"
}

data class NativeToolCall(
    val callId: String,
    val name: String,
    val arguments: JsonObject,
) {
    init {
        require(callId.isNotBlank()) { "Tool call id must not be blank" }
        require(NATIVE_TOOL_NAME_REGEX.matches(name)) { "Invalid tool call name" }
    }

    override fun toString(): String =
        "NativeToolCall(callId=<redacted>, name=$name, arguments=<redacted>)"
}

data class NativeToolResult(
    val callId: String,
    val name: String,
    val output: String,
    val isError: Boolean = false,
) {
    init {
        require(callId.isNotBlank()) { "Tool result call id must not be blank" }
        require(NATIVE_TOOL_NAME_REGEX.matches(name)) { "Invalid tool result name" }
        require(output.length <= MAX_NATIVE_TOOL_RESULT_CHARS) {
            "Tool result exceeds local output bound"
        }
    }

    override fun toString(): String =
        "NativeToolResult(callId=<redacted>, name=$name, output=<redacted>, isError=$isError)"
}

fun validateNativeToolResult(call: NativeToolCall, result: NativeToolResult): NativeToolResult {
    require(result.callId == call.callId) { "Tool result call id does not match request" }
    require(result.name == call.name) { "Tool result name does not match request" }
    return result
}

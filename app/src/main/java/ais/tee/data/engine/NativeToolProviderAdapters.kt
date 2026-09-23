package ais.tee.data.engine

import ais.tee.data.model.MAX_NATIVE_TOOL_CALLS_PER_ROUND
import ais.tee.data.model.NativeToolCall
import ais.tee.data.model.NativeToolDefinition
import ais.tee.data.model.NativeToolResult
import ais.tee.data.model.validateNativeToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val TOOL_TYPE = "function"
private const val TOOL_CALL_TYPE = "function_call"
private const val TOOL_CALL_OUTPUT_TYPE = "function_call_output"
private const val TOOL_USE_TYPE = "tool_use"
private const val TOOL_RESULT_TYPE = "tool_result"
private const val FUNCTION_CALL_KEY = "functionCall"
private const val FUNCTION_RESPONSE_KEY = "functionResponse"

internal data class ParsedProviderToolCall(
    val call: NativeToolCall,
    val providerCallId: String? = call.callId,
)

internal suspend fun executeNativeToolBatch(
    parsedCalls: List<ParsedProviderToolCall>,
    definitions: List<NativeToolDefinition>,
    executor: suspend (NativeToolCall) -> NativeToolResult,
): List<NativeToolResult> {
    require(parsedCalls.size <= MAX_NATIVE_TOOL_CALLS_PER_ROUND) {
        "Provider requested too many tool calls in one round"
    }
    require(parsedCalls.map { it.call.callId }.distinct().size == parsedCalls.size) {
        "Provider returned duplicate tool call ids"
    }
    val allowedNames = definitions.mapTo(hashSetOf()) { it.name }
    return parsedCalls.map { parsed ->
        val call = parsed.call
        if (call.name !in allowedNames) {
            NativeToolResult(
                callId = call.callId,
                name = call.name,
                output = "Tool is not available.",
                isError = true,
            )
        } else {
            try {
                validateNativeToolResult(call, executor(call))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NativeToolResult(
                    callId = call.callId,
                    name = call.name,
                    output = "Tool execution failed.",
                    isError = true,
                )
            }
        }
    }
}

internal fun buildOpenAiToolDefinitions(tools: List<NativeToolDefinition>): JsonArray =
    buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("type", TOOL_TYPE)
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.inputSchema)
                put("strict", false)
            })
        }
    }

internal fun parseOpenAiToolCalls(
    response: JsonObject,
    json: Json = Json,
): List<ParsedProviderToolCall> =
    (response["output"] as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        if (item["type"]?.jsonPrimitive?.contentOrNull != TOOL_CALL_TYPE) return@mapNotNull null
        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: error("OpenAI function call is missing call_id")
        val name = item["name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: error("OpenAI function call is missing name")
        val rawArguments = item["arguments"]?.jsonPrimitive?.contentOrNull
            ?: error("OpenAI function call is missing arguments")
        val arguments = json.parseToJsonElement(rawArguments) as? JsonObject
            ?: error("OpenAI function arguments must be an object")
        ParsedProviderToolCall(NativeToolCall(callId, name, arguments))
    }

internal fun buildOpenAiToolResultItems(results: List<NativeToolResult>): JsonArray =
    buildJsonArray {
        results.forEach { result ->
            add(buildJsonObject {
                put("type", TOOL_CALL_OUTPUT_TYPE)
                put("call_id", result.callId)
                put(
                    "output",
                    if (result.isError) {
                        buildJsonObject {
                            put("error", true)
                            put("message", result.output)
                        }.toString()
                    } else {
                        result.output
                    }
                )
            })
        }
    }

internal fun buildClaudeToolDefinitions(tools: List<NativeToolDefinition>): JsonArray =
    buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", tool.inputSchema)
            })
        }
    }

internal fun parseClaudeToolCalls(response: JsonObject): List<ParsedProviderToolCall> =
    (response["content"] as? JsonArray).orEmpty().mapNotNull { element ->
        val block = element as? JsonObject ?: return@mapNotNull null
        if (block["type"]?.jsonPrimitive?.contentOrNull != TOOL_USE_TYPE) return@mapNotNull null
        val callId = block["id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: error("Claude tool_use block is missing id")
        val name = block["name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: error("Claude tool_use block is missing name")
        val arguments = block["input"] as? JsonObject
            ?: error("Claude tool_use input must be an object")
        ParsedProviderToolCall(NativeToolCall(callId, name, arguments))
    }

internal fun buildClaudeToolResultContent(results: List<NativeToolResult>): JsonArray =
    buildJsonArray {
        results.forEach { result ->
            add(buildJsonObject {
                put("type", TOOL_RESULT_TYPE)
                put("tool_use_id", result.callId)
                put("content", result.output)
                if (result.isError) put("is_error", true)
            })
        }
    }

internal fun buildGeminiToolDefinitions(tools: List<NativeToolDefinition>): JsonArray =
    buildJsonArray {
        add(buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.inputSchema)
                    })
                }
            })
        })
    }

internal fun parseGeminiToolCalls(
    modelContent: JsonObject,
    round: Int,
): List<ParsedProviderToolCall> =
    (modelContent["parts"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
        val part = element as? JsonObject ?: return@mapIndexedNotNull null
        val functionCall = part[FUNCTION_CALL_KEY] as? JsonObject ?: return@mapIndexedNotNull null
        val providerId = functionCall["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val name = functionCall["name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: error("Gemini functionCall is missing name")
        val arguments = functionCall["args"] as? JsonObject ?: JsonObject(emptyMap())
        val localId = providerId ?: "gemini-$round-$index-$name"
        ParsedProviderToolCall(
            call = NativeToolCall(localId, name, arguments),
            providerCallId = providerId,
        )
    }

internal fun buildGeminiToolResultContent(
    calls: List<ParsedProviderToolCall>,
    results: List<NativeToolResult>,
    json: Json = Json,
): JsonObject {
    require(calls.size == results.size) { "Gemini tool result count does not match calls" }
    return buildJsonObject {
        put("role", "user")
        put("parts", buildJsonArray {
            calls.zip(results).forEach { (parsed, result) ->
                add(buildJsonObject {
                    putJsonObject(FUNCTION_RESPONSE_KEY) {
                        parsed.providerCallId?.let { put("id", it) }
                        put("name", result.name)
                        putJsonObject("response") {
                            val parsedOutput = runCatching { json.parseToJsonElement(result.output) }.getOrNull()
                            put("output", parsedOutput ?: JsonPrimitive(result.output))
                            if (result.isError) put("isError", true)
                        }
                    }
                })
            }
        })
    }
}

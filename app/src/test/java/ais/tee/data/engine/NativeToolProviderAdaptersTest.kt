package ais.tee.data.engine

import ais.tee.data.model.NativeToolCall
import ais.tee.data.model.NativeToolDefinition
import ais.tee.data.model.NativeToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolProviderAdaptersTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val definition = NativeToolDefinition(
        name = "inspect_text",
        description = "Inspect text locally.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("text", buildJsonObject { put("type", "string") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add("text") })
            put("additionalProperties", false)
        },
    )

    @Test
    fun openAiAdapterUsesResponsesFunctionItems() {
        val tools = buildOpenAiToolDefinitions(listOf(definition))
        val tool = tools.single().jsonObject
        assertEquals("function", tool["type"]?.jsonPrimitive?.content)
        assertEquals("inspect_text", tool["name"]?.jsonPrimitive?.content)
        assertFalse(tool["strict"]?.jsonPrimitive?.content?.toBooleanStrict() ?: true)

        val response = json.parseToJsonElement(
            """{"output":[{"type":"function_call","call_id":"call_1","name":"inspect_text","arguments":"{\"text\":\"hello\"}"}]}"""
        ).jsonObject
        val calls = parseOpenAiToolCalls(response, json)
        assertEquals("call_1", calls.single().call.callId)
        assertEquals("hello", calls.single().call.arguments["text"]?.jsonPrimitive?.content)

        val result = buildOpenAiToolResultItems(
            listOf(NativeToolResult("call_1", "inspect_text", """{"ok":true}"""))
        ).single().jsonObject
        assertEquals("function_call_output", result["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", result["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun claudeAdapterRoundTripsToolUseAndErrors() {
        val response = json.parseToJsonElement(
            """{"content":[{"type":"tool_use","id":"toolu_1","name":"inspect_text","input":{"text":"hello"}}]}"""
        ).jsonObject
        val call = parseClaudeToolCalls(response).single().call
        assertEquals("toolu_1", call.callId)
        assertEquals("hello", call.arguments["text"]?.jsonPrimitive?.content)

        val result = buildClaudeToolResultContent(
            listOf(NativeToolResult(call.callId, call.name, "blocked", isError = true))
        ).single().jsonObject
        assertEquals("tool_result", result["type"]?.jsonPrimitive?.content)
        assertTrue(result["is_error"]?.jsonPrimitive?.content?.toBooleanStrict() == true)
    }

    @Test
    fun geminiAdapterKeepsOptionalProviderCallId() {
        val content = json.parseToJsonElement(
            """{"role":"model","parts":[{"functionCall":{"id":"fc_1","name":"inspect_text","args":{"text":"hello"}}}]}"""
        ).jsonObject
        val parsed = parseGeminiToolCalls(content, round = 0)
        assertEquals("fc_1", parsed.single().providerCallId)
        assertEquals("hello", parsed.single().call.arguments["text"]?.jsonPrimitive?.content)

        val responseContent = buildGeminiToolResultContent(
            parsed,
            listOf(NativeToolResult("fc_1", "inspect_text", """{"safe":true}""")),
            json,
        )
        val functionResponse = responseContent["parts"]!!.jsonArray.single().jsonObject["functionResponse"]!!.jsonObject
        assertEquals("fc_1", functionResponse["id"]?.jsonPrimitive?.content)
        assertEquals("inspect_text", functionResponse["name"]?.jsonPrimitive?.content)
        assertTrue(functionResponse["response"]!!.jsonObject["output"]!!.jsonObject["safe"]?.jsonPrimitive?.content?.toBooleanStrict() == true)
    }

    @Test
    fun executorFailsClosedForUnknownToolsWithoutInvokingImplementation() = runBlocking {
        var invoked = false
        val result = executeNativeToolBatch(
            parsedCalls = listOf(
                ParsedProviderToolCall(
                    NativeToolCall("call_1", "not_offered", buildJsonObject {})
                )
            ),
            definitions = listOf(definition),
            executor = {
                invoked = true
                NativeToolResult(it.callId, it.name, "should not run")
            },
        ).single()

        assertFalse(invoked)
        assertTrue(result.isError)
        assertEquals("not_offered", result.name)
    }
}

package ais.tee.data.model

import kotlinx.serialization.json.*
import kotlin.test.*

class McpToolInvocationTest {
    private val catalog = requireNotNull(McpToolCatalog.fromToolsListResponse("demo", "1".repeat(32),
        """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"sample.echo","inputSchema":{"type":"object"}}]}}"""))
    private val name = catalog.browse().single().nativeName

    @Test fun everyCallNeedsExplicitUserConsentAndModelsCannotBorrowTheManualRoute() {
        assertEquals(CapabilityDecision.ASK, decision(approved = false))
        assertEquals(CapabilityDecision.ALLOW, decision(approved = true))
        assertEquals(CapabilityDecision.REQUIRES_USER_INTERACTION,
            catalog.callDecision(name, EMPTY_ARGUMENTS, BenchToolInvocationMode.MODEL_TOOL_CALL, approved = true))
        assertEquals(CapabilityDecision.DENY,
            catalog.callDecision("missing", EMPTY_ARGUMENTS, BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved = true))
        assertEquals(CapabilityDecision.DENY,
            catalog.callDecision(name, "[]", BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved = true))
    }

    private fun decision(approved: Boolean) = catalog.callDecision(name, EMPTY_ARGUMENTS,
        BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved)

    @Test fun strictArgumentsRejectBareTokensOversizeAndDeepNestingWithoutTruncation() {
        for (raw in listOf("[]", "{\"value\":bare}", "{\"value\":NaN}", "{\"value\":01}",
            " ".repeat(MAX_MCP_ARGUMENT_CHARS) + EMPTY_ARGUMENTS, "{\"x\":" + "[".repeat(25) + "0" + "]".repeat(25) + "}")) {
            assertNull(parseMcpArguments(raw))
            assertNull(catalog.exportToolCallDraft(name, raw))
        }
        assertNotNull(parseMcpArguments("""{"string":"value","number":-12.5e2,"null":null,"bool":false,"array":[1,"x"]}"""))
    }

    private fun annotatedCatalog(schema: String): McpToolCatalog = requireNotNull(
        McpToolCatalog.fromToolsListResponse("demo", "2".repeat(32),
            """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"headers","inputSchema":$schema},{"name":"ordinary","inputSchema":{"type":"object"}}]}}""")
    )

    @Test fun mappedHeadersRequireConsentAndUseExactNestedPaths() {
        val mapped = annotatedCatalog("""{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"},"nested":{"type":"object","properties":{"count":{"type":"integer","x-mcp-header":"Count"},"flag":{"type":"boolean","x-mcp-header":"Flag"}}}}}""")
        val tool = mapped.browse().first().nativeName
        val raw = """{"region":"世界","nested":{"count":4.2e1,"flag":false},"count":123}"""
        val args = requireNotNull(parseMcpArguments(raw))
        assertEquals(mapOf("Mcp-Param-Region" to "世界", "Mcp-Param-Count" to "42", "Mcp-Param-Flag" to "false"),
            mapped.parameterHeaders(tool, args))
        assertEquals(emptyMap(), mapped.parameterHeaders(tool, JsonObject(emptyMap())))
        assertEquals(CapabilityDecision.ASK, mapped.callDecision(tool, raw, BenchToolInvocationMode.EXPLICIT_USER_ACTION, false))
        assertEquals(CapabilityDecision.ALLOW, mapped.callDecision(tool, raw, BenchToolInvocationMode.EXPLICIT_USER_ACTION, true))
        assertEquals(CapabilityDecision.REQUIRES_USER_INTERACTION,
            mapped.callDecision(tool, raw, BenchToolInvocationMode.MODEL_TOOL_CALL, true))
        for (bad in listOf("""{"region":true}""", """{"nested":{"count":9007199254740992}}""",
            """{"nested":{"count":9007199254740990.9}}""", """{"nested":{"count":1e-1}}""",
            """{"nested":{"flag":"false"}}""", """{"nested":null}""")) {
            assertEquals(CapabilityDecision.DENY, mapped.callDecision(tool, bad, BenchToolInvocationMode.EXPLICIT_USER_ACTION, true))
        }
    }

    @Test fun invalidHeaderDefinitionsAreExcludedWithoutDiscardingOrdinaryTools() {
        val annotation = """{"type":"string","x-mcp-header":"Region"}"""
        for (schema in listOf(
            """{"type":"object","x-mcp-header":"Root"}""",
            """{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"bad header"}}}""",
            """{"type":"object","properties":{"a":{"type":"number","x-mcp-header":"Number"}}}""",
            """{"type":"object","properties":{"a":$annotation,"b":{"type":"string","x-mcp-header":"region"}}}""",
            """{"type":"object","properties":{"a":{"type":"array","items":$annotation}}}""",
            """{"type":"object","allOf":[{"properties":{"a":$annotation}}]}""",
            """{"type":"object","dependencies":{"value":{"properties":{"region":$annotation}}}}""",
            """{"type":"object","additionalItems":$annotation}""",
            """{"type":"object","contentSchema":$annotation}"""
        )) {
            assertEquals(listOf("ordinary"), annotatedCatalog(schema).browse().map { it.remoteName })
        }
        val ordinary = annotatedCatalog("""{"type":"object","properties":{"x-mcp-header":{"type":"string"}},"default":{"x-mcp-header":"literal"},"examples":[{"x-mcp-header":"data"}],"dependencies":{"value":["x-mcp-header"]}}""")
        assertEquals(2, ordinary.count)
        assertEquals(CapabilityDecision.ASK, ordinary.callDecision(ordinary.browse().first().nativeName,
            EMPTY_ARGUMENTS, BenchToolInvocationMode.EXPLICIT_USER_ACTION, false))
    }

    @Test fun resultsAreBoundedUntrustedTextWithoutResourceFetchingOrOpaqueState() {
        val call = NativeToolCall("call", name, JsonObject(emptyMap()))
        val result = buildJsonObject {
            put(RESULT_TYPE_FIELD, COMPLETE_RESULT)
            put("isError", true)
            putJsonArray("content") {
                add(buildJsonObject { put("type", "resource_link"); put("uri", "file:///private") })
                add(buildJsonObject { put("type", "text"); put("text", "x".repeat(60_000)) })
            }
            put("requestState", OPAQUE_STATE)
        }
        val parsed = requireNotNull(parseMcpToolResult(call, result))
        assertEquals(MAX_NATIVE_TOOL_RESULT_CHARS, parsed.output.length)
        assertTrue(parsed.isError)
        assertEquals(call.callId, parsed.callId)
        assertFalse(parsed.output.contains("file:///private"))
        assertFalse(parsed.output.contains(OPAQUE_STATE))
        assertFalse(parsed.toString().contains(OPAQUE_STATE))
        assertNull(parseMcpToolResult(call, buildJsonObject { put(RESULT_TYPE_FIELD, "input_required") }))
        assertNull(parseMcpToolResult(call, buildJsonObject { put(RESULT_TYPE_FIELD, "claimed") }))
        assertNull(parseMcpToolResult(call, buildJsonObject { put(RESULT_TYPE_FIELD, COMPLETE_RESULT); putJsonArray("content") {}; put("isError", "false") }))
    }

    @Test fun structuredResultsAcceptEveryJsonValueIncludingNull() {
        val call = NativeToolCall("call", name, JsonObject(emptyMap()))
        for (raw in listOf("{}", "[1,2]", "\"value\"", "42", "true", "null")) {
            val result = buildJsonObject {
                put(RESULT_TYPE_FIELD, COMPLETE_RESULT)
                put("structuredContent", Json.parseToJsonElement(raw))
            }
            assertEquals(raw, requireNotNull(parseMcpToolResult(call, result)).output)
        }
    }
}

private const val EMPTY_ARGUMENTS = "{}"
private const val RESULT_TYPE_FIELD = "resultType"

private const val COMPLETE_RESULT = "complete"
private const val OPAQUE_STATE = "opaque-secret"

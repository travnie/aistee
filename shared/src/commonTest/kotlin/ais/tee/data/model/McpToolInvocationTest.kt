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

    @Test fun parameterHeaderToolsStayBlockedUntilTheMappingRouteIsImplemented() {
        val annotated = requireNotNull(McpToolCatalog.fromToolsListResponse("demo", "2".repeat(32),
            """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"headers","inputSchema":{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"}}}}]}}"""))
        assertEquals(CapabilityDecision.DENY, annotated.callDecision(annotated.browse().single().nativeName,
            EMPTY_ARGUMENTS, BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved = true))
        val ordinary = requireNotNull(McpToolCatalog.fromToolsListResponse("demo", "3".repeat(32),
            """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"ordinary","inputSchema":{"type":"object","properties":{"x-mcp-header":{"type":"string"}},"default":{"x-mcp-header":"literal"},"examples":[{"x-mcp-header":"data"}]}}]}}"""))
        assertEquals(CapabilityDecision.ASK, ordinary.callDecision(ordinary.browse().single().nativeName,
            EMPTY_ARGUMENTS, BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved = false))
        val dependency = requireNotNull(McpToolCatalog.fromToolsListResponse("demo", "4".repeat(32),
            """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"dependency","inputSchema":{"type":"object","dependencies":{"value":{"properties":{"region":{"type":"string","x-mcp-header":"Region"}}}}}}]}}"""))
        assertEquals(CapabilityDecision.DENY, dependency.callDecision(dependency.browse().single().nativeName,
            EMPTY_ARGUMENTS, BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved = false))
        val propertyDependency = requireNotNull(McpToolCatalog.fromToolsListResponse("demo", "5".repeat(32),
            """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"propertyDependency","inputSchema":{"type":"object","dependencies":{"value":["x-mcp-header"]}}}]}}"""))
        assertEquals(CapabilityDecision.ASK, propertyDependency.callDecision(propertyDependency.browse().single().nativeName,
            EMPTY_ARGUMENTS, BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved = false))
    }

    @Test fun annotationsInOtherSchemaValuedKeywordsCannotEnableManualCalls() {
        for (keyword in listOf("additionalItems", "contentSchema")) {
            val schema = buildJsonObject {
                put("type", "object")
                put(keyword, buildJsonObject { put("type", "string"); put("x-mcp-header", "Value") })
            }
            val raw = """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"extension","inputSchema":$schema}]}}"""
            val other = requireNotNull(McpToolCatalog.fromToolsListResponse("demo", "6".repeat(32), raw))
            assertEquals(CapabilityDecision.DENY, other.callDecision(other.browse().single().nativeName,
                EMPTY_ARGUMENTS, BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved = false))
        }
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

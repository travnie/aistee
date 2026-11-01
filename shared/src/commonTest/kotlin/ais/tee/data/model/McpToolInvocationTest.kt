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
            catalog.callDecision(name, "{}", BenchToolInvocationMode.MODEL_TOOL_CALL, approved = true))
        assertEquals(CapabilityDecision.DENY,
            catalog.callDecision("missing", "{}", BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved = true))
        assertEquals(CapabilityDecision.DENY,
            catalog.callDecision(name, "[]", BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved = true))
    }

    private fun decision(approved: Boolean) = catalog.callDecision(name, "{}",
        BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved)

    @Test fun strictArgumentsRejectBareTokensOversizeAndDeepNestingWithoutTruncation() {
        for (raw in listOf("[]", "{\"value\":bare}", "{\"value\":NaN}", "{\"value\":01}",
            " ".repeat(MAX_MCP_ARGUMENT_CHARS) + "{}", "{\"x\":" + "[".repeat(25) + "0" + "]".repeat(25) + "}")) {
            assertNull(parseMcpArguments(raw))
            assertNull(catalog.exportToolCallDraft(name, raw))
        }
        assertNotNull(parseMcpArguments("""{"string":"value","number":-12.5e2,"null":null,"bool":false,"array":[1,"x"]}"""))
    }

    @Test fun parameterHeaderToolsStayBlockedUntilTheMappingRouteIsImplemented() {
        val annotated = requireNotNull(McpToolCatalog.fromToolsListResponse("demo", "2".repeat(32),
            """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"headers","inputSchema":{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"}}}}]}}"""))
        assertEquals(CapabilityDecision.DENY, annotated.callDecision(annotated.browse().single().nativeName,
            "{}", BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved = true))
    }

    @Test fun resultsAreBoundedUntrustedTextWithoutResourceFetchingOrOpaqueState() {
        val call = NativeToolCall("call", name, JsonObject(emptyMap()))
        val result = buildJsonObject {
            put("resultType", "complete")
            put("isError", true)
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", "x".repeat(60_000)) })
                add(buildJsonObject { put("type", "resource_link"); put("uri", "file:///private") })
            }
            put("requestState", "opaque-secret")
        }
        val parsed = requireNotNull(parseMcpToolResult(call, result))
        assertEquals(MAX_NATIVE_TOOL_RESULT_CHARS, parsed.output.length)
        assertTrue(parsed.isError)
        assertEquals(call.callId, parsed.callId)
        assertFalse(parsed.toString().contains("opaque-secret"))
        assertNull(parseMcpToolResult(call, buildJsonObject { put("resultType", "input_required") }))
        assertNull(parseMcpToolResult(call, buildJsonObject { put("resultType", "claimed") }))
        assertNull(parseMcpToolResult(call, buildJsonObject { put("resultType", "complete"); putJsonArray("content") {}; put("isError", "false") }))
    }
}

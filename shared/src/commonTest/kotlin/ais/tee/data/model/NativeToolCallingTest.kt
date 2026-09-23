package ais.tee.data.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NativeToolCallingTest {
    private val objectSchema = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
    }

    @Test
    fun typedToolContractKeepsPayloadsOutOfDebugStrings() {
        val definition = NativeToolDefinition("inspect_text", "Inspect selected text", objectSchema)
        val call = NativeToolCall(
            callId = "call-secret",
            name = definition.name,
            arguments = buildJsonObject { put("text", "secret text") },
        )
        val result = NativeToolResult(
            callId = call.callId,
            name = call.name,
            output = "secret output",
        )

        assertFalse(definition.toString().contains("Inspect selected text"))
        assertFalse(call.toString().contains("secret text"))
        assertFalse(call.toString().contains("call-secret"))
        assertFalse(result.toString().contains("secret output"))
        assertFalse(result.toString().contains("call-secret"))
    }

    @Test
    fun rejectsUnsafeNamesAndNonObjectSchemas() {
        assertFailsWith<IllegalArgumentException> {
            NativeToolDefinition("bad tool", "Bad name", objectSchema)
        }
        assertFailsWith<IllegalArgumentException> {
            NativeToolDefinition(
                "bad_schema",
                "Bad schema",
                buildJsonObject { put("type", "string") },
            )
        }
    }

    @Test
    fun resultMustMatchTheRequestedCall() {
        val call = NativeToolCall("call-1", "inspect_text", buildJsonObject {})

        assertEquals(
            "ok",
            validateNativeToolResult(
                call,
                NativeToolResult("call-1", "inspect_text", "ok"),
            ).output
        )
        assertFailsWith<IllegalArgumentException> {
            validateNativeToolResult(
                call,
                NativeToolResult("call-2", "inspect_text", "wrong"),
            )
        }
    }
}

package ais.tee.data.engine

import android.content.Context
import ais.tee.data.document.LineEnding
import ais.tee.data.document.MarkdownStructureDiagnostics
import ais.tee.data.document.StructuredTextDiagnostics
import ais.tee.data.document.StructuredTextFormat
import ais.tee.data.document.TextDocumentCodec
import ais.tee.data.model.BenchToolDataKind
import ais.tee.data.model.BenchToolInvocationMode
import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import ais.tee.data.model.BuiltInBenchTool
import ais.tee.data.model.CapabilityDecision
import ais.tee.data.model.NativeToolCall
import ais.tee.data.model.NativeToolDefinition
import ais.tee.data.model.NativeToolResult
import ais.tee.data.model.availability
import ais.tee.data.preferences.BuiltInBenchPreferencesStore
import ais.tee.data.security.TextInspector
import ais.tee.data.tokenizer.LocalTokenCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val FORMAT_STRUCTURED = "docbench_format_structured_text"
private const val REPAIR_MARKDOWN = "docbench_repair_markdown"
private const val NORMALIZE_EOL = "docbench_normalize_eol"
private const val INSPECT_TEXT = "docbench_inspect_text"
private const val COUNT_TOKENS = "docbench_count_tokens"
private const val MAX_INLINE_TOOL_TEXT_CHARS = 20_000
private const val MAX_INSPECTOR_FINDINGS_FOR_MODEL = 64

/**
 * First-party native-chat tools backed by the same Docbench cores used by the companion UI.
 *
 * The executor receives only inline text already present in the model turn. It never opens files,
 * reads arbitrary app storage, uses the camera, writes exports or performs network access.
 */
internal class NativeBenchChatTools(context: Context) {
    private val preferences = BuiltInBenchPreferencesStore(context.applicationContext)

    fun definitions(): List<NativeToolDefinition> {
        val enabled = preferences.loadEnabledTools()
        return buildList {
            if (modelRouteAllowed(BuiltInBenchTool.DOCBENCH_DOCUMENT, enabled)) {
                add(structuredFormatDefinition)
                add(markdownRepairDefinition)
                add(normalizeEolDefinition)
                add(tokenCountDefinition)
            }
            if (modelRouteAllowed(BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR, enabled)) {
                add(textInspectorDefinition)
            }
        }
    }

    suspend fun execute(call: NativeToolCall): NativeToolResult = withContext(Dispatchers.Default) {
        val enabled = preferences.loadEnabledTools()
        val owner = toolOwner(call.name)
            ?: return@withContext errorResult(call, "Unknown first-party tool.")
        if (!modelRouteAllowed(owner, enabled)) {
            return@withContext errorResult(call, "Tool is disabled or blocked by the current Bench policy.")
        }
        when (call.name) {
            FORMAT_STRUCTURED -> formatStructured(call)
            REPAIR_MARKDOWN -> repairMarkdown(call)
            NORMALIZE_EOL -> normalizeEol(call)
            INSPECT_TEXT -> inspectText(call)
            COUNT_TOKENS -> countTokens(call)
            else -> errorResult(call, "Unknown first-party tool.")
        }
    }

    private fun modelRouteAllowed(
        tool: BuiltInBenchTool,
        enabled: Set<BuiltInBenchTool>,
    ): Boolean =
        tool.availability(
            surface = BenchToolSurface.NATIVE_CHAT,
            invocationMode = BenchToolInvocationMode.MODEL_TOOL_CALL,
            inputKind = BenchToolDataKind.TEXT,
            isEnabled = tool in enabled,
            grantedPermissions = INLINE_TEXT_GRANT,
            networkAvailable = false,
        ).decision == CapabilityDecision.ALLOW

    private fun formatStructured(call: NativeToolCall): NativeToolResult {
        val text = call.inlineText() ?: return errorResult(call, "Missing or oversized text.")
        val format = when (call.stringArgument("format")?.lowercase()) {
            "json" -> StructuredTextFormat.JSON
            "json5" -> StructuredTextFormat.JSON5
            "yaml", "yml" -> StructuredTextFormat.YAML
            else -> return errorResult(call, "format must be json, json5 or yaml.")
        }
        val result = when (format) {
            StructuredTextFormat.JSON -> StructuredTextDiagnostics.formatJson(text)
            StructuredTextFormat.JSON5 -> StructuredTextDiagnostics.formatJson5(text)
            StructuredTextFormat.YAML -> StructuredTextDiagnostics.formatYaml(text)
            StructuredTextFormat.XML -> error("XML is not exposed by this formatter")
        }
        if (!result.isSuccess) {
            return errorResult(call, result.errorMessage ?: "Formatting failed.")
        }
        return successResult(
            call,
            buildJsonObject {
                put("changed", result.changed)
                put("format", format.name.lowercase())
                put("text", result.text)
            }
        )
    }

    private fun repairMarkdown(call: NativeToolCall): NativeToolResult {
        val text = call.inlineText() ?: return errorResult(call, "Missing or oversized text.")
        val result = MarkdownStructureDiagnostics.repair(text)
        if (!result.isSuccess) {
            return errorResult(call, result.errorMessage ?: "Markdown repair failed.")
        }
        return successResult(
            call,
            buildJsonObject {
                put("changed", result.changed)
                put("repairedIssueCount", result.repairedIssueCount)
                put("text", result.text)
            }
        )
    }

    private fun normalizeEol(call: NativeToolCall): NativeToolResult {
        val text = call.inlineText() ?: return errorResult(call, "Missing or oversized text.")
        val target = when (call.stringArgument("target")?.uppercase()) {
            "LF" -> LineEnding.LF
            "CRLF" -> LineEnding.CRLF
            "CR" -> LineEnding.CR
            else -> return errorResult(call, "target must be LF, CRLF or CR.")
        }
        val normalized = TextDocumentCodec.normalizeLineEndings(text, target)
        return successResult(
            call,
            buildJsonObject {
                put("changed", normalized != text)
                put("target", target.name)
                put("text", normalized)
            }
        )
    }

    private fun inspectText(call: NativeToolCall): NativeToolResult {
        val text = call.inlineText() ?: return errorResult(call, "Missing or oversized text.")
        val inspection = TextInspector.inspect(text)
        return successResult(
            call,
            buildJsonObject {
                put("detectedCount", inspection.detectedCount)
                put("highCount", inspection.highCount)
                put("mediumCount", inspection.mediumCount)
                put("lowCount", inspection.lowCount)
                put("truncated", inspection.truncated || inspection.findings.size > MAX_INSPECTOR_FINDINGS_FOR_MODEL)
                putJsonArray("findings") {
                    inspection.findings.take(MAX_INSPECTOR_FINDINGS_FOR_MODEL).forEach { finding ->
                        add(buildJsonObject {
                            put("severity", finding.severity.name.lowercase())
                            put("kind", finding.kind)
                            put("label", finding.label)
                            put("detail", finding.detail)
                            put("line", finding.line)
                            put("column", finding.column)
                            put("length", finding.length)
                        })
                    }
                }
            }
        )
    }

    private fun countTokens(call: NativeToolCall): NativeToolResult {
        val text = call.inlineText() ?: return errorResult(call, "Missing or oversized text.")
        return successResult(
            call,
            buildJsonObject {
                put("encoding", LocalTokenCounter.ENCODING_LABEL)
                put("tokens", LocalTokenCounter.count(text))
            }
        )
    }

    private fun NativeToolCall.inlineText(): String? =
        stringArgument("text")
            ?.takeIf { it.isNotBlank() && it.length <= MAX_INLINE_TOOL_TEXT_CHARS }

    private fun NativeToolCall.stringArgument(name: String): String? =
        (arguments[name] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull

    private fun successResult(call: NativeToolCall, payload: JsonObject): NativeToolResult =
        NativeToolResult(call.callId, call.name, payload.toString())

    private fun errorResult(call: NativeToolCall, message: String): NativeToolResult =
        NativeToolResult(call.callId, call.name, message.take(1_000), isError = true)

    private fun toolOwner(name: String): BuiltInBenchTool? = when (name) {
        FORMAT_STRUCTURED,
        REPAIR_MARKDOWN,
        NORMALIZE_EOL,
        COUNT_TOKENS -> BuiltInBenchTool.DOCBENCH_DOCUMENT
        INSPECT_TEXT -> BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR
        else -> null
    }

    private companion object {
        val INLINE_TEXT_GRANT = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)

        val textProperty = buildJsonObject {
            put("type", "string")
            put("description", "Inline text already available in the current chat turn.")
        }

        val structuredFormatDefinition = NativeToolDefinition(
            name = FORMAT_STRUCTURED,
            description = "Validate and locally format inline JSON, JSON5 or YAML without network access.",
            inputSchema = objectSchema(
                properties = buildJsonObject {
                    put("text", textProperty)
                    put("format", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(listOf("json", "json5", "yaml").map(::JsonPrimitive)))
                    })
                },
                required = listOf("text", "format"),
            ),
        )

        val markdownRepairDefinition = NativeToolDefinition(
            name = REPAIR_MARKDOWN,
            description = "Safely repair unclosed root-level Markdown code fences in inline text.",
            inputSchema = objectSchema(
                properties = buildJsonObject { put("text", textProperty) },
                required = listOf("text"),
            ),
        )

        val normalizeEolDefinition = NativeToolDefinition(
            name = NORMALIZE_EOL,
            description = "Normalize line endings of inline text to LF, CRLF or CR locally.",
            inputSchema = objectSchema(
                properties = buildJsonObject {
                    put("text", textProperty)
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(listOf("LF", "CRLF", "CR").map(::JsonPrimitive)))
                    })
                },
                required = listOf("text", "target"),
            ),
        )

        val textInspectorDefinition = NativeToolDefinition(
            name = INSPECT_TEXT,
            description = "Inspect inline text for hidden Unicode, bidi controls, confusables and prompt-injection-like content.",
            inputSchema = objectSchema(
                properties = buildJsonObject { put("text", textProperty) },
                required = listOf("text"),
            ),
        )

        val tokenCountDefinition = NativeToolDefinition(
            name = COUNT_TOKENS,
            description = "Count inline text locally with Aistee's bundled o200k tokenizer.",
            inputSchema = objectSchema(
                properties = buildJsonObject { put("text", textProperty) },
                required = listOf("text"),
            ),
        )

        fun objectSchema(properties: JsonObject, required: List<String>): JsonObject =
            buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put("required", JsonArray(required.map(::JsonPrimitive)))
                put("additionalProperties", false)
            }
    }
}

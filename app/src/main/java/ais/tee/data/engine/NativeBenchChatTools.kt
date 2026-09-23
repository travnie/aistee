package ais.tee.data.engine

import android.content.Context
import ais.tee.data.codebench.CodebenchBarcodeCodec
import ais.tee.data.codebench.CodebenchBarcodeFormat
import ais.tee.data.codebench.CodebenchBarcodeMatrix
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
import ais.tee.data.model.DEFAULT_PROJECT_ID
import ais.tee.data.model.MAX_NATIVE_TOOL_RESULT_CHARS
import ais.tee.data.model.NativeToolCall
import ais.tee.data.model.NativeToolDefinition
import ais.tee.data.model.NativeToolResult
import ais.tee.data.model.availability
import ais.tee.data.preferences.BuiltInBenchPreferencesStore
import ais.tee.data.preferences.ProjectLibraryStore
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
private const val GENERATE_QR = "codebench_generate_qr"
private const val MAX_INLINE_TOOL_TEXT_CHARS = 20_000
private const val MAX_INSPECTOR_FINDINGS_FOR_MODEL = 64

/**
 * First-party native-chat tools backed by the same Docbench cores used by the companion UI.
 *
 * The executor receives only inline text already present in the model turn. It never opens files,
 * reads arbitrary app storage, uses the camera, writes exports or performs network access.
 */
internal class NativeBenchChatTools(
    context: Context,
    private val projectId: String = DEFAULT_PROJECT_ID,
) {
    private val preferences = BuiltInBenchPreferencesStore(context.applicationContext)
    private val projectLibraryStore = ProjectLibraryStore(context.applicationContext.noBackupFilesDir)

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
            if (modelRouteAllowed(BuiltInBenchTool.CODEBENCH_QR_BARCODE, enabled)) {
                add(qrDefinition)
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
            GENERATE_QR -> generateQr(call)
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

    private fun generateQr(call: NativeToolCall): NativeToolResult {
        val text = call.stringArgument("text")
            ?.takeIf { it.isNotEmpty() && it.length <= CodebenchBarcodeCodec.MAX_CONTENT_UTF16_UNITS }
            ?: return errorResult(call, "Missing or oversized QR text.")
        val title = call.stringArgument("title")?.trim()?.takeIf(String::isNotEmpty) ?: "QR code"
        val matrix = runCatching {
            CodebenchBarcodeCodec.encode(
                text = text,
                format = CodebenchBarcodeFormat.QR_CODE,
                width = 1,
                height = 1,
            )
        }.getOrElse {
            return errorResult(call, "QR payload could not be encoded.")
        }
        val asset = projectLibraryStore.saveTextAsset(
            projectId = projectId,
            title = title,
            mediaType = "image/svg+xml",
            extension = "svg",
            text = matrix.toSvg(),
        ) ?: return errorResult(call, "Could not save generated QR to Project Library.")
        return successResult(
            call,
            buildJsonObject {
                put("assetId", asset.id)
                put("projectId", asset.projectId)
                put("title", asset.title)
                put("mediaType", asset.mediaType)
                put("width", matrix.width)
                put("height", matrix.height)
            }
        )
    }

    private fun CodebenchBarcodeMatrix.toSvg(): String = buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 """)
        append(width)
        append(' ')
        append(height)
        append(""""><rect width="100%" height="100%" fill="white"/><path fill="black" d="""")
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                if (!this@toSvg[x, y]) {
                    x++
                    continue
                }
                val start = x
                while (x < width && this@toSvg[x, y]) x++
                append("M")
                append(start)
                append(' ')
                append(y)
                append("h")
                append(x - start)
                append("v1H")
                append(start)
                append("z")
            }
        }
        append(""""/></svg>""")
    }

    private fun NativeToolCall.inlineText(): String? =
        stringArgument("text")
            ?.takeIf { it.isNotBlank() && it.length <= MAX_INLINE_TOOL_TEXT_CHARS }

    private fun NativeToolCall.stringArgument(name: String): String? =
        (arguments[name] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull

    private fun successResult(call: NativeToolCall, payload: JsonObject): NativeToolResult {
        val encoded = payload.toString()
        return if (encoded.length <= MAX_NATIVE_TOOL_RESULT_CHARS) {
            NativeToolResult(call.callId, call.name, encoded)
        } else {
            errorResult(call, "Tool output exceeds the native chat result limit.")
        }
    }

    private fun errorResult(call: NativeToolCall, message: String): NativeToolResult =
        NativeToolResult(call.callId, call.name, message.take(1_000), isError = true)

    private fun toolOwner(name: String): BuiltInBenchTool? = when (name) {
        FORMAT_STRUCTURED,
        REPAIR_MARKDOWN,
        NORMALIZE_EOL,
        COUNT_TOKENS -> BuiltInBenchTool.DOCBENCH_DOCUMENT
        INSPECT_TEXT -> BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR
        GENERATE_QR -> BuiltInBenchTool.CODEBENCH_QR_BARCODE
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


        val qrDefinition = NativeToolDefinition(
            name = GENERATE_QR,
            description = "Generate a QR code locally from inline text and save it as an SVG asset in Aistee Project Library. Returns metadata only, not image bytes.",
            inputSchema = objectSchema(
                properties = buildJsonObject {
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("description", "QR payload, up to 4096 UTF-16 code units.")
                    })
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional short title for the saved Library asset.")
                    })
                },
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

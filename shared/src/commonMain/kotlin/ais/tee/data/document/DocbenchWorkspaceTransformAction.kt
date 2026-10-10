package ais.tee.data.document

import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import ais.tee.data.model.BuiltInBenchToolAvailability

/** Portable metadata for explicit local workspace transformations. */
enum class DocbenchWorkspaceTransformKind(val label: String, val formatName: String) {
    REPAIR_MARKDOWN_FENCES("Markdown fence repair", "Markdown"),
    FORMAT_JSON("JSON formatting", "JSON"),
    FORMAT_JSON5("JSON5 formatting", "JSON5"),
    FORMAT_YAML("YAML formatting", "YAML"),
}

sealed interface DocbenchWorkspaceTransformResult {
    data class Completed(
        val kind: DocbenchWorkspaceTransformKind,
        val content: String,
        val changed: Boolean,
        val warnings: List<String> = emptyList(),
        val sourceIds: List<String> = emptyList(),
        val repairedIssueCount: Int = 0,
    ) : DocbenchWorkspaceTransformResult {
        override fun toString(): String =
            "DocbenchWorkspaceTransformResult.Completed(kind=$kind, content=<redacted>, changed=$changed, " +
                "warnings=${warnings.size}, sources=${sourceIds.size}, repairedIssueCount=$repairedIssueCount)"
    }

    data class Rejected(val message: String) : DocbenchWorkspaceTransformResult

    data class Blocked(
        val availability: BuiltInBenchToolAvailability,
    ) : DocbenchWorkspaceTransformResult
}

/**
 * Adapter over the existing Docbench actions; no separate parser or persistence layer.
 * sourceIds is caller-owned provenance only, never permission to overwrite an asset.
 */
object DocbenchWorkspaceTransformAction {
    fun execute(
        text: String,
        kind: DocbenchWorkspaceTransformKind,
        surface: BenchToolSurface,
        isEnabled: Boolean,
        grantedPermissions: Set<BenchToolPermission>,
        sourceIds: List<String> = emptyList(),
    ): DocbenchWorkspaceTransformResult = when (kind) {
        DocbenchWorkspaceTransformKind.REPAIR_MARKDOWN_FENCES ->
            when (val result = DocbenchMarkdownRepairAction.execute(
                text = text,
                surface = surface,
                isEnabled = isEnabled,
                grantedPermissions = grantedPermissions,
            )) {
                is DocbenchMarkdownRepairActionResult.Completed ->
                    DocbenchWorkspaceTransformResult.Completed(
                        kind = kind,
                        content = result.text,
                        changed = result.changed,
                        sourceIds = sourceIds.toList(),
                        repairedIssueCount = result.repairedIssueCount,
                    )
                is DocbenchMarkdownRepairActionResult.Rejected ->
                    DocbenchWorkspaceTransformResult.Rejected(result.message)
                is DocbenchMarkdownRepairActionResult.Blocked ->
                    DocbenchWorkspaceTransformResult.Blocked(result.availability)
            }
        else -> {
            val format = when (kind) {
                DocbenchWorkspaceTransformKind.FORMAT_JSON -> StructuredTextFormat.JSON
                DocbenchWorkspaceTransformKind.FORMAT_JSON5 -> StructuredTextFormat.JSON5
                DocbenchWorkspaceTransformKind.FORMAT_YAML -> StructuredTextFormat.YAML
                DocbenchWorkspaceTransformKind.REPAIR_MARKDOWN_FENCES -> error("Handled above")
            }
            when (val result = DocbenchJsonFormatAction.execute(
                text = text,
                format = format,
                surface = surface,
                isEnabled = isEnabled,
                grantedPermissions = grantedPermissions,
            )) {
                is DocbenchJsonFormatActionResult.Completed ->
                    DocbenchWorkspaceTransformResult.Completed(
                        kind = kind,
                        content = result.text,
                        changed = result.changed,
                        sourceIds = sourceIds.toList(),
                    )
                is DocbenchJsonFormatActionResult.Rejected ->
                    DocbenchWorkspaceTransformResult.Rejected(result.message)
                is DocbenchJsonFormatActionResult.Blocked ->
                    DocbenchWorkspaceTransformResult.Blocked(result.availability)
            }
        }
    }
}

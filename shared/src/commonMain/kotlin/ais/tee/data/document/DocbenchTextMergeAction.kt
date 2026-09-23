package ais.tee.data.document

import ais.tee.data.model.BenchToolDataKind
import ais.tee.data.model.BenchToolInvocationMode
import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import ais.tee.data.model.BuiltInBenchTool
import ais.tee.data.model.BuiltInBenchToolAvailability
import ais.tee.data.model.availability
import ais.tee.data.tokenizer.MAX_INTERACTIVE_TOKENIZED_CHARS

const val MAX_DOCBENCH_TEXT_MERGE_PARTS = 16
const val MAX_DOCBENCH_MERGED_TEXT_CHARS = MAX_INTERACTIVE_TOKENIZED_CHARS
private const val DOCBENCH_TEXT_MERGE_SEPARATOR = "\n\n"

data class DocbenchTextMergePart(
    val text: String
) {
    override fun toString(): String = "DocbenchTextMergePart(text=<redacted>)"
}

sealed interface DocbenchTextMergeActionResult {
    data class Completed(
        val text: String,
        val appendedParts: Int,
        val changed: Boolean
    ) : DocbenchTextMergeActionResult {
        override fun toString(): String =
            "DocbenchTextMergeActionResult.Completed(text=<redacted>, appendedParts=$appendedParts, changed=$changed)"
    }

    data class Rejected(val message: String) : DocbenchTextMergeActionResult

    data class Blocked(
        val availability: BuiltInBenchToolAvailability
    ) : DocbenchTextMergeActionResult
}

/**
 * Appends user-selected text documents to the existing Docbench editor in picker order.
 *
 * Source text is preserved byte-for-byte at the String level. Aistee adds only one explicit blank
 * line separator between the existing editor content and each selected document.
 */
object DocbenchTextMergeAction {
    fun availability(
        surface: BenchToolSurface,
        isEnabled: Boolean,
        grantedPermissions: Set<BenchToolPermission>
    ): BuiltInBenchToolAvailability = BuiltInBenchTool.DOCBENCH_DOCUMENT.availability(
        surface = surface,
        invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
        inputKind = BenchToolDataKind.DOCUMENT,
        isEnabled = isEnabled,
        grantedPermissions = grantedPermissions,
        networkAvailable = false
    )

    fun execute(
        existingText: String,
        parts: List<DocbenchTextMergePart>,
        surface: BenchToolSurface,
        isEnabled: Boolean,
        grantedPermissions: Set<BenchToolPermission>
    ): DocbenchTextMergeActionResult {
        val availability = availability(surface, isEnabled, grantedPermissions)
        if (!availability.canOffer) return DocbenchTextMergeActionResult.Blocked(availability)
        if (parts.isEmpty()) {
            return DocbenchTextMergeActionResult.Rejected("Select at least one text document.")
        }
        if (parts.size > MAX_DOCBENCH_TEXT_MERGE_PARTS) {
            return DocbenchTextMergeActionResult.Rejected(
                "Merge at most $MAX_DOCBENCH_TEXT_MERGE_PARTS documents at a time."
            )
        }

        var projectedLength = existingText.length.toLong()
        parts.forEachIndexed { index, part ->
            if (existingText.isNotEmpty() || index > 0) {
                projectedLength += DOCBENCH_TEXT_MERGE_SEPARATOR.length
            }
            projectedLength += part.text.length
            if (projectedLength > MAX_DOCBENCH_MERGED_TEXT_CHARS) {
                return DocbenchTextMergeActionResult.Rejected(
                    "Merged text would exceed the $MAX_DOCBENCH_MERGED_TEXT_CHARS-character interactive limit."
                )
            }
        }

        val merged = buildString(projectedLength.toInt()) {
            append(existingText)
            parts.forEachIndexed { index, part ->
                if (existingText.isNotEmpty() || index > 0) append(DOCBENCH_TEXT_MERGE_SEPARATOR)
                append(part.text)
            }
        }
        return DocbenchTextMergeActionResult.Completed(
            text = merged,
            appendedParts = parts.size,
            changed = merged != existingText
        )
    }
}

package ais.tee.data.document

import ais.tee.data.model.BenchToolDataKind
import ais.tee.data.model.BenchToolInvocationMode
import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import ais.tee.data.model.BuiltInBenchTool
import ais.tee.data.model.BuiltInBenchToolAvailability
import ais.tee.data.model.availability

sealed interface DocbenchMarkdownRepairActionResult {
    data class Completed(
        val text: String,
        val changed: Boolean,
        val repairedIssueCount: Int
    ) : DocbenchMarkdownRepairActionResult {
        override fun toString(): String =
            "DocbenchMarkdownRepairActionResult.Completed(text=<redacted>, changed=$changed, repairedIssueCount=$repairedIssueCount)"
    }

    data class Rejected(val message: String) : DocbenchMarkdownRepairActionResult

    data class Blocked(
        val availability: BuiltInBenchToolAvailability
    ) : DocbenchMarkdownRepairActionResult
}

/** Explicit local Markdown structure repair behind the canonical Docbench document capability. */
object DocbenchMarkdownRepairAction {
    fun availability(
        surface: BenchToolSurface,
        isEnabled: Boolean,
        grantedPermissions: Set<BenchToolPermission>
    ): BuiltInBenchToolAvailability = BuiltInBenchTool.DOCBENCH_DOCUMENT.availability(
        surface = surface,
        invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
        inputKind = BenchToolDataKind.TEXT,
        isEnabled = isEnabled,
        grantedPermissions = grantedPermissions,
        networkAvailable = false
    )

    fun execute(
        text: String,
        surface: BenchToolSurface,
        isEnabled: Boolean,
        grantedPermissions: Set<BenchToolPermission>
    ): DocbenchMarkdownRepairActionResult {
        val availability = availability(surface, isEnabled, grantedPermissions)
        if (!availability.canOffer) {
            return DocbenchMarkdownRepairActionResult.Blocked(availability)
        }

        val repaired = MarkdownStructureDiagnostics.repair(text)
        return if (repaired.isSuccess) {
            DocbenchMarkdownRepairActionResult.Completed(
                text = repaired.text,
                changed = repaired.changed,
                repairedIssueCount = repaired.repairedIssueCount
            )
        } else {
            DocbenchMarkdownRepairActionResult.Rejected(
                repaired.errorMessage ?: "Could not repair Markdown structure."
            )
        }
    }
}

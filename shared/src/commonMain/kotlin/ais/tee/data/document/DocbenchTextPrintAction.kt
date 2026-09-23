package ais.tee.data.document

import ais.tee.data.model.BenchToolDataKind
import ais.tee.data.model.BenchToolInvocationMode
import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import ais.tee.data.model.BuiltInBenchTool
import ais.tee.data.model.BuiltInBenchToolAvailability
import ais.tee.data.model.availability
import ais.tee.data.tokenizer.MAX_INTERACTIVE_TOKENIZED_CHARS

sealed interface DocbenchTextPrintActionResult {
    data class Completed(val text: String) : DocbenchTextPrintActionResult {
        override fun toString(): String =
            "DocbenchTextPrintActionResult.Completed(text=<redacted>)"
    }

    data class Rejected(val message: String) : DocbenchTextPrintActionResult

    data class Blocked(
        val availability: BuiltInBenchToolAvailability
    ) : DocbenchTextPrintActionResult
}

/** Prepares current editor text for an explicit local handoff to the Android print framework. */
object DocbenchTextPrintAction {
    private val PRINT_PERMISSION = setOf(BenchToolPermission.WRITE_USER_EXPORT)

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
        networkAvailable = false,
        actionRequiredPermissions = PRINT_PERMISSION
    )

    fun execute(
        text: String,
        surface: BenchToolSurface,
        isEnabled: Boolean,
        grantedPermissions: Set<BenchToolPermission>
    ): DocbenchTextPrintActionResult {
        val availability = availability(surface, isEnabled, grantedPermissions)
        if (!availability.canOffer) {
            return DocbenchTextPrintActionResult.Blocked(availability)
        }
        if (text.isEmpty()) {
            return DocbenchTextPrintActionResult.Rejected("Enter text to print.")
        }
        if (text.length > MAX_INTERACTIVE_TOKENIZED_CHARS) {
            return DocbenchTextPrintActionResult.Rejected(
                "Printing is limited to $MAX_INTERACTIVE_TOKENIZED_CHARS characters."
            )
        }
        return DocbenchTextPrintActionResult.Completed(text)
    }
}

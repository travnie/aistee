package ais.tee.data.document

import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import ais.tee.data.tokenizer.MAX_INTERACTIVE_TOKENIZED_CHARS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocbenchTextPrintActionTest {
    @Test
    fun printRequiresExplicitExportPermission() {
        val result = DocbenchTextPrintAction.execute(
            text = "hello",
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)
        )

        val blocked = assertIs<DocbenchTextPrintActionResult.Blocked>(result)
        assertEquals(
            setOf(BenchToolPermission.WRITE_USER_EXPORT),
            blocked.availability.missingRequiredPermissions
        )
    }

    @Test
    fun allowedPrintPreservesEditorTextExactly() {
        val source = "first\r\nsecond\n"
        val result = DocbenchTextPrintAction.execute(
            text = source,
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = PRINT_GRANTS
        )

        val completed = assertIs<DocbenchTextPrintActionResult.Completed>(result)
        assertEquals(source, completed.text)
    }

    @Test
    fun emptyOrOversizedTextIsRejected() {
        assertIs<DocbenchTextPrintActionResult.Rejected>(
            DocbenchTextPrintAction.execute(
                text = "",
                surface = BenchToolSurface.COMPANION_UI,
                isEnabled = true,
                grantedPermissions = PRINT_GRANTS
            )
        )
        assertIs<DocbenchTextPrintActionResult.Rejected>(
            DocbenchTextPrintAction.execute(
                text = "x".repeat(MAX_INTERACTIVE_TOKENIZED_CHARS + 1),
                surface = BenchToolSurface.COMPANION_UI,
                isEnabled = true,
                grantedPermissions = PRINT_GRANTS
            )
        )
    }

    @Test
    fun debugStringDoesNotExposePrintContent() {
        val secret = "private-print-content"
        val completed = DocbenchTextPrintActionResult.Completed(secret)

        assertFalse(secret in completed.toString())
        assertTrue(completed.toString().contains("<redacted>"))
    }

    private companion object {
        val PRINT_GRANTS = setOf(
            BenchToolPermission.READ_USER_SELECTED_CONTENT,
            BenchToolPermission.WRITE_USER_EXPORT
        )
    }
}

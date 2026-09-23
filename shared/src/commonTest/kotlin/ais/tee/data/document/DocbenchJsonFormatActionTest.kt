package ais.tee.data.document

import ais.tee.data.model.BenchToolAvailabilityBlocker
import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocbenchJsonFormatActionTest {
    @Test
    fun missingReadGrantBlocksFormatting() {
        val result = DocbenchJsonFormatAction.execute(
            text = "{\"a\":1}",
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = emptySet()
        )

        val blocked = assertIs<DocbenchJsonFormatActionResult.Blocked>(result)
        assertEquals(
            setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
            blocked.availability.missingRequiredPermissions
        )
        assertTrue(
            BenchToolAvailabilityBlocker.MISSING_REQUIRED_PERMISSION in blocked.availability.blockers
        )
    }

    @Test
    fun companionFormattingReusesFidelityPreservingJsonCore() {
        val source = "{\"n\":123456789012345678901234567890,\"items\":[1,2]}"
        val result = DocbenchJsonFormatAction.execute(
            text = source,
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = DOCUMENT_READ_GRANT
        )

        val completed = assertIs<DocbenchJsonFormatActionResult.Completed>(result)
        assertTrue(completed.changed)
        assertTrue(completed.text.contains("123456789012345678901234567890"))
        assertTrue(completed.text.contains('\n'))
    }


    @Test
    fun json5FormattingPreservesCommentsAndExtendedLiterals() {
        val source = "{port:0x1f90,// default\nenabled:true,}"
        val result = DocbenchJsonFormatAction.execute(
            text = source,
            format = StructuredTextFormat.JSON5,
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = DOCUMENT_READ_GRANT
        )

        val completed = assertIs<DocbenchJsonFormatActionResult.Completed>(result)
        assertTrue(completed.changed)
        assertTrue(completed.text.contains("// default"))
        assertTrue(completed.text.contains("0x1f90"))
    }


    @Test
    fun yamlFormattingUsesCommentAndAnchorPreservingCore() {
        val source = "# keep\nbase: &item {name: Aistee}\ncopy: *item\n"
        val result = DocbenchJsonFormatAction.execute(
            text = source,
            format = StructuredTextFormat.YAML,
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = DOCUMENT_READ_GRANT
        )

        val completed = assertIs<DocbenchJsonFormatActionResult.Completed>(result)
        assertTrue(completed.text.contains("# keep"))
        assertTrue(completed.text.contains("&item"))
        assertTrue(completed.text.contains("*item"))
    }

    @Test
    fun invalidOrAmbiguousJsonIsRejectedWithoutReturningModifiedText() {
        listOf(
            "{\"broken\":}",
            "{\"a\":1,\"\\u0061\":2}"
        ).forEach { source ->
            val result = DocbenchJsonFormatAction.execute(
                text = source,
                surface = BenchToolSurface.COMPANION_UI,
                isEnabled = true,
                grantedPermissions = DOCUMENT_READ_GRANT
            )

            assertIs<DocbenchJsonFormatActionResult.Rejected>(result)
            assertFalse(source in result.toString())
        }
    }

    @Test
    fun completedDebugStringDoesNotExposeFormattedContent() {
        val secret = "private-json-value"
        val completed = DocbenchJsonFormatActionResult.Completed(
            text = "{\"secret\":\"$secret\"}",
            changed = true
        )

        val debug = completed.toString()
        assertFalse(secret in debug)
        assertEquals(
            "DocbenchJsonFormatActionResult.Completed(text=<redacted>, changed=true)",
            debug
        )
    }

    private companion object {
        val DOCUMENT_READ_GRANT = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)
    }
}

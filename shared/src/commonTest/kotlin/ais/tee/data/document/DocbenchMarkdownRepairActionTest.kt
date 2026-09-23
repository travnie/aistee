package ais.tee.data.document

import ais.tee.data.model.BenchToolAvailabilityBlocker
import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocbenchMarkdownRepairActionTest {
    private val tick = 96.toChar().toString()

    @Test
    fun missingReadGrantBlocksRepair() {
        val result = DocbenchMarkdownRepairAction.execute(
            text = tick.repeat(3) + "text\nhello",
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = emptySet()
        )

        val blocked = assertIs<DocbenchMarkdownRepairActionResult.Blocked>(result)
        assertEquals(
            setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
            blocked.availability.missingRequiredPermissions
        )
        assertTrue(
            BenchToolAvailabilityBlocker.MISSING_REQUIRED_PERMISSION in blocked.availability.blockers
        )
    }

    @Test
    fun explicitLocalActionRepairsUnclosedFence() {
        val fence = tick.repeat(3)
        val result = DocbenchMarkdownRepairAction.execute(
            text = fence + "kotlin\nval answer = 42",
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = DOCUMENT_READ_GRANT
        )

        val completed = assertIs<DocbenchMarkdownRepairActionResult.Completed>(result)
        assertTrue(completed.changed)
        assertEquals(1, completed.repairedIssueCount)
        assertTrue(completed.text.endsWith("\n$fence"))
    }

    @Test
    fun nestedFenceIsRejectedWithoutReturningModifiedText() {
        val secret = "private-markdown-value"
        val source = "> " + tick.repeat(3) + "text\n> " + secret
        val result = DocbenchMarkdownRepairAction.execute(
            text = source,
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = DOCUMENT_READ_GRANT
        )

        val rejected = assertIs<DocbenchMarkdownRepairActionResult.Rejected>(result)
        assertFalse(secret in rejected.message)
    }

    @Test
    fun completedDebugStringDoesNotExposeMarkdownContent() {
        val secret = "private-markdown-value"
        val completed = DocbenchMarkdownRepairActionResult.Completed(
            text = secret,
            changed = true,
            repairedIssueCount = 1
        )

        val debug = completed.toString()
        assertFalse(secret in debug)
        assertEquals(
            "DocbenchMarkdownRepairActionResult.Completed(text=<redacted>, changed=true, repairedIssueCount=1)",
            debug
        )
    }

    private companion object {
        val DOCUMENT_READ_GRANT = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)
    }
}

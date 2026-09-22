package ais.tee.data.document

import ais.tee.data.model.BenchToolAvailabilityBlocker
import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocbenchTextMergeActionTest {
    @Test
    fun missingReadGrantBlocksMerge() {
        val result = DocbenchTextMergeAction.execute(
            existingText = "",
            parts = listOf(DocbenchTextMergePart("one")),
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = emptySet()
        )

        val blocked = assertIs<DocbenchTextMergeActionResult.Blocked>(result)
        assertTrue(
            BenchToolAvailabilityBlocker.MISSING_REQUIRED_PERMISSION in blocked.availability.blockers
        )
    }

    @Test
    fun appendsSelectedPartsInOrderWithoutRewritingTheirContents() {
        val result = DocbenchTextMergeAction.execute(
            existingText = "existing\r\n",
            parts = listOf(
                DocbenchTextMergePart("# first\nbody"),
                DocbenchTextMergePart("second\r\nbody")
            ),
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = READ_GRANT
        )

        val completed = assertIs<DocbenchTextMergeActionResult.Completed>(result)
        assertEquals("existing\r\n\n\n# first\nbody\n\nsecond\r\nbody", completed.text)
        assertEquals(2, completed.appendedParts)
        assertTrue(completed.changed)
    }

    @Test
    fun emptyEditorStartsWithFirstSelectedDocument() {
        val result = DocbenchTextMergeAction.execute(
            existingText = "",
            parts = listOf(
                DocbenchTextMergePart("one"),
                DocbenchTextMergePart("two")
            ),
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = READ_GRANT
        )

        val completed = assertIs<DocbenchTextMergeActionResult.Completed>(result)
        assertEquals("one\n\ntwo", completed.text)
    }

    @Test
    fun tooManyDocumentsAreRejected() {
        val result = DocbenchTextMergeAction.execute(
            existingText = "",
            parts = List(MAX_DOCBENCH_TEXT_MERGE_PARTS + 1) { DocbenchTextMergePart("x") },
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = READ_GRANT
        )

        assertIs<DocbenchTextMergeActionResult.Rejected>(result)
    }

    @Test
    fun mergedInteractiveLimitIsCheckedBeforeBuildingOutput() {
        val result = DocbenchTextMergeAction.execute(
            existingText = "x".repeat(MAX_DOCBENCH_MERGED_TEXT_CHARS),
            parts = listOf(DocbenchTextMergePart("y")),
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = true,
            grantedPermissions = READ_GRANT
        )

        val rejected = assertIs<DocbenchTextMergeActionResult.Rejected>(result)
        assertTrue(rejected.message.contains("interactive limit"))
    }

    @Test
    fun debugStringsDoNotExposeMergedText() {
        val part = DocbenchTextMergePart("private-input")
        val completed = DocbenchTextMergeActionResult.Completed(
            text = "private-output",
            appendedParts = 1,
            changed = true
        )

        assertFalse("private-input" in part.toString())
        assertFalse("private-output" in completed.toString())
    }

    private companion object {
        val READ_GRANT = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)
    }
}

package ais.tee.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTransformExcerptTest {
    @Test
    fun formattingPreviewShowsChangesBeyondLongCommonPrefix() {
        val before = "a".repeat(900) + """{"a":1}"""
        val after = "a".repeat(900) + """{"a": 1}"""

        val (original, formatted) = workspaceTransformExcerpts(before, after, repairing = false)

        assertTrue(original.contains("""{"a":1}"""))
        assertTrue(formatted.contains("""{"a": 1}"""))
        assertTrue(original.length <= 600)
        assertTrue(formatted.length <= 600)
    }

    @Test
    fun fenceRepairPreviewKeepsDocumentEnding() {
        val before = "x".repeat(700) + "\n~~~kotlin\nval x = 1"
        val after = before + "\n~~~"

        val (original, repaired) = workspaceTransformExcerpts(before, after, repairing = true)

        assertTrue(original.endsWith("val x = 1"))
        assertTrue(repaired.endsWith("\n~~~"))
        assertEquals(600, original.length)
    }
}

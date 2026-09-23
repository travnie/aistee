package ais.tee.data.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownStructureDiagnosticsTest {
    private val tick = 96.toChar().toString()

    @Test
    fun acceptsClosedBacktickAndTildeFences() {
        val ticks = tick.repeat(3)
        listOf(
            "$ticks" + "kotlin\nval answer = 42\n$ticks",
            "~~~text\nhello\n~~~"
        ).forEach { source ->
            assertTrue(MarkdownStructureDiagnostics.validate(source).isValid, source)
            val repair = MarkdownStructureDiagnostics.repair(source)
            assertTrue(repair.isSuccess)
            assertFalse(repair.changed)
            assertEquals(source, repair.text)
        }
    }

    @Test
    fun detectsAndClosesRootLevelFenceWithMatchingDelimiter() {
        val fence = tick.repeat(4)
        val source = fence + "kotlin\nval answer = 42"
        val validation = MarkdownStructureDiagnostics.validate(source)

        assertFalse(validation.isValid)
        assertEquals(1, validation.issues.size)
        val issue = validation.issues.single()
        assertEquals(MarkdownStructureIssueKind.UNCLOSED_CODE_FENCE, issue.kind)
        assertEquals(fence, issue.delimiter)
        assertTrue(issue.repairable)

        val repaired = MarkdownStructureDiagnostics.repair(source)
        assertTrue(repaired.isSuccess)
        assertTrue(repaired.changed)
        assertEquals(1, repaired.repairedIssueCount)
        assertTrue(repaired.text.endsWith("\n$fence"))
        assertTrue(MarkdownStructureDiagnostics.validate(repaired.text).isValid)
    }

    @Test
    fun preservesLastObservedLineEndingWhenAppendingFence() {
        val fence = tick.repeat(3)
        val source = fence + "text\r\nhello"
        val repaired = MarkdownStructureDiagnostics.repair(source)

        assertTrue(repaired.isSuccess)
        assertEquals(source + "\r\n" + fence, repaired.text)
    }

    @Test
    fun nestedFenceIsReportedButNotAutoRepaired() {
        val fence = tick.repeat(3)
        val source = "> " + fence + "text\n> hello"
        val validation = MarkdownStructureDiagnostics.validate(source)

        assertFalse(validation.isValid)
        assertEquals(1, validation.issues.size)
        assertFalse(validation.issues.single().repairable)

        val repaired = MarkdownStructureDiagnostics.repair(source)
        assertFalse(repaired.isSuccess)
        assertFalse(repaired.changed)
        assertEquals(source, repaired.text)
    }

    @Test
    fun shorterFenceInsideCodeDoesNotCloseLongerOpeningFence() {
        val longFence = tick.repeat(4)
        val shortFence = tick.repeat(3)
        val source = longFence + "text\n" + shortFence + "\nstill code"
        val validation = MarkdownStructureDiagnostics.validate(source)

        assertFalse(validation.isValid)
        assertEquals(longFence, validation.issues.single().delimiter)
    }

    @Test
    fun oversizedMarkdownIsRejectedWithoutMutation() {
        val source = "x".repeat(3 * 1024 * 1024 + 1)
        val validation = MarkdownStructureDiagnostics.validate(source)
        val repaired = MarkdownStructureDiagnostics.repair(source)

        assertFalse(validation.isValid)
        assertTrue(validation.errorMessage.orEmpty().contains("limit", ignoreCase = true))
        assertFalse(repaired.isSuccess)
        assertFalse(repaired.changed)
        assertEquals(source, repaired.text)
    }
}

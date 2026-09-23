package ais.tee.ui.screens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocbenchTextPrintPaginationTest {
    @Test
    fun pageStartsUseOnlyFullyFittingLines() {
        assertArrayEquals(
            intArrayOf(0, 2),
            docbenchPrintPageStartLines(
                lineTops = intArrayOf(0, 10, 20, 30, 40),
                pageHeight = 25
            )
        )
    }

    @Test
    fun aLineTallerThanThePageStillMakesForwardProgress() {
        assertArrayEquals(
            intArrayOf(0, 1, 2),
            docbenchPrintPageStartLines(
                lineTops = intArrayOf(0, 30, 40, 70),
                pageHeight = 20
            )
        )
    }

    @Test
    fun exactPageBoundaryDoesNotCreateAnEmptyPage() {
        assertArrayEquals(
            intArrayOf(0, 2),
            docbenchPrintPageStartLines(
                lineTops = intArrayOf(0, 10, 20, 30, 40),
                pageHeight = 20
            )
        )
    }

    @Test
    fun requestedPagesIgnoreRangesOutsideTheDocument() {
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            docbenchRequestedPageIndexes(
                pageCount = 5,
                ranges = listOf(0..1, 3..10)
            )
        )
        assertArrayEquals(
            intArrayOf(),
            docbenchRequestedPageIndexes(
                pageCount = 3,
                ranges = listOf(5..8)
            )
        )
    }

    @Test
    fun invalidPageGeometryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            docbenchPrintPageStartLines(intArrayOf(0, 10), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            docbenchPrintPageStartLines(intArrayOf(0, 10, 9), 20)
        }
    }
}

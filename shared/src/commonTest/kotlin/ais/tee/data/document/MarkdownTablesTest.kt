package ais.tee.data.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTablesTest {
    @Test
    fun parsesHeaderAlignmentsAndRows() {
        val tables = extractMarkdownTables(
            """
            Intro
            | Name | Qty | Note |
            |:-----|----:|:----:|
            | Apple | 3 | red |
            | Pear | 10 | |

            After
            """.trimIndent()
        )
        assertEquals(1, tables.size)
        val table = tables.single()
        assertEquals(1, table.startLine)
        assertEquals(4, table.endLine)
        assertEquals(listOf("Name", "Qty", "Note"), table.header)
        assertEquals(
            listOf(MarkdownTableAlignment.LEFT, MarkdownTableAlignment.RIGHT, MarkdownTableAlignment.CENTER),
            table.alignments,
        )
        assertEquals(listOf(listOf("Apple", "3", "red"), listOf("Pear", "10", "")), table.rows)
    }

    @Test
    fun outerPipesAreOptionalAndRowsArePaddedOrTruncated() {
        val table = extractMarkdownTables("a | b\n--- | ---\n1\n2 | 3 | 4").single()
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", ""), listOf("2", "3")), table.rows)
    }

    @Test
    fun escapedPipesStayInsideCells() {
        val table = extractMarkdownTables("| expr |\n| --- |\n| a \\| b |").single()
        assertEquals(listOf(listOf("a | b")), table.rows)
    }

    @Test
    fun mismatchedDelimiterIsNotATable() {
        assertTrue(extractMarkdownTables("| a | b |\n| --- |\n| 1 | 2 |").isEmpty())
        assertTrue(extractMarkdownTables("| a | b |\n| x | y |").isEmpty())
    }

    @Test
    fun tablesInsideFencedCodeAreIgnored() {
        val text = "```\n| a | b |\n| - | - |\n```\n~~~md\n| c |\n| - |\n~~~\n| d |\n| - |\n| 1 |"
        val tables = extractMarkdownTables(text)
        assertEquals(1, tables.size)
        assertEquals(listOf("d"), tables.single().header)
    }

    @Test
    fun pipeLessLineStillBelongsToTheTableAsInGfm() {
        val table = extractMarkdownTables("| a | b |\n| - | - |\n| 1 | 2 |\nloose").single()
        assertEquals(listOf(listOf("1", "2"), listOf("loose", "")), table.rows)
    }

    @Test
    fun headingOrQuoteEndsTable() {
        val table = extractMarkdownTables("| a |\n| - |\n| 1 |\n# Next\n> quote").single()
        assertEquals(2, table.endLine)
        assertEquals(listOf(listOf("1")), table.rows)
    }

    @Test
    fun blankLineEndsTableAndSecondTableIsFound() {
        val tables = extractMarkdownTables("| a |\n| - |\n| 1 |\n\n| b |\n| - |\n| 2 |")
        assertEquals(listOf(listOf("a"), listOf("b")), tables.map { it.header })
        assertEquals(2, tables[0].endLine)
    }

    @Test
    fun crlfInputIsHandled() {
        val table = extractMarkdownTables("| a | b |\r\n| - | - |\r\n| 1 | 2 |\r\n").single()
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun csvQuotesSpecialFieldsAndUsesCrlf() {
        val table = MarkdownTable(
            startLine = 0,
            endLine = 2,
            header = listOf("name", "note"),
            alignments = listOf(MarkdownTableAlignment.NONE, MarkdownTableAlignment.NONE),
            rows = listOf(listOf("a,b", "say \"hi\""), listOf(" pad ", "plain")),
        )
        assertEquals(
            "name,note\r\n\"a,b\",\"say \"\"hi\"\"\"\r\n\" pad \",plain\r\n",
            table.toCsv(),
        )
    }

    @Test
    fun csvNeutralizesFormulasButKeepsNumbers() {
        val table = MarkdownTable(
            startLine = 0,
            endLine = 1,
            header = listOf("v"),
            alignments = listOf(MarkdownTableAlignment.NONE),
            rows = listOf(listOf("=SUM(A1)"), listOf("-5"), listOf("+1.5e3"), listOf("@cmd"), listOf("-x")),
        )
        assertEquals("v\r\n'=SUM(A1)\r\n-5\r\n+1.5e3\r\n'@cmd\r\n'-x\r\n", table.toCsv())
        assertTrue(table.toCsv(neutralizeFormulas = false).contains("\r\n=SUM(A1)\r\n"))
    }
}

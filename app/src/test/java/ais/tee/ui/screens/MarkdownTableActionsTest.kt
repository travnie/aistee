package ais.tee.ui.screens

import ais.tee.data.model.AiProvider
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ModelChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableActionsTest {
    private val twoTables = """
        | Name | Score |
        | :--- | ----: |
        | Ada | 3 |

        Between tables.

        | A | B |
        | - | - |
        | =SUM(1,2) | x |
    """.trimIndent()

    private val response = ModelChatMessage(
        id = "a1",
        sender = CHAT_ROLE_ASSISTANT,
        provider = AiProvider.CHATGPT,
        text = twoTables,
    )

    @Test
    fun completedResponseOffersOneActionPerTable() {
        val tables = markdownTablesForMessageActions(response)

        assertEquals(2, tables.size)
        assertEquals("View table 1 (2×1)", markdownTableActionLabel(tables[0], 0, tables.size))
        assertEquals("View table 2 (2×1)", markdownTableActionLabel(tables[1], 1, tables.size))
        assertEquals("View table", markdownTableActionLabel(tables[0], 0, 1))
    }

    @Test
    fun plainTextPartialErrorAndUserMessagesOfferNoTableAction() {
        assertTrue(markdownTablesForMessageActions(response.copy(text = "No table here.")).isEmpty())
        assertTrue(markdownTablesForMessageActions(response.copy(isPartial = true)).isEmpty())
        assertTrue(markdownTablesForMessageActions(response.copy(isError = true)).isEmpty())
        assertTrue(markdownTablesForMessageActions(response.copy(sender = CHAT_ROLE_USER)).isEmpty())
    }

    @Test
    fun csvExportAlwaysNeutralizesFormulas() {
        val csv = markdownTableCsv(markdownTablesForMessageActions(response)[1])

        assertEquals("A,B\r\n\"'=SUM(1,2)\",x\r\n", csv)
    }
}

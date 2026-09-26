package ais.tee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ais.tee.data.document.MarkdownTable
import ais.tee.data.document.MarkdownTableAlignment
import ais.tee.data.document.extractMarkdownTables
import ais.tee.data.document.toCsv
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.isCompletedAssistantResponse

internal const val MARKDOWN_TABLE_CSV_EXPORT_NAME = "aistee-table.csv"

/** Tables offered by the message actions; only completed assistant responses qualify. */
internal fun markdownTablesForMessageActions(message: ModelChatMessage): List<MarkdownTable> =
    if (message.isCompletedAssistantResponse()) extractMarkdownTables(message.text) else emptyList()

internal fun markdownTableActionLabel(table: MarkdownTable, index: Int, tableCount: Int): String =
    if (tableCount == 1) {
        "View table"
    } else {
        "View table ${index + 1} (${table.columnCount}×${table.rows.size})"
    }

/** CSV for export and copy; formulas are always neutralized. */
internal fun markdownTableCsv(table: MarkdownTable): String = table.toCsv()

private const val TABLE_CELL_CHAR_WIDTH_DP = 8
private val MIN_TABLE_COLUMN_WIDTH = 72.dp
private val MAX_TABLE_COLUMN_WIDTH = 280.dp

internal fun markdownTableColumnWidths(table: MarkdownTable): List<Dp> =
    List(table.columnCount) { column ->
        val longest = (listOf(table.header) + table.rows).maxOf { row -> row.getOrElse(column) { "" }.length }
        (longest * TABLE_CELL_CHAR_WIDTH_DP + 24).dp.coerceIn(MIN_TABLE_COLUMN_WIDTH, MAX_TABLE_COLUMN_WIDTH)
    }

private fun MarkdownTableAlignment.toTextAlign(): TextAlign = when (this) {
    MarkdownTableAlignment.LEFT, MarkdownTableAlignment.NONE -> TextAlign.Start
    MarkdownTableAlignment.CENTER -> TextAlign.Center
    MarkdownTableAlignment.RIGHT -> TextAlign.End
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownTableScreen(
    table: MarkdownTable,
    title: String,
    onDismiss: () -> Unit,
    onCopyCsv: (String) -> Unit,
    onExportCsv: (String) -> Unit,
    onSaveToLibrary: ((String) -> Unit)?,
) {
    val widths = remember(table) { markdownTableColumnWidths(table) }
    val horizontalScroll = rememberScrollState()
    var showActions by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("markdown_table_screen"),
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, modifier = Modifier.testTag("btn_markdown_table_close")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close table")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showActions = true },
                            modifier = Modifier.testTag("btn_markdown_table_actions"),
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Table actions")
                        }
                        DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy as CSV") },
                                onClick = {
                                    showActions = false
                                    onCopyCsv(markdownTableCsv(table))
                                },
                                modifier = Modifier.testTag("btn_markdown_table_copy_csv"),
                            )
                            DropdownMenuItem(
                                text = { Text("Export CSV") },
                                onClick = {
                                    showActions = false
                                    onExportCsv(markdownTableCsv(table))
                                },
                                modifier = Modifier.testTag("btn_markdown_table_export_csv"),
                            )
                            if (onSaveToLibrary != null) {
                                DropdownMenuItem(
                                    text = { Text("Save to Library") },
                                    onClick = {
                                        showActions = false
                                        onSaveToLibrary(markdownTableCsv(table))
                                    },
                                    modifier = Modifier.testTag("btn_markdown_table_save_library"),
                                )
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .horizontalScroll(horizontalScroll),
            ) {
                SelectionContainer {
                    Column(modifier = Modifier.width(widths.fold(0.dp) { total, width -> total + width })) {
                        MarkdownTableRow(
                            cells = table.header,
                            widths = widths,
                            alignments = table.alignments,
                            header = true,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .testTag("markdown_table_header"),
                        )
                        HorizontalDivider()
                        LazyColumn(modifier = Modifier.fillMaxHeight().testTag("markdown_table_rows")) {
                            itemsIndexed(table.rows) { index, row ->
                                MarkdownTableRow(
                                    cells = row,
                                    widths = widths,
                                    alignments = table.alignments,
                                    header = false,
                                    modifier = if (index % 2 == 1) {
                                        Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    widths: List<Dp>,
    alignments: List<MarkdownTableAlignment>,
    header: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        widths.forEachIndexed { column, width ->
            Text(
                text = cells.getOrElse(column) { "" },
                textAlign = alignments.getOrElse(column) { MarkdownTableAlignment.NONE }.toTextAlign(),
                fontWeight = if (header) FontWeight.Bold else null,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .width(width)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

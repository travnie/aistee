package ais.tee.data.document

/** Column alignment declared by a GFM delimiter row. */
enum class MarkdownTableAlignment { NONE, LEFT, CENTER, RIGHT }

/**
 * One GFM pipe table found in Markdown text. Line numbers are 0-based and inclusive; cells keep
 * their inline Markdown as written, with escaped pipes (`\|`) unescaped.
 */
data class MarkdownTable(
    val startLine: Int,
    val endLine: Int,
    val header: List<String>,
    val alignments: List<MarkdownTableAlignment>,
    val rows: List<List<String>>,
) {
    val columnCount: Int get() = header.size

    override fun toString(): String =
        "MarkdownTable(lines=$startLine..$endLine, columns=$columnCount, rows=${rows.size})"
}

/**
 * Finds GFM pipe tables outside fenced code blocks. A table is a header row, a matching delimiter
 * row and the following lines up to a blank line or the start of another block (fence, ATX heading
 * or blockquote). As in GFM, body rows need no pipe; short rows are padded and long rows are
 * truncated to the header width.
 */
fun extractMarkdownTables(text: String): List<MarkdownTable> {
    val lines = text.lines().map { it.removeSuffix("\r") }
    val tables = mutableListOf<MarkdownTable>()
    var fence: String? = null
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val fenceMarker = fenceMarker(line)
        if (fence != null) {
            if (fenceMarker != null && fenceMarker.first() == fence.first() && fenceMarker.length >= fence.length &&
                line.trim().length == fenceMarker.length
            ) {
                fence = null
            }
            index++
            continue
        }
        if (fenceMarker != null) {
            fence = fenceMarker
            index++
            continue
        }
        val table = if (index + 1 < lines.size) tableAt(lines, index) else null
        if (table != null) {
            tables += table
            index = table.endLine + 1
        } else {
            index++
        }
    }
    return tables
}

/**
 * RFC 4180 CSV with CRLF line endings. With [neutralizeFormulas], cells that a spreadsheet would
 * evaluate as a formula (leading `=`, `+`, `-`, `@`, tab or CR, except plain numbers) are prefixed
 * with `'` so opening an exported table cannot run formulas.
 */
fun MarkdownTable.toCsv(neutralizeFormulas: Boolean = true): String =
    (listOf(header) + rows).joinToString(separator = "\r\n", postfix = "\r\n") { row ->
        row.joinToString(",") { cell -> csvField(if (neutralizeFormulas) neutralizeFormula(cell) else cell) }
    }

private val DELIMITER_CELL = Regex("^:?-+:?$")
private val PLAIN_NUMBER = Regex("^[+-]?(\\d+([.,]\\d+)?|[.,]\\d+)([eE][+-]?\\d+)?$")
private const val FORMULA_TRIGGERS = "=+-@\t\r"
private val ATX_HEADING = Regex("^#{1,6}(\\s|$)")

private fun tableAt(lines: List<String>, headerIndex: Int): MarkdownTable? {
    val headerLine = lines[headerIndex]
    val delimiterLine = lines[headerIndex + 1]
    if (!isTableCandidate(headerLine) || !isTableCandidate(delimiterLine)) return null
    val header = splitRow(headerLine)
    val delimiter = splitRow(delimiterLine)
    if (header.isEmpty() || delimiter.size != header.size) return null
    if (delimiter.any { !DELIMITER_CELL.matches(it) }) return null
    val alignments = delimiter.map { cell ->
        val left = cell.startsWith(':')
        val right = cell.endsWith(':')
        when {
            left && right -> MarkdownTableAlignment.CENTER
            left -> MarkdownTableAlignment.LEFT
            right -> MarkdownTableAlignment.RIGHT
            else -> MarkdownTableAlignment.NONE
        }
    }
    val rows = mutableListOf<List<String>>()
    var index = headerIndex + 2
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank() || fenceMarker(line) != null || startsOtherBlock(line)) break
        val cells = splitRow(line)
        rows += List(header.size) { column -> cells.getOrElse(column) { "" } }
        index++
    }
    return MarkdownTable(
        startLine = headerIndex,
        endLine = index - 1,
        header = header,
        alignments = alignments,
        rows = rows,
    )
}

private fun startsOtherBlock(line: String): Boolean {
    val trimmed = line.trimStart(' ')
    if (line.length - trimmed.length > 3) return false
    return trimmed.startsWith(">") || ATX_HEADING.containsMatchIn(trimmed)
}

/** At most three spaces of indentation and at least one unescaped pipe. */
private fun isTableCandidate(line: String): Boolean {
    val indent = line.length - line.trimStart(' ').length
    if (indent > 3 || line.startsWith("\t")) return false
    var escaped = false
    for (char in line) {
        if (escaped) {
            escaped = false
        } else if (char == '\\') {
            escaped = true
        } else if (char == '|') {
            return true
        }
    }
    return false
}

private fun splitRow(line: String): List<String> {
    var content = line.trim()
    if (content.startsWith("|")) content = content.substring(1)
    if (content.endsWith("|") && !content.endsWith("\\|")) content = content.dropLast(1)
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var index = 0
    while (index < content.length) {
        val char = content[index]
        if (char == '\\' && index + 1 < content.length && content[index + 1] == '|') {
            current.append('|')
            index += 2
            continue
        }
        if (char == '|') {
            cells += current.toString().trim()
            current.clear()
        } else {
            current.append(char)
        }
        index++
    }
    cells += current.toString().trim()
    return cells
}

private fun fenceMarker(line: String): String? {
    val indent = line.length - line.trimStart(' ').length
    if (indent > 3) return null
    val trimmed = line.trimStart(' ')
    val marker = trimmed.takeWhile { it == '`' }.ifEmpty { trimmed.takeWhile { it == '~' } }
    if (marker.length < 3) return null
    if (marker.first() == '`' && trimmed.substring(marker.length).contains('`')) return null
    return marker
}

private fun neutralizeFormula(cell: String): String =
    if (cell.isNotEmpty() && cell.first() in FORMULA_TRIGGERS && !PLAIN_NUMBER.matches(cell)) "'$cell" else cell

private fun csvField(value: String): String {
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
        value.startsWith(' ') || value.endsWith(' ')
    return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
}

package ais.tee.data.document

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * One styled run of inline text. [link] is set only for http(s) destinations. When [math] is set,
 * [text] is the TeX source of an inline expression (see [chatMathRuns]).
 */
data class ChatMarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strikethrough: Boolean = false,
    val link: String? = null,
    val math: Boolean = false,
)

/** Display model for chat Markdown. Rendering never executes HTML; raw HTML stays literal text. */
sealed interface ChatMarkdownBlock {
    data class Heading(val level: Int, val spans: List<ChatMarkdownSpan>) : ChatMarkdownBlock
    data class Paragraph(val spans: List<ChatMarkdownSpan>) : ChatMarkdownBlock
    data class CodeBlock(val language: String?, val code: String) : ChatMarkdownBlock
    data class Quote(val blocks: List<ChatMarkdownBlock>) : ChatMarkdownBlock
    data class ListBlock(
        val ordered: Boolean,
        val startNumber: Int,
        val items: List<List<ChatMarkdownBlock>>,
    ) : ChatMarkdownBlock
    data class Table(val table: MarkdownTable) : ChatMarkdownBlock
    /** Display math; [tex] is the source without delimiters. */
    data class Math(val tex: String) : ChatMarkdownBlock
    data object Rule : ChatMarkdownBlock
}

/** Larger messages are shown as plain text rather than parsed on every bind. */
const val MAX_CHAT_MARKDOWN_CHARS: Int = 200_000

private const val MAX_BLOCK_DEPTH = 12
private val ESCAPABLE = Regex("\\\\([!-/:-@\\[-`{-~])")
private val LIST_NUMBER = Regex("^\\s*(\\d{1,9})")

/**
 * Parses GFM into [ChatMarkdownBlock]s, or returns null when [text] is too large or the parser
 * fails, so callers can fall back to plain text. Math (`$…$`, `\(…\)`, `$$…$$`, `\[…\]`) is
 * taken out before parsing so Markdown emphasis and escapes cannot break it; in code and tables it
 * stays as written.
 */
fun parseChatMarkdown(text: String): List<ChatMarkdownBlock>? {
    if (text.length > MAX_CHAT_MARKDOWN_CHARS) return null
    val math = runCatching { extractChatMath(text) }.getOrElse { ChatMathExtraction(text, emptyList()) }
    val root = runCatching {
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(math.text as CharSequence)
    }.getOrNull() ?: return null
    return runCatching { ChatMarkdownReader(math.text, math).blocks(root.children, depth = 0) }.getOrNull()
}

private class ChatMarkdownReader(private val source: String, private val math: ChatMathExtraction) {
    /** Literal source, with any math put back as written. */
    private fun ASTNode.text(): String = math.restore(raw())
    private fun ASTNode.raw(): String = source.substring(startOffset, endOffset)

    fun blocks(nodes: List<ASTNode>, depth: Int): List<ChatMarkdownBlock> {
        if (depth > MAX_BLOCK_DEPTH) {
            val text = nodes.joinToString("") { it.text() }.trim()
            return if (text.isEmpty()) emptyList() else listOf(ChatMarkdownBlock.Paragraph(listOf(ChatMarkdownSpan(text))))
        }
        return nodes.flatMap { node ->
            if (node.type == MarkdownElementTypes.PARAGRAPH) paragraphs(node.children) else listOfNotNull(block(node, depth))
        }
    }

    private fun block(node: ASTNode, depth: Int): ChatMarkdownBlock? = when (val type = node.type) {
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> heading(
            level = ATX_LEVELS.getValue(type),
            content = node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT },
        )
        MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 -> heading(
            level = if (type == MarkdownElementTypes.SETEXT_1) 1 else 2,
            content = node.children.firstOrNull { it.type == MarkdownTokenTypes.SETEXT_CONTENT },
        )
        MarkdownElementTypes.CODE_FENCE -> codeFence(node)
        MarkdownElementTypes.CODE_BLOCK -> ChatMarkdownBlock.CodeBlock(
            language = null,
            code = node.text().lines().joinToString("\n") { it.removeIndent(4) }.trimEnd('\n'),
        )
        MarkdownElementTypes.BLOCK_QUOTE -> ChatMarkdownBlock.Quote(
            blocks(node.children.filter { it.type != MarkdownTokenTypes.BLOCK_QUOTE }, depth + 1)
        )
        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> list(node, depth)
        GFMElementTypes.TABLE -> extractMarkdownTables(node.text()).firstOrNull()
            ?.let(ChatMarkdownBlock::Table)
            ?: plain(node)
        MarkdownTokenTypes.HORIZONTAL_RULE -> ChatMarkdownBlock.Rule
        MarkdownElementTypes.LINK_DEFINITION,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.WHITE_SPACE -> null
        else -> plain(node)
    }

    private fun plain(node: ASTNode): ChatMarkdownBlock? =
        node.text().trim().takeIf { it.isNotEmpty() }
            ?.let { ChatMarkdownBlock.Paragraph(listOf(ChatMarkdownSpan(it))) }

    /** A paragraph, split around any display math it contains. */
    private fun paragraphs(children: List<ASTNode>): List<ChatMarkdownBlock> {
        val result = mutableListOf<ChatMarkdownBlock>()
        var current = mutableListOf<ChatMarkdownSpan>()
        fun flush() {
            current.trimmed().takeIf { spans -> spans.any { it.text.isNotBlank() } }
                ?.let { result += ChatMarkdownBlock.Paragraph(it) }
            current = mutableListOf()
        }
        styledSpans(children).forEach { span ->
            if (span.code) {
                current += span.copy(text = math.restore(span.text))
                return@forEach
            }
            math.split(span.text).forEach { (text, segment) ->
                when {
                    segment == null -> current += span.copy(text = text)
                    segment.display -> {
                        flush()
                        result += ChatMarkdownBlock.Math(segment.tex)
                    }
                    else -> current += span.copy(text = segment.tex, math = true)
                }
            }
        }
        flush()
        return result
    }

    private fun heading(level: Int, content: ASTNode?): ChatMarkdownBlock? {
        val spans = content?.let { inline(it.children).trimmed() }.orEmpty()
        return if (spans.isEmpty()) null else ChatMarkdownBlock.Heading(level, spans)
    }

    private fun codeFence(node: ASTNode): ChatMarkdownBlock {
        val language = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val bodyStart = node.children.indexOfFirst { it.type == MarkdownTokenTypes.EOL }
        val code = if (bodyStart < 0) {
            ""
        } else {
            node.children.drop(bodyStart + 1)
                .takeWhile { it.type != MarkdownTokenTypes.CODE_FENCE_END }
                .joinToString("") { it.text() }
                .removeSuffix("\n")
        }
        return ChatMarkdownBlock.CodeBlock(language, code)
    }

    private fun list(node: ASTNode, depth: Int): ChatMarkdownBlock {
        val ordered = node.type == MarkdownElementTypes.ORDERED_LIST
        val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val startNumber = items.firstOrNull()
            ?.children?.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
            ?.let { LIST_NUMBER.find(it.text())?.groupValues?.get(1)?.toIntOrNull() }
            ?: 1
        return ChatMarkdownBlock.ListBlock(
            ordered = ordered,
            startNumber = startNumber,
            items = items.map { item ->
                val content = item.children.filter {
                    it.type != MarkdownTokenTypes.LIST_BULLET && it.type != MarkdownTokenTypes.LIST_NUMBER
                }
                val checkBox = content.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
                val itemBlocks = blocks(content.filter { it.type != GFMTokenTypes.CHECK_BOX }, depth + 1)
                if (checkBox == null) itemBlocks else itemBlocks.withCheckBox(checkBox.text().contains('x', ignoreCase = true))
            },
        )
    }

    private fun List<ChatMarkdownBlock>.withCheckBox(checked: Boolean): List<ChatMarkdownBlock> {
        val marker = ChatMarkdownSpan(if (checked) "☑ " else "☐ ")
        val first = firstOrNull()
        return if (first is ChatMarkdownBlock.Paragraph) {
            listOf(first.copy(spans = listOf(marker) + first.spans)) + drop(1)
        } else {
            listOf(ChatMarkdownBlock.Paragraph(listOf(marker))) + this
        }
    }

    /** Inline spans; display math is shown inline here (headings). */
    fun inline(nodes: List<ASTNode>): List<ChatMarkdownSpan> = styledSpans(nodes).flatMap { span ->
        if (span.code) {
            listOf(span.copy(text = math.restore(span.text)))
        } else {
            math.split(span.text).map { (text, segment) ->
                if (segment == null) span.copy(text = text) else span.copy(text = segment.tex, math = true)
            }
        }
    }

    /** Styled spans whose text may still hold math placeholders. */
    private fun styledSpans(nodes: List<ASTNode>): List<ChatMarkdownSpan> {
        val spans = mutableListOf<ChatMarkdownSpan>()
        nodes.forEach { appendInline(it, ChatMarkdownSpan(""), spans) }
        return spans.merged()
    }

    private fun appendInline(node: ASTNode, style: ChatMarkdownSpan, out: MutableList<ChatMarkdownSpan>) {
        fun emit(text: String, spanStyle: ChatMarkdownSpan = style) {
            if (text.isNotEmpty()) out += spanStyle.copy(text = text)
        }
        when (node.type) {
            MarkdownElementTypes.EMPH -> node.children.forEach { appendInline(it, style.copy(italic = true), out) }
            MarkdownElementTypes.STRONG -> node.children.forEach { appendInline(it, style.copy(bold = true), out) }
            GFMElementTypes.STRIKETHROUGH -> node.children.forEach { appendInline(it, style.copy(strikethrough = true), out) }
            MarkdownElementTypes.CODE_SPAN -> emit(
                node.children
                    .filter { it.type != MarkdownTokenTypes.BACKTICK }
                    .joinToString("") { it.text() }
                    .replace('\n', ' ')
                    .let { if (it.length > 2 && it.startsWith(' ') && it.endsWith(' ')) it.substring(1, it.length - 1) else it },
                style.copy(code = true),
            )
            MarkdownElementTypes.INLINE_LINK,
            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                val destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    ?.text()?.trim()?.removeSurrounding("<", ">")
                val label = node.children.firstOrNull {
                    it.type == MarkdownElementTypes.LINK_TEXT || it.type == MarkdownElementTypes.LINK_LABEL
                }
                val linkStyle = style.copy(link = destination?.takeIf(::isWebLink))
                label?.children
                    ?.filter { it.type != MarkdownTokenTypes.LBRACKET && it.type != MarkdownTokenTypes.RBRACKET }
                    ?.forEach { appendInline(it, linkStyle, out) }
                    ?: emit(node.text())
            }
            MarkdownElementTypes.AUTOLINK -> {
                val url = node.children.firstOrNull {
                    it.type == MarkdownTokenTypes.AUTOLINK || it.type == MarkdownTokenTypes.EMAIL_AUTOLINK
                }?.text() ?: node.text().removeSurrounding("<", ">")
                emit(url, style.copy(link = url.takeIf(::isWebLink)))
            }
            GFMTokenTypes.GFM_AUTOLINK -> node.text().let { url -> emit(url, style.copy(link = url.takeIf(::isWebLink))) }
            MarkdownElementTypes.IMAGE -> {
                val alt = node.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK }
                    ?.children?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                    ?.text()?.removeSurrounding("[", "]")
                emit("[image${alt?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}]")
            }
            MarkdownTokenTypes.EMPH, MarkdownTokenTypes.BACKTICK, GFMTokenTypes.TILDE -> Unit
            MarkdownTokenTypes.HARD_LINE_BREAK -> emit("\n")
            // Chat answers use single newlines as line breaks, so soft breaks stay line breaks.
            MarkdownTokenTypes.EOL -> emit("\n")
            MarkdownTokenTypes.TEXT -> emit(node.raw().replace(ESCAPABLE, "$1"))
            else -> if (node.children.isEmpty()) {
                emit(node.raw())
            } else {
                node.children.forEach { appendInline(it, style, out) }
            }
        }
    }

    private companion object {
        val ATX_LEVELS: Map<IElementType, Int> = mapOf(
            MarkdownElementTypes.ATX_1 to 1,
            MarkdownElementTypes.ATX_2 to 2,
            MarkdownElementTypes.ATX_3 to 3,
            MarkdownElementTypes.ATX_4 to 4,
            MarkdownElementTypes.ATX_5 to 5,
            MarkdownElementTypes.ATX_6 to 6,
        )
    }
}

private fun isWebLink(value: String): Boolean {
    val lower = value.lowercase()
    return (lower.startsWith("https://") || lower.startsWith("http://")) && value.none { it.isWhitespace() }
}

private fun String.removeIndent(columns: Int): String {
    var removed = 0
    while (removed < columns && removed < length && this[removed] == ' ') removed++
    return substring(removed)
}

private fun List<ChatMarkdownSpan>.merged(): List<ChatMarkdownSpan> {
    val result = mutableListOf<ChatMarkdownSpan>()
    forEach { span ->
        val last = result.lastOrNull()
        if (last != null && last.copy(text = "") == span.copy(text = "")) {
            result[result.lastIndex] = last.copy(text = last.text + span.text)
        } else {
            result += span
        }
    }
    return result
}

private fun List<ChatMarkdownSpan>.trimmed(): List<ChatMarkdownSpan> {
    if (isEmpty()) return this
    val result = toMutableList()
    result[0] = result[0].copy(text = result[0].text.trimStart())
    result[result.lastIndex] = result[result.lastIndex].copy(text = result[result.lastIndex].text.trimEnd())
    return result.filter { it.text.isNotEmpty() }
}

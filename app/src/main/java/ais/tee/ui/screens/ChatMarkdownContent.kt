package ais.tee.ui.screens

import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import ais.tee.data.document.ChatMarkdownBlock
import ais.tee.data.document.ChatMarkdownSpan
import ais.tee.data.document.ChatMathRun
import ais.tee.data.document.ChatMathStyle
import ais.tee.data.document.MarkdownTable
import ais.tee.data.document.chatMathRuns
import ais.tee.data.document.parseChatMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CHAT_MARKDOWN_CACHE_ENTRIES = 128

/**
 * A parsed answer with every math expression already converted, so composition never runs the
 * TeX converter. [math] maps TeX source to its runs, or to null when it is shown as source.
 */
internal class ChatMarkdownRender(val blocks: List<ChatMarkdownBlock>, val math: Map<String, List<ChatMathRun>?>)

// Parsed bubbles survive LazyColumn recycling, so scrolling a long chat does not re-parse.
private val chatMarkdownCache = LruCache<String, ChatMarkdownRender>(CHAT_MARKDOWN_CACHE_ENTRIES)

internal fun chatMarkdownCacheKey(messageId: String, text: String): String =
    "$messageId:${text.length}:${text.hashCode()}"

/** Parses and converts math off the main thread; null until parsed or when the text should stay plain. */
@Composable
internal fun rememberChatMarkdown(messageId: String, text: String): ChatMarkdownRender? {
    val key = remember(messageId, text) { chatMarkdownCacheKey(messageId, text) }
    val render by produceState(initialValue = chatMarkdownCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.Default) { parseChatMarkdown(text)?.let(::prepareChatMarkdown) }
                ?.also { parsed -> chatMarkdownCache.put(key, parsed) }
        }
    }
    return render
}

internal fun prepareChatMarkdown(blocks: List<ChatMarkdownBlock>): ChatMarkdownRender {
    val math = HashMap<String, List<ChatMathRun>?>()
    fun convert(tex: String) {
        if (tex !in math) math[tex] = chatMathRuns(tex)
    }
    fun visit(nodes: List<ChatMarkdownBlock>) {
        nodes.forEach { block ->
            when (block) {
                is ChatMarkdownBlock.Math -> convert(block.tex)
                is ChatMarkdownBlock.Paragraph -> block.spans.forEach { if (it.math) convert(it.text) }
                is ChatMarkdownBlock.Heading -> block.spans.forEach { if (it.math) convert(it.text) }
                is ChatMarkdownBlock.Quote -> visit(block.blocks)
                is ChatMarkdownBlock.ListBlock -> block.items.forEach(::visit)
                else -> Unit
            }
        }
    }
    visit(blocks)
    return ChatMarkdownRender(blocks, math)
}

internal fun chatMarkdownAnnotatedString(
    spans: List<ChatMarkdownSpan>,
    linkColor: Color,
    codeBackground: Color,
    math: Map<String, List<ChatMathRun>?> = emptyMap(),
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        if (span.math) {
            val link = span.link
            if (link != null) {
                val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                withLink(LinkAnnotation.Url(link, TextLinkStyles(style = linkStyle))) { appendMath(span.text, codeBackground, math) }
            } else {
                appendMath(span.text, codeBackground, math)
            }
            return@forEach
        }
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) codeBackground else Color.Unspecified,
            textDecoration = if (span.strikethrough) TextDecoration.LineThrough else null,
        )
        val link = span.link
        if (link != null) {
            val linkStyle = style.copy(color = linkColor, textDecoration = TextDecoration.Underline)
            withLink(LinkAnnotation.Url(link, TextLinkStyles(style = linkStyle))) { append(span.text) }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}

/** Converted math, or the TeX source in code style when it uses anything the converter does not know. */
internal fun AnnotatedString.Builder.appendMath(
    tex: String,
    codeBackground: Color,
    math: Map<String, List<ChatMathRun>?>,
) {
    val runs = if (tex in math) math[tex] else chatMathRuns(tex)
    if (runs == null) {
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(tex) }
        return
    }
    runs.forEach { run -> withStyle(mathRunStyle(run)) { append(run.text) } }
}

private fun mathRunStyle(run: ChatMathRun): SpanStyle {
    val depth = run.scripts.length
    var shift = 0f
    run.scripts.forEachIndexed { level, script ->
        shift += (if (script == '^') 0.45f else -0.25f) * 0.75f.pow(level)
    }
    return SpanStyle(
        fontStyle = if (run.style == ChatMathStyle.ITALIC) FontStyle.Italic else FontStyle.Normal,
        fontWeight = if (run.style == ChatMathStyle.BOLD) FontWeight.Bold else null,
        fontSize = if (depth == 0) TextUnit.Unspecified else 0.75f.pow(depth).em,
        baselineShift = if (depth == 0) null else BaselineShift(shift),
    )
}

@Composable
internal fun ChatMarkdownContent(
    markdown: ChatMarkdownRender,
    color: Color,
    modifier: Modifier = Modifier,
    onCopyCode: ((String) -> Unit)? = null,
) {
    ChatMarkdownContent(markdown.blocks, color, modifier, markdown.math, onCopyCode)
}

@Composable
internal fun ChatMarkdownContent(
    blocks: List<ChatMarkdownBlock>,
    color: Color,
    modifier: Modifier = Modifier,
    math: Map<String, List<ChatMathRun>?> = emptyMap(),
    onCopyCode: ((String) -> Unit)? = null,
) {
    Column(
        modifier = modifier.testTag("chat_markdown_content"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block -> ChatMarkdownBlockView(block, color, math, onCopyCode) }
    }
}

@Composable
private fun ChatMarkdownBlockView(
    block: ChatMarkdownBlock,
    color: Color,
    math: Map<String, List<ChatMathRun>?>,
    onCopyCode: ((String) -> Unit)?,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 21.sp)
    when (block) {
        is ChatMarkdownBlock.Paragraph -> Text(
            text = remember(block, linkColor, codeBackground) { chatMarkdownAnnotatedString(block.spans, linkColor, codeBackground, math) },
            style = bodyStyle,
        )
        is ChatMarkdownBlock.Heading -> Text(
            text = chatMarkdownAnnotatedString(block.spans, linkColor, codeBackground, math),
            style = headingStyle(block.level).copy(color = color),
            fontWeight = FontWeight.Bold,
        )
        is ChatMarkdownBlock.CodeBlock -> Surface(
            color = codeBackground,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                if (onCopyCode != null) {
                    // One-tap copy of the block's source, without the fences.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = block.language?.takeIf { it.isNotBlank() } ?: "code",
                            style = MaterialTheme.typography.labelSmall,
                            color = color.copy(alpha = 0.7f),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                        )
                        IconButton(
                            onClick = { onCopyCode(block.code) },
                            modifier = Modifier.testTag("btn_copy_code_block"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy code",
                                tint = color.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                Text(
                    text = block.code,
                    style = bodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
                    softWrap = false,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = if (onCopyCode != null) 0.dp else 10.dp),
                )
            }
        }
        is ChatMarkdownBlock.Quote -> Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(color.copy(alpha = 0.35f))
            )
            Column(
                modifier = Modifier.padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                block.blocks.forEach { ChatMarkdownBlockView(it, color.copy(alpha = 0.85f), math, onCopyCode) }
            }
        }
        is ChatMarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(
                        text = if (block.ordered) "${block.startNumber + index}." else "•",
                        style = bodyStyle,
                        modifier = Modifier.width(if (block.ordered) 28.dp else 18.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.forEach { ChatMarkdownBlockView(it, color, math, onCopyCode) }
                    }
                }
            }
        }
        is ChatMarkdownBlock.Table -> ChatMarkdownTable(block.table, color)
        // Centered when it fits, scrolls sideways when it does not.
        is ChatMarkdownBlock.Math -> Box(
            modifier = Modifier.fillMaxWidth().testTag("chat_markdown_math"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = remember(block, codeBackground) { buildAnnotatedString { appendMath(block.tex, codeBackground, math) } },
                style = bodyStyle.copy(fontSize = 17.sp, lineHeight = 26.sp, textAlign = TextAlign.Center),
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            )
        }
        ChatMarkdownBlock.Rule -> HorizontalDivider(color = color.copy(alpha = 0.25f))
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

@Composable
private fun ChatMarkdownTable(table: MarkdownTable, color: Color) {
    val widths = remember(table) { markdownTableColumnWidths(table) }
    Box(
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.2f), MaterialTheme.shapes.small)
            .horizontalScroll(rememberScrollState())
            .testTag("chat_markdown_table"),
    ) {
        Column {
            MarkdownTableRow(
                cells = table.header,
                widths = widths,
                alignments = table.alignments,
                header = true,
                modifier = Modifier.background(color.copy(alpha = 0.08f)),
            )
            table.rows.forEach { row ->
                HorizontalDivider(color = color.copy(alpha = 0.12f))
                MarkdownTableRow(cells = row, widths = widths, alignments = table.alignments, header = false)
            }
        }
    }
}

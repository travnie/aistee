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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ais.tee.data.document.ChatMarkdownBlock
import ais.tee.data.document.ChatMarkdownSpan
import ais.tee.data.document.MarkdownTable
import ais.tee.data.document.parseChatMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CHAT_MARKDOWN_CACHE_ENTRIES = 128

// Parsed bubbles survive LazyColumn recycling, so scrolling a long chat does not re-parse.
private val chatMarkdownCache = LruCache<String, List<ChatMarkdownBlock>>(CHAT_MARKDOWN_CACHE_ENTRIES)

internal fun chatMarkdownCacheKey(messageId: String, text: String): String =
    "$messageId:${text.length}:${text.hashCode()}"

/** Parses off the main thread; null until parsed or when the text should stay plain. */
@Composable
internal fun rememberChatMarkdown(messageId: String, text: String): List<ChatMarkdownBlock>? {
    val key = remember(messageId, text) { chatMarkdownCacheKey(messageId, text) }
    val blocks by produceState(initialValue = chatMarkdownCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.Default) { parseChatMarkdown(text) }
                ?.also { parsed -> chatMarkdownCache.put(key, parsed) }
        }
    }
    return blocks
}

internal fun chatMarkdownAnnotatedString(
    spans: List<ChatMarkdownSpan>,
    linkColor: Color,
    codeBackground: Color,
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
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

@Composable
internal fun ChatMarkdownContent(
    blocks: List<ChatMarkdownBlock>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.testTag("chat_markdown_content"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block -> ChatMarkdownBlockView(block, color) }
    }
}

@Composable
private fun ChatMarkdownBlockView(block: ChatMarkdownBlock, color: Color) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 21.sp)
    when (block) {
        is ChatMarkdownBlock.Paragraph -> Text(
            text = chatMarkdownAnnotatedString(block.spans, linkColor, codeBackground),
            style = bodyStyle,
        )
        is ChatMarkdownBlock.Heading -> Text(
            text = chatMarkdownAnnotatedString(block.spans, linkColor, codeBackground),
            style = headingStyle(block.level).copy(color = color),
            fontWeight = FontWeight.Bold,
        )
        is ChatMarkdownBlock.CodeBlock -> Surface(
            color = codeBackground,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = block.code,
                style = bodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
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
                block.blocks.forEach { ChatMarkdownBlockView(it, color.copy(alpha = 0.85f)) }
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
                        item.forEach { ChatMarkdownBlockView(it, color) }
                    }
                }
            }
        }
        is ChatMarkdownBlock.Table -> ChatMarkdownTable(block.table, color)
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

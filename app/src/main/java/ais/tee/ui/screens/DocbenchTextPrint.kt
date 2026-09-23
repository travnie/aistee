package ais.tee.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DOCBENCH_PRINT_JOB_NAME = "Aistee Docbench"
private const val DOCBENCH_PRINT_DOCUMENT_NAME = "docbench.txt"
private const val DOCBENCH_PRINT_TEXT_SIZE_PT = 10f
private const val MAX_DOCBENCH_PRINT_PAGES = 1_000

internal fun launchDocbenchTextPrint(context: Context, text: String): String {
    val activity = context.findActivity()
        ?: return "Printing requires an active Aistee window."
    val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        ?: return "Android printing is not available on this device."

    return try {
        printManager.print(
            DOCBENCH_PRINT_JOB_NAME,
            DocbenchTextPrintAdapter(activity, text),
            null
        )
        "Opened the Android print dialog."
    } catch (_: IllegalStateException) {
        "Could not open the Android print dialog."
    } catch (_: SecurityException) {
        "Android blocked access to the print service."
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}

private data class DocbenchPrintLayout(
    val attributes: PrintAttributes,
    val contentRect: Rect,
    val textLayout: StaticLayout,
    val pageStartLines: IntArray
) {
    val pageCount: Int
        get() = pageStartLines.size
}

private sealed interface DocbenchPrintWriteOutcome {
    data class Completed(val writtenRanges: Array<PageRange>) : DocbenchPrintWriteOutcome
    data object Cancelled : DocbenchPrintWriteOutcome
    data object Failed : DocbenchPrintWriteOutcome
}

internal class DocbenchTextPrintAdapter(
    private val context: Context,
    private val text: String
) : PrintDocumentAdapter() {
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var layout: DocbenchPrintLayout? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val attributes = newAttributes
        if (attributes == null) {
            callback.onLayoutFailed("Print attributes are unavailable.")
            return
        }

        layout = null
        workerScope.launch {
            val prepared = runCatching { prepareLayout(attributes) }
            withContext(Dispatchers.Main.immediate) {
                when {
                    cancellationSignal.isCanceled -> callback.onLayoutCancelled()
                    prepared.isFailure -> callback.onLayoutFailed(
                        "Could not lay out the text for printing."
                    )
                    else -> {
                        val completedLayout = requireNotNull(prepared.getOrNull())
                        layout = completedLayout
                        val info = PrintDocumentInfo.Builder(DOCBENCH_PRINT_DOCUMENT_NAME)
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(completedLayout.pageCount)
                            .build()
                        callback.onLayoutFinished(info, oldAttributes != newAttributes)
                    }
                }
            }
        }
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        val prepared = layout
        if (prepared == null) {
            callback.onWriteFailed("Print layout is unavailable.")
            return
        }
        val requestedPages = requestedPageIndexes(prepared.pageCount, pages)
        if (requestedPages.isEmpty()) {
            callback.onWriteFailed("No requested pages are in the document.")
            return
        }

        workerScope.launch {
            val outcome = writeRequestedPages(
                prepared = prepared,
                requestedPages = requestedPages,
                destination = destination,
                cancellationSignal = cancellationSignal
            )
            withContext(Dispatchers.Main.immediate) {
                when (outcome) {
                    is DocbenchPrintWriteOutcome.Completed ->
                        callback.onWriteFinished(outcome.writtenRanges)
                    DocbenchPrintWriteOutcome.Cancelled -> callback.onWriteCancelled()
                    DocbenchPrintWriteOutcome.Failed ->
                        callback.onWriteFailed("Could not render the print document.")
                }
            }
        }
    }

    override fun onFinish() {
        layout = null
        workerScope.cancel()
        super.onFinish()
    }

    private fun writeRequestedPages(
        prepared: DocbenchPrintLayout,
        requestedPages: IntArray,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal
    ): DocbenchPrintWriteOutcome {
        val document = PrintedPdfDocument(context, prepared.attributes)
        return try {
            ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                for (pageIndex in requestedPages) {
                    if (cancellationSignal.isCanceled) {
                        return DocbenchPrintWriteOutcome.Cancelled
                    }
                    val page = document.startPage(pageIndex)
                    try {
                        drawPage(page.canvas, prepared, pageIndex)
                    } finally {
                        document.finishPage(page)
                    }
                }
                if (cancellationSignal.isCanceled) {
                    return DocbenchPrintWriteOutcome.Cancelled
                }
                document.writeTo(output)
                if (cancellationSignal.isCanceled) {
                    DocbenchPrintWriteOutcome.Cancelled
                } else {
                    DocbenchPrintWriteOutcome.Completed(pageIndexesToRanges(requestedPages))
                }
            }
        } catch (_: IOException) {
            DocbenchPrintWriteOutcome.Failed
        } catch (_: RuntimeException) {
            DocbenchPrintWriteOutcome.Failed
        } finally {
            document.close()
        }
    }

    private fun prepareLayout(attributes: PrintAttributes): DocbenchPrintLayout {
        val document = PrintedPdfDocument(context, attributes)
        val contentRect = try {
            Rect(document.pageContentRect)
        } finally {
            document.close()
        }
        require(contentRect.width() > 0 && contentRect.height() > 0) {
            "The selected print layout has no printable area."
        }

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = DOCBENCH_PRINT_TEXT_SIZE_PT
            typeface = Typeface.MONOSPACE
        }
        val nominalLineHeight = ceil(paint.fontSpacing.toDouble()).toInt().coerceAtLeast(1)
        val nominalLinesPerPage = (contentRect.height() / nominalLineHeight).coerceAtLeast(1)
        val explicitLineCount = text.count { it == '\n' } + 1
        require(explicitLineCount <= nominalLinesPerPage * MAX_DOCBENCH_PRINT_PAGES) {
            "The document would exceed the supported print page limit."
        }

        val textLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentRect.width())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        val lineTops = IntArray(textLayout.lineCount + 1) { line ->
            if (line == textLayout.lineCount) textLayout.height else textLayout.getLineTop(line)
        }
        val pageStartLines = docbenchPrintPageStartLines(lineTops, contentRect.height())
        require(pageStartLines.size <= MAX_DOCBENCH_PRINT_PAGES) {
            "The document would exceed the supported print page limit."
        }
        return DocbenchPrintLayout(
            attributes = attributes,
            contentRect = contentRect,
            textLayout = textLayout,
            pageStartLines = pageStartLines
        )
    }

    private fun drawPage(
        canvas: android.graphics.Canvas,
        prepared: DocbenchPrintLayout,
        pageIndex: Int
    ) {
        val startLine = prepared.pageStartLines[pageIndex]
        val nextStartLine = prepared.pageStartLines.getOrNull(pageIndex + 1)
            ?: prepared.textLayout.lineCount
        val startTop = prepared.textLayout.getLineTop(startLine)
        val nextTop = if (nextStartLine == prepared.textLayout.lineCount) {
            prepared.textLayout.height
        } else {
            prepared.textLayout.getLineTop(nextStartLine)
        }
        val drawHeight = min(prepared.contentRect.height(), nextTop - startTop)

        canvas.save()
        try {
            canvas.clipRect(
                prepared.contentRect.left,
                prepared.contentRect.top,
                prepared.contentRect.right,
                prepared.contentRect.top + drawHeight
            )
            canvas.translate(
                prepared.contentRect.left.toFloat(),
                prepared.contentRect.top.toFloat() - startTop
            )
            prepared.textLayout.draw(canvas)
        } finally {
            canvas.restore()
        }
    }
}

internal fun docbenchPrintPageStartLines(
    lineTops: IntArray,
    pageHeight: Int
): IntArray {
    require(pageHeight > 0) { "Page height must be positive." }
    require(lineTops.size >= 2) { "At least one text line is required." }
    for (index in 1 until lineTops.size) {
        require(lineTops[index] >= lineTops[index - 1]) {
            "Line positions must be monotonic."
        }
    }

    val lineCount = lineTops.size - 1
    val starts = mutableListOf<Int>()
    var startLine = 0
    while (startLine < lineCount) {
        starts += startLine
        val pageBottom = lineTops[startLine] + pageHeight
        var nextLine = startLine + 1
        while (nextLine < lineCount && lineTops[nextLine + 1] <= pageBottom) {
            nextLine++
        }
        startLine = nextLine
    }
    return starts.toIntArray()
}

private fun requestedPageIndexes(
    pageCount: Int,
    ranges: Array<out PageRange>
): IntArray = docbenchRequestedPageIndexes(
    pageCount = pageCount,
    ranges = ranges.map { range -> range.start..range.end }
)

internal fun docbenchRequestedPageIndexes(
    pageCount: Int,
    ranges: List<IntRange>
): IntArray {
    if (pageCount <= 0) return IntArray(0)
    val requested = BooleanArray(pageCount)
    ranges.forEach { range ->
        if (range.first >= pageCount || range.last < 0) return@forEach
        val start = range.first.coerceAtLeast(0)
        val end = range.last.coerceAtMost(pageCount - 1)
        if (start <= end) {
            for (page in start..end) requested[page] = true
        }
    }
    return requested.indices.filter(requested::get).toIntArray()
}

private fun pageIndexesToRanges(pageIndexes: IntArray): Array<PageRange> {
    require(pageIndexes.isNotEmpty()) { "Written page ranges must not be empty." }
    val ranges = mutableListOf<PageRange>()
    var start = pageIndexes.first()
    var end = start
    for (index in 1 until pageIndexes.size) {
        val page = pageIndexes[index]
        if (page == end + 1) {
            end = page
        } else {
            ranges += PageRange(start, end)
            start = page
            end = page
        }
    }
    ranges += PageRange(start, end)
    return ranges.toTypedArray()
}

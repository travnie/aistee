package ais.tee.data.document

import org.intellij.markdown.CancellationToken
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownParsingException
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

enum class MarkdownStructureIssueKind {
    UNCLOSED_CODE_FENCE
}

data class MarkdownStructureIssue(
    val kind: MarkdownStructureIssueKind,
    val offset: Int,
    val line: Int,
    val column: Int,
    val delimiter: String,
    val repairable: Boolean,
    val message: String
)

data class MarkdownStructureValidationResult(
    val issues: List<MarkdownStructureIssue> = emptyList(),
    val errorMessage: String? = null
) {
    val isValid: Boolean
        get() = errorMessage == null && issues.isEmpty()
}

data class MarkdownStructureRepairResult(
    val text: String,
    val changed: Boolean,
    val repairedIssueCount: Int = 0,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean
        get() = errorMessage == null
}

/**
 * Portable Markdown structure checks backed by JetBrains' GFM parser.
 *
 * Markdown is intentionally permissive, so this does not invent a fake strict-validation layer.
 * It currently reports one structural condition with an unambiguous AST signal: an opened fenced
 * code block without CODE_FENCE_END. Automatic repair is limited to root-level fences with at most
 * three leading spaces; nested list/blockquote fences are reported but left untouched.
 */
object MarkdownStructureDiagnostics {
    private const val MAX_MARKDOWN_CHARS = 3 * 1024 * 1024

    fun validate(text: String): MarkdownStructureValidationResult {
        inputError(text)?.let { return MarkdownStructureValidationResult(errorMessage = it) }

        val tree = try {
            MarkdownParser(
                flavour = GFMFlavourDescriptor(),
                assertionsEnabled = true,
                cancellationToken = CancellationToken.NonCancellable
            ).buildMarkdownTreeFromString(text as CharSequence)
        } catch (error: MarkdownParsingException) {
            return MarkdownStructureValidationResult(
                errorMessage = error.message ?: "Could not parse Markdown structure."
            )
        }

        val issues = mutableListOf<MarkdownStructureIssue>()
        collectIssues(tree, text, issues)
        return MarkdownStructureValidationResult(issues = issues)
    }

    fun repair(text: String): MarkdownStructureRepairResult {
        val validation = validate(text)
        validation.errorMessage?.let { error ->
            return MarkdownStructureRepairResult(
                text = text,
                changed = false,
                errorMessage = error
            )
        }
        if (validation.issues.isEmpty()) {
            return MarkdownStructureRepairResult(text = text, changed = false)
        }

        val unsafe = validation.issues.firstOrNull { !it.repairable }
        if (unsafe != null) {
            return MarkdownStructureRepairResult(
                text = text,
                changed = false,
                errorMessage =
                    "Automatic repair skipped: an unclosed code fence is inside a nested Markdown container."
            )
        }

        val lineEnding = lineEndingForAppend(text)
        var repaired = text
        validation.issues.forEach { issue ->
            if (!repaired.endsWith("\n") && !repaired.endsWith("\r")) {
                repaired += lineEnding
            }
            repaired += issue.delimiter
        }

        val verification = validate(repaired)
        if (!verification.isValid) {
            return MarkdownStructureRepairResult(
                text = text,
                changed = false,
                errorMessage = "Automatic Markdown repair did not pass structure verification."
            )
        }

        return MarkdownStructureRepairResult(
            text = repaired,
            changed = repaired != text,
            repairedIssueCount = validation.issues.size
        )
    }

    private fun inputError(text: String): String? = when {
        text.length > MAX_MARKDOWN_CHARS ->
            "Markdown input exceeds the supported 3 Mi character limit."
        else -> null
    }

    private fun collectIssues(
        node: ASTNode,
        text: String,
        output: MutableList<MarkdownStructureIssue>
    ) {
        if (
            node.type == MarkdownElementTypes.CODE_FENCE &&
            node.children.none { it.type == MarkdownTokenTypes.CODE_FENCE_END }
        ) {
            openingFence(text, node)?.let { opening ->
                val location = lineAndColumn(text, opening.offset)
                output += MarkdownStructureIssue(
                    kind = MarkdownStructureIssueKind.UNCLOSED_CODE_FENCE,
                    offset = opening.offset,
                    line = location.first,
                    column = location.second,
                    delimiter = opening.delimiter,
                    repairable = opening.repairable,
                    message = if (opening.repairable) {
                        "Fenced code block opened with ${opening.delimiter} is not closed."
                    } else {
                        "Nested fenced code block opened with ${opening.delimiter} is not closed; automatic repair is disabled."
                    }
                )
            }
        }
        node.children.forEach { child -> collectIssues(child, text, output) }
    }

    private fun openingFence(text: String, node: ASTNode): FenceOpening? {
        val startNode = node.children.firstOrNull { it.type == MarkdownTokenTypes.CODE_FENCE_START }
            ?: return null
        val start = startNode.startOffset.coerceIn(0, text.length)
        val end = startNode.endOffset.coerceIn(start, text.length)
        val raw = text.substring(start, end)
        val match = fenceRun(raw) ?: return null
        val delimiterOffset = start + match.first
        val lineStart = previousLineStart(text, delimiterOffset)
        val prefix = text.substring(lineStart, delimiterOffset)
        val repairable = prefix.length <= 3 && prefix.all { it == ' ' }
        return FenceOpening(
            offset = delimiterOffset,
            delimiter = raw.substring(match.first, match.last + 1),
            repairable = repairable
        )
    }

    private fun fenceRun(raw: String): IntRange? {
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            val isFenceChar = char.code == 96 || char == '~'
            if (!isFenceChar) {
                index++
                continue
            }
            var end = index + 1
            while (end < raw.length && raw[end] == char) end++
            if (end - index >= 3) return index until end
            index = end
        }
        return null
    }

    private fun previousLineStart(text: String, offset: Int): Int {
        var index = (offset - 1).coerceAtMost(text.lastIndex)
        while (index >= 0) {
            if (text[index] == '\n' || text[index] == '\r') return index + 1
            index--
        }
        return 0
    }

    private fun lineAndColumn(text: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var column = 1
        var index = 0
        val stop = offset.coerceIn(0, text.length)
        while (index < stop) {
            when (text[index]) {
                '\r' -> {
                    line++
                    column = 1
                    index += if (index + 1 < stop && text[index + 1] == '\n') 2 else 1
                }
                '\n' -> {
                    line++
                    column = 1
                    index++
                }
                else -> {
                    column++
                    index++
                }
            }
        }
        return line to column
    }

    private fun lineEndingForAppend(text: String): String {
        var index = text.lastIndex
        while (index >= 0) {
            when (text[index]) {
                '\n' -> return if (index > 0 && text[index - 1] == '\r') "\r\n" else "\n"
                '\r' -> return "\r"
            }
            index--
        }
        return "\n"
    }

    private data class FenceOpening(
        val offset: Int,
        val delimiter: String,
        val repairable: Boolean
    )
}

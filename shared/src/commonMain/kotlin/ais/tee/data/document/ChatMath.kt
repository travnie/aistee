package ais.tee.data.document

/** One run of converted math text. [scripts] is the superscript/subscript path, e.g. "^_" for a subscript in a superscript. */
data class ChatMathRun(
    val text: String,
    val style: ChatMathStyle = ChatMathStyle.NORMAL,
    val scripts: String = "",
)

enum class ChatMathStyle { NORMAL, ITALIC, BOLD }

/** Longer expressions stay as TeX source rather than being converted. */
const val MAX_CHAT_MATH_CHARS: Int = 4_000

private const val MAX_MATH_DEPTH = 16
private const val PLACEHOLDER_START = ''
private const val PLACEHOLDER_END = ''
private val PLACEHOLDER = Regex("$PLACEHOLDER_START(\\d+)$PLACEHOLDER_END")
private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})")
private val BLANK_LINE = Regex("\\n[ \\t]*\\n")

internal data class ChatMathSegment(val source: String, val tex: String, val display: Boolean)

/** [text] with each math expression replaced by a placeholder the Markdown parser leaves alone. */
internal class ChatMathExtraction(val text: String, val segments: List<ChatMathSegment>) {
    /** Puts the original source (delimiters included) back, for code, tables and other literal text. */
    fun restore(value: String): String =
        if (segments.isEmpty()) value else PLACEHOLDER.replace(value) { segments[it.groupValues[1].toInt()].source }

    /** Splits [value] into literal text and math segments. */
    fun split(value: String): List<Pair<String, ChatMathSegment?>> {
        if (segments.isEmpty() || PLACEHOLDER_START !in value) return listOf(value to null)
        val parts = mutableListOf<Pair<String, ChatMathSegment?>>()
        var last = 0
        PLACEHOLDER.findAll(value).forEach { match ->
            if (match.range.first > last) parts += value.substring(last, match.range.first) to null
            parts += "" to segments[match.groupValues[1].toInt()]
            last = match.range.last + 1
        }
        if (last < value.length) parts += value.substring(last) to null
        return parts
    }
}

/**
 * Finds `$…$`, `\(…\)` (inline) and `$$…$$`, `\[…\]` (display) outside code. A `$` opens only
 * before a non-space and the next unescaped `$` must close it: after a non-space and not before a
 * digit. Prices such as "$5 and $10" or "Costs $5; solve $x$" stay text around the formula. `\$` is a literal dollar. Math never spans a blank line.
 */
internal fun extractChatMath(text: String): ChatMathExtraction {
    if (PLACEHOLDER_START in text || PLACEHOLDER_END in text || ('$' !in text && '\\' !in text)) {
        return ChatMathExtraction(text, emptyList())
    }
    val scan = MathDelimiters(text)
    val out = StringBuilder(text.length)
    val segments = mutableListOf<ChatMathSegment>()
    var i = 0
    var lineStart = true
    while (i < text.length) {
        if (lineStart) {
            val lineEnd = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            val fence = FENCE.find(text.substring(i, lineEnd))
            if (fence != null) {
                val marker = fence.groupValues[1]
                val close = Regex("(?m)^ {0,3}${marker[0]}{${marker.length},}[ \\t]*$")
                val end = close.find(text, lineEnd)?.range?.last?.plus(1) ?: text.length
                out.append(text, i, end)
                i = end
                lineStart = false
                continue
            }
        }
        val c = text[i]
        lineStart = c == '\n'
        when {
            c == '`' -> {
                val stop = scan.codeSpanEnd(i)
                out.append(text, i, stop)
                i = stop
            }
            c == '\\' && i + 1 < text.length && (text[i + 1] == '(' || text[i + 1] == '[') -> {
                val display = text[i + 1] == '['
                val end = scan.closer(if (display) scan.bracketClosers else scan.parenClosers, i + 2)
                val tex = if (end < 0) null else text.substring(i + 2, end)
                if (tex != null && tex.isNotBlank()) {
                    out.placeholder(segments, ChatMathSegment(text.substring(i, end + 2), tex.trim(), display))
                    i = end + 2
                } else {
                    out.append(text, i, i + 2)
                    i += 2
                }
            }
            c == '\\' && i + 1 < text.length -> {
                out.append(text, i, i + 2)
                i += 2
            }
            c == '$' && i + 1 < text.length && text[i + 1] == '$' -> {
                val end = scan.closer(scan.doubleDollars, i + 2)
                val tex = if (end < 0) null else text.substring(i + 2, end)
                if (tex != null && tex.isNotBlank()) {
                    out.placeholder(segments, ChatMathSegment(text.substring(i, end + 2), tex.trim(), display = true))
                    i = end + 2
                } else {
                    out.append("$$")
                    i += 2
                }
            }
            c == '$' -> {
                val end = scan.inlineDollarEnd(i)
                if (end > 0) {
                    out.placeholder(segments, ChatMathSegment(text.substring(i, end + 1), text.substring(i + 1, end), display = false))
                    i = end + 1
                } else {
                    out.append(c)
                    i++
                }
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return ChatMathExtraction(out.toString(), segments)
}

private fun StringBuilder.placeholder(segments: MutableList<ChatMathSegment>, segment: ChatMathSegment) {
    append(PLACEHOLDER_START).append(segments.size).append(PLACEHOLDER_END)
    segments += segment
}

/**
 * Delimiter positions found in one pass, so each lookup is a binary search instead of a rescan:
 * a long answer full of unmatched `$` or backticks stays linear. Every match stays inside its
 * paragraph and within [MAX_CHAT_MATH_CHARS].
 */
private class MathDelimiters(private val text: String) {
    private val paragraphBreaks = BLANK_LINE.findAll(text).map { it.range.first }.toList().toIntArray()
    val doubleDollars = positions { text[it] == '$' && text.getOrNull(it + 1) == '$' }
    val parenClosers = positions { text[it] == '\\' && text.getOrNull(it + 1) == ')' }
    val bracketClosers = positions { text[it] == '\\' && text.getOrNull(it + 1) == ']' }
    private val singleDollars = positions { text[it] == '$' }
    private val inlineClose = IntArray(singleDollars.size).also { result ->
        for (k in singleDollars.indices.reversed()) {
            val j = singleDollars[k]
            val next = text.getOrNull(j + 1)
            // Math never holds an unescaped `$`, so the first one after an opener closes it or nothing
            // does: "Costs $5; solve $x$" keeps "$5" as text instead of swallowing the formula.
            result[k] = when {
                escaped(j) -> if (k + 1 < singleDollars.size) result[k + 1] else -1
                j > 0 && !text[j - 1].isWhitespace() && text[j - 1] != '$' && next != '$' && next?.isDigit() != true -> j
                else -> -1
            }
        }
    }
    private val tickRuns: Map<Int, IntArray> = buildMap<Int, MutableList<Int>> {
        var i = 0
        while (i < text.length) {
            if (text[i] == '`') {
                var run = 0
                while (i + run < text.length && text[i + run] == '`') run++
                getOrPut(run) { mutableListOf() } += i
                i += run
            } else {
                i++
            }
        }
    }.mapValues { it.value.toIntArray() }

    /** End (exclusive) of the code span opening at [open], or just past the backticks when it is not closed. */
    fun codeSpanEnd(open: Int): Int {
        var run = 0
        while (open + run < text.length && text[open + run] == '`') run++
        val starts = tickRuns[run] ?: return open + run
        val close = starts.firstAtOrAfter(open + run)
        return if (close >= 0 && close < paragraphEnd(open)) close + run else open + run
    }

    /** Start of the first two-character closer in [closers] at or after [from], or -1. */
    fun closer(closers: IntArray, from: Int): Int =
        closers.firstAtOrAfter(from).takeIf { it >= 0 && it < limit(from) } ?: -1

    /** Index of the closing `$` for an inline `$` at [open], or -1. */
    fun inlineDollarEnd(open: Int): Int {
        if (open + 1 >= text.length || text[open + 1].isWhitespace()) return -1
        val k = singleDollars.indexAtOrAfter(open + 1)
        if (k < 0) return -1
        return inlineClose[k].takeIf { it >= 0 && it < limit(open) } ?: -1
    }

    private fun limit(from: Int): Int = minOf(paragraphEnd(from), from + MAX_CHAT_MATH_CHARS + 2)

    private fun paragraphEnd(from: Int): Int = paragraphBreaks.firstAtOrAfter(from).let { if (it < 0) text.length else it }

    private fun escaped(index: Int): Boolean {
        var backslashes = 0
        while (index - backslashes - 1 >= 0 && text[index - backslashes - 1] == '\\') backslashes++
        return backslashes % 2 == 1
    }

    private inline fun positions(predicate: (Int) -> Boolean): IntArray =
        text.indices.filter(predicate).toIntArray()
}

private fun IntArray.indexAtOrAfter(value: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid] < value) low = mid + 1 else high = mid
    }
    return if (low < size) low else -1
}

private fun IntArray.firstAtOrAfter(value: Int): Int = indexAtOrAfter(value).let { if (it < 0) -1 else this[it] }

/**
 * Converts a TeX subset (Greek, common operators and relations, scripts, `\frac`, `\sqrt`, text
 * and font commands, accents, `aligned`/`cases`-style line environments) into display runs, or
 * returns null when the expression uses anything else so the caller can show the source.
 */
fun chatMathRuns(tex: String): List<ChatMathRun>? {
    if (tex.length > MAX_CHAT_MATH_CHARS) return null
    return runCatching { TexReader(tex).read() }.getOrNull()?.merged()
}

private class Unsupported : Exception()

private class TexReader(private val tex: String) {
    private var pos = 0

    fun read(): List<ChatMathRun> {
        val runs = sequence(ChatMathStyle.NORMAL, "", depth = 0, stopAt = null)
        if (pos < tex.length) throw Unsupported()
        return runs
    }

    private fun sequence(style: ChatMathStyle, scripts: String, depth: Int, stopAt: Char?): List<ChatMathRun> {
        if (depth > MAX_MATH_DEPTH) throw Unsupported()
        val out = mutableListOf<ChatMathRun>()
        while (true) {
            skipSpaces()
            if (pos >= tex.length) break
            val c = tex[pos]
            if (c == stopAt) return out
            when (c) {
                '}' -> throw Unsupported()
                '^', '_' -> {
                    pos++
                    out += atom(style, scripts + c, depth + 1)
                }
                else -> out += atom(style, scripts, depth)
            }
        }
        if (stopAt != null) throw Unsupported()
        return out
    }

    /** One character, group or command. */
    private fun atom(style: ChatMathStyle, scripts: String, depth: Int): List<ChatMathRun> {
        if (depth > MAX_MATH_DEPTH) throw Unsupported()
        skipSpaces()
        if (pos >= tex.length) throw Unsupported()
        val c = tex[pos]
        return when {
            c == '{' -> group(style, scripts, depth)
            c == '\\' -> command(style, scripts, depth)
            c == '&' -> { pos++; listOf(ChatMathRun(" ", style, scripts)) }
            c == '~' -> { pos++; listOf(ChatMathRun(" ", style, scripts)) }
            c == '\'' -> { pos++; listOf(ChatMathRun("′", style, scripts)) }
            c.isLetter() -> {
                pos++
                listOf(ChatMathRun(c.toString(), if (style == ChatMathStyle.NORMAL) ChatMathStyle.ITALIC else style, scripts))
            }
            c == '-' -> { pos++; listOf(ChatMathRun("−", style, scripts)) }
            c == '*' -> { pos++; listOf(ChatMathRun("∗", style, scripts)) }
            c == '\n' || c == '\r' || c == '\t' || c == ' ' -> { pos++; emptyList() }
            else -> { pos++; listOf(ChatMathRun(c.toString(), style, scripts)) }
        }
    }

    private fun group(style: ChatMathStyle, scripts: String, depth: Int): List<ChatMathRun> {
        pos++ // {
        val runs = sequence(style, scripts, depth + 1, stopAt = '}')
        pos++ // }
        return runs
    }

    private fun argument(style: ChatMathStyle, scripts: String, depth: Int): List<ChatMathRun> = atom(style, scripts, depth + 1)

    private fun textArgument(): String = rawArgument().replace(ESCAPED_TEXT, "$1")

    private fun rawArgument(): String {
        skipSpaces()
        if (pos >= tex.length) throw Unsupported()
        if (tex[pos] != '{') return tex[pos++].toString()
        var level = 0
        val start = pos
        while (pos < tex.length) {
            when (tex[pos]) {
                '{' -> level++
                '}' -> if (--level == 0) {
                    pos++
                    return tex.substring(start + 1, pos - 1)
                }
                '\\' -> pos++
            }
            pos++
        }
        throw Unsupported()
    }

    private fun command(style: ChatMathStyle, scripts: String, depth: Int): List<ChatMathRun> {
        pos++ // backslash
        if (pos >= tex.length) throw Unsupported()
        val first = tex[pos]
        if (!first.isLetter()) {
            pos++
            return when (first) {
                '\\' -> listOf(ChatMathRun("\n", ChatMathStyle.NORMAL, ""))
                ',', ':', '>', ';', ' ' -> listOf(ChatMathRun(" ", style, scripts))
                '!' -> emptyList()
                '{', '}', '$', '%', '#', '&', '_', '|' -> listOf(ChatMathRun(if (first == '|') "‖" else first.toString(), style, scripts))
                else -> throw Unsupported()
            }
        }
        val start = pos
        while (pos < tex.length && tex[pos].isLetter()) pos++
        val name = tex.substring(start, pos)
        SYMBOLS[name]?.let { return listOf(ChatMathRun(it, style, scripts)) }
        if (name in FUNCTIONS) return listOf(ChatMathRun(name, ChatMathStyle.NORMAL, scripts))
        return when (name) {
            in IGNORED -> emptyList()
            "left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr" -> {
                skipSpaces()
                if (pos < tex.length && tex[pos] == '.') {
                    pos++
                    emptyList()
                } else {
                    atom(style, scripts, depth)
                }
            }
            "frac", "dfrac", "tfrac" -> {
                val top = argument(style, scripts, depth)
                val bottom = argument(style, scripts, depth)
                wrapped(top, style, scripts) + ChatMathRun("/", style, scripts) + wrapped(bottom, style, scripts)
            }
            "sqrt" -> {
                skipSpaces()
                val index = if (pos < tex.length && tex[pos] == '[') {
                    val close = tex.indexOf(']', pos)
                    if (close < 0) throw Unsupported()
                    val inner = TexReader(tex.substring(pos + 1, close)).sequence(style, "$scripts^", depth + 1, stopAt = null)
                    pos = close + 1
                    inner
                } else {
                    emptyList()
                }
                val body = argument(style, scripts, depth)
                index + ChatMathRun("√", ChatMathStyle.NORMAL, scripts) + wrapped(body, style, scripts)
            }
            "text", "textrm", "mathrm", "operatorname", "textnormal", "mbox", "rm" ->
                if (name == "rm") emptyList() else listOf(ChatMathRun(textArgument(), ChatMathStyle.NORMAL, scripts))
            // Text-mode commands keep their spaces; the math-mode ones below drop them like TeX does.
            "textbf" -> listOf(ChatMathRun(textArgument(), ChatMathStyle.BOLD, scripts))
            "textit", "emph" -> listOf(ChatMathRun(textArgument(), ChatMathStyle.ITALIC, scripts))
            "texttt", "textsf" -> listOf(ChatMathRun(textArgument(), ChatMathStyle.NORMAL, scripts))
            "mathbf", "boldsymbol", "bm" -> argument(ChatMathStyle.BOLD, scripts, depth).map { it.copy(style = ChatMathStyle.BOLD) }
            "mathit" -> argument(ChatMathStyle.ITALIC, scripts, depth).map { it.copy(style = ChatMathStyle.ITALIC) }
            "mathcal", "mathscr", "mathsf", "mathtt" -> argument(ChatMathStyle.NORMAL, scripts, depth).map { it.copy(style = ChatMathStyle.NORMAL) }
            "mathbb" -> rawArgument().trim().map { letter ->
                ChatMathRun(DOUBLE_STRUCK[letter] ?: letter.toString(), ChatMathStyle.BOLD, scripts)
            }
            in ACCENTS -> {
                val mark = ACCENTS.getValue(name)
                argument(style, scripts, depth).map { run ->
                    run.copy(text = run.text.map { ch -> if (ch.isWhitespace()) ch.toString() else "$ch$mark" }.joinToString(""))
                }
            }
            "not" -> atom(style, scripts, depth).map { it.copy(text = it.text + "̸") }
            "begin" -> environment(style, scripts, depth)
            else -> throw Unsupported()
        }
    }

    /** Line-based environments only; matrices and arrays stay as source. */
    private fun environment(style: ChatMathStyle, scripts: String, depth: Int): List<ChatMathRun> {
        val name = rawArgument().trim()
        if (name.removeSuffix("*") !in LINE_ENVIRONMENTS) throw Unsupported()
        val end = "\\end{$name}"
        val close = tex.indexOf(end, pos)
        if (close < 0) throw Unsupported()
        val body = TexReader(tex.substring(pos, close)).sequence(style, scripts, depth + 1, stopAt = null)
        pos = close + end.length
        val prefix = if (name == "cases") listOf(ChatMathRun("{ ", ChatMathStyle.NORMAL, scripts)) else emptyList()
        return listOf(ChatMathRun("\n", ChatMathStyle.NORMAL, "")) + prefix + body.flatMap { run ->
            if (run.text == "\n" && prefix.isNotEmpty()) listOf(run) + prefix else listOf(run)
        } + ChatMathRun("\n", ChatMathStyle.NORMAL, "")
    }

    private fun wrapped(runs: List<ChatMathRun>, style: ChatMathStyle, scripts: String): List<ChatMathRun> {
        val simple = runs.all { it.scripts == scripts } && runs.joinToString("") { it.text }.let { text ->
            text.length <= 1 || text.all { it.isLetterOrDigit() || it == '.' }
        }
        return if (simple) runs else listOf(ChatMathRun("(", style, scripts)) + runs + ChatMathRun(")", style, scripts)
    }

    private fun skipSpaces() {
        while (pos < tex.length && tex[pos].isWhitespace()) pos++
    }
}

private val REPEATED_BREAKS = Regex("\\n[ ]*\\n+")
private val ESCAPED_TEXT = Regex("\\\\([{}$%#&_ ])")

private fun List<ChatMathRun>.merged(): List<ChatMathRun> {
    val result = mutableListOf<ChatMathRun>()
    forEach { run ->
        val last = result.lastOrNull()
        if (last != null && last.style == run.style && last.scripts == run.scripts) {
            result[result.lastIndex] = last.copy(text = last.text + run.text)
        } else if (run.text.isNotEmpty()) {
            result += run
        }
    }
    // Environments add line breaks at both ends; they are not useful at the edges of the expression.
    while (result.firstOrNull()?.text?.isBlank() == true) result.removeAt(0)
    while (result.lastOrNull()?.text?.isBlank() == true) result.removeAt(result.lastIndex)
    if (result.isNotEmpty()) {
        result[0] = result[0].copy(text = result[0].text.trimStart())
        result[result.lastIndex] = result[result.lastIndex].copy(text = result[result.lastIndex].text.trimEnd())
    }
    return result.map { it.copy(text = it.text.replace(REPEATED_BREAKS, "\n")) }
}

private val LINE_ENVIRONMENTS = setOf("aligned", "align", "gathered", "gather", "split", "cases", "equation", "multline", "flalign", "alignat", "alignedat")

private val IGNORED = setOf(
    "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "nonumber", "notag",
    "middle",
)

private val FUNCTIONS = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
    "log", "ln", "lg", "exp", "lim", "liminf", "limsup", "max", "min", "sup", "inf", "det", "dim",
    "ker", "deg", "gcd", "arg", "Pr", "mod", "bmod",
)

private val ACCENTS = mapOf(
    "hat" to "̂", "widehat" to "̂", "bar" to "̄", "overline" to "̅",
    "vec" to "⃗", "dot" to "̇", "ddot" to "̈", "tilde" to "̃",
    "widetilde" to "̃", "underline" to "̲",
)

private val DOUBLE_STRUCK = mapOf(
    'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'R' to "ℝ", 'C' to "ℂ", 'P' to "ℙ", 'H' to "ℍ",
)

private val SYMBOLS: Map<String, String> = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ϵ", "varepsilon" to "ε",
    "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ",
    "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "ϕ",
    "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
    "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    "times" to "×", "cdot" to "⋅", "div" to "÷", "pm" to "±", "mp" to "∓", "ast" to "∗", "star" to "⋆",
    "circ" to "∘", "bullet" to "∙", "oplus" to "⊕", "otimes" to "⊗",
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈",
    "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝", "ll" to "≪", "gg" to "≫",
    "leqslant" to "⩽", "geqslant" to "⩾", "prec" to "≺", "succ" to "≻", "perp" to "⊥", "parallel" to "∥",
    "mid" to "∣", "nmid" to "∤", "vert" to "|", "Vert" to "‖", "lvert" to "|", "rvert" to "|", "lVert" to "‖", "rVert" to "‖",
    "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃",
    "supseteq" to "⊇", "cup" to "∪", "cap" to "∩", "setminus" to "∖", "emptyset" to "∅", "varnothing" to "∅",
    "forall" to "∀", "exists" to "∃", "nexists" to "∄", "neg" to "¬", "lnot" to "¬", "land" to "∧", "lor" to "∨",
    "wedge" to "∧", "vee" to "∨", "implies" to "⟹", "iff" to "⟺",
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←", "leftrightarrow" to "↔",
    "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "mapsto" to "↦",
    "longrightarrow" to "⟶", "longleftarrow" to "⟵", "Longrightarrow" to "⟹", "Longleftarrow" to "⟸",
    "uparrow" to "↑", "downarrow" to "↓",
    "infty" to "∞", "partial" to "∂", "nabla" to "∇", "sum" to "∑", "prod" to "∏", "coprod" to "∐",
    "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮", "bigcup" to "⋃", "bigcap" to "⋂",
    "ldots" to "…", "dots" to "…", "dotsc" to "…", "dotsb" to "⋯", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
    "angle" to "∠", "degree" to "°", "prime" to "′", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
    "aleph" to "ℵ", "therefore" to "∴", "because" to "∵", "triangle" to "△", "square" to "□",
    "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉",
    "lbrace" to "{", "rbrace" to "}", "lbrack" to "[", "rbrack" to "]", "backslash" to "\\",
    "quad" to "  ", "qquad" to "    ", "colon" to ":", "cdotp" to "⋅",
)

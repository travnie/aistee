package ais.tee.data.model

/**
 * Stages [excerpt] as a Markdown blockquote at the end of [draft], leaving the cursor on a fresh line
 * below it. Quoting only edits the local draft; nothing is sent. Returns null when the excerpt is
 * blank or the result would exceed [maxChars].
 */
fun quoteIntoNativeChatDraft(excerpt: String, draft: String, maxChars: Int): String? {
    val lines = excerpt.replace("\r\n", "\n").replace('\r', '\n').trim('\n').lines()
        .map { it.trimEnd() }
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
    if (lines.isEmpty()) return null
    val quote = lines.joinToString("\n") { line -> if (line.isBlank()) ">" else "> $line" }
    val prefix = draft.trimEnd().let { if (it.isEmpty()) "" else "$it\n\n" }
    val result = "$prefix$quote\n\n"
    return result.takeIf { it.length <= maxChars }
}

/**
 * Appends [text] (for example a Library text asset) below [draft], separated by a blank line, leaving
 * the cursor on a fresh line. Like quoting, this only edits the local draft. Returns null when the text
 * is blank or the result would exceed [maxChars].
 */
fun appendToNativeChatDraft(text: String, draft: String, maxChars: Int): String? {
    val body = text.replace("\r\n", "\n").replace('\r', '\n').trim('\n').trimEnd()
    if (body.isBlank()) return null
    val prefix = draft.trimEnd().let { if (it.isEmpty()) "" else "$it\n\n" }
    val result = "$prefix$body\n"
    return result.takeIf { it.length <= maxChars }
}

/** Whether a Library asset holds text that can go straight into the composer. */
fun ProjectLibraryAsset.canInsertIntoDraft(): Boolean = mediaType.trim().lowercase().startsWith("text/")

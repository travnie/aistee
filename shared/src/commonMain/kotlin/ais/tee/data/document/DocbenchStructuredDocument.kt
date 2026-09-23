package ais.tee.data.document

const val MAX_DOCBENCH_STRUCTURED_BLOCKS = 20_000
const val MAX_DOCBENCH_STRUCTURED_TEXT_CHARS = 8 * 1024 * 1024
const val MAX_DOCBENCH_OUTLINE_ENTRIES = 20_000
const val MAX_DOCBENCH_OUTLINE_TITLE_CHARS = 1_024
const val MAX_DOCBENCH_BOOKMARKS_PER_BLOCK = 32
const val MAX_DOCBENCH_BOOKMARK_NAME_CHARS = 255

/**
 * Portable text-first document structure used by DOCX/PDF adapters.
 *
 * Layout, fonts and arbitrary Office/PDF drawing primitives intentionally stay outside this model.
 * Chapters/outlines are anchored to block indexes so PDF bookmark titles remain independent from
 * the visible paragraph text while DOCX headings can map to the same source of truth.
 */
data class DocbenchStructuredDocument(
    val blocks: List<DocbenchStructuredBlock>,
    val outline: List<DocbenchOutlineEntry> = emptyList()
) {
    init {
        require(blocks.size <= MAX_DOCBENCH_STRUCTURED_BLOCKS) {
            "Structured document has too many blocks."
        }
        var textChars = 0L
        blocks.forEach { block ->
            textChars += block.text.length
            require(textChars <= MAX_DOCBENCH_STRUCTURED_TEXT_CHARS) {
                "Structured document text exceeds the supported limit."
            }
        }
        require(outline.size <= MAX_DOCBENCH_OUTLINE_ENTRIES) {
            "Structured document has too many outline entries."
        }
        outline.forEach { entry ->
            require(entry.blockIndex in blocks.indices) {
                "Outline entry points outside the structured document."
            }
        }
    }
}

data class DocbenchStructuredBlock(
    val text: String,
    val bookmarks: List<String> = emptyList()
) {
    init {
        require(bookmarks.size <= MAX_DOCBENCH_BOOKMARKS_PER_BLOCK) {
            "Too many bookmarks on one document block."
        }
        require(bookmarks.all { it.length <= MAX_DOCBENCH_BOOKMARK_NAME_CHARS }) {
            "Bookmark name exceeds the supported limit."
        }
    }
}

data class DocbenchOutlineEntry(
    val title: String,
    val level: Int,
    val blockIndex: Int
) {
    init {
        require(title.length <= MAX_DOCBENCH_OUTLINE_TITLE_CHARS) {
            "Outline title exceeds the supported limit."
        }
        require(level in 1..9) {
            "Outline level must be between 1 and 9."
        }
        require(blockIndex >= 0) {
            "Outline block index must not be negative."
        }
    }
}

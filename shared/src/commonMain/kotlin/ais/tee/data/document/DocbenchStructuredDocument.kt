package ais.tee.data.document

const val MAX_DOCBENCH_STRUCTURED_BLOCKS = 20_000
const val MAX_DOCBENCH_STRUCTURED_TEXT_CHARS = 8 * 1024 * 1024
const val MAX_DOCBENCH_BOOKMARKS_PER_BLOCK = 32
const val MAX_DOCBENCH_BOOKMARK_NAME_CHARS = 255

/**
 * Portable text-first document structure used by DOCX/PDF adapters.
 *
 * This deliberately models only structure Aistee can preserve reliably across formats: paragraph
 * text, heading level and named anchors/bookmarks. Layout, fonts and arbitrary Office/PDF drawing
 * primitives stay outside this interchange model.
 */
data class DocbenchStructuredDocument(
    val blocks: List<DocbenchStructuredBlock>
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
    }
}

data class DocbenchStructuredBlock(
    val text: String,
    val headingLevel: Int? = null,
    val bookmarks: List<String> = emptyList()
) {
    init {
        require(headingLevel == null || headingLevel in 1..9) {
            "Heading level must be between 1 and 9."
        }
        require(bookmarks.size <= MAX_DOCBENCH_BOOKMARKS_PER_BLOCK) {
            "Too many bookmarks on one document block."
        }
        require(bookmarks.all { it.length <= MAX_DOCBENCH_BOOKMARK_NAME_CHARS }) {
            "Bookmark name exceeds the supported limit."
        }
    }
}

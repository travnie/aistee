package ais.tee.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class WebBookmarkFolderHeadingTest {
    @Test fun nestedFoldersExposeEachAncestorExactlyOnce() {
        assertEquals(
            listOf(
                WebBookmarkFolderHeading("Work", "Work", 0),
                WebBookmarkFolderHeading("Work/Research", "Research", 1),
                WebBookmarkFolderHeading("Work/Research/AI", "AI", 2),
            ),
            webBookmarkFolderHeadings("Work/Research/AI"),
        )
        assertEquals(
            listOf(WebBookmarkFolderHeading("", "Unfiled", 0)),
            webBookmarkFolderHeadings(""),
        )
    }
}

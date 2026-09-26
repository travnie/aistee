package ais.tee.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeChatShareDraftTest {
    @Test
    fun sharedTextFillsAnEmptyDraftAndAppendsToAnExistingOne() {
        assertEquals("Shared", stageSharedTextInDraft("  ", "Shared"))
        assertEquals("Draft\n\nShared", stageSharedTextInDraft("Draft  \n", "Shared"))
    }
}

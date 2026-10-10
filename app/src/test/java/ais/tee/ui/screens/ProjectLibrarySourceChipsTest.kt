package ais.tee.ui.screens

import ais.tee.data.model.ProjectLibraryArchive
import ais.tee.data.model.ProjectLibraryAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectLibrarySourceChipsTest {
    private val library = ProjectLibraryArchive(assets = listOf(
        ProjectLibraryAsset(
            id = "asset-1", projectId = "inbox", title = "notes.md",
            mediaType = "text/markdown", fileName = "notes.md", sizeBytes = 15, createdAtEpochMs = 1,
        ),
    ))

    @Test
    fun sourceChipUsesTitleAndHandlesMissingOrDuplicateIds() {
        val previews = projectLibrarySourcePreviews(
            listOf("asset-1", "asset-1", "deleted-1", "", "deleted-2"), library,
        )
        assertEquals(3, previews.size)
        assertEquals("notes.md", previews[0].label)
        assertEquals("Removed source", previews[1].label)
        assertNull(previews[1].asset)
        assertFalse(previews[0].toString().contains("notes.md"))
        assertFalse(previews[0].toString().contains("asset-1"))
    }

    @Test
    fun boundsUntrustedSourceList() {
        assertEquals(4, projectLibrarySourcePreviews((1..100).map { "id-$it" }, library).size)
    }
}

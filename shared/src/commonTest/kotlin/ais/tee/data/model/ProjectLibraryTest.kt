package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectLibraryTest {
    @Test
    fun normalizationAddsInboxAndDropsDanglingAssets() {
        val validProject = LocalProject(
            id = "p1",
            name = "  Alpha  ",
            createdAtEpochMs = 1,
        )
        val validAsset = ProjectLibraryAsset(
            id = "a1",
            projectId = "p1",
            title = "  Notes  ",
            mediaType = " TEXT/MARKDOWN ",
            fileName = "a1.md",
            sizeBytes = 12,
            createdAtEpochMs = 2,
        )
        val dangling = validAsset.copy(id = "a2", projectId = "missing")
        val normalized = assertNotNull(
            ProjectLibraryArchive(
                projects = listOf(validProject),
                assets = listOf(validAsset, dangling),
            ).normalizedProjectLibrary(now = 10)
        )

        assertEquals(listOf(DEFAULT_PROJECT_ID, "p1"), normalized.projects.map { it.id })
        assertEquals("Alpha", normalized.projects.last().name)
        assertEquals(listOf("a1"), normalized.assets.map { it.id })
        assertEquals("text/markdown", normalized.assets.single().mediaType)
        assertFalse(normalized.toString().contains("Notes"))
        assertFalse(normalized.assets.single().toString().contains("Notes"))
    }

    @Test
    fun unsupportedVersionFailsClosed() {
        assertEquals(
            null,
            ProjectLibraryArchive(version = PROJECT_LIBRARY_VERSION + 1)
                .normalizedProjectLibrary(now = 1)
        )
    }

    @Test
    fun inboxIsStableWhenAlreadyPresent() {
        val inbox = LocalProject(
            id = DEFAULT_PROJECT_ID,
            name = "Inbox",
            createdAtEpochMs = 1,
        )
        val normalized = assertNotNull(
            ProjectLibraryArchive(projects = listOf(inbox)).normalizedProjectLibrary(now = 10)
        )
        assertEquals(1, normalized.projects.size)
        assertTrue(normalized.assets.isEmpty())
    }
}

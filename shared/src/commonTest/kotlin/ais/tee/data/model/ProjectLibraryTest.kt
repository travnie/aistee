package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectLibraryTest {
    private companion object {
        const val PROJECT_ID = "p1"
        const val ASSET_ID = "a1"
        const val SECOND_ASSET_ID = "a2"
    }

    @Test
    fun normalizationAddsInboxAndDropsDanglingAssets() {
        val validProject = LocalProject(
            id = PROJECT_ID,
            name = "  Alpha  ",
            createdAtEpochMs = 1,
        )
        val validAsset = ProjectLibraryAsset(
            id = ASSET_ID,
            projectId = PROJECT_ID,
            title = "  Notes  ",
            mediaType = " TEXT/MARKDOWN ",
            fileName = "a1.md",
            sizeBytes = 12,
            createdAtEpochMs = 2,
            contentSha256 = "a".repeat(64),
        )
        val dangling = validAsset.copy(id = SECOND_ASSET_ID, projectId = "missing")
        val normalized = assertNotNull(
            ProjectLibraryArchive(
                projects = listOf(validProject),
                assets = listOf(validAsset, dangling),
            ).normalizedProjectLibrary(now = 10),
        )

        assertEquals(listOf(DEFAULT_PROJECT_ID, PROJECT_ID), normalized.projects.map { it.id })
        assertEquals("Alpha", normalized.projects.last().name)
        assertEquals(listOf(ASSET_ID), normalized.assets.map { it.id })
        assertEquals("text/markdown", normalized.assets.single().mediaType)
        assertEquals(1L, normalized.assets.single().revision)
        assertEquals(ProjectLibraryAssetKind.DOCUMENT, normalized.assets.single().kind)
        assertEquals(ProjectLibraryAssetOrigin.LOCAL, normalized.assets.single().origin)
        assertFalse(normalized.toString().contains("Notes"))
        assertFalse(normalized.assets.single().toString().contains("Notes"))
    }

    @Test
    fun invalidRevisionOrHashIsDropped() {
        val project = LocalProject(id = PROJECT_ID, name = "P", createdAtEpochMs = 1)
        val valid = ProjectLibraryAsset(
            id = ASSET_ID,
            projectId = PROJECT_ID,
            title = "A",
            mediaType = "text/plain",
            fileName = "a1.txt",
            sizeBytes = 1,
            createdAtEpochMs = 1,
            contentSha256 = "b".repeat(64),
        )
        val normalized = assertNotNull(
            ProjectLibraryArchive(
                projects = listOf(project),
                assets = listOf(
                    valid,
                    valid.copy(id = SECOND_ASSET_ID, revision = 0),
                    valid.copy(id = "a3", contentSha256 = "not-a-hash"),
                ),
            ).normalizedProjectLibrary(now = 2),
        )
        assertEquals(listOf(ASSET_ID), normalized.assets.map { it.id })
    }

    @Test
    fun unsupportedVersionFailsClosed() {
        assertEquals(
            null,
            ProjectLibraryArchive(version = PROJECT_LIBRARY_VERSION + 1)
                .normalizedProjectLibrary(now = 1),
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
            ProjectLibraryArchive(projects = listOf(inbox)).normalizedProjectLibrary(now = 10),
        )
        assertEquals(1, normalized.projects.size)
        assertTrue(normalized.assets.isEmpty())
    }
}

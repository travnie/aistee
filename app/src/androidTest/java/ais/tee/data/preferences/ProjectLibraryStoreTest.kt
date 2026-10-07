package ais.tee.data.preferences

import ais.tee.data.model.DEFAULT_PROJECT_ID
import ais.tee.data.model.PROJECT_LIBRARY_VERSION
import ais.tee.data.model.ProjectLibraryAssetKind
import ais.tee.data.model.ProjectLibraryAssetOrigin
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ProjectLibraryStoreTest {
    private companion object {
        const val PRIVATE_CONTENT = "# private content"
        const val EDITED_PRIVATE_CONTENT = "# edited private content"
        const val LEGACY_ASSET_ID = "legacy"
        const val TEXT_PLAIN = "text/plain"
        const val TEXT_MARKDOWN = "text/markdown"
        const val TXT_EXTENSION = "txt"
        const val ASSETS_DIRECTORY = "assets"
        const val INDEX_FILE = "index.json"
        const val SAMPLE_TEXT = "x"
    }

    private fun testRoot(): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return File(context.cacheDir, "project-library-test-\${UUID.randomUUID()}")
    }

    @Test
    fun textAssetRoundTripsAndDeletesWithoutLeakingContentInMetadata() {
        val root = testRoot()
        try {
            val store = ProjectLibraryStore(root)
            val initial = store.load(now = 1)
            assertTrue(initial.projects.any { it.id == DEFAULT_PROJECT_ID })

            val project = requireNotNull(store.createProject("Docs", now = 2))
            val asset = requireNotNull(
                store.saveTextAsset(
                    projectId = project.id,
                    title = "Chat export",
                    mediaType = TEXT_MARKDOWN,
                    extension = "md",
                    text = PRIVATE_CONTENT,
                    now = 3,
                ),
            )
            val loaded = requireNotNull(store.loadTextAsset(asset.id, now = 4))
            assertEquals(PRIVATE_CONTENT, loaded.text)
            assertFalse(loaded.toString().contains(PRIVATE_CONTENT))
            assertEquals(TEXT_MARKDOWN, loaded.metadata.mediaType)
            assertEquals(1L, loaded.metadata.revision)
            assertEquals(ProjectLibraryAssetKind.DOCUMENT, loaded.metadata.kind)
            assertEquals(ProjectLibraryAssetOrigin.LOCAL, loaded.metadata.origin)
            assertEquals(64, requireNotNull(loaded.metadata.contentSha256).length)

            val updated = requireNotNull(
                store.updateTextAsset(
                    assetId = asset.id,
                    expectedRevision = asset.revision,
                    text = EDITED_PRIVATE_CONTENT,
                    now = 5,
                ),
            )
            assertEquals(asset.id, updated.id)
            assertEquals(2L, updated.revision)
            assertEquals(EDITED_PRIVATE_CONTENT, requireNotNull(store.loadTextAsset(asset.id, now = 6)).text)
            assertNull(
                store.updateTextAsset(
                    assetId = asset.id,
                    expectedRevision = 1L,
                    text = "stale overwrite",
                    now = 7,
                ),
            )
            assertEquals(EDITED_PRIVATE_CONTENT, requireNotNull(store.loadTextAsset(asset.id, now = 8)).text)
            assertNull(
                store.updateTextAsset(
                    assetId = asset.id,
                    expectedRevision = updated.revision,
                    text = "\uD800",
                    now = 9,
                ),
            )
            assertEquals(EDITED_PRIVATE_CONTENT, requireNotNull(store.loadTextAsset(asset.id, now = 10)).text)

            assertTrue(store.deleteAsset(asset.id, now = 11))
            assertNull(store.loadTextAsset(asset.id, now = 12))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyIndexMigratesWithoutDroppingAssetBytes() {
        val root = testRoot()
        try {
            val libraryRoot = File(root, ProjectLibraryStore.DIRECTORY_NAME)
            val assets = File(libraryRoot, ASSETS_DIRECTORY)
            assertTrue(assets.mkdirs())
            val payload = LEGACY_ASSET_ID
            File(assets, "$LEGACY_ASSET_ID.txt").writeText(payload)
            File(libraryRoot, INDEX_FILE).writeText(
                """{"version":1,"projects":[{"id":"inbox","name":"Inbox","createdAtEpochMs":1,"updatedAtEpochMs":1}],"assets":[{"id":"$LEGACY_ASSET_ID","projectId":"inbox","title":"Legacy","mediaType":"text/plain","fileName":"$LEGACY_ASSET_ID.txt","sizeBytes":6,"createdAtEpochMs":2}]}""",
            )

            val store = ProjectLibraryStore(root)
            val archive = store.load(now = 3)
            val migrated = archive.assets.single()

            assertEquals(PROJECT_LIBRARY_VERSION, archive.version)
            assertEquals(LEGACY_ASSET_ID, migrated.id)
            assertEquals(1L, migrated.revision)
            assertEquals(64, requireNotNull(migrated.contentSha256).length)
            assertEquals(ProjectLibraryAssetKind.DOCUMENT, migrated.kind)
            assertEquals(ProjectLibraryAssetOrigin.LOCAL, migrated.origin)
            assertEquals(payload, requireNotNull(store.loadTextAsset(LEGACY_ASSET_ID, now = 4)).text)
            assertTrue(File(libraryRoot, INDEX_FILE).readText().contains("\"version\":2"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingCurrentRevisionRecoversPreviousAvailableRevision() {
        val root = testRoot()
        try {
            val store = ProjectLibraryStore(root)
            val original = requireNotNull(
                store.saveTextAsset(
                    title = "Recovery",
                    mediaType = TEXT_PLAIN,
                    extension = TXT_EXTENSION,
                    text = PRIVATE_CONTENT,
                    now = 1,
                ),
            )
            val updated = requireNotNull(
                store.updateTextAsset(
                    assetId = original.id,
                    expectedRevision = original.revision,
                    text = EDITED_PRIVATE_CONTENT,
                    now = 2,
                ),
            )
            val assets = File(File(root, ProjectLibraryStore.DIRECTORY_NAME), ASSETS_DIRECTORY)
            File(assets, original.fileName).writeText(PRIVATE_CONTENT)
            assertTrue(File(assets, updated.fileName).delete())

            val recovered = store.load(now = 3).assets.single { it.id == original.id }
            assertEquals(1L, recovered.revision)
            assertEquals(original.fileName, recovered.fileName)
            assertEquals(PRIVATE_CONTENT, requireNotNull(store.loadTextAsset(original.id, now = 4)).text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnknownProjectAndOversizedAsset() {
        val root = testRoot()
        try {
            val store = ProjectLibraryStore(root)
            assertNull(
                store.saveTextAsset(
                    projectId = "missing",
                    title = "Nope",
                    mediaType = TEXT_PLAIN,
                    extension = TXT_EXTENSION,
                    text = SAMPLE_TEXT,
                ),
            )
            assertNull(
                store.saveTextAsset(
                    title = "Too large",
                    mediaType = TEXT_PLAIN,
                    extension = TXT_EXTENSION,
                    text = SAMPLE_TEXT.repeat(ProjectLibraryStore.MAX_ASSET_BYTES + 1),
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}

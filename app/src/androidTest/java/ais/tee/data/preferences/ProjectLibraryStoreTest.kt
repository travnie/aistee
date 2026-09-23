package ais.tee.data.preferences

import androidx.test.core.app.ApplicationProvider
import ais.tee.data.model.DEFAULT_PROJECT_ID
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectLibraryStoreTest {
    @Test
    fun textAssetRoundTripsAndDeletesWithoutLeakingContentInMetadata() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "project-library-test-${UUID.randomUUID()}")
        try {
            val store = ProjectLibraryStore(root)
            val initial = store.load(now = 1)
            assertTrue(initial.projects.any { it.id == DEFAULT_PROJECT_ID })

            val project = assertNotNull(store.createProject("Docs", now = 2))
            val asset = assertNotNull(
                store.saveTextAsset(
                    projectId = project.id,
                    title = "Chat export",
                    mediaType = "text/markdown",
                    extension = "md",
                    text = "# private content",
                    now = 3,
                )
            )
            val loaded = assertNotNull(store.loadTextAsset(asset.id, now = 4))
            assertEquals("# private content", loaded.text)
            assertFalse(loaded.toString().contains("# private content"))
            assertEquals("text/markdown", loaded.metadata.mediaType)

            assertTrue(store.deleteAsset(asset.id, now = 5))
            assertNull(store.loadTextAsset(asset.id, now = 6))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnknownProjectAndOversizedAsset() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "project-library-test-${UUID.randomUUID()}")
        try {
            val store = ProjectLibraryStore(root)
            assertNull(
                store.saveTextAsset(
                    projectId = "missing",
                    title = "Nope",
                    mediaType = "text/plain",
                    extension = "txt",
                    text = "x",
                )
            )
            assertNull(
                store.saveTextAsset(
                    title = "Too large",
                    mediaType = "text/plain",
                    extension = "txt",
                    text = "x".repeat(ProjectLibraryStore.MAX_ASSET_BYTES + 1),
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }
}

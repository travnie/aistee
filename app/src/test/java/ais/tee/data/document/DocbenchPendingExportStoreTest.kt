package ais.tee.data.document

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocbenchPendingExportStoreTest {
    @Test
    fun roundTripPreservesTextBomAndLineEndings() {
        val directory = Files.createTempDirectory(TEMP_DIRECTORY_PREFIX).toFile()
        try {
            val store = DocbenchPendingExportStore(directory)
            val source = TextDocumentCodec.decodeUtf8(
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                    "a\r\nb\nc".encodeToByteArray()
            )

            val id = store.save(source)
            val restored = requireNotNull(store.load(id))

            assertEquals(source.text, restored.text)
            assertEquals(source.hadUtf8Bom, restored.hadUtf8Bom)
            assertEquals(source.lineEndings, restored.lineEndings)

            store.delete(id)
            assertNull(store.load(id))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun pruningDoesNotDeleteAnActiveUnpublishedExport() {
        val directory = Files.createTempDirectory(TEMP_DIRECTORY_PREFIX).toFile()
        try {
            val store = DocbenchPendingExportStore(directory)
            val id = store.save(TextDocumentCodec.decodeUtf8("active".encodeToByteArray()))

            store.pruneOrphans(null)

            assertEquals("active", requireNotNull(store.load(id)).text)
            store.delete(id)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun pruningKeepsOnlyTheRestoredPendingExport() {
        val directory = Files.createTempDirectory(TEMP_DIRECTORY_PREFIX).toFile()
        try {
            val previousStore = DocbenchPendingExportStore(directory)
            val firstId = previousStore.save(
                TextDocumentCodec.decodeUtf8("first".encodeToByteArray())
            )
            val preservedId = previousStore.save(
                TextDocumentCodec.decodeUtf8("second".encodeToByteArray())
            )
            directory.resolve("$firstId.tmp").writeText("stale")

            val restoredStore = DocbenchPendingExportStore(directory)
            restoredStore.pruneOrphans(preservedId)

            assertNull(restoredStore.load(firstId))
            assertEquals("second", requireNotNull(restoredStore.load(preservedId)).text)
            assertTrue(directory.listFiles().orEmpty().none { it.extension == "tmp" })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun pendingMergeSurvivesRecreationWithoutPuttingEditorTextInSavedState() {
        val directory = Files.createTempDirectory(TEMP_DIRECTORY_PREFIX).toFile()
        try {
            val original = TextDocumentCodec.decodeUtf8(
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                    "unsaved notes\r\n".repeat(30_000).encodeToByteArray()
            )
            val savedId = DocbenchPendingExportStore(directory).save(original)

            val recreatedStore = DocbenchPendingExportStore(directory)
            recreatedStore.pruneOrphans(savedId)
            assertEquals(original, recreatedStore.load(savedId))
            recreatedStore.delete(savedId)
            assertNull(DocbenchPendingExportStore(directory).load(savedId))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsUntrustedIdentifiersOutsideTheStoreDirectory() {
        val directory = Files.createTempDirectory(TEMP_DIRECTORY_PREFIX).toFile()
        try {
            val store = DocbenchPendingExportStore(directory)
            val outside = directory.parentFile.resolve("outside.bin")
            outside.writeText("keep")

            assertNull(store.load("../outside"))
            store.delete("../outside")
            assertTrue(outside.isFile)
            assertEquals("keep", outside.readText())
        } finally {
            directory.parentFile.resolve("outside.bin").delete()
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val TEMP_DIRECTORY_PREFIX = "docbench-export-test"
    }
}

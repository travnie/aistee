package ais.tee.data.skills

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioPendingPluginExportTest {
    @Test
    fun restoredOpaqueIdCanCompleteExportAfterInMemoryDraftIsGone() {
        val root = Files.createTempDirectory("studio-export-test").toFile()
        try {
            val skill = AgentSkillDraftFactory.create("guide", "When needed", "# Guide\nSteps.")
            val draft = StudioPackageBuilder.createPlugin("guide-pack", "A guide", listOf(skill))
            val id = StudioPendingPluginExport.prepare(root, draft)
            val stream = ByteArrayOutputStream()
            // The only simulated post-activity-recreation value is the saved string ID.
            StudioPendingPluginExport.copyTo(root, id, stream)
            val names = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(stream.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    names += entry.name
                }
            }
            assertEquals(setOf("plugin.json", "skills/guide/SKILL.md"), names.toSet())
            StudioPendingPluginExport.discard(root, id)
            assertThrows(IOException::class.java) {
                StudioPendingPluginExport.copyTo(root, id, ByteArrayOutputStream())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalAndMissingCacheEntries() {
        val root = Files.createTempDirectory("studio-export-test").toFile()
        try {
            assertThrows(IOException::class.java) {
                StudioPendingPluginExport.copyTo(root, "../secrets", ByteArrayOutputStream())
            }
            StudioPendingPluginExport.discard(root, "../secrets")
            assertFalse(root.resolve("secrets").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}

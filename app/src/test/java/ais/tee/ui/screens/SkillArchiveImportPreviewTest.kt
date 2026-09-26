package ais.tee.ui.screens

import ais.tee.data.skills.ACTIVE_SKILL_ENTRY_FILE
import ais.tee.data.skills.ActiveSkillTool
import ais.tee.data.skills.SkillArchive
import ais.tee.data.skills.activeSkillBundleDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillArchiveImportPreviewTest {
    private fun source(metadata: String) = "---\nname: unit-convert\ndescription: Converts units.\n$metadata---\nUse it.\n"

    @Test
    fun runnableArchiveShowsToolsAndBundleDigest() = runBlocking {
        val text = source("metadata:\n  aistee-runtime: webview-v1\n  aistee-tools: current_datetime\n")
        val scripts = mapOf(ACTIVE_SKILL_ENTRY_FILE to "<p>".toByteArray())
        val preview = buildSkillImportPreview("SKILL.md", text, validateFilename = false, archive = SkillArchive(text, scripts, emptyList()))

        assertTrue(preview.isValid)
        assertTrue(preview.activeRuntimeIssues.isEmpty())
        assertEquals(setOf(ActiveSkillTool.CURRENT_DATETIME), preview.activeRuntime?.tools)
        assertEquals(activeSkillBundleDigest(scripts + ("SKILL.md" to text.toByteArray())), preview.bundleDigest)
    }

    @Test
    fun runtimeProblemsAreReportedWithoutBlockingTheInstructions() = runBlocking {
        val text = source("metadata:\n  aistee-runtime: webview-v1\n  aistee-network: https://api.example.com\n")
        val preview = buildSkillImportPreview("SKILL.md", text, validateFilename = false, archive = SkillArchive(text, emptyMap(), emptyList()))

        assertTrue(preview.isValid)
        assertEquals(2, preview.activeRuntimeIssues.size)

        val plain = buildSkillImportPreview("SKILL.md", source(""))
        assertNull(plain.bundleDigest)
        assertTrue(plain.activeRuntimeIssues.isEmpty())
    }
}

package ais.tee.ui.screens

import ais.tee.data.model.LocalProject
import ais.tee.data.model.ProjectLibraryArchive
import ais.tee.data.model.ProjectLibraryAsset
import ais.tee.data.model.ProjectLibraryAssetKind
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WebPromptVaultTest {
    private fun asset(id: String, name: String, kind: ProjectLibraryAssetKind, bytes: Int = 100) =
        ProjectLibraryAsset(
            id = id, projectId = "inbox", title = name, mediaType = "text/plain",
            fileName = "$id.md", sizeBytes = bytes, createdAtEpochMs = 1L, kind = kind,
        )

    @Test
    fun selectsOnlyNonEmptyPromptAssetsInStableOrder() {
        val archive = ProjectLibraryArchive(
            projects = listOf(LocalProject("inbox", "Inbox", 1L)),
            assets = listOf(
                asset("z", "Write tests", ProjectLibraryAssetKind.PROMPT),
                asset("doc", "Other doc", ProjectLibraryAssetKind.DOCUMENT),
                asset("a", "Audit", ProjectLibraryAssetKind.PROMPT),
                asset("empty", "Empty", ProjectLibraryAssetKind.PROMPT, bytes = 0),
                asset("skill", "Skill", ProjectLibraryAssetKind.SKILL),
            )
        )
        assertEquals(listOf("a", "z"), webPromptVaultItems(archive).map { it.id })
    }

    @Test
    fun longPromptPreviewPreservesTheEntireText() {
        val text = "start" + "x".repeat(6_000) + "end"
        val chunks = webPromptPreviewChunks(text)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.last().endsWith("end"))
        assertThrows(IndexOutOfBoundsException::class.java) { chunks[chunks.size] }
    }

    @Test
    fun expectedPromptIoFailuresReturnEmptyWithoutLeakingDetails() = runBlocking {
        assertNull(loadWebPromptText("prompt") { throw IOException("private disk path") })
        assertEquals("safe text", loadWebPromptText("prompt") { "safe text" })
    }

    @Test
    fun stagingNeverTruncatesOrSendsOversizedText() {
        assertFalse(webPromptCanStage(""))
        assertFalse(webPromptCanStage(" \n "))
        assertTrue(webPromptCanStage("A".repeat(4_000)))
        assertFalse(webPromptCanStage("A".repeat(4_001)))
        assertFalse(webPromptCanStage("界".repeat(4_000) + "A"))
    }
}

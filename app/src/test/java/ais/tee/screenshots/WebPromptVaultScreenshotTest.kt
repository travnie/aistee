package ais.tee.screenshots

import ais.tee.data.model.LocalProject
import ais.tee.data.model.ProjectLibraryArchive
import ais.tee.data.model.ProjectLibraryAsset
import ais.tee.data.model.ProjectLibraryAssetKind
import ais.tee.data.model.WebAiService
import ais.tee.ui.screens.WebPromptVaultDialog
import ais.tee.ui.theme.AisteeTheme
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class WebPromptVaultScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val prompt = "Be concise, cite your sources, and preserve the full context."

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun compactLargeType() = capture("web_prompt_vault_compact_font150", 1.5f)

    @Test
    @Config(qualifiers = "w610dp-h500dp-xxhdpi")
    fun mediumLandscape() = capture("web_prompt_vault_medium")

    private fun capture(name: String, scale: Float = 1f) {
        val archive = ProjectLibraryArchive(
            projects = listOf(LocalProject("inbox", "Inbox", 1L)),
            assets = listOf(
                ProjectLibraryAsset(
                    id = "p1", projectId = "inbox", title = "Source-aware writing",
                    mediaType = "text/plain", fileName = "source-aware.md", sizeBytes = 64,
                    createdAtEpochMs = 1L, kind = ProjectLibraryAssetKind.PROMPT,
                ),
                ProjectLibraryAsset(
                    id = "d1", projectId = "inbox", title = "Not a prompt",
                    mediaType = "text/plain", fileName = "notes.txt", sizeBytes = 64,
                    createdAtEpochMs = 1L, kind = ProjectLibraryAssetKind.DOCUMENT,
                ),
            )
        )
        var stageCalls = 0
        var exportAllCalls = 0
        var staged: String? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                AisteeTheme(darkTheme = true) {
                    WebPromptVaultDialog(
                        service = WebAiService.CHATGPT, archive = archive, libraryReady = true,
                        pendingDraft = false, draftBusy = false,
                        loadText = { id -> if (id == "p1") prompt else null },
                        onStage = { text -> stageCalls++; staged = text; true },
                        onCopy = { false }, onImport = {}, onExport = {}, onExportAll = { exportAllCalls++ },
                        transferBusy = false, onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("web_prompt_vault_search_open").assertIsDisplayed()
        compose.onNodeWithText("Not a prompt").assertDoesNotExist()
        compose.onNodeWithTag("web_prompt_vault_dialog")
            .captureRoboImage("build/outputs/roborazzi/$name.png")
        assertEquals(0, stageCalls)
        compose.onNodeWithTag("web_prompt_vault_export_all").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, exportAllCalls)
        compose.onNodeWithText("Source-aware writing").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(prompt).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, stageCalls)
        compose.onNodeWithTag("web_prompt_vault_stage").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, stageCalls)
        assertEquals(prompt, staged)
    }
}

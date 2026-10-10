package ais.tee.screenshots

import ais.tee.data.document.MarkdownTable
import ais.tee.data.document.MarkdownTableAlignment
import ais.tee.data.model.ProjectLibraryArchive
import ais.tee.data.model.ProjectLibraryAsset
import ais.tee.ui.screens.BenchToolsScreen
import ais.tee.ui.screens.ChatScreen
import ais.tee.ui.screens.InstructionsScreen
import ais.tee.ui.screens.MarkdownTableScreen
import ais.tee.ui.screens.MarkdownWorkspaceScreen
import ais.tee.ui.screens.PlaygroundScreen
import ais.tee.ui.screens.ProjectLibrarySourceChips
import ais.tee.ui.screens.StudioScreen
import ais.tee.ui.screens.YamlEditorScreen
import ais.tee.ui.theme.AisteeTheme
import ais.tee.ui.viewmodel.MarkdownWorkspaceViewModel
import ais.tee.ui.viewmodel.StudioUiState
import ais.tee.ui.viewmodel.StudioViewModel
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the native (non-WebView) screens in their fresh-install state, for reviewing
 * UI changes without an emulator. Record: `./gradlew :app:recordRoborazziDebug --tests
 * '*ScreenshotTest*'`; PNGs land in app/build/outputs/roborazzi. WebView chats are left to the
 * emulator smoke job: Robolectric has no WebView renderer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Plain Application: skips AisteeApplication's process/lock callbacks, which screenshots don't need.
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(
        name: String,
        dark: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        compose.setContent { AisteeTheme(darkTheme = dark) { content() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/$name.png")
    }

    private fun studio(
        name: String,
        dark: Boolean = true,
        screen: @Composable (StudioViewModel, StudioUiState) -> Unit,
    ) {
        val viewModel = StudioViewModel(RuntimeEnvironment.getApplication())
        capture(name, dark) {
            val state by viewModel.uiState.collectAsState()
            screen(viewModel, state)
        }
    }

    @Test
    fun studioDark() =
        studio("studio_dark") { vm, state ->
            StudioScreen(vm, state, onNavigateToInstructions = {}, onNavigateToSkills = {}, onNavigateToYaml = {}, onNavigateToPlayground = {})
        }

    @Test
    fun studioLight() =
        studio("studio_light", dark = false) { vm, state ->
            StudioScreen(vm, state, onNavigateToInstructions = {}, onNavigateToSkills = {}, onNavigateToYaml = {}, onNavigateToPlayground = {})
        }

    @Test fun compareHub() = studio("compare_hub") { vm, state -> ChatScreen(vm, state) }

    @Test
    fun instructions() =
        studio("instructions") { vm, state -> InstructionsScreen(vm, state, onNavigateToPlayground = {}) }

    @Test fun playground() = studio("playground") { vm, state -> PlaygroundScreen(vm, state) }

    @Test fun yamlEditor() = studio("yaml_editor") { vm, state -> YamlEditorScreen(vm, state) }

    @Test
    fun markdownWorkspace() {
        val viewModel = MarkdownWorkspaceViewModel()
        capture("markdown_workspace") { MarkdownWorkspaceScreen(workspaceViewModel = viewModel) }
    }

    @Test
    fun localLibrarySourceChips() = capture("library_source_chips") {
        Surface(color = MaterialTheme.colorScheme.background) {
            ProjectLibrarySourceChips(
            sourceIds = listOf("example", "missing"),
            archive = ProjectLibraryArchive(assets = listOf(
                ProjectLibraryAsset(
                    id = "example", projectId = "inbox", title = "example.py",
                    mediaType = "text/plain", fileName = "example.py",
                    sizeBytes = 48, createdAtEpochMs = 1,
                ),
            )),
                onOpenAsset = {},
            )
        }
    }

    @Test fun benchTools() = capture("bench_tools") { BenchToolsScreen() }

    @Test
    fun markdownTable() =
        capture("markdown_table") {
            MarkdownTableScreen(
                table =
                    MarkdownTable(
                        startLine = 0,
                        endLine = 4,
                        header = listOf("Model", "Context", "Price / 1M in", "Notes"),
                        alignments =
                            listOf(
                                MarkdownTableAlignment.LEFT,
                                MarkdownTableAlignment.RIGHT,
                                MarkdownTableAlignment.RIGHT,
                                MarkdownTableAlignment.NONE,
                            ),
                        rows =
                            listOf(
                                listOf("Model A", "200k", "$3.00", "Long notes column that should wrap or scroll"),
                                listOf("Model B", "1M", "$1.25", "—"),
                                listOf("Model C", "128k", "$0.15", "Cheap"),
                            ),
                    ),
                title = "Pricing",
                onDismiss = {},
                onCopyCsv = {},
                onExportCsv = {},
                onSaveToLibrary = {},
            )
        }

    private companion object {
        const val OUT = "build/outputs/roborazzi"
    }
}

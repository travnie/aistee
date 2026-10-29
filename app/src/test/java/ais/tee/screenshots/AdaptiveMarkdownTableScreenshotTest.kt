package ais.tee.screenshots

import ais.tee.data.document.MarkdownTable
import ais.tee.data.document.MarkdownTableAlignment
import ais.tee.ui.screens.MarkdownTableScreen
import ais.tee.ui.theme.AisteeTheme
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Record-only visual coverage for one existing responsive Docbench screen.
 *
 * Unlike Baseline Profiles, these JVM screenshots do not benchmark startup or WebView behavior.
 * CI uploads the PNGs for manual review; there are no committed pixel baselines yet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class AdaptiveMarkdownTableScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun compactWithLargeText() = capture("markdown_table_compact_font150", fontScale = 1.5f)

    @Test
    @Config(qualifiers = "w610dp-h500dp-xxhdpi")
    fun mediumLandscape() = capture("markdown_table_medium")

    @Test
    @Config(qualifiers = "w900dp-h1000dp-xxhdpi")
    fun expanded() = capture("markdown_table_expanded")

    private fun capture(name: String, fontScale: Float = 1f) {
        compose.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale)
            ) {
                AisteeTheme(darkTheme = true) {
                    MarkdownTableScreen(
                        table = SAMPLE_TABLE,
                        title = "Context and model costs",
                        onDismiss = {},
                        onCopyCsv = {},
                        onExportCsv = {},
                        onSaveToLibrary = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private companion object {
        val SAMPLE_TABLE = MarkdownTable(
            startLine = 0,
            endLine = 5,
            header = listOf("Model", "Context", "Cost", "Notes"),
            alignments = listOf(
                MarkdownTableAlignment.LEFT,
                MarkdownTableAlignment.RIGHT,
                MarkdownTableAlignment.RIGHT,
                MarkdownTableAlignment.NONE,
            ),
            rows = listOf(
                listOf("Small model", "128k", "$0.10", "Offline-compatible summary that should wrap cleanly"),
                listOf("Balanced model", "200k", "$0.50", "A longer note about comparing many local documents"),
                listOf("Large model", "1M", "$2.00", "Source references and extended explanatory text"),
            ),
        )
    }
}

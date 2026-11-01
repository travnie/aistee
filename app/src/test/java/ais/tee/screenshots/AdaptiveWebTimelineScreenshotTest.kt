package ais.tee.screenshots

import ais.tee.web.WebUserTurnTimeline
import ais.tee.ui.screens.WebUserTurnTimelinePicker
import ais.tee.ui.theme.AisteeTheme
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Assert.assertEquals
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Record-only picker coverage; no WebView or provider network.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class AdaptiveWebTimelineScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun compactWithLargeText() = capture("web_timeline_compact_font150", fontScale = 1.5f)

    @Test
    @Config(qualifiers = "w610dp-h500dp-xxhdpi")
    fun mediumLandscape() = capture("web_timeline_medium")

    @Test
    @Config(qualifiers = "w900dp-h1000dp-xxhdpi")
    fun expanded() = capture("web_timeline_expanded")

    private fun capture(name: String, fontScale: Float = 1f) {
        var selected = -1
        compose.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale)
            ) {
                AisteeTheme(darkTheme = true) {
                    WebUserTurnTimelinePicker(
                        loading = false,
                        snapshot = WebUserTurnTimeline(200, true),
                        jumping = false,
                        onDismiss = {},
                        onSelect = { selected = it }
                    )
                }
            }
        }
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithTag("web_timeline_dialog").captureRoboImage("build/outputs/roborazzi/$name.png")
        compose.onNodeWithTag("web_timeline_list").performScrollToIndex(201)
        compose.onNodeWithTag("web_timeline_turn_199").assertIsDisplayed().performClick()
        assertEquals(199, selected)
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}

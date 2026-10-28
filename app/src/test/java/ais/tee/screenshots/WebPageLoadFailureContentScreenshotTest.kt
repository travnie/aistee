package ais.tee.screenshots

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import ais.tee.data.model.WebAiService
import ais.tee.ui.screens.WebPageLoadFailure
import ais.tee.ui.screens.WebPageLoadFailureContent
import ais.tee.ui.theme.AisteeTheme
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
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi", application = Application::class)
class WebPageLoadFailureContentScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun connectionFailureOffersRetryAndProviderHome() {
        var retries = 0
        var homes = 0
        compose.setContent {
            AisteeTheme(darkTheme = true) {
                WebPageLoadFailureContent(
                    WebAiService.CHATGPT, WebPageLoadFailure.CONNECTION, canRetry = true,
                    onRetry = { retries++ }, onOpenHome = { homes++ }
                )
            }
        }
        compose.onNodeWithText("Could not load ChatGPT").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed().performClick()
        compose.onNodeWithText("Open ChatGPT home").assertIsDisplayed().performClick()
        assertEquals(1, retries)
        assertEquals(1, homes)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/web_load_connection.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h320dp-xhdpi")
    fun unsafeReplayIsAbsentAndHomeRemainsReachableWithLargeText() {
        var homes = 0
        compose.setContent {
            AisteeTheme(darkTheme = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                    WebPageLoadFailureContent(
                        WebAiService.CHATGPT, WebPageLoadFailure.SECURE_CONNECTION, canRetry = false,
                        onRetry = { error("Failed forms must not be replayed") }, onOpenHome = { homes++ }
                    )
                }
            }
        }
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Open ChatGPT home").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, homes)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/web_load_secure_large_text.png")
    }
}

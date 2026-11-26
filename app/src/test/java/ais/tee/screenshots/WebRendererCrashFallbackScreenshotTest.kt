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
import ais.tee.ui.screens.WebRendererCrashFallback
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
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xhdpi", application = Application::class)
class WebRendererCrashFallbackScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun recognizedConversationOffersBothRecoveryActions() {
        val actions = mutableListOf<String>()
        compose.setContent {
            AisteeTheme(darkTheme = true) {
                WebRendererCrashFallback(
                    WebAiService.CHATGPT,
                    onOpenHome = { actions.add("home") },
                    onReopenConversation = { actions.add("conversation") },
                )
            }
        }
        compose.onNodeWithText("Reopen conversation").assertIsDisplayed().performClick()
        compose.onNodeWithText("Open provider home").assertIsDisplayed().performClick()
        assertEquals(listOf("conversation", "home"), actions)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/web_renderer_crash.png")
    }

    @Test
    @Config(qualifiers = "w610dp-h320dp-xhdpi")
    fun bothRecoveryActionsRemainReachableAtLargeFontScale() {
        val actions = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AisteeTheme(darkTheme = true) {
                    WebRendererCrashFallback(
                        WebAiService.CHATGPT,
                        onOpenHome = { actions.add("home") },
                        onReopenConversation = { actions.add("conversation") },
                    )
                }
            }
        }
        compose.onNodeWithText("Reopen conversation").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Open provider home").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("conversation", "home"), actions)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/web_renderer_crash_large_text.png")
    }

    @Test
    @Config(qualifiers = "w610dp-h320dp-xhdpi")
    fun unavailableConversationOffersHomeAtLargeFontScale() {
        var homes = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AisteeTheme(darkTheme = true) {
                    WebRendererCrashFallback(WebAiService.CLAUDE, onOpenHome = { homes++ })
                }
            }
        }
        compose.onNodeWithText("Reopen conversation").assertDoesNotExist()
        compose.onNodeWithText("Open provider home").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, homes)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/web_renderer_crash_home.png")
    }
}

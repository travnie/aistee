package ais.tee.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import ais.tee.data.model.WebAiService
import ais.tee.ui.screens.WebProviderDrawer
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
@Config(sdk = [34], application = Application::class)
class AdaptiveWebProviderSearchScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun compactModalLargeText() = capture("web_provider_search_compact_font150", false, 1.5f)

    @Test
    @Config(qualifiers = "w610dp-h500dp-xxhdpi")
    fun mediumModal() = capture("web_provider_search_medium", false)

    @Test
    @Config(qualifiers = "w900dp-h1000dp-xxhdpi")
    fun expandedPersistent() = capture("web_provider_search_expanded", true)

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun compactRecentShortcutsLargeText() =
        captureRecent("web_recent_providers_compact_font150", false, 1.5f)

    @Test
    @Config(qualifiers = "w900dp-h1000dp-xxhdpi")
    fun expandedRecentShortcuts() = captureRecent("web_recent_providers_expanded", true)

    private fun captureRecent(name: String, persistent: Boolean, fontScale: Float = 1f) {
        val selections = mutableListOf<WebAiService>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AisteeTheme {
                    val drawer: @androidx.compose.runtime.Composable () -> Unit = {
                        WebProviderDrawer(
                            persistent = persistent,
                            selectedService = WebAiService.CHATGPT,
                            activityStatuses = emptyMap(),
                            providerFavicons = emptyMap(),
                            favoriteServices = setOf(WebAiService.GEMINI),
                            recentServices = listOf(
                                WebAiService.CHATGPT, WebAiService.CLAUDE,
                                WebAiService.GEMINI, WebAiService.GROK
                            ),
                            onToggleFavorite = {},
                            onSelectService = { selections.add(it) },
                            onOpenNativeCompare = {},
                            onOpenStudio = {},
                        )
                    }
                    if (persistent) drawer()
                    else ModalNavigationDrawer(
                        drawerState = rememberDrawerState(DrawerValue.Open),
                        drawerContent = drawer,
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.onNodeWithTag("btn_recent_web_service_chatgpt").assertDoesNotExist()
        compose.onNodeWithTag("btn_recent_web_service_gemini").assertDoesNotExist()
        compose.onNodeWithTag("btn_recent_web_service_claude").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        compose.onNodeWithTag("btn_recent_web_service_claude").performClick()
        compose.runOnIdle { assertEquals(listOf(WebAiService.CLAUDE), selections) }
    }

    private fun capture(name: String, persistent: Boolean, fontScale: Float = 1f) {
        val selections = mutableListOf<WebAiService>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AisteeTheme {
                    val drawer: @androidx.compose.runtime.Composable () -> Unit = {
                        WebProviderDrawer(
                            persistent = persistent,
                            selectedService = WebAiService.CLAUDE,
                            activityStatuses = emptyMap(),
                            providerFavicons = emptyMap(),
                            favoriteServices = setOf(WebAiService.CHATGPT),
                            onToggleFavorite = {},
                            onSelectService = { selections.add(it) },
                            onOpenNativeCompare = {},
                            onOpenStudio = {},
                        )
                    }
                    if (persistent) drawer()
                    else ModalNavigationDrawer(
                        drawerState = rememberDrawerState(DrawerValue.Open),
                        drawerContent = drawer,
                    ) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.onNodeWithTag("web_provider_search").assertIsDisplayed().performTextInput("grok")
        compose.onNodeWithTag("tab_web_service_chatgpt").assertDoesNotExist()
        compose.onNodeWithTag("tab_web_service_grok").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("web_provider_search").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        compose.runOnIdle { assertEquals(emptyList<WebAiService>(), selections) }
        compose.onNodeWithTag("tab_web_service_grok").performClick()
        compose.runOnIdle { assertEquals(listOf(WebAiService.GROK), selections) }
        compose.onNodeWithTag("tab_web_service_chatgpt").performScrollTo().assertIsDisplayed()
    }
}

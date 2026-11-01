package ais.tee.screenshots

import ais.tee.data.engine.*
import ais.tee.data.model.*
import ais.tee.ui.screens.McpDiscoveryPanel
import ais.tee.ui.screens.McpPreviewState
import ais.tee.ui.theme.AisteeTheme
import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h850dp-xxhdpi")
class McpSavedServersTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compact() = exerciseSavedServers("mcp_saved_servers_compact_font150", fontScale = 1.5f)

    @Test @Config(qualifiers = "w610dp-h500dp-xxhdpi")
    fun medium() = exerciseSavedServers("mcp_saved_servers_medium")

    @Test @Config(qualifiers = "w900dp-h1000dp-xxhdpi")
    fun expanded() = exerciseSavedServers("mcp_saved_servers_expanded")

    private fun exerciseSavedServers(screenshot: String, fontScale: Float = 1f) {
        val store = McpServerStore(FakeMcpServerPersistence())
        val state = McpPreviewState()
        val existing = (store.save(null, "Work tools", "https://work.example.org/mcp") as McpServerSaveResult.Saved).server
        var calls = 0
        var cancelled = false
        var discoveredId: String? = null
        var discoveredEndpoint: String? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AisteeTheme(darkTheme = true) {
                    Surface {
                        McpDiscoveryPanel(state, serverStore = store, discovery = { endpoint, id ->
                            calls++
                            discoveredId = id
                            discoveredEndpoint = endpoint
                            try { awaitCancellation() } finally { cancelled = true }
                        })
                    }
                }
            }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Saved servers (1/8)").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("mcp_server_name").performScrollTo().performTextInput("Personal")
        compose.onNodeWithTag("mcp_endpoint").performScrollTo().performTextInput("https://personal.example.org/mcp")
        compose.onNodeWithTag(SAVE_TAG).performScrollTo().performClick()
        compose.waitUntil(5_000) { store.load().servers.size == 2 && state.serverId != null && !state.savedServers.busy }
        compose.onNodeWithTag("mcp_discovery_panel").performScrollToIndex(0)
        compose.onNodeWithTag("mcp_discovery_panel").captureRoboImage("build/outputs/roborazzi/$screenshot.png")
        assertEquals(0, calls)
        compose.onNodeWithTag("mcp_discover").performScrollTo().performClick()
        compose.waitUntil(5_000) { calls == 1 }
        compose.runOnIdle {
            assertEquals(state.serverId, discoveredId)
            assertEquals("https://personal.example.org/mcp", discoveredEndpoint)
        }
        compose.onNodeWithTag("mcp_choose_server").performScrollTo().performClick()
        compose.onNodeWithTag("mcp_select_${existing.id}").performClick()
        compose.waitUntil(5_000) { cancelled }
        compose.runOnIdle {
            assertEquals(existing.id, state.serverId)
            assertEquals(existing.endpoint, state.endpoint)
            assertFalse(state.busy)
            assertNull(state.outcome)
            assertEquals(1, calls)
        }
        compose.onNodeWithTag("mcp_remove_server").performScrollTo().performClick()
        compose.waitUntil(5_000) { store.load().servers.size == 1 && state.serverId == null }
        compose.runOnIdle {
            assertEquals("", state.endpoint)
            assertEquals(1, calls)
        }
    }

    @Test fun unreadableStorageDisablesSavingAndRequiresConfirmedReset() {
        val persistence = FakeMcpServerPersistence().apply { present = true }
        val store = McpServerStore(persistence)
        val state = McpPreviewState()
        compose.setContent { AisteeTheme { McpDiscoveryPanel(state, serverStore = store) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(UNREADABLE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UNREADABLE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Reset saved servers").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(persistence.present)
        compose.onNodeWithText("Reset saved servers").performScrollTo().performClick()
        compose.onNodeWithText("Delete saved servers").performClick()
        compose.waitUntil(5_000) { !persistence.present }
        compose.onNodeWithTag(UNREADABLE_TAG).assertDoesNotExist()
    }

    @Test fun oversizedPasteIsKeptForCorrectionAndCannotBeSaved() {
        val store = McpServerStore(FakeMcpServerPersistence())
        val state = McpPreviewState()
        compose.setContent { AisteeTheme { McpDiscoveryPanel(state, serverStore = store) } }
        compose.waitUntil(5_000) { state.savedServers.snapshot != null }
        val oversized = "https://tools.example.org/" + "a".repeat(MAX_MCP_ENDPOINT_LENGTH)
        compose.onNodeWithTag("mcp_server_name").performScrollTo().performTextInput("Work")
        compose.onNodeWithTag("mcp_endpoint").performScrollTo().performTextInput(oversized)
        compose.onNodeWithTag(SAVE_TAG).performScrollTo().performClick()
        compose.waitUntil(5_000) { state.savedServers.error != null }
        compose.runOnIdle { assertEquals(oversized, state.endpoint) }
        assertTrue(store.load().servers.isEmpty())
    }
}

private const val SAVE_TAG = "mcp_save_server"
private const val UNREADABLE_TAG = "mcp_storage_unreadable"

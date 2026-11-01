package ais.tee.screenshots

import ais.tee.data.engine.*
import ais.tee.data.model.NativeToolResult
import ais.tee.ui.screens.McpDiscoveryPanel
import ais.tee.ui.screens.McpPreviewState
import ais.tee.ui.theme.AisteeTheme
import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
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
class McpManualCallTest {
    @get:Rule val compose = createComposeRule()

    private fun preview() = McpPreviewState().apply {
        val catalog = toolCallCatalog()
        serverId = "demo"
        serverLabel = "Test server"
        endpoint = CALL_ENDPOINT
        discoveredEndpoint = CALL_ENDPOINT
        outcome = McpDiscoveryOutcome.Success(catalog)
        selectedTool = catalog.browse().single().nativeName
        draftArguments = CALL_ARGUMENTS
    }

    @Test fun compactLargeText() = reviewFlow("mcp_call_compact_font150", 1.5f)
    @Test @Config(qualifiers = "w610dp-h500dp-xxhdpi")
    fun medium() = reviewFlow("mcp_call_medium")
    @Test @Config(qualifiers = "w900dp-h1000dp-xxhdpi")
    fun expanded() = reviewFlow("mcp_call_expanded")

    private fun reviewFlow(screenshot: String, fontScale: Float = 1f) {
        val state = preview()
        var calls = 0
        val store = McpServerStore(FakeMcpServerPersistence())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AisteeTheme(darkTheme = true) {
                    Surface {
                        McpDiscoveryPanel(state, serverStore = store, toolCaller = { review ->
                            assertTrue(review.takeForExecution())
                            calls++
                            McpToolCallOutcome.Completed(NativeToolResult(review.call.callId,
                                review.call.name, "Manual result"))
                        })
                    }
                }
            }
        }
        compose.onNodeWithTag(REVIEW_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("mcp_review_endpoint").assertTextEquals(CALL_ENDPOINT)
        compose.onNodeWithTag("mcp_review_arguments").assertTextEquals(CALL_ARGUMENTS)
        assertEquals(0, calls)
        compose.onNodeWithTag("mcp_call_review").captureRoboImage("build/outputs/roborazzi/$screenshot.png")
        compose.onNodeWithTag("mcp_deny_call").performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag(REVIEW_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(RUN_TAG).performClick()
        compose.waitUntil(5_000) { calls == 1 && !state.busy }
        compose.onNodeWithTag("mcp_call_output").performScrollTo().assertTextEquals("Manual result")
        compose.onNodeWithTag(REVIEW_TAG).performScrollTo().performClick()
        compose.runOnIdle { state.newServer() }
        compose.onNodeWithTag("mcp_call_review").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun stoppingWaitCancelsTheRequestWithoutAutomaticallyRetrying() {
        val state = preview()
        var requests = 0
        var cancelled = false
        val store = McpServerStore(FakeMcpServerPersistence())
        compose.setContent {
            AisteeTheme {
                Surface {
                    McpDiscoveryPanel(state, serverStore = store, toolCaller = {
                        requests++
                        try { awaitCancellation() } finally { cancelled = true }
                    })
                }
            }
        }
        compose.onNodeWithTag(REVIEW_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(RUN_TAG).performClick()
        compose.waitUntil(5_000) { state.calling }
        compose.onNodeWithTag("mcp_stop_call").performScrollTo().performClick()
        compose.waitUntil(5_000) { cancelled && !state.busy }
        compose.onNodeWithTag("mcp_uncertain_call_notice").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, requests)
            assertEquals(McpToolCallOutcome.Failure(McpToolCallFailure.CANCELLED), state.callOutcome)
        }
        compose.onNodeWithTag(REVIEW_TAG).performScrollTo().performClick()
        compose.runOnIdle { state.draftArguments = "{}" }
        compose.onNodeWithTag(RUN_TAG).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.runOnIdle { state.newServer() }
        compose.onNodeWithTag("mcp_uncertain_call_notice").assertIsDisplayed()
        compose.onNodeWithTag("mcp_dismiss_call_notice").performClick()
        compose.onNodeWithTag("mcp_uncertain_call_notice").assertDoesNotExist()
    }
}

private const val REVIEW_TAG = "mcp_review_call"
private const val RUN_TAG = "mcp_run_once"

package ais.tee.screenshots

import ais.tee.data.engine.*
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
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
class McpAuthorizationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactLargeText() = inspectionFlow("mcp_auth_compact_font150", 1.5f)
    @Test @Config(qualifiers = "w610dp-h500dp-xxhdpi")
    fun medium() = inspectionFlow("mcp_auth_medium")
    @Test @Config(qualifiers = "w900dp-h1000dp-xxhdpi")
    fun expanded() = inspectionFlow("mcp_auth_expanded")

    private fun inspectionFlow(screenshot: String, fontScale: Float = 1f) {
        val requests = AtomicInteger()
        val endpoint = "https://tools.example.org/mcp"
        val state = McpPreviewState().apply {
            this.endpoint = endpoint
            outcome = McpDiscoveryOutcome.AuthorizationRequired(McpAuthorizationChallenge(endpoint))
        }
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requests.incrementAndGet()
            val request = chain.request()
            val body = if (request.url.host == "tools.example.org")
                """{"resource":"https://tools.example.org/mcp","authorization_servers":["https://auth.example.org"],"scopes_supported":["tools:read"]}"""
            else """{"issuer":"https://auth.example.org","authorization_endpoint":"https://auth.example.org/authorize","token_endpoint":"https://auth.example.org/token","code_challenge_methods_supported":["S256"],"client_id_metadata_document_supported":true}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val store = McpServerStore(FakeMcpServerPersistence())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AisteeTheme(darkTheme = true) {
                    Surface { McpDiscoveryPanel(state, serverStore = store,
                        authorizationInspector = McpAuthorizationInspector(client)) }
                }
            }
        }
        assertEquals(0, requests.get())
        clickVisible("mcp_inspect_resource")
        compose.waitUntil(5_000) { state.resourceInspection is McpAuthorizationInspection.Found && !state.busy }
        assertEquals(1, requests.get())
        compose.onNodeWithTag("mcp_oauth_scopes").performScrollTo()
            .assertTextEquals("Requested permissions: tools:read")
        clickVisible("mcp_inspect_issuer_0")
        compose.waitUntil(5_000) { state.authorizationInspection is McpAuthorizationInspection.Found && !state.busy }
        assertEquals(2, requests.get())
        compose.onNodeWithTag("mcp_oauth_server").performScrollTo()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$screenshot.png")
        compose.runOnIdle { state.newServer() }
        compose.onNodeWithTag("mcp_authorization").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(state.resourceInspection)
            assertNull(state.authorizationInspection)
            assertEquals(2, requests.get())
        }
    }

    private fun clickVisible(tag: String) {
        compose.onNodeWithTag("mcp_discovery_panel").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
}

package ais.tee.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ais.tee.data.engine.McpDiscoveryFailure
import ais.tee.data.engine.McpDiscoveryOutcome
import ais.tee.data.model.McpServerConfiguration
import ais.tee.data.model.McpToolCatalog
import ais.tee.data.model.McpToolSummary
import ais.tee.data.engine.McpServerStore
import ais.tee.data.engine.McpHttpDiscovery
import ais.tee.data.engine.McpToolCallReview
import ais.tee.data.engine.McpToolCallOutcome
import ais.tee.data.engine.McpToolCallFailure
import ais.tee.share.copyPlainTextToClipboard
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** In-memory state survives Studio tab switches but never leaves the Studio destination. */
internal class McpPreviewState {
    val savedServers = McpSavedServersState()
    var serverId by mutableStateOf<String?>(null)
    var serverLabel by mutableStateOf("")
    var endpoint by mutableStateOf("")
    var discoveredEndpoint by mutableStateOf<String?>(null)
    var callReview by mutableStateOf<McpToolCallReview?>(null)
    var callOutcome by mutableStateOf<McpToolCallOutcome?>(null)
    var calling by mutableStateOf(false)
    var query by mutableStateOf("")
    var outcome by mutableStateOf<McpDiscoveryOutcome?>(null)
    var selectedTool by mutableStateOf<String?>(null)
    var copiedTool by mutableStateOf<String?>(null)
    var draftArguments by mutableStateOf("{}")
    var copiedCallDraft by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var epoch = 0
    var job: Job? = null

    fun selectServer(server: McpServerConfiguration) {
        reset()
        serverId = server.id
        serverLabel = server.label
        endpoint = server.endpoint
    }

    fun newServer() {
        reset()
        serverId = null
        serverLabel = ""
        endpoint = ""
    }

    fun invalidateCall() {
        if (calling) cancelPending()
        callReview = null
        callOutcome = null
    }

    fun cancelPending() {
        epoch++
        if (calling) callOutcome = McpToolCallOutcome.Failure(McpToolCallFailure.CANCELLED)
        calling = false
        callReview = null
        job?.cancel()
        job = null
        busy = false
    }

    fun reset() {
        cancelPending()
        outcome = null
        discoveredEndpoint = null
        callOutcome = null
        selectedTool = null
        copiedTool = null
        copiedCallDraft = null
        draftArguments = "{}"
        query = ""
    }
}

/** Saved configurations, ephemeral discovery and user-reviewed manual tool calls. */
@Composable
internal fun McpDiscoveryPanel(
    state: McpPreviewState,
    modifier: Modifier = Modifier,
    serverStore: McpServerStore? = null,
    discovery: (suspend (String, String) -> McpDiscoveryOutcome)? = null,
    toolCaller: (suspend (McpToolCallReview) -> McpToolCallOutcome)? = null,
) {
    val transport = remember { McpHttpDiscovery() }
    val scope = rememberCoroutineScope()
    val onRun: (McpToolCallReview) -> Unit = { review ->
        state.executeCall(review, scope, toolCaller ?: transport::callToolChecked)
    }
    val context = LocalContext.current
    val store = remember(context, serverStore) { serverStore ?: McpServerStore(context) }
    DisposableEffect(state) {
        onDispose { state.cancelPending() }
    }
    val catalog = (state.outcome as? McpDiscoveryOutcome.Success)?.catalog
    val tools = catalog?.browse(state.query, limit = 128).orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("mcp_discovery_panel"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("MCP tools", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(
                    "Test a public HTTPS MCP 2026-07-28 endpoint. This is one unauthenticated " +
                        "tools/list discovery. Tapping Discover " +
                        "contacts the server, which can see your network address.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { McpSavedServerControls(state, store) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = click@{
                        // State changes synchronously, before Compose recomposes the disabled button.
                        if (state.busy) return@click
                        state.reset()
                        state.busy = true
                        val requestEpoch = ++state.epoch
                        val url = state.endpoint.trim()
                        val serverId = state.serverId ?: "preview"
                        state.outcome = null
                        state.job = scope.launch {
                            val response = discovery?.invoke(url, serverId)
                                ?: transport.discoverChecked(url, serverId)
                            if (requestEpoch == state.epoch) {
                                state.outcome = response
                                state.discoveredEndpoint = if (response is McpDiscoveryOutcome.Success)
                                    ais.tee.data.engine.validatedMcpEndpoint(url)?.toString() else null
                                state.busy = false
                                state.job = null
                            }
                        }
                    },
                    enabled = state.endpoint.isNotBlank() && !state.busy,
                    modifier = Modifier.testTag("mcp_discover"),
                ) { Text(if (state.busy) "Discovering…" else "Discover tools") }
                Text(
                    "No account cookies, API keys, chats or local files are attached. " +
                        "Manual tool calls require a separate review and confirmation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (val response = state.outcome) {
            is McpDiscoveryOutcome.Success -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(response.catalog.count.toString() + " discovered tools",
                            style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = {
                                state.invalidateCall()
                                state.query = it.take(256)
                                state.selectedTool = null
                                state.copiedCallDraft = null
                                state.draftArguments = "{}"
                            },
                            label = { Text("Search tools") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("mcp_search"),
                        )
                        if (tools.isEmpty()) Text("No matching tools.")
                    }
                }
                items(tools, key = { it.nativeName }) { summary ->
                    McpToolCard(state, response.catalog, summary, onRun)
                }
            }
            is McpDiscoveryOutcome.Failure -> item {
                Text(
                    text = mcpDiscoveryFailureMessage(response.reason),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("mcp_discovery_error"),
                )
            }
            null -> Unit
        }
    }
}

@Composable
private fun McpToolCard(state: McpPreviewState, catalog: McpToolCatalog, summary: McpToolSummary,
                        onRun: (McpToolCallReview) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(summary.title, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(summary.remoteName, style = MaterialTheme.typography.labelSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (summary.description != summary.remoteName) {
                Text(summary.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = {
                    state.invalidateCall()
                    state.selectedTool = if (state.selectedTool == summary.nativeName) null
                    else summary.nativeName
                    state.copiedTool = null
                    state.copiedCallDraft = null
                    state.draftArguments = "{}"
                }
            ) {
                Text(if (state.selectedTool == summary.nativeName) "Hide schema"
                    else "Inspect schema")
            }
            if (state.selectedTool == summary.nativeName) {
                McpToolDetails(state, catalog, summary.nativeName, onRun)
            }
        }
    }
}

@Composable
private fun McpToolDetails(state: McpPreviewState, catalog: McpToolCatalog, nativeName: String,
                           onRun: (McpToolCallReview) -> Unit) {
    val context = LocalContext.current
    val schema = catalog.loadDefinition(nativeName)
        ?.inputSchema?.toString()
    if (schema != null) {
        Text(schema.take(2000) +
            if (schema.length > 2000) "\n… Preview truncated" else "",
            style = MaterialTheme.typography.bodySmall)
        val spec = catalog.exportToolSpec(nativeName)
        if (spec != null) {
            OutlinedButton(
                onClick = {
                    if (copyPlainTextToClipboard(
                            context, "MCP tool spec", spec, sensitive = true
                        )) state.copiedTool = nativeName
                },
                modifier = Modifier.testTag("mcp_copy_selected_tool"),
            ) {
                Text(if (state.copiedTool == nativeName)
                    "Copied spec" else "Copy tool spec")
            }
        }
        OutlinedTextField(
            value = state.draftArguments,
            onValueChange = {
                state.invalidateCall()
                state.draftArguments = it
                state.copiedCallDraft = null
            },
            label = { Text("tools/call arguments (JSON object)") },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("mcp_call_draft_args"),
        )
        val draft = catalog.exportToolCallDraft(
            nativeName, state.draftArguments)
        OutlinedButton(
            onClick = {
                if (draft != null && copyPlainTextToClipboard(
                        context, "MCP tools/call draft", draft, sensitive = true
                    )) state.copiedCallDraft = nativeName
            },
            enabled = draft != null,
            modifier = Modifier.testTag("mcp_copy_call_draft"),
        ) {
            Text(if (state.copiedCallDraft == nativeName)
                "Copied call draft" else "Copy tools/call draft")
        }
        Text(
            "Local draft only. JSON syntax is checked, not the " +
                "server schema. Copying does not run a tool.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        McpManualToolCallControls(state, catalog, nativeName, onRun)
    }
}

private fun mcpDiscoveryFailureMessage(reason: McpDiscoveryFailure): String = when (reason) {
    McpDiscoveryFailure.INVALID_ENDPOINT ->
        "Enter a public HTTPS URL without credentials, query or fragment."
    McpDiscoveryFailure.INVALID_RESPONSE ->
        "No compatible MCP 2026-07-28 catalog. Legacy or authenticated " +
            "servers are not supported in this preview yet."
    McpDiscoveryFailure.NETWORK_FAILURE ->
        "Cannot reach this endpoint. Check the address and network."
    McpDiscoveryFailure.TIMEOUT ->
        "Discovery timed out before all pages arrived."
}

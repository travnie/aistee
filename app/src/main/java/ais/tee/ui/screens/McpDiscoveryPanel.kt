package ais.tee.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ais.tee.data.engine.McpDiscoveryFailure
import ais.tee.data.engine.McpDiscoveryOutcome
import ais.tee.data.engine.McpHttpDiscovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Manual, ephemeral preview: no saved endpoints, background activity or tool execution. */
@Composable
internal fun McpDiscoveryPanel(modifier: Modifier = Modifier) {
    val transport = remember { McpHttpDiscovery() }
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<McpDiscoveryOutcome?>(null) }
    var selectedTool by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var epoch by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun reset() {
        epoch++
        job?.cancel()
        job = null
        busy = false
        outcome = null
        selectedTool = null
        query = ""
    }
    val catalog = (outcome as? McpDiscoveryOutcome.Success)?.catalog
    val tools = catalog?.browse(query, limit = 128).orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("mcp_discovery_panel"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("MCP discovery", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(
                    "Test a public HTTPS MCP 2026-07-28 endpoint. This is one unauthenticated " +
                        "tools/list discovery, not a saved server connection. Tapping Discover " +
                        "contacts the server, which can see your network address.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it.take(2048); reset() },
                    label = { Text("MCP endpoint (HTTPS)") },
                    placeholder = { Text("https://example.org/mcp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("mcp_endpoint"),
                )
                Button(
                    onClick = {
                        val requestEpoch = ++epoch
                        val url = endpoint.trim()
                        job?.cancel()
                        outcome = null
                        busy = true
                        job = scope.launch {
                            val response = transport.discoverChecked(url, "preview")
                            if (requestEpoch == epoch) {
                                outcome = response
                                busy = false
                            }
                        }
                    },
                    enabled = endpoint.isNotBlank() && !busy,
                    modifier = Modifier.testTag("mcp_discover"),
                ) { Text(if (busy) "Discovering…" else "Discover tools") }
                Text(
                    "No account cookies, API keys, chats or local files are attached. " +
                        "This preview cannot execute tools.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (val response = outcome) {
            is McpDiscoveryOutcome.Success -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(response.catalog.count.toString() + " discovered tools",
                            style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it.take(256); selectedTool = null },
                            label = { Text("Search tools") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("mcp_search"),
                        )
                        if (tools.isEmpty()) Text("No matching tools.")
                    }
                }
                items(tools, key = { it.nativeName }) { summary ->
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
                                    selectedTool = if (selectedTool == summary.nativeName) null
                                    else summary.nativeName
                                }
                            ) {
                                Text(if (selectedTool == summary.nativeName) "Hide schema"
                                    else "Inspect schema")
                            }
                            if (selectedTool == summary.nativeName) {
                                val schema = response.catalog.loadDefinition(summary.nativeName)
                                    ?.inputSchema?.toString()
                                if (schema != null) {
                                    Text(schema.take(2000) +
                                        if (schema.length > 2000) "\n… Preview truncated" else "",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            is McpDiscoveryOutcome.Failure -> item {
                Text(
                    text = when (response.reason) {
                        McpDiscoveryFailure.INVALID_ENDPOINT ->
                            "Enter a public HTTPS URL without credentials, query or fragment."
                        McpDiscoveryFailure.INVALID_RESPONSE ->
                            "No compatible MCP 2026-07-28 catalog. Legacy or authenticated " +
                                "servers are not supported in this preview yet."
                        McpDiscoveryFailure.NETWORK_FAILURE ->
                            "Cannot reach this endpoint. Check the address and network."
                        McpDiscoveryFailure.TIMEOUT ->
                            "Discovery timed out before all pages arrived."
                    },
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("mcp_discovery_error"),
                )
            }
            null -> Unit
        }
    }
}

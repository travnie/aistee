package ais.tee.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ais.tee.data.engine.*
import ais.tee.data.model.*
import kotlinx.coroutines.*

/** Selecting and saving are local operations; only the separate Discover action contacts a server. */
@Composable
internal fun McpSavedServerControls(state: McpPreviewState, store: McpServerStore) {
    val scope = rememberCoroutineScope()
    val saved = state.savedServers
    val snapshot = saved.snapshot
    val busy = saved.busy
    var menu by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(store) { saved.load(store) }
    val servers = snapshot?.servers.orEmpty()
    val writable = snapshot?.readable == true && !busy

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Saved servers (${servers.size}/$MAX_MCP_SERVERS)", style = MaterialTheme.typography.titleSmall)
        Text("Stored encrypted on this device and excluded from Android backup. " +
            "Saving or selecting does not connect or grant tool access.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(
                    onClick = { menu = true }, enabled = servers.isNotEmpty() && !busy,
                    modifier = Modifier.testTag("mcp_choose_server"),
                ) { Text("Choose server") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    servers.forEach { server ->
                        DropdownMenuItem(
                            text = { Text(server.label) },
                            onClick = { state.selectServer(server); saved.error = null; menu = false },
                            modifier = Modifier.testTag("mcp_select_${server.id}"),
                        )
                    }
                }
            }
            TextButton(
                onClick = { state.newServer(); saved.error = null }, enabled = !busy,
                modifier = Modifier.testTag("mcp_new_server"),
            ) { Text("New server") }
        }
        OutlinedTextField(
            value = state.serverLabel,
            onValueChange = { state.serverLabel = it; saved.error = null },
            label = { Text("Server name") }, singleLine = true, enabled = !busy,
            isError = state.serverLabel.length > MAX_MCP_SERVER_LABEL,
            modifier = Modifier.fillMaxWidth().testTag("mcp_server_name"),
        )
        OutlinedTextField(
            value = state.endpoint,
            onValueChange = { state.endpoint = it; state.reset(); saved.error = null },
            label = { Text("MCP endpoint (HTTPS)") },
            placeholder = { Text("https://example.org/mcp") },
            singleLine = true, enabled = !busy,
            isError = state.endpoint.length > MAX_MCP_ENDPOINT_LENGTH,
            modifier = Modifier.fillMaxWidth().testTag("mcp_endpoint"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { saved.save(scope, store, state) },
                enabled = writable && state.serverLabel.isNotBlank() && state.endpoint.isNotBlank(),
                modifier = Modifier.testTag("mcp_save_server"),
            ) { Text(if (state.serverId == null) "Save server" else "Update server") }
            if (state.serverId != null) {
                TextButton(
                    onClick = { saved.remove(scope, store, state) }, enabled = writable, modifier = Modifier.testTag("mcp_remove_server"),
                ) { Text("Remove") }
            }
        }
        if (snapshot == null) Text("Loading saved servers…", style = MaterialTheme.typography.bodySmall)
        if (snapshot?.readable == false) {
            Text("Saved servers cannot be read. Saving is disabled to protect the existing record.",
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("mcp_storage_unreadable"))
            TextButton(onClick = { confirmReset = true }, enabled = !busy) { Text("Reset saved servers") }
        }
        saved.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("mcp_save_error")) }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset saved servers?") },
            text = { Text("This deletes the unreadable saved endpoint configurations from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    saved.clear(scope, store, state)
                }) { Text("Delete saved servers") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

/** Lives with the Studio preview, including across MCP tab disposal during a disk commit. */
internal class McpSavedServersState {
    var snapshot by mutableStateOf<McpServerSnapshot?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    suspend fun load(store: McpServerStore) {
        if (snapshot == null && !busy) snapshot = withContext(Dispatchers.IO) { store.load() }
    }

    fun save(scope: CoroutineScope, store: McpServerStore, state: McpPreviewState) {
        val id = state.serverId
        val label = state.serverLabel
        val endpoint = state.endpoint
        mutate(scope, store) {
            when (val result = withContext(Dispatchers.IO) { store.save(id, label, endpoint) }) {
                is McpServerSaveResult.Saved -> state.selectServer(result.server)
                is McpServerSaveResult.Failure -> error = saveFailureMessage(result.reason)
            }
        }
    }

    fun remove(scope: CoroutineScope, store: McpServerStore, state: McpPreviewState) {
        val id = state.serverId ?: return
        mutate(scope, store) {
            if (withContext(Dispatchers.IO) { store.remove(id) }) state.newServer()
            else error = "Cannot remove this saved configuration."
        }
    }

    fun clear(scope: CoroutineScope, store: McpServerStore, state: McpPreviewState) = mutate(scope, store) {
        if (withContext(Dispatchers.IO) { store.clear() }) state.newServer()
        else error = "Cannot reset saved servers."
    }

    private fun mutate(scope: CoroutineScope, store: McpServerStore, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        // A synchronous preference commit cannot be cancelled midway. Keep its short completion
        // and state reconciliation together; no network operation runs in this scope.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try { block() } finally {
                    snapshot = withContext(Dispatchers.IO) { store.load() }
                    busy = false
                }
            }
        }
    }
}

private fun saveFailureMessage(reason: McpServerSaveFailure): String = when (reason) {
    McpServerSaveFailure.INVALID_LABEL -> "Enter a name of 1–60 characters without control characters."
    McpServerSaveFailure.INVALID_ENDPOINT -> "Enter a public HTTPS URL of at most 2048 characters without credentials, query or fragment."
    McpServerSaveFailure.DUPLICATE_ENDPOINT -> "This endpoint is already saved. Choose its existing configuration."
    McpServerSaveFailure.DUPLICATE_NAME -> "This server name is already used. Choose a distinct name."
    McpServerSaveFailure.FULL -> "Up to $MAX_MCP_SERVERS servers can be saved. Remove one first."
    McpServerSaveFailure.STORAGE -> "Cannot save this configuration. Try again or reopen Studio."
}

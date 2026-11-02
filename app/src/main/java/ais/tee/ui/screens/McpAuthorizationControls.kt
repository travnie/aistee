package ais.tee.ui.screens

import ais.tee.data.engine.McpAuthorizationChallenge
import ais.tee.data.engine.McpAuthorizationFailure
import ais.tee.data.engine.McpAuthorizationInspection
import ais.tee.data.engine.McpAuthorizationInspector
import ais.tee.data.engine.McpDiscoveryOutcome
import ais.tee.data.engine.validatedMcpEndpoint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun McpAuthorizationControls(
    state: McpPreviewState,
    challenge: McpAuthorizationChallenge,
    scope: CoroutineScope,
    inspector: McpAuthorizationInspector,
) {
    Column(Modifier.fillMaxWidth().testTag("mcp_authorization"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sign-in required", style = MaterialTheme.typography.titleMedium)
        Text("This server requires authorization. Check its public sign-in details before connecting.",
            style = MaterialTheme.typography.bodySmall)
        Text("Resource metadata: " + (challenge.resourceMetadata?.take(2_048)
            ?: "Well-known location at ${challenge.endpoint}"), style = MaterialTheme.typography.bodySmall)
        Text("This check contacts that address. Each issuer is contacted only when you select it below. " +
            "These details are supplied by the server and do not establish trust.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            state.resourceInspection = null
            state.authorizationInspection = null
            state.inspectAuthorization(challenge, scope, { inspector.inspectResource(challenge) }) {
                state.resourceInspection = it
            }
        }, enabled = !state.busy, modifier = Modifier.testTag("mcp_inspect_resource")) {
            Text("Check sign-in details")
        }
        when (val resourceResult = state.resourceInspection) {
            is McpAuthorizationInspection.Found -> {
                val resource = resourceResult.metadata
                Text("Resource: ${resource.resource}", style = MaterialTheme.typography.bodySmall)
                Text("Requested permissions: " + resource.scopes.joinToString(" ").ifEmpty { "Not specified" },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mcp_oauth_scopes"))
                resource.authorizationServers.forEachIndexed { index, issuer ->
                    Text(issuer, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        state.authorizationInspection = null
                        state.inspectAuthorization(challenge, scope, { inspector.inspectServer(resource, issuer) }) {
                            state.authorizationInspection = it
                        }
                    }, enabled = !state.busy, modifier = Modifier.testTag("mcp_inspect_issuer_$index")) {
                        Text("Inspect issuer ${index + 1}")
                    }
                }
            }
            is McpAuthorizationInspection.Failed -> InspectionError(resourceResult.reason)
            null -> Unit
        }
        when (val serverResult = state.authorizationInspection) {
            is McpAuthorizationInspection.Found -> {
                val server = serverResult.metadata
                Column(Modifier.testTag("mcp_oauth_server"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Issuer: ${server.issuer}", style = MaterialTheme.typography.bodySmall)
                    Text("Authorization: ${server.authorizationEndpoint}", style = MaterialTheme.typography.bodySmall)
                    Text("Token endpoint: ${server.tokenEndpoint}", style = MaterialTheme.typography.bodySmall)
                    Text("PKCE S256: ${if (server.supportsS256) "Advertised" else "Not advertised"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Client metadata registration: ${if (server.supportsClientMetadata) "Advertised" else "Not advertised"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Legacy dynamic registration: ${if (server.hasRegistrationEndpoint) "Advertised" else "Not advertised"}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            is McpAuthorizationInspection.Failed -> InspectionError(serverResult.reason)
            null -> Unit
        }
        if (state.busy) {
            Text("Checking sign-in details…", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = state::cancelPending, modifier = Modifier.testTag("mcp_stop_auth_inspection")) {
                Text("Stop checking")
            }
        }
        Text("Sign-in is not available in this preview. No account is connected and no tool call is retried.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InspectionError(reason: McpAuthorizationFailure) {
    Text(when (reason) {
        McpAuthorizationFailure.INVALID_METADATA -> "The sign-in details are missing, incompatible or do not match this server."
        McpAuthorizationFailure.NETWORK_FAILURE -> "Cannot reach the metadata address. Check the network and try again."
        McpAuthorizationFailure.TIMEOUT -> "Checking sign-in details timed out."
    }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("mcp_oauth_error"))
}

/** One explicit check at a time; edits, server switches and disposal invalidate late responses. */
internal fun <T : Any> McpPreviewState.inspectAuthorization(
    challenge: McpAuthorizationChallenge,
    scope: CoroutineScope,
    check: suspend () -> McpAuthorizationInspection<T>,
    accept: (McpAuthorizationInspection<T>) -> Unit,
) {
    fun current() = (outcome as? McpDiscoveryOutcome.AuthorizationRequired)?.challenge === challenge &&
        validatedMcpEndpoint(endpoint) == validatedMcpEndpoint(challenge.endpoint)
    if (busy || !current()) return
    busy = true
    val requestEpoch = ++epoch
    job = scope.launch {
        try {
            val result = check()
            if (epoch == requestEpoch && current()) accept(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { // skipcq: KT-W1064
            // Injectable callbacks can throw; do not expose remote exception messages.
            if (epoch == requestEpoch && current()) accept(
                McpAuthorizationInspection.Failed(McpAuthorizationFailure.INVALID_METADATA)
            )
        } finally {
            if (epoch == requestEpoch) {
                busy = false
                job = null
            }
        }
    }
}

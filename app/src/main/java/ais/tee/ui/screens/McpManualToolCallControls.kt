package ais.tee.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ais.tee.data.engine.*
import ais.tee.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun McpManualToolCallControls(
    state: McpPreviewState, catalog: McpToolCatalog, nativeName: String,
    onRun: (McpToolCallReview) -> Unit,
) {
    val decision = catalog.callDecision(nativeName, state.draftArguments,
        BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved = false)
    OutlinedButton(
        onClick = { state.prepareCall(catalog, nativeName) },
        enabled = decision == CapabilityDecision.ASK && !state.busy,
        modifier = Modifier.testTag("mcp_review_call"),
    ) { Text("Review manual call") }
    Text("Each call needs confirmation. JSON syntax checked; server validates its schema. " +
        "Parameter-header tools and authentication are not supported in this preview.",
        style = MaterialTheme.typography.bodySmall)
    if (state.calling) {
        Text("Calling tool…")
        TextButton(onClick = { state.cancelPending() }, modifier = Modifier.testTag("mcp_stop_call")) {
            Text("Stop waiting")
        }
    }
    state.callOutcome?.let { outcome -> McpManualCallResult(outcome) }
    state.callReview?.let { review ->
        AlertDialog(
            onDismissRequest = { state.callReview = null },
            title = { Text("Run this tool once?") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Server endpoint")
                    Text(review.endpoint, modifier = Modifier.testTag("mcp_review_endpoint"))
                    Text("Server tool name")
                    Text(review.target.remoteName)
                    Text("Arguments sent")
                    Text(review.call.arguments.toString(), modifier = Modifier.testTag("mcp_review_arguments"))
                    Text("May change remote data or incur costs. No account cookies, API keys or local files " +
                        "are attached. Stopping the request cannot undo remote actions.")
                }
            },
            confirmButton = {
                TextButton(onClick = { onRun(review) }, enabled = state.isCurrentReview(review) && !state.busy,
                    modifier = Modifier.testTag("mcp_run_once")) { Text("Run once") }
            },
            dismissButton = {
                TextButton(onClick = {
                    state.callReview = null
                    state.callOutcome = McpToolCallOutcome.Failure(McpToolCallFailure.DENIED)
                }, modifier = Modifier.testTag("mcp_deny_call")) { Text("Deny") }
            },
            modifier = Modifier.testTag("mcp_call_review"),
        )
    }
}

@Composable
private fun McpManualCallResult(outcome: McpToolCallOutcome) {
    when (outcome) {
        is McpToolCallOutcome.Completed -> {
            Text(if (outcome.result.isError) "Server reported an error" else "Server returned a result")
            Text("Server output (untrusted preview)", style = MaterialTheme.typography.labelMedium)
            val output = outcome.result.output
            Text(output.take(4_000) + if (output.length > 4_000) "\n… Preview truncated" else "",
                modifier = Modifier.testTag("mcp_call_output"))
        }
        is McpToolCallOutcome.Failure -> Text(
            when (outcome.reason) {
                McpToolCallFailure.DENIED -> "Call denied. Nothing sent."
                McpToolCallFailure.BLOCKED -> "Review expired or request unsupported. Nothing sent."
                McpToolCallFailure.AUTHORIZATION_REQUIRED ->
                    "Server requires sign-in. Authenticated calls are not available yet."
                McpToolCallFailure.CANCELLED -> "Request stopped locally."
                else -> "Result unknown: no supported response. The server may have acted. " +
                    "Trying again can duplicate remote changes."
            }, modifier = Modifier.testTag("mcp_call_status"))
    }
}

@Composable
internal fun McpUncertainCallNotice(onDismiss: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(12.dp).testTag("mcp_uncertain_call_notice")) {
        Column(Modifier.padding(12.dp)) {
            Text("An earlier manual call may already have changed remote data. Stopping or editing " +
                "cannot undo it. Check the server before running it again.")
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("mcp_dismiss_call_notice")) {
                Text("Dismiss notice")
            }
        }
    }
}

internal fun McpPreviewState.isCurrentReview(review: McpToolCallReview): Boolean =
    callReview === review && epoch == review.epoch && selectedTool == review.call.name &&
        discoveredEndpoint == review.endpoint &&
        validatedMcpEndpoint(endpoint.trim())?.toString() == review.endpoint &&
        draftArguments == review.rawArguments &&
        (outcome as? McpDiscoveryOutcome.Success)?.catalog === review.catalog

private fun McpPreviewState.prepareCall(catalog: McpToolCatalog, nativeName: String) {
    if (busy || discoveredEndpoint == null) return
    callOutcome = null
    callReview = McpToolCallReview.prepare(endpoint.trim(), catalog, nativeName, draftArguments, epoch)
    callReview?.let { if (!isCurrentReview(it)) callReview = null }
}

internal fun McpPreviewState.executeCall(
    review: McpToolCallReview,
    scope: CoroutineScope,
    caller: suspend (McpToolCallReview) -> McpToolCallOutcome,
) {
    if (busy || !isCurrentReview(review) || !review.approveOnce()) return
    callReview = null
    calling = true
    busy = true
    val requestEpoch = epoch
    job = scope.launch {
        try {
            val response = caller(review)
            if (requestEpoch == epoch) {
                callOutcome = response
                if (response is McpToolCallOutcome.Failure && response.reason != McpToolCallFailure.BLOCKED &&
                    response.reason != McpToolCallFailure.DENIED) callUncertain = true
            }
        } catch (cancelled: CancellationException) {
            if (requestEpoch == epoch) {
                callUncertain = true
                callOutcome = McpToolCallOutcome.Failure(McpToolCallFailure.CANCELLED)
            }
            throw cancelled
        } catch (_: Exception) { // skipcq: KT-W1064
            // Injected suspend callers can throw; always release busy state.
            if (requestEpoch == epoch) {
                callUncertain = true
                callOutcome = McpToolCallOutcome.Failure(McpToolCallFailure.INVALID_RESPONSE)
            }
        } finally {
            if (requestEpoch == epoch) {
                calling = false
                busy = false
                job = null
            }
        }
    }
}

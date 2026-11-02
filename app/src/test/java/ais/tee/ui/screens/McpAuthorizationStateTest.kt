package ais.tee.ui.screens

import ais.tee.data.engine.*
import ais.tee.data.model.McpProtectedResourceMetadata
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class McpAuthorizationStateTest {
    private val endpoint = "https://tools.example.org/mcp"

    @Test fun lateMetadataCannotApplyAfterServerSelectionOrEndpointEdit() = runBlocking {
        for (switchServer in listOf(false, true)) {
            val challenge = McpAuthorizationChallenge(endpoint)
            val state = McpPreviewState().apply {
                this.endpoint = challenge.endpoint
                outcome = McpDiscoveryOutcome.AuthorizationRequired(challenge)
            }
            val started = CompletableDeferred<Unit>()
            val result = CompletableDeferred<McpAuthorizationInspection<McpProtectedResourceMetadata>>()
            var accepted = false
            state.inspectAuthorization(challenge, this, {
                started.complete(Unit)
                withContext(NonCancellable) { result.await() }
            }) { accepted = true }
            started.await()
            val pending = requireNotNull(state.job)
            if (switchServer) state.newServer() else state.endpoint += "/changed"
            result.complete(McpAuthorizationInspection.Found(McpProtectedResourceMetadata(endpoint,
                listOf("https://auth.example.org"), emptyList())))
            pending.join()
            assertFalse(accepted)
            assertFalse(state.busy)
            assertNull(state.resourceInspection)
        }
    }

    @Test fun repeatedChecksCannotRunConcurrentlyAndCancellationReleasesBusyState() = runBlocking {
        val challenge = McpAuthorizationChallenge(endpoint)
        val state = McpPreviewState().apply {
            this.endpoint = challenge.endpoint
            outcome = McpDiscoveryOutcome.AuthorizationRequired(challenge)
        }
        var requests = 0
        val started = CompletableDeferred<Unit>()
        val check: suspend () -> McpAuthorizationInspection<McpProtectedResourceMetadata> = {
            requests++
            started.complete(Unit)
            awaitCancellation()
        }
        state.inspectAuthorization(challenge, this, check) { fail("Cancelled check applied") }
        started.await()
        val pending = requireNotNull(state.job)
        state.inspectAuthorization(challenge, this, check) { fail("Duplicate applied") }
        state.cancelPending()
        pending.join()
        assertEquals(1, requests)
        assertFalse(state.busy)
        assertNull(state.job)
    }

    @Test fun synchronousCallbackFailureIsSanitized() {
        val challenge = McpAuthorizationChallenge(endpoint)
        val state = McpPreviewState().apply {
            this.endpoint = challenge.endpoint
            outcome = McpDiscoveryOutcome.AuthorizationRequired(challenge)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            state.inspectAuthorization<McpProtectedResourceMetadata>(challenge, scope,
                { error("private callback diagnostics") }) { state.resourceInspection = it }
            assertEquals(McpAuthorizationInspection.Failed(McpAuthorizationFailure.INVALID_METADATA), state.resourceInspection)
            assertFalse(state.busy)
        } finally { scope.cancel() }
    }
}

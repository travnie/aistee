package ais.tee.ui.screens

import ais.tee.data.engine.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class McpManualReviewStateTest {
    private fun preview(): Pair<McpPreviewState, McpToolCallReview> {
        val catalog = toolCallCatalog()
        val name = catalog.browse().single().nativeName
        val state = McpPreviewState().apply {
            endpoint = CALL_ENDPOINT
            discoveredEndpoint = CALL_ENDPOINT
            outcome = McpDiscoveryOutcome.Success(catalog)
            selectedTool = name
            draftArguments = CALL_ARGUMENTS
        }
        val review = requireNotNull(McpToolCallReview.prepare(CALL_ENDPOINT, catalog, name, CALL_ARGUMENTS, state.epoch))
        state.callReview = review
        return state to review
    }

    @Test fun everyReviewedFieldAndTheDiscoverySnapshotMustStillMatch() {
        val mutations: List<(McpPreviewState) -> Unit> = listOf(
            { it.endpoint += "/changed" }, { it.discoveredEndpoint = null },
            { it.draftArguments = "{}" }, { it.selectedTool = null }, { it.epoch++ },
            { it.outcome = McpDiscoveryOutcome.Success(toolCallCatalog()) },
        )
        mutations.forEach { mutate ->
            val (state, review) = preview()
            assertTrue(state.isCurrentReview(review))
            mutate(state)
            assertFalse(state.isCurrentReview(review))
        }
    }

    @Test fun injectedCallerFailureAlwaysReleasesBusyStateWithoutRetry() {
        val (state, review) = preview()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        try {
            state.executeCall(review, scope) { calls++; error("private callback diagnostics") }
            assertEquals(1, calls)
            assertFalse(state.busy)
            assertFalse(state.calling)
            assertNull(state.callReview)
            assertEquals(McpToolCallOutcome.Failure(McpToolCallFailure.INVALID_RESPONSE), state.callOutcome)
        } finally { scope.cancel() }
    }

    @Test fun editsAndServerChangesCannotEraseTheUnknownOutcomeNotice() {
        val (state, _) = preview()
        state.calling = true
        state.busy = true
        state.invalidateCall()
        assertTrue(state.callUncertain)
        assertNull(state.callOutcome)
        state.newServer()
        assertTrue(state.callUncertain)
        assertNull(state.callReview)
    }
}

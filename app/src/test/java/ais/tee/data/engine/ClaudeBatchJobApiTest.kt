package ais.tee.data.engine

import ais.tee.data.model.AsyncProviderJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeBatchJobApiTest {
    private val service = AiChatService()

    @Test
    fun batchStatusMapsProcessingStates() {
        val running = service.parseClaudeBatch("""{"id":"msgbatch_1","type":"message_batch","processing_status":"in_progress"}""")
        assertEquals("msgbatch_1", running.remoteId)
        assertFalse(running.ended)
        assertFalse(running.cancelling)
        assertTrue(service.parseClaudeBatch("""{"id":"msgbatch_1","processing_status":"canceling"}""").cancelling)
        assertTrue(service.parseClaudeBatch("""{"id":"msgbatch_1","processing_status":"ended"}""").ended)
    }

    @Test
    fun succeededResultJoinsTextBlocksAndSkipsThinking() {
        val result = service.parseClaudeBatchResult(
            """
            {"custom_id":"other","result":{"type":"errored","error":{"type":"error","error":{"type":"x","message":"not ours"}}}}
            {"custom_id":"$CLAUDE_BATCH_CUSTOM_ID","result":{"type":"succeeded","message":{"model":"claude-opus-5","stop_reason":"end_turn","content":[{"type":"thinking","thinking":""},{"type":"text","text":"Hello "},{"type":"text","text":"batch"}]}}}
            """.trimIndent()
        )
        assertEquals(AsyncProviderJobState.SUCCEEDED, result.state)
        assertEquals("claude-opus-5", result.model)
        assertEquals("Hello batch", result.outputText)
        assertNull(result.errorMessage)
    }

    @Test
    fun truncatedErroredCanceledAndExpiredResultsMap() {
        val truncated = service.parseClaudeBatchResult(
            """{"custom_id":"$CLAUDE_BATCH_CUSTOM_ID","result":{"type":"succeeded","message":{"model":"m","stop_reason":"max_tokens","content":[{"type":"text","text":"part"}]}}}"""
        )
        assertEquals("The answer stopped at the output limit.", truncated.errorMessage)

        val errored = service.parseClaudeBatchResult(
            """{"custom_id":"$CLAUDE_BATCH_CUSTOM_ID","result":{"type":"errored","error":{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens too large"}}}}"""
        )
        assertEquals(AsyncProviderJobState.FAILED, errored.state)
        assertEquals("max_tokens too large", errored.errorMessage)

        assertEquals(
            AsyncProviderJobState.CANCELLED,
            service.parseClaudeBatchResult("""{"custom_id":"$CLAUDE_BATCH_CUSTOM_ID","result":{"type":"canceled"}}""").state,
        )
        assertEquals(
            AsyncProviderJobState.EXPIRED,
            service.parseClaudeBatchResult("""{"custom_id":"$CLAUDE_BATCH_CUSTOM_ID","result":{"type":"expired"}}""").state,
        )
        assertEquals(AsyncProviderJobState.FAILED, service.parseClaudeBatchResult("").state)
    }
}

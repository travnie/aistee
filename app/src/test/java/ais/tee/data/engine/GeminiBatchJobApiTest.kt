package ais.tee.data.engine

import ais.tee.data.model.AsyncProviderJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiBatchJobApiTest {
    private val service = AiChatService()

    @Test
    fun batchPayloadUsesOneInlineRequestWithStableMetadataKey() {
        val payload = service.buildGeminiBatchRequestPayload(
            prompt = "Explain coroutines.",
            model = "gemini-3.7-flash",
        )
        val text = payload.toString()
        assertTrue(text.contains("\"display_name\":\"Aistee batch job\""))
        assertTrue(text.contains("\"key\":\"$GEMINI_BATCH_REQUEST_KEY\""))
        assertTrue(text.contains("\"text\":\"Explain coroutines.\""))
    }

    @Test
    fun batchStatusMapsDocumentedStates() {
        fun snapshot(state: String, done: Boolean = false) = service.parseGeminiBatch(
            """{"name":"batches/123","done":$done,"metadata":{"state":"$state"}}"""
        )

        assertEquals(AsyncProviderJobState.QUEUED, snapshot("JOB_STATE_PENDING").state)
        assertEquals(AsyncProviderJobState.RUNNING, snapshot("JOB_STATE_RUNNING").state)
        assertEquals(AsyncProviderJobState.FAILED, snapshot("JOB_STATE_FAILED", true).state)
        assertEquals(AsyncProviderJobState.CANCELLED, snapshot("JOB_STATE_CANCELLED", true).state)
        assertEquals(AsyncProviderJobState.EXPIRED, snapshot("JOB_STATE_EXPIRED", true).state)
        assertEquals(AsyncProviderJobState.QUEUED, snapshot("BATCH_STATE_PENDING").state)
        assertEquals(AsyncProviderJobState.RUNNING, snapshot("BATCH_STATE_RUNNING").state)
    }

    @Test
    fun succeededInlineResultUsesAisteeMetadataAndJoinsTextParts() {
        val result = service.parseGeminiBatch(
            """
            {
              "name":"batches/123",
              "done":true,
              "metadata":{"state":"JOB_STATE_SUCCEEDED"},
              "response":{
                "inlinedResponses":[
                  {
                    "metadata":{"key":"other"},
                    "response":{"candidates":[{"content":{"role":"model","parts":[{"text":"wrong"}]}}]}
                  },
                  {
                    "metadata":{"key":"$GEMINI_BATCH_REQUEST_KEY"},
                    "response":{"candidates":[{"content":{"role":"model","parts":[{"text":"Hello "},{"text":"batch"}]}}]}
                  }
                ]
              }
            }
            """.trimIndent()
        )
        assertEquals("batches/123", result.remoteId)
        assertEquals(AsyncProviderJobState.SUCCEEDED, result.state)
        assertEquals("Hello batch", result.outputText)
        assertNull(result.errorMessage)
    }

    @Test
    fun wrappedInlineResponsesFromReferenceShapeAreAccepted() {
        val result = service.parseGeminiBatch(
            """
            {
              "name":"batches/123",
              "done":true,
              "metadata":{"state":"JOB_STATE_SUCCEEDED"},
              "response":{
                "inlinedResponses":{
                  "inlinedResponses":[
                    {
                      "metadata":{"key":"$GEMINI_BATCH_REQUEST_KEY"},
                      "response":{"candidates":[{"content":{"role":"model","parts":[{"text":"wrapped"}]}}]}
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )
        assertEquals(AsyncProviderJobState.SUCCEEDED, result.state)
        assertEquals("wrapped", result.outputText)
    }

    @Test
    fun inlineErrorAndOperationCancellationAreTerminal() {
        val requestError = service.parseGeminiBatch(
            """
            {
              "name":"batches/123",
              "done":true,
              "metadata":{"state":"JOB_STATE_SUCCEEDED"},
              "response":{
                "inlinedResponses":[
                  {
                    "metadata":{"key":"$GEMINI_BATCH_REQUEST_KEY"},
                    "error":{"code":400,"message":"Bad prompt"}
                  }
                ]
              }
            }
            """.trimIndent()
        )
        assertEquals(AsyncProviderJobState.FAILED, requestError.state)
        assertEquals("Bad prompt", requestError.errorMessage)

        val cancelled = service.parseGeminiBatch(
            """{"name":"batches/456","done":true,"error":{"code":1,"message":"Cancelled"}}"""
        )
        assertEquals(AsyncProviderJobState.CANCELLED, cancelled.state)
    }

    @Test
    fun unknownTerminalStateFailsSafeInsteadOfPretendingSuccess() {
        val result = service.parseGeminiBatch(
            """
            {
              "name":"batches/123",
              "done":true,
              "metadata":{"state":"JOB_STATE_FUTURE"}
            }
            """.trimIndent()
        )
        assertEquals(AsyncProviderJobState.INCOMPLETE, result.state)
        assertTrue(result.errorMessage.orEmpty().contains("unknown terminal batch state"))
    }

    @Test
    fun successfulBatchWithoutMatchingResultIsIncomplete() {
        val result = service.parseGeminiBatch(
            """
            {
              "name":"batches/123",
              "done":true,
              "metadata":{"state":"JOB_STATE_SUCCEEDED"},
              "response":{"inlinedResponses":[]}
            }
            """.trimIndent()
        )
        assertEquals(AsyncProviderJobState.INCOMPLETE, result.state)
        assertTrue(result.errorMessage.orEmpty().contains("without the Aistee result"))
    }
}

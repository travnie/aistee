package ais.tee.data.engine

import ais.tee.data.model.AsyncProviderJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiBackgroundJobApiTest {
    private val service = AiChatService()

    @Test
    fun backgroundRequestStaysStatelessAndNonStreaming() {
        val payload = service.buildOpenAiBackgroundRequestPayload(
            prompt = "long task",
            model = "gpt-5.6",
        )

        assertEquals(true, payload["background"]?.toString()?.toBooleanStrictOrNull())
        assertEquals(false, payload["store"]?.toString()?.toBooleanStrictOrNull())
        assertNull(payload["stream"])
    }

    @Test
    fun completedBackgroundResponseMapsToSucceededAndExtractsText() {
        val parsed = service.parseOpenAiBackgroundResponse(
            """
            {
              "id": "resp_123",
              "status": "completed",
              "model": "gpt-5.6",
              "output": [
                {
                  "id": "msg_1",
                  "type": "message",
                  "status": "completed",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "output_text",
                      "text": "background result",
                      "annotations": []
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("resp_123", parsed.remoteId)
        assertEquals("gpt-5.6", parsed.model)
        assertEquals(AsyncProviderJobState.SUCCEEDED, parsed.state)
        assertEquals("background result", parsed.outputText)
        assertNull(parsed.errorMessage)
    }

    @Test
    fun activeAndTerminalStatusesMapWithoutInventingOutput() {
        val queued = service.parseOpenAiBackgroundResponse(
            """{"id":"resp_q","status":"queued","model":"gpt-5.6","output":[]}"""
        )
        val running = service.parseOpenAiBackgroundResponse(
            """{"id":"resp_r","status":"in_progress","model":"gpt-5.6","output":[]}"""
        )
        val cancelled = service.parseOpenAiBackgroundResponse(
            """{"id":"resp_c","status":"cancelled","model":"gpt-5.6","output":[]}"""
        )

        assertEquals(AsyncProviderJobState.QUEUED, queued.state)
        assertEquals(AsyncProviderJobState.RUNNING, running.state)
        assertEquals(AsyncProviderJobState.CANCELLED, cancelled.state)
        assertNull(queued.outputText)
        assertNull(running.outputText)
        assertNull(cancelled.outputText)
    }

    @Test
    fun failedAndIncompleteResponsesPreserveProviderError() {
        val failed = service.parseOpenAiBackgroundResponse(
            """
            {
              "id": "resp_f",
              "status": "failed",
              "model": "gpt-5.6",
              "error": {"message": "provider failure"},
              "output": []
            }
            """.trimIndent()
        )
        val incomplete = service.parseOpenAiBackgroundResponse(
            """
            {
              "id": "resp_i",
              "status": "incomplete",
              "model": "gpt-5.6",
              "incomplete_details": {"reason": "max_output_tokens"},
              "output": []
            }
            """.trimIndent()
        )

        assertEquals(AsyncProviderJobState.FAILED, failed.state)
        assertEquals("provider failure", failed.errorMessage)
        assertEquals(AsyncProviderJobState.INCOMPLETE, incomplete.state)
        assertEquals("max_output_tokens", incomplete.errorMessage)
    }

    @Test
    fun unknownStatusFailsClosedWithDiagnostic() {
        val parsed = service.parseOpenAiBackgroundResponse(
            """{"id":"resp_new","status":"future_state","model":"gpt-5.6","output":[]}"""
        )

        assertEquals(AsyncProviderJobState.FAILED, parsed.state)
        assertTrue(parsed.errorMessage.orEmpty().contains("future_state"))
    }
}

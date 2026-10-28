package ais.tee.data.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopilotSdkSessionEventsTest {
    private val session = "copilot-session-1"
    private fun frame(type: String, data: String, extra: String = "") =
        """{"id":"event-1","type":"$type","data":$data$extra}"""

    private fun start(id: String = session) =
        frame("session.start", """{"sessionId":"$id"}""")

    @Test
    fun streamIsInertUntilExactHostSessionIsBound() {
        val stream = CopilotSdkSessionEvents(session)
        val delta = frame("assistant.message_delta", """{"messageId":"msg","deltaContent":"hi"}""")
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(delta))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(start("someone-else")))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(delta))
        assertEquals(CopilotSdkSessionEvent.Connected, stream.event(start()))
        val text = stream.event(delta) as CopilotSdkSessionEvent.TextDelta
        assertEquals("msg", text.messageId)
        assertEquals("hi", text.text)
        assertFalse(text.toString().contains("hi"))
        assertFalse(stream.toString().contains(session))
        assertEquals(CopilotSdkSessionEvent.Idle(true),
            stream.event(frame("session.shutdown", "{}")))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(delta))
    }

    @Test
    fun resumedStreamsRequireAnExplicitMatchingHostConfirmation() {
        val stream = CopilotSdkSessionEvents(session)
        assertFalse(stream.bindVerifiedResume("other-session"))
        assertEquals(CopilotSdkSessionEvent.Ignored,
            stream.event(frame("session.idle", """{"aborted":false}""")))
        assertTrue(stream.bindVerifiedResume(session))
        assertEquals(CopilotSdkSessionEvent.Idle(false),
            stream.event(frame("session.idle", """{"aborted":false}""")))
        assertEquals(CopilotSdkSessionEvent.Failed, stream.event(
            frame("session.error", """{"message":"private detail"}""")))
    }

    @Test
    fun onlyRootAssistantTextIsProjectedNoReasoningOrOpaqueFields() {
        val stream = CopilotSdkSessionEvents(session)
        stream.event(start())
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(
            frame("assistant.message_delta",
                """{"messageId":"msg","deltaContent":"child"}""", ""","agentId":"subagent-1"""")))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(
            frame("assistant.message_delta",
                """{"messageId":"msg","deltaContent":"legacy-child","parentToolCallId":"tool-1"}""")))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(
            frame("assistant.message",
                """{"messageId":"msg","content":"legacy-child","parentToolCallId":"tool-1"}""")))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(
            frame("assistant.reasoning_delta", """{"deltaContent":"hidden"}""")))
        val message = stream.event(frame("assistant.message",
            """{"messageId":"msg","content":"done","reasoningText":"secret","encryptedContent":"opaque"}"""))
            as CopilotSdkSessionEvent.Message
        assertEquals("done", message.text)
        assertFalse(message.toString().contains("done"))
        assertFalse(message.toString().contains("secret"))
    }

    @Test
    fun approvalIsMetadataOnlyAndNeverInterpretedAsConsent() {
        val stream = CopilotSdkSessionEvents(session)
        stream.event(start())
        val data = """{"requestId":"approval-1","ownerSessionId":"copilot-session-1","permissionRequest":{"kind":"shell","command":"danger"},"promptRequest":{"text":"private"}}"""
        val pending = stream.event(frame("permission.requested", data))
            as CopilotSdkSessionEvent.ApprovalPending
        assertEquals("approval-1", pending.requestId)
        assertEquals("shell", pending.kind)
        assertFalse(pending.toString().contains("approval-1"))
        assertFalse(pending.toString().contains("danger"))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(
            frame("permission.requested", data.replace(session, "other-session"))))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(
            frame("permission.requested",
                data.replace("\"ownerSessionId\":\"copilot-session-1\"", "\"ownerSessionId\":12"))))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(
            frame("permission.requested",
                data.replace("\"ownerSessionId\":\"copilot-session-1\"", "\"ownerSessionId\":null"))))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(
            frame("permission.requested", data.dropLast(1) + ""","resolvedByHook":true}""")))
    }

    @Test
    fun invalidAndOverlargeFramesNeverYieldDisplayOrPermissionEvents() {
        val stream = CopilotSdkSessionEvents(session)
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event("not-json"))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(start().replace(
            """"id":"event-1",""", "")))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event("[".repeat(80) + "]".repeat(80)))
        // This is valid JSON and would connect an unbound stream without the length guard.
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(
            frame("session.start", """{"sessionId":"$session","pad":"${"x".repeat(262_144)}"}""")))
        // Legitimate shorter frames with large opaque metadata still deliver the answer.
        stream.event(start())
        val longAnswer = stream.event(frame("assistant.message",
            """{"messageId":"msg","content":"visible","encryptedContent":"${"x".repeat(90_000)}"}"""))
        assertEquals(CopilotSdkSessionEvent.Message("msg", "visible"), longAnswer)
        // Once bound, a too-large frame returns an explicit failure instead of silent loss.
        assertEquals(CopilotSdkSessionEvent.Failed, stream.event(
            frame("assistant.message",
                """{"messageId":"msg","content":"hidden","encryptedContent":"${"x".repeat(262_144)}"}""")))
        stream.event(start())
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(
            frame("assistant.message_delta", """{"messageId":"msg","deltaContent":12}""")))
        assertEquals(CopilotSdkSessionEvent.Ignored, stream.event(
            frame("permission.requested", """{"requestId":"","permissionRequest":{"kind":"shell"}}""")))
    }
}

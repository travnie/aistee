package ais.tee.data.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodexAppServerProtocolTest {
    private fun payload(frame: String) = Json.parseToJsonElement(frame).jsonObject

    @Test
    fun handshakeAndThreadStartStayWithinReadOnlyApprovalPolicy() {
        val init = payload(CodexAppServerProtocol.initialize(1, "1.0"))
        assertEquals("initialize", init["method"]?.jsonPrimitive?.content)
        assertEquals("Aistee", init["params"]?.jsonObject?.get("clientInfo")
            ?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("initialized", payload(CodexAppServerProtocol.initialized())
            ["method"]?.jsonPrimitive?.content)

        val request = payload(CodexAppServerProtocol.startThread(2, "gpt-6", "/repo"))
        val params = request["params"]!!.jsonObject
        assertEquals("on-request", params["approvalPolicy"]?.jsonPrimitive?.content)
        assertEquals("read-only", params["sandbox"]?.jsonPrimitive?.content)
        assertEquals("/repo", params["cwd"]?.jsonPrimitive?.content)
        assertEquals("gpt-6", params["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun turnAndInterruptUseExplicitThreadAndTurnIds() {
        val request = payload(CodexAppServerProtocol.startTurn(3, "thread-1", "Check files"))
        assertEquals("thread-1", request["params"]?.jsonObject?.get("threadId")?.jsonPrimitive?.content)
        val input = request["params"]!!.jsonObject["input"]!!.jsonArray.first().jsonObject
        assertEquals("text", input["type"]?.jsonPrimitive?.content)
        assertEquals("Check files", input["text"]?.jsonPrimitive?.content)
        assertEquals(0, input["text_elements"]!!.jsonArray.size)
        val interrupted = payload(CodexAppServerProtocol.interruptTurn(4, "thread-1", "turn-1"))
        assertEquals("turn-1", interrupted["params"]?.jsonObject?.get("turnId")?.jsonPrimitive?.content)
        assertEquals("thread/resume", payload(CodexAppServerProtocol.resumeThread(5, "thread-1"))
            ["method"]?.jsonPrimitive?.content)
    }

    @Test
    fun responseIdsAreCheckedBeforeAcceptingSessionIds() {
        assertEquals("thr", CodexAppServerProtocol.threadId(
            """{"id":2,"result":{"thread":{"id":"thr"}}}""", 2))
        assertEquals("turn", CodexAppServerProtocol.turnId(
            """{"id":3,"result":{"turn":{"id":"turn"}}}""", 3))
        assertFailsWith<IllegalArgumentException> {
            CodexAppServerProtocol.threadId("""{"id":99,"result":{"thread":{"id":"thr"}}}""", 2)
        }
        assertFailsWith<IllegalArgumentException> {
            CodexAppServerProtocol.turnId("""{"id":3,"error":{"message":"denied"}}""", 3)
        }
    }

    @Test
    fun deltaAndCompletionAreScopedToTheActiveTurn() {
        val delta = """{"method":"item/agentMessage/delta","params":{"threadId":"thr","turnId":"a","delta":"ok"}}"""
        assertEquals(CodexAppServerEvent.AgentText("ok"), CodexAppServerProtocol.event(delta, "thr", "a"))
        assertEquals(CodexAppServerEvent.Ignored, CodexAppServerProtocol.event(delta, "thr", "b"))
        assertEquals(CodexAppServerEvent.Ignored, CodexAppServerProtocol.event(delta, "other", "a"))
        val completed = """{"method":"turn/completed","params":{"threadId":"thr","turn":{"id":"a","status":"completed"}}}"""
        val failed = completed.replace("completed\"}}", "failed\"}}")
        val interrupted = completed.replace("completed\"}}", "interrupted\"}}")
        assertEquals(CodexAppServerEvent.TurnFinished(CodexTurnOutcome.COMPLETED),
            CodexAppServerProtocol.event(completed, "thr", "a"))
        assertEquals(CodexAppServerEvent.TurnFinished(CodexTurnOutcome.FAILED),
            CodexAppServerProtocol.event(failed, "thr", "a"))
        assertEquals(CodexAppServerEvent.TurnFinished(CodexTurnOutcome.INTERRUPTED),
            CodexAppServerProtocol.event(interrupted, "thr", "a"))
    }

    @Test
    fun approvalRequestsNeverBecomeAgentText() {
        val request = """{"id":"approval-4","method":"item/commandExecution/requestApproval","params":{"threadId":"thr","turnId":"a","command":"rm -rf ."}}"""
        val event = CodexAppServerProtocol.event(request, "thr", "a")
        assertEquals("item/commandExecution/requestApproval",
            (event as CodexAppServerEvent.ServerRequest).method)
        assertEquals("approval-4", event.id.content)
        assertEquals("rm -rf .", event.params["command"]?.jsonPrimitive?.content)
        assertEquals(CodexAppServerEvent.Ignored,
            CodexAppServerProtocol.event("""{"method":"item/commandExecution/outputDelta","params":{"threadId":"thr","turnId":"a","delta":"secret"}}""", "thr", "a"))
    }

    @Test
    fun invalidRequestsAndBlankIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> { CodexAppServerProtocol.initialize(-1, "1.0") }
        assertFailsWith<IllegalArgumentException> { CodexAppServerProtocol.startThread(1, "") }
        assertFailsWith<IllegalArgumentException> { CodexAppServerProtocol.startTurn(1, "thr", "") }
        assertFailsWith<IllegalArgumentException> { CodexAppServerProtocol.interruptTurn(1, "thr", "") }
    }
}

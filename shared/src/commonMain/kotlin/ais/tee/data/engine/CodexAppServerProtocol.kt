package ais.tee.data.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed interface CodexAppServerEvent {
    data class AgentText(val delta: String) : CodexAppServerEvent
    data class TurnFinished(val outcome: CodexTurnOutcome) : CodexAppServerEvent

    // A host must route this through its permission UI. Never reply implicitly.
    data class ServerRequest(val id: JsonPrimitive, val method: String, val params: JsonObject) : CodexAppServerEvent
    data object Ignored : CodexAppServerEvent
}

enum class CodexTurnOutcome { COMPLETED, FAILED, INTERRUPTED }

/**
 * Portable Codex app-server protocol framing. No process, network or token ownership.
 * The host transports newline-delimited JSON and retains request IDs and thread IDs.
 */
object CodexAppServerProtocol {
    private val json = Json

    fun initialize(id: Long, version: String): String {
        require(version.isNotBlank())
        return request(id, "initialize", buildJsonObject {
            put("clientInfo", buildJsonObject {
                put("name", "Aistee")
                put("title", "Aistee")
                put("version", version)
            })
        })
    }

    fun initialized(): String = """{"method":"initialized"}"""

    fun startThread(id: Long, model: String, cwd: String? = null): String {
        require(model.isNotBlank())
        require(cwd == null || cwd.isNotBlank())
        return request(id, "thread/start", buildJsonObject {
            put("model", model)
            put("approvalPolicy", "on-request")
            put("sandbox", "read-only")
            if (cwd != null) put("cwd", cwd)
        })
    }

    fun resumeThread(id: Long, threadId: String): String {
        require(threadId.isNotBlank())
        return request(id, "thread/resume", buildJsonObject { put("threadId", threadId) })
    }

    fun startTurn(id: Long, threadId: String, text: String): String {
        require(threadId.isNotBlank() && text.isNotBlank())
        return request(id, "turn/start", buildJsonObject {
            put("threadId", threadId)
            put("input", JsonArray(listOf(buildJsonObject {
                put("type", "text")
                put("text", text)
                put("text_elements", JsonArray(emptyList()))
            })))
        })
    }

    fun interruptTurn(id: Long, threadId: String, turnId: String): String {
        require(threadId.isNotBlank() && turnId.isNotBlank())
        return request(id, "turn/interrupt", buildJsonObject {
            put("threadId", threadId)
            put("turnId", turnId)
        })
    }

    fun threadId(response: String, requestId: Long): String =
        responseEntityId(response, requestId, "thread")

    fun turnId(response: String, requestId: Long): String =
        responseEntityId(response, requestId, "turn")

    /** Parse notifications for one active turn; all unrelated frames are ignored. */
    fun event(frame: String, threadId: String, turnId: String): CodexAppServerEvent {
        require(threadId.isNotBlank() && turnId.isNotBlank())
        val root = json.parseToJsonElement(frame) as? JsonObject
            ?: throw IllegalArgumentException("Expected a JSON object")
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull
            ?: return CodexAppServerEvent.Ignored

        // Server-initiated RPC requests may request shell or patch approvals.
        // Do not treat them as output, or automatically acknowledge them.
        val serverRequestId = root["id"] as? JsonPrimitive
        if (serverRequestId != null) {
            return CodexAppServerEvent.ServerRequest(serverRequestId, method, (root["params"] as? JsonObject) ?: JsonObject(emptyMap()))
        }

        val params = root["params"] as? JsonObject ?: return CodexAppServerEvent.Ignored
        if ((params["threadId"] as? JsonPrimitive)?.contentOrNull != threadId) {
            return CodexAppServerEvent.Ignored
        }
        return when (method) {
            "item/agentMessage/delta" -> {
                if ((params["turnId"] as? JsonPrimitive)?.contentOrNull != turnId) {
                    CodexAppServerEvent.Ignored
                } else {
                    val delta = (params["delta"] as? JsonPrimitive)?.contentOrNull
                    if (delta.isNullOrEmpty()) CodexAppServerEvent.Ignored
                    else CodexAppServerEvent.AgentText(delta)
                }
            }
            "turn/completed" -> {
                val turn = params["turn"] as? JsonObject
                    ?: return CodexAppServerEvent.Ignored
                if ((turn["id"] as? JsonPrimitive)?.contentOrNull != turnId) {
                    return CodexAppServerEvent.Ignored
                }
                val outcome = when ((turn["status"] as? JsonPrimitive)?.contentOrNull) {
                    "completed" -> CodexTurnOutcome.COMPLETED
                    "failed" -> CodexTurnOutcome.FAILED
                    "interrupted" -> CodexTurnOutcome.INTERRUPTED
                    else -> return CodexAppServerEvent.Ignored
                }
                CodexAppServerEvent.TurnFinished(outcome)
            }
            else -> CodexAppServerEvent.Ignored
        }
    }

    private fun request(id: Long, method: String, params: JsonObject): String {
        require(id >= 0)
        return buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
    }

    private fun responseEntityId(frame: String, requestId: Long, entity: String): String {
        val root = json.parseToJsonElement(frame) as? JsonObject
            ?: throw IllegalArgumentException("Expected a JSON object")
        require((root["id"] as? JsonPrimitive)?.longOrNull == requestId) {
            "Unexpected Codex request ID"
        }
        require(root["error"] == null) { "Codex RPC failed" }
        val result = root["result"] as? JsonObject
        val item = result?.get(entity) as? JsonObject
        val id = (item?.get("id") as? JsonPrimitive)?.contentOrNull
        return id?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing Codex entity ID")
    }
}

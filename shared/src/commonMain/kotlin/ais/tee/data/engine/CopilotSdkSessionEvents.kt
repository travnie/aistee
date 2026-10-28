package ais.tee.data.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val MAX_COPILOT_EVENT_FRAME_CHARS = 262_144

/** User-facing projection of the official Copilot SDK session-event stream. */
sealed interface CopilotSdkSessionEvent {
    data object Connected : CopilotSdkSessionEvent
    data class TextDelta(val messageId: String, val text: String) : CopilotSdkSessionEvent {
        override fun toString() = "TextDelta(messageId=<redacted>, text=<redacted>)"
    }
    data class Message(val messageId: String, val text: String) : CopilotSdkSessionEvent {
        override fun toString() = "Message(messageId=<redacted>, text=<redacted>)"
    }
    data class ApprovalPending(val requestId: String, val kind: String?) : CopilotSdkSessionEvent {
        override fun toString() = "ApprovalPending(requestId=<redacted>, kind=<redacted>)"
    }
    data class Idle(val aborted: Boolean) : CopilotSdkSessionEvent
    data object Failed : CopilotSdkSessionEvent
    data object Ignored : CopilotSdkSessionEvent
}

/**
 * Kotlin Multiplatform event projection, NOT an SDK client, permission handler or transport.
 *
 * A trusted external Copilot SDK host must authenticate, authorize and isolate the session.
 * SDK envelopes do not repeat the session ID on ordinary events. Therefore no event is
 * accepted before a matching session.start or an explicitly host-verified resume.
 *
 * Raw reasoning, tool parameters, private provider state and session identifiers never
 * enter this presentation projection. ApprovalPending is a notification, not consent.
 */
class CopilotSdkSessionEvents(private val expectedSessionId: String) {
    init {
        require(expectedSessionId.isNotBlank() && expectedSessionId.length <= 128)
    }

    private var bound = false

    /** Call ONLY after an authenticated SDK host confirms this exact resumed session. */
    fun bindVerifiedResume(verifiedSessionId: String): Boolean {
        if (verifiedSessionId != expectedSessionId) return false
        bound = true
        return true
    }

    fun event(raw: String): CopilotSdkSessionEvent {
        // Valid SDK frames may include large opaque fields alongside a short displayed answer.
        // An oversized frame from an active session must surface a failure, not lose the reply.
        if (raw.length > MAX_COPILOT_EVENT_FRAME_CHARS) {
            return if (bound) CopilotSdkSessionEvent.Failed else CopilotSdkSessionEvent.Ignored
        }
        if (!boundedCopilotJson(raw)) return CopilotSdkSessionEvent.Ignored
        val envelope = runCatching { Json.parseToJsonElement(raw) as? JsonObject }
            .getOrNull() ?: return CopilotSdkSessionEvent.Ignored
        val type = envelope.text("type") ?: return CopilotSdkSessionEvent.Ignored
        if (envelope.text("id").isNullOrBlank()) return CopilotSdkSessionEvent.Ignored
        val data = envelope["data"] as? JsonObject ?: return CopilotSdkSessionEvent.Ignored

        if (type == "session.start") {
            if (data.text("sessionId") != expectedSessionId) return CopilotSdkSessionEvent.Ignored
            bound = true
            return CopilotSdkSessionEvent.Connected
        }
        if (!bound) return CopilotSdkSessionEvent.Ignored
        if (type == "session.shutdown") {
            bound = false
            return CopilotSdkSessionEvent.Idle(aborted = true)
        }
        return when (type) {
            "assistant.message_delta" -> {
                // Older SDK streams may attribute a subagent only via parentToolCallId.
                if (envelope["agentId"] != null || data["parentToolCallId"] != null) {
                    return CopilotSdkSessionEvent.Ignored
                }
                val messageId = data.text("messageId")?.takeIf { it.isNotBlank() && it.length <= 128 }
                    ?: return CopilotSdkSessionEvent.Ignored
                val delta = data.text("deltaContent")?.takeIf { it.isNotEmpty() && it.length <= 16_384 }
                    ?: return CopilotSdkSessionEvent.Ignored
                CopilotSdkSessionEvent.TextDelta(messageId, delta)
            }
            "assistant.message" -> {
                // Older SDK streams may attribute a subagent only via parentToolCallId.
                if (envelope["agentId"] != null || data["parentToolCallId"] != null) {
                    return CopilotSdkSessionEvent.Ignored
                }
                val messageId = data.text("messageId")?.takeIf { it.isNotBlank() && it.length <= 128 }
                    ?: return CopilotSdkSessionEvent.Ignored
                val content = data.text("content")?.takeIf { it.length <= 30_000 }
                    ?: return CopilotSdkSessionEvent.Ignored
                CopilotSdkSessionEvent.Message(messageId, content)
            }
            "permission.requested" -> {
                if ((data.containsKey("ownerSessionId") &&
                        data.text("ownerSessionId") != expectedSessionId) ||
                    (data["resolvedByHook"] as? JsonPrimitive)?.booleanOrNull == true) {
                    return CopilotSdkSessionEvent.Ignored
                }
                val requestId = data.text("requestId")?.takeIf { it.isNotBlank() && it.length <= 128 }
                    ?: return CopilotSdkSessionEvent.Ignored
                val kind = (data["permissionRequest"] as? JsonObject)?.text("kind")?.take(80)
                CopilotSdkSessionEvent.ApprovalPending(requestId, kind)
            }
            "session.idle" -> CopilotSdkSessionEvent.Idle(
                aborted = (data["aborted"] as? JsonPrimitive)?.booleanOrNull == true
            )
            "session.error" -> CopilotSdkSessionEvent.Failed
            else -> CopilotSdkSessionEvent.Ignored
        }
    }

    override fun toString(): String = "CopilotSdkSessionEvents(session=<redacted>, bound=$bound)"
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Avoid feeding adversarially deep JSON directly into the recursive decoder. */
private fun boundedCopilotJson(raw: String): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (ch in raw) {
        if (quoted) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> quoted = false
            }
        } else {
            when (ch) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 32) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
    }
    return !quoted && depth == 0
}

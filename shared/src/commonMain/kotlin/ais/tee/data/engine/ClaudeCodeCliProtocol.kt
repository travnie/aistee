package ais.tee.data.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

sealed interface ClaudeCodeCliEvent {
    data class SessionStarted(val sessionId: String) : ClaudeCodeCliEvent
    data class TextDelta(val text: String) : ClaudeCodeCliEvent
    data class ToolsObserved(val toolNames: List<String>) : ClaudeCodeCliEvent
    data class Finished(val outcome: ClaudeCodeCliOutcome) : ClaudeCodeCliEvent
    data object Ignored : ClaudeCodeCliEvent
}

enum class ClaudeCodeCliOutcome { COMPLETED, FAILED }

/**
 * A portable codec for the official, unmodified Claude Code executable.
 *
 * There is no process, network, credentials or billing implementation here. An explicit
 * external runtime must perform Claude's own sign-in and independently enforce permissions.
 */
object ClaudeCodeCliProtocol {
    private val json = Json
    // Explicit session identifiers are UUIDs; Claude --resume also accepts names, which are unsafe here.
    private val sessionIdPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private fun validSessionId(id: String): Boolean =
        sessionIdPattern.matches(id) && !id.startsWith("-")
    private val meteredEnvironment = setOf(
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_BASE_URL",
        "ANTHROPIC_CUSTOM_HEADERS",
        "CLAUDE_CODE_OAUTH_TOKEN",
        "CLAUDE_CODE_USE_ANTHROPIC_AWS",
        "CLAUDE_CODE_USE_MANTLE",
        "CLAUDE_CODE_USE_BEDROCK",
        "CLAUDE_CODE_USE_VERTEX",
        "CLAUDE_CODE_USE_FOUNDRY",
    )

    /** Read-only auth check. Never accept Claude Console/API credentials for a plan-backed run. */
    fun authenticationStatusCommand(): List<String> = listOf("claude", "auth", "status")

    fun hasMeteredEnvironmentOverrides(keys: Set<String>): Boolean =
        keys.any { it in meteredEnvironment || it.startsWith("CLAUDE_CODE_USE_") }

    fun usesSubscription(authStatusFrame: String): Boolean {
        val root = runCatching {
            json.parseToJsonElement(authStatusFrame) as? JsonObject
        }.getOrNull() ?: return false
        return (root["loggedIn"] as? JsonPrimitive)?.booleanOrNull == true &&
            (root["authMethod"] as? JsonPrimitive)?.contentOrNull == "claude.ai"
    }

    /**
     * Arguments only, never shell-interpolated command text. Host chooses its own safe cwd,
     * verifies installed binary and subscription auth, and restricts tool permissions.
     */
    fun readOnlyCommand(prompt: String, resumeSessionId: String? = null): List<String> {
        require(prompt.isNotBlank() && prompt.length <= 20_000) { "Invalid agent task" }
        require(resumeSessionId == null || validSessionId(resumeSessionId)) {
            "Invalid Claude Code session ID"
        }
        return buildList {
            add("claude")
            add("--restricted")
            // Do not load untrusted repository hooks, skills, project rules, or .mcp.json.
            // Explicit empty sources exclude user/project/local config; only trusted managed
            // policy can remain. CLI must be >= 2.1.248 for --restricted.
            add("--setting-sources")
            add("")
            add("--strict-mcp-config")
            add("--settings")
            add("{\"disableAllHooks\":true}")
            add("--permission-mode")
            add("plan")
            add("--tools")
            add("Read,Glob,Grep")
            add("--disallowedTools")
            add("mcp__*")
            add("--max-turns")
            add("6")
            add("--output-format")
            add("stream-json")
            add("--verbose")
            add("--include-partial-messages")
            if (resumeSessionId != null) {
                add("--resume")
                add(resumeSessionId)
            }
            add("-p")
            add(prompt)
        }
    }

    /** Decode only agent-facing metadata; ignore thinking, tool arguments and user events. */
    fun event(jsonLine: String, expectedSessionId: String? = null): ClaudeCodeCliEvent {
        require(expectedSessionId == null || validSessionId(expectedSessionId))
        val root = json.parseToJsonElement(jsonLine) as? JsonObject
            ?: throw IllegalArgumentException("Expected Claude Code JSON object")
        val session = (root["session_id"] as? JsonPrimitive)?.contentOrNull
        val type = (root["type"] as? JsonPrimitive)?.contentOrNull
        // Bind first through an init event; never process non-init frames from an unbound stream.
        if (expectedSessionId == null && type != "system") return ClaudeCodeCliEvent.Ignored
        // Claude Code streaming wrappers may omit session_id. A bound, isolated process owns them.
        if (expectedSessionId != null && session != expectedSessionId &&
            !(type == "stream_event" && session == null)
        ) return ClaudeCodeCliEvent.Ignored
        return when (type) {
            "system" -> {
                if ((root["subtype"] as? JsonPrimitive)?.contentOrNull == "init" &&
                    session != null && validSessionId(session)
                ) ClaudeCodeCliEvent.SessionStarted(session)
                else ClaudeCodeCliEvent.Ignored
            }
            "stream_event" -> {
                val envelope = root["event"] as? JsonObject ?: return ClaudeCodeCliEvent.Ignored
                if ((envelope["type"] as? JsonPrimitive)?.contentOrNull != "content_block_delta") {
                    return ClaudeCodeCliEvent.Ignored
                }
                val delta = envelope["delta"] as? JsonObject ?: return ClaudeCodeCliEvent.Ignored
                if ((delta["type"] as? JsonPrimitive)?.contentOrNull != "text_delta") {
                    return ClaudeCodeCliEvent.Ignored
                }
                val text = (delta["text"] as? JsonPrimitive)?.contentOrNull
                if (text.isNullOrEmpty()) ClaudeCodeCliEvent.Ignored
                else ClaudeCodeCliEvent.TextDelta(text)
            }
            "assistant" -> {
                val message = root["message"] as? JsonObject ?: return ClaudeCodeCliEvent.Ignored
                val blocks = message["content"] as? JsonArray ?: return ClaudeCodeCliEvent.Ignored
                val tools = blocks.filterIsInstance<JsonObject>().mapNotNull { block ->
                    if ((block["type"] as? JsonPrimitive)?.contentOrNull != "tool_use") {
                        null
                    } else {
                        (block["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    }
                }
                if (tools.isEmpty()) ClaudeCodeCliEvent.Ignored
                else ClaudeCodeCliEvent.ToolsObserved(tools)
            }
            "result" -> {
                val isError = (root["is_error"] as? JsonPrimitive)?.booleanOrNull
                val success = isError == false &&
                    (root["subtype"] as? JsonPrimitive)?.contentOrNull == "success"
                ClaudeCodeCliEvent.Finished(
                    if (success) ClaudeCodeCliOutcome.COMPLETED else ClaudeCodeCliOutcome.FAILED
                )
            }
            else -> ClaudeCodeCliEvent.Ignored
        }
    }
}

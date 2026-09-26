package ais.tee.data.skills

import ais.tee.data.model.CapabilityDecision
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val strictJson = Json { isLenient = false }

/**
 * The only data a skill receives: the input, locale and current time. Never chat history,
 * other skills, provider data or secrets.
 */
fun activeSkillRequestJson(input: JsonElement, locale: String, nowIso8601: String): String =
    buildJsonObject {
        put("version", 1)
        put("input", input)
        put("locale", locale)
        put("now", nowIso8601)
    }.toString()

/** Shown only to the user, full screen in the skills sandbox; never sent to a model or exported. */
@Serializable
data class ActiveSkillCard(
    val title: String,
    val html: String,
    /** The skill version that made the card; opening it needs that exact version still trusted. */
    val skillName: String = "",
    val bundleDigest: String = "",
) {
    override fun toString(): String = "ActiveSkillCard(title=<redacted>, html=<${html.length} chars>)"
}

data class ActiveSkillToolRequest(
    val tool: ActiveSkillTool,
    val arguments: JsonObject,
    /** ALLOW or REQUIRES_USER_INTERACTION; denied requests fail the whole response. */
    val decision: CapabilityDecision,
)

sealed interface ActiveSkillOutcome {
    data class Result(val result: JsonElement, val card: ActiveSkillCard?) : ActiveSkillOutcome
    data class ToolRequests(val requests: List<ActiveSkillToolRequest>) : ActiveSkillOutcome
    /** The skill reported an error, or its output was rejected. Nothing is retried. */
    data class Error(val message: String) : ActiveSkillOutcome
}

private val responseKeys = setOf("result", "card", "tools", "error")

/**
 * Validates the raw string a skill returned against the strict v1 schema: exactly one of
 * `result` (optionally with `card`), `tools` or `error`, at most [ACTIVE_SKILL_MAX_OUTPUT_BYTES].
 */
fun parseActiveSkillOutput(raw: String?, declaration: ActiveSkillDeclaration): ActiveSkillOutcome {
    if (raw == null) return ActiveSkillOutcome.Error("The skill returned nothing.")
    if (raw.encodeToByteArray().size > ACTIVE_SKILL_MAX_OUTPUT_BYTES) {
        return ActiveSkillOutcome.Error("The skill output is larger than ${ACTIVE_SKILL_MAX_OUTPUT_BYTES / 1024} KiB.")
    }
    val root = try {
        strictJson.parseToJsonElement(raw)
    } catch (_: SerializationException) {
        return ActiveSkillOutcome.Error("The skill output is not valid JSON.")
    } as? JsonObject ?: return ActiveSkillOutcome.Error("The skill output must be a JSON object.")

    val unknown = root.keys - responseKeys
    if (unknown.isNotEmpty()) return ActiveSkillOutcome.Error("Unexpected field '${unknown.first()}' in skill output.")
    val kinds = listOf("result", "tools", "error").filter(root::containsKey)
    if (kinds.size != 1) return ActiveSkillOutcome.Error("Skill output needs exactly one of result, tools or error.")
    if ("card" in root && kinds.single() != "result") {
        return ActiveSkillOutcome.Error("A card can only accompany a result.")
    }
    return when (kinds.single()) {
        "result" -> parseResult(root)
        "tools" -> parseTools(root.getValue("tools"), declaration)
        else -> {
            val message = (root.getValue("error") as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return ActiveSkillOutcome.Error("The skill error must be a string.")
            ActiveSkillOutcome.Error(message.take(500))
        }
    }
}

private fun parseResult(root: JsonObject): ActiveSkillOutcome {
    val cardElement = root["card"] ?: return ActiveSkillOutcome.Result(root.getValue("result"), card = null)
    val card = cardElement as? JsonObject ?: return ActiveSkillOutcome.Error("The card must be an object.")
    if (card.keys != setOf("title", "html")) return ActiveSkillOutcome.Error("The card needs exactly title and html.")
    val title = card.stringField("title") ?: return ActiveSkillOutcome.Error("The card title must be a string.")
    val html = card.stringField("html") ?: return ActiveSkillOutcome.Error("The card html must be a string.")
    if (title.isBlank() || title.length > ACTIVE_SKILL_MAX_CARD_TITLE_CHARS) {
        return ActiveSkillOutcome.Error("The card title must be 1-$ACTIVE_SKILL_MAX_CARD_TITLE_CHARS characters.")
    }
    return ActiveSkillOutcome.Result(root.getValue("result"), ActiveSkillCard(title, html))
}

private fun parseTools(element: JsonElement, declaration: ActiveSkillDeclaration): ActiveSkillOutcome {
    val entries = element as? kotlinx.serialization.json.JsonArray
        ?: return ActiveSkillOutcome.Error("tools must be an array.")
    if (entries.isEmpty() || entries.size > ACTIVE_SKILL_MAX_TOOL_REQUESTS) {
        return ActiveSkillOutcome.Error("tools must contain 1-$ACTIVE_SKILL_MAX_TOOL_REQUESTS requests.")
    }
    val requests = entries.map { entry ->
        val request = entry as? JsonObject ?: return ActiveSkillOutcome.Error("Each tool request must be an object.")
        if (request.keys != setOf("name", "arguments")) {
            return ActiveSkillOutcome.Error("Each tool request needs exactly name and arguments.")
        }
        val name = request.stringField("name") ?: return ActiveSkillOutcome.Error("Tool name must be a string.")
        val arguments = request["arguments"] as? JsonObject
            ?: return ActiveSkillOutcome.Error("Tool arguments must be an object.")
        val decision = activeSkillToolDecision(name, declaration)
        val tool = ActiveSkillTool.fromId(name)
        if (decision == CapabilityDecision.DENY || tool == null) {
            return ActiveSkillOutcome.Error("The skill requested a tool it did not declare: '${name.take(64)}'.")
        }
        ActiveSkillToolRequest(tool, arguments, decision)
    }
    return ActiveSkillOutcome.ToolRequests(requests)
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Name of the web message listener object the sandbox page posts its one result through. */
const val ACTIVE_SKILL_HOST_OBJECT = "aisteeSkillHost"

sealed interface ActiveSkillHostMessage {
    /** The raw skill output, still to be checked by [parseActiveSkillOutput]. */
    data class Output(val raw: String) : ActiveSkillHostMessage
    data class Failed(val message: String) : ActiveSkillHostMessage
    /** Not from the expected main frame, origin or call; ignored. */
    data object Rejected : ActiveSkillHostMessage
}

/**
 * Accepts a result message only from the main frame of the exact sandbox origin and only for
 * the one-use [expectedCallId]. The payload is `{"callId", "output"}` or `{"callId", "error"}`.
 */
fun acceptActiveSkillHostMessage(
    payload: String?,
    isMainFrame: Boolean,
    sourceOrigin: String?,
    expectedOrigin: String,
    expectedCallId: String,
): ActiveSkillHostMessage {
    if (!isMainFrame || sourceOrigin != expectedOrigin || payload == null) return ActiveSkillHostMessage.Rejected
    // Output is capped separately; this bound only stops a runaway payload before parsing.
    if (payload.length > ACTIVE_SKILL_MAX_OUTPUT_BYTES * 2 + 1024) {
        return ActiveSkillHostMessage.Failed("The skill output is larger than ${ACTIVE_SKILL_MAX_OUTPUT_BYTES / 1024} KiB.")
    }
    val message = try {
        strictJson.parseToJsonElement(payload)
    } catch (_: SerializationException) {
        return ActiveSkillHostMessage.Rejected
    } as? JsonObject ?: return ActiveSkillHostMessage.Rejected
    if (message.stringField("callId") != expectedCallId) return ActiveSkillHostMessage.Rejected
    return when (message.keys) {
        setOf("callId", "output") -> message.stringField("output")
            ?.let { ActiveSkillHostMessage.Output(it) }
            ?: ActiveSkillHostMessage.Rejected
        setOf("callId", "error") -> ActiveSkillHostMessage.Failed(
            "The skill failed: " + (message.stringField("error") ?: "unknown error").take(500)
        )
        else -> ActiveSkillHostMessage.Rejected
    }
}

package ais.tee.data.skills

import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_TITLE_CHARS = 200
private const val MAX_TEXT_CHARS = 10_000
private const val MAX_CLIPBOARD_CHARS = 64 * 1024
private const val MAX_RECIPIENTS = 10
private val emailAddress = Regex("^[^@\\s,;<>\"]+@[^@\\s,;<>\"]+\\.[^@\\s,;<>\"]+$")

/**
 * A validated native action, ready to show to the user for confirmation. Every field is exactly
 * what will be handed to the system UI; nothing else from the skill is passed along.
 */
sealed interface ActiveSkillToolAction {
    data object CurrentDateTime : ActiveSkillToolAction
    data class CalendarEvent(
        val title: String,
        val startEpochMs: Long,
        val endEpochMs: Long?,
        val location: String?,
        val description: String?,
    ) : ActiveSkillToolAction
    data class Email(val to: List<String>, val subject: String, val body: String) : ActiveSkillToolAction
    data class Notification(val title: String, val text: String, val atEpochMs: Long) : ActiveSkillToolAction
    data class Clipboard(val text: String) : ActiveSkillToolAction
}

sealed interface ActiveSkillToolValidation {
    data class Valid(val action: ActiveSkillToolAction) : ActiveSkillToolValidation
    data class Invalid(val reason: String) : ActiveSkillToolValidation
}

/** Strict per-tool argument check. Unknown fields are rejected so nothing unexpected is carried. */
fun validateActiveSkillToolRequest(request: ActiveSkillToolRequest): ActiveSkillToolValidation {
    val args = request.arguments
    fun invalid(reason: String) = ActiveSkillToolValidation.Invalid("${request.tool.id}: $reason")
    fun unknownField(allowed: Set<String>): String? = (args.keys - allowed).firstOrNull()

    return when (request.tool) {
        ActiveSkillTool.CURRENT_DATETIME -> {
            if (args.isNotEmpty()) invalid("takes no arguments") else ActiveSkillToolValidation.Valid(ActiveSkillToolAction.CurrentDateTime)
        }
        ActiveSkillTool.CREATE_CALENDAR_EVENT -> {
            unknownField(setOf("title", "start", "end", "location", "description"))?.let { return invalid("unexpected field '$it'") }
            val title = args.text("title", MAX_TITLE_CHARS) ?: return invalid("title must be 1-$MAX_TITLE_CHARS characters")
            val start = args.instant("start") ?: return invalid("start must be an ISO-8601 time with offset")
            val end = if ("end" in args) args.instant("end") ?: return invalid("end must be an ISO-8601 time with offset") else null
            if (end != null && end < start) return invalid("end is before start")
            ActiveSkillToolValidation.Valid(
                ActiveSkillToolAction.CalendarEvent(
                    title = title,
                    startEpochMs = start,
                    endEpochMs = end,
                    location = if ("location" in args) args.text("location", MAX_TITLE_CHARS) ?: return invalid("location is too long") else null,
                    description = if ("description" in args) args.text("description", MAX_TEXT_CHARS, allowEmpty = true) ?: return invalid("description is too long") else null,
                )
            )
        }
        ActiveSkillTool.COMPOSE_EMAIL -> {
            unknownField(setOf("to", "subject", "body"))?.let { return invalid("unexpected field '$it'") }
            val to = when (val value = args["to"]) {
                null -> emptyList()
                is JsonPrimitive -> if (value.isString) listOf(value.content) else return invalid("to must be addresses")
                is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return invalid("to must be addresses") }
                else -> return invalid("to must be addresses")
            }.map(String::trim)
            if (to.size > MAX_RECIPIENTS || to.any { !emailAddress.matches(it) }) return invalid("to must be up to $MAX_RECIPIENTS valid addresses")
            ActiveSkillToolValidation.Valid(
                ActiveSkillToolAction.Email(
                    to = to,
                    subject = args.text("subject", MAX_TITLE_CHARS, allowEmpty = true) ?: "",
                    body = args.text("body", MAX_TEXT_CHARS, allowEmpty = true) ?: "",
                )
            )
        }
        ActiveSkillTool.SCHEDULE_NOTIFICATION -> {
            unknownField(setOf("title", "text", "at"))?.let { return invalid("unexpected field '$it'") }
            val title = args.text("title", MAX_TITLE_CHARS) ?: return invalid("title must be 1-$MAX_TITLE_CHARS characters")
            val text = args.text("text", MAX_TEXT_CHARS, allowEmpty = true) ?: ""
            val at = args.instant("at") ?: return invalid("at must be an ISO-8601 time with offset")
            ActiveSkillToolValidation.Valid(ActiveSkillToolAction.Notification(title, text, at))
        }
        ActiveSkillTool.COPY_TO_CLIPBOARD -> {
            unknownField(setOf("text"))?.let { return invalid("unexpected field '$it'") }
            val text = args.text("text", MAX_CLIPBOARD_CHARS) ?: return invalid("text must be 1-$MAX_CLIPBOARD_CHARS characters")
            ActiveSkillToolValidation.Valid(ActiveSkillToolAction.Clipboard(text))
        }
    }
}

private fun JsonObject.text(name: String, max: Int, allowEmpty: Boolean = false): String? {
    val value = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return value.takeIf { (allowEmpty || it.isNotBlank()) && it.length <= max }
}

private fun JsonObject.instant(name: String): Long? {
    val value = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
}

/** What the user typed into "Run skill": JSON when it parses as JSON, otherwise a plain string. */
fun activeSkillUserInput(text: String): kotlinx.serialization.json.JsonElement =
    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull()
        ?: JsonPrimitive(text)

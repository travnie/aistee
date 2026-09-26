package ais.tee.data.skills

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val ACTIVE_SKILL_CHAT_TOOL_PREFIX = "skill_"
private const val MAX_CHAT_TOOL_NAME_CHARS = 64
private const val MAX_CHAT_TOOL_DESCRIPTION_CHARS = 1_024
const val ACTIVE_SKILL_CHAT_INPUT_FIELD = "input"
const val ACTIVE_SKILL_MAX_CHAT_INPUT_CHARS = 20_000

/**
 * Function-tool name a model sees for a trusted skill (`skill_<name>`, at most 64 characters),
 * or null when the skill name cannot form a valid tool name.
 */
fun activeSkillChatToolName(skillName: String): String? {
    val name = (ACTIVE_SKILL_CHAT_TOOL_PREFIX + skillName).take(MAX_CHAT_TOOL_NAME_CHARS)
    return name.takeIf { skillName.isNotEmpty() && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '-' } }
}

fun activeSkillChatToolDescription(skillName: String, description: String): String =
    "Run the user's trusted offline skill '$skillName'. The user sees and approves the exact input and the result. " +
        description.trim().replace(Regex("\\s+"), " ")
            .let { it.take(MAX_CHAT_TOOL_DESCRIPTION_CHARS - 120 - skillName.length).trimEnd() }

/** The model's input for a skill call: exactly one string field, bounded; anything else is rejected. */
fun activeSkillChatInput(arguments: JsonObject): String? {
    if (arguments.keys != setOf(ACTIVE_SKILL_CHAT_INPUT_FIELD)) return null
    val value = (arguments[ACTIVE_SKILL_CHAT_INPUT_FIELD] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return value.takeIf { it.length <= ACTIVE_SKILL_MAX_CHAT_INPUT_CHARS }
}

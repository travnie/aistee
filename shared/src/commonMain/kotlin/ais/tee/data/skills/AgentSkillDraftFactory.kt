package ais.tee.data.skills

/**
 * Generates an inert, portable SKILL.md draft. Import, trust and execution are handled by
 * the existing skill library; generating a draft never grants permissions.
 */
object AgentSkillDraftFactory {
    fun create(name: String, description: String, instructions: String): String =
        buildString {
            append("---\nname: ")
            append(yamlString(name.trim()))
            append("\ndescription: ")
            append(yamlString(description.trim()))
            append("\n---\n\n")
            append(instructions.trim('\n', '\r'))
            append('\n')
        }

    /** Always quote user input to prevent YAML frontmatter injection or accidental scalar typing. */
    private fun yamlString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}

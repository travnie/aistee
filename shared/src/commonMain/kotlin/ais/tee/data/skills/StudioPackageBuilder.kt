package ais.tee.data.skills

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Files ready for explicit preview/export. This is not an installed or trusted plugin. */
data class StudioPackageFiles(
    val name: String,
    val files: Map<String, String>,
) {
    override fun toString(): String = "StudioPackageFiles(files=" + files.size + ")"
}

/**
 * Portable Agent Plugins 1.0 package builder for the existing Studio.
 * Standalone SKILL.md creation continues through AgentSkillDraftFactory and the local library.
 * A list of skill sources is used so a later multi-select UI will not require a second builder.
 */
object StudioPackageBuilder {
    private val packageName = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private const val MAX_SKILLS = 16
    private const val MAX_SKILL_CHARS = 128_000
    private const val MAX_TOTAL_CHARS = 512_000

    fun createPlugin(
        name: String,
        description: String,
        skillSources: List<String>,
    ): StudioPackageFiles {
        val id = name.trim()
        require(id.length in 1..64 && packageName.matches(id)) {
            "Use a lowercase package name with single hyphens, up to 64 characters."
        }
        require(description.isNotBlank() && description.length <= 4_000) {
            "A package description of up to 4000 characters is required."
        }
        require(skillSources.size in 1..MAX_SKILLS) {
            "A plugin draft must contain between 1 and 16 skills."
        }
        require(skillSources.sumOf { it.length.toLong() } <= MAX_TOTAL_CHARS) {
            "The selected skill sources are too large for this preview."
        }
        val sourcesByName = linkedMapOf<String, String>()
        skillSources.forEach { source ->
            require(source.length <= MAX_SKILL_CHARS) { "A skill source is too large." }
            val parse = AgentSkillManifestParser.parse(source)
            val manifest = parse.manifest
            require(parse.isValid && manifest != null && manifest.instructions.isNotBlank()) {
                "Every included SKILL.md must have valid frontmatter and instructions."
            }
            // Keep paths ASCII and portable across host filesystems; the source remains untouched.
            require(packageName.matches(manifest.name) && manifest.name.length <= 64) {
                "Plugin skill folder names must use lowercase ASCII letters, digits and single hyphens."
            }
            require(manifest.name !in sourcesByName) {
                "Duplicate skill names are not allowed in one plugin."
            }
            sourcesByName[manifest.name] = source
        }
        val manifest = buildJsonObject {
            put("\$schema", "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json")
            put("name", id)
            put("version", "0.1.0")
            put("description", description.trim())
        }.toString()
        val files = linkedMapOf("plugin.json" to manifest)
        sourcesByName.forEach { (skillName, source) ->
            files["skills/" + skillName + "/SKILL.md"] = source
        }
        return StudioPackageFiles(id, files)
    }
}

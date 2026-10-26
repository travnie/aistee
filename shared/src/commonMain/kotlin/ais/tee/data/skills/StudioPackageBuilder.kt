package ais.tee.data.skills

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put

/** Files ready for explicit preview/export. This is not an installed or trusted plugin. */
data class StudioPackageFiles(
    val name: String,
    val files: Map<String, String>,
) {
    override fun toString(): String = "StudioPackageFiles(files=" + files.size + ")"
}

/** Only an exported MCP mapping: no connection, credentials or execution. */
data class StudioMcpServer(val name: String, val url: String)

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
    private const val MAX_MCP_SERVERS = 8
    private val mcpName = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")
    private val mcpUrl = Regex("^https://([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?(/[^?#]*)?$")

    fun createPlugin(
        name: String,
        description: String,
        skillSources: List<String>,
        mcpServers: List<StudioMcpServer> = emptyList(),
    ): StudioPackageFiles {
        val id = name.trim()
        require(id.length in 1..64 && packageName.matches(id)) {
            "Use a lowercase package name with single hyphens, up to 64 characters."
        }
        require(description.isNotBlank() && description.length <= 4_000) {
            "A package description of up to 4000 characters is required."
        }
        require(skillSources.size <= MAX_SKILLS && mcpServers.size <= MAX_MCP_SERVERS &&
            (skillSources.isNotEmpty() || mcpServers.isNotEmpty())) {
            "A plugin must contain at least one skill or MCP server (limits: 16 skills, 8 servers)."
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
        val validatedServers = linkedMapOf<String, String>()
        mcpServers.forEach { server ->
            require(mcpName.matches(server.name)) {
                "MCP server names must start with a letter and contain letters, digits, _ or -."
            }
            require(server.name !in validatedServers) { "Duplicate MCP server alias." }
            val endpoint = server.url.trim()
            require(endpoint.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) {
                "MCP endpoint may not contain whitespace or control characters."
            }
            val match = mcpUrl.matchEntire(endpoint)
            require(endpoint.length <= 2_048 && match != null) {
                "MCP endpoint must be an HTTPS domain URL without credentials, query or fragment."
            }
            val host = match.groupValues[1]
            val labels = host.split('.')
            require(labels.size >= 2 &&
                labels.all { label ->
                    label.length in 1..63 &&
                    label.first().isLetterOrDigit() && label.last().isLetterOrDigit()
                } &&
                labels.last().any(Char::isLetter) &&
                !host.endsWith(".local", ignoreCase = true) &&
                !host.equals("localhost", ignoreCase = true)) {
                "MCP endpoint must use a public-looking DNS hostname."
            }
            val port = match.groupValues[2]
            require(port.isEmpty() || port.toIntOrNull() in 1..65535) {
                "MCP port must be between 1 and 65535."
            }
            validatedServers[server.name] = endpoint
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
        if (validatedServers.isNotEmpty()) {
            files["mcp.json"] = buildJsonObject {
                put("\$schema", "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json")
                putJsonObject("mcpServers") {
                    validatedServers.forEach { (alias, url) ->
                        putJsonObject(alias) {
                            put("type", "streamable-http")
                            put("url", url)
                        }
                    }
                }
            }.toString()
        }
        return StudioPackageFiles(id, files)
    }
}

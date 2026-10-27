package ais.tee.data.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Host packaging variants are generated from the same portable source and never persisted. */
enum class StudioPackageHost { PORTABLE, CLAUDE_CODE }

object StudioPackageAdapters {
    private val pluginName = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    fun forHost(source: StudioPackageFiles, host: StudioPackageHost): StudioPackageFiles {
        if (host == StudioPackageHost.PORTABLE) return source
        val root = Json.parseToJsonElement(source.files.getValue("plugin.json")).jsonObject
        val name = root.getValue("name").jsonPrimitive.content
        val version = root.getValue("version").jsonPrimitive.content
        val description = root.getValue("description").jsonPrimitive.content
        require(name.length in 1..64 && pluginName.matches(name)) {
            "Invalid canonical plugin name."
        }
        // Claude Code reserves the claude-* prefix. Do not silently invent a second identity.
        require(!name.startsWith("claude-")) {
            "Claude Code reserves names starting with claude-. Rename the plugin before export."
        }

        val files = LinkedHashMap(source.files)
        files[".claude-plugin/plugin.json"] = buildJsonObject {
            put("name", name)
            put("version", version)
            put("description", description)
        }.toString()

        source.files["mcp.json"]?.let { canonical ->
            val servers = Json.parseToJsonElement(canonical).jsonObject
                .getValue("mcpServers").jsonObject
            files[".mcp.json"] = buildJsonObject {
                putJsonObject("mcpServers") {
                    servers.forEach { (alias, config) ->
                        val remote = config as? JsonObject
                            ?: throw IllegalArgumentException("MCP server entry is not an object.")
                        require(remote.getValue("type").jsonPrimitive.content == "streamable-http") {
                            "Claude adapter only supports remote Streamable HTTP servers."
                        }
                        val endpoint = remote.getValue("url").jsonPrimitive.content
                        // No credentials, headers, login grants or app IDs are emitted by this adapter.
                        putJsonObject(alias) {
                            put("type", "http")
                            put("url", endpoint)
                        }
                    }
                }
            }.toString()
        }
        return StudioPackageFiles(source.name, files)
    }
}

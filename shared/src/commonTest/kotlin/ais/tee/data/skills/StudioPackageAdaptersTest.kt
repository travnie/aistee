package ais.tee.data.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StudioPackageAdaptersTest {
    private val skill = AgentSkillDraftFactory.create(
        "helper", "Help with requests", "# Instructions\nHelp the user."
    )

    @Test
    fun claudeAdapterKeepsOneCanonicalPluginAndOriginalSkill() {
        val portable = StudioPackageBuilder.createPlugin("helper-kit", "A useful helper", listOf(skill))
        val derived = StudioPackageAdapters.forHost(portable, StudioPackageHost.CLAUDE_CODE)
        assertEquals(
            setOf("plugin.json", "skills/helper/SKILL.md", ".claude-plugin/plugin.json"),
            derived.files.keys
        )
        assertEquals(skill, derived.files.getValue("skills/helper/SKILL.md"))
        val claude = Json.parseToJsonElement(
            derived.files.getValue(".claude-plugin/plugin.json")
        ).jsonObject
        assertEquals("helper-kit", claude.getValue("name").jsonPrimitive.content)
        assertEquals("A useful helper", claude.getValue("description").jsonPrimitive.content)
        assertEquals(2, portable.files.size)
        assertEquals(portable, StudioPackageAdapters.forHost(portable, StudioPackageHost.PORTABLE))
    }

    @Test
    fun transformsExistingMcpMetadataWithoutChangingPortableSource() {
        val portable = StudioPackageBuilder.createPlugin("docs", "Docs", listOf(skill))
         // Use real canonical metadata, but do not depend on the MCP editor's branch.
        val mcp = """{"mcpServers":{"readApi":{"type":"streamable-http","url":"https://docs.example.org/mcp"}}}"""
        val actual = StudioPackageFiles(portable.name, portable.files + ("mcp.json" to mcp))
        val converted = StudioPackageAdapters.forHost(actual, StudioPackageHost.CLAUDE_CODE)
        val server = Json.parseToJsonElement(converted.files.getValue(".mcp.json"))
            .jsonObject.getValue("mcpServers").jsonObject.getValue("readApi").jsonObject
        assertEquals("http", server.getValue("type").jsonPrimitive.content)
        assertEquals("https://docs.example.org/mcp", server.getValue("url").jsonPrimitive.content)
        assertEquals(mcp, actual.files.getValue("mcp.json"))
        assertFalse(portable.files.containsKey(".mcp.json"))
    }

    @Test
    fun rejectsReservedClaudeNameAndUnsupportedTransport() {
        val reserved = StudioPackageBuilder.createPlugin("claude-helper", "Helper", listOf(skill))
        assertFailsWith<IllegalArgumentException> {
            StudioPackageAdapters.forHost(reserved, StudioPackageHost.CLAUDE_CODE)
        }
        val ordinary = StudioPackageBuilder.createPlugin("helper", "Helper", listOf(skill))
        val invalid = StudioPackageFiles(
            ordinary.name,
            ordinary.files + ("mcp.json" to
                """{"mcpServers":{"a":{"type":"stdio","command":"bash"}}}""")
        )
        assertFailsWith<IllegalArgumentException> {
            StudioPackageAdapters.forHost(invalid, StudioPackageHost.CLAUDE_CODE)
        }
    }
}

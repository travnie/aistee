package ais.tee.data.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StudioPackageBuilderTest {
    private fun skill(name: String): String =
        AgentSkillDraftFactory.create(name, "When asked to help", "# Run\nExplain steps.")

    @Test
    fun pluginWithSeveralSkillsUsesOnePortableRootManifest() {
        val pack = StudioPackageBuilder.createPlugin(
            "demo-kit", "A small pack", listOf(skill("first"), skill("second"))
        )
        assertEquals(
            listOf("plugin.json", "skills/first/SKILL.md", "skills/second/SKILL.md"),
            pack.files.keys.toList()
        )
        val metadata = Json.parseToJsonElement(pack.files.getValue("plugin.json")).jsonObject
        assertEquals("demo-kit", metadata.getValue("name").jsonPrimitive.content)
        assertEquals("0.1.0", metadata.getValue("version").jsonPrimitive.content)
        assertEquals(
            "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json",
            metadata.getValue("\$schema").jsonPrimitive.content
        )
        assertFalse(metadata.containsKey("skills"))
        assertFalse(metadata.containsKey("mcpServers"))
        assertEquals(skill("first"), pack.files.getValue("skills/first/SKILL.md"))
    }

    @Test
    fun rejectsDuplicateNamesUnsafePathsAndMalformedSkills() {
        assertFailsWith<IllegalArgumentException> {
            StudioPackageBuilder.createPlugin("example", "Description", listOf(skill("first"), skill("first")))
        }
        for (bad in listOf("../hidden", "Uppercase", "double--hyphen", "test/path")) {
            assertFailsWith<IllegalArgumentException> {
                StudioPackageBuilder.createPlugin(bad, "Description", listOf(skill("first")))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            StudioPackageBuilder.createPlugin("example", "Description", listOf("not frontmatter"))
        }
        assertFailsWith<IllegalArgumentException> {
            StudioPackageBuilder.createPlugin("example", "Description", emptyList())
        }
    }

    @Test
    fun namesAndDescriptionsAreJsonEscapedNotInterpolated() {
        val pack = StudioPackageBuilder.createPlugin(
            "example", "With quotes \" and a newline\nand braces { }", listOf(skill("first"))
        )
        val metadata = Json.parseToJsonElement(pack.files.getValue("plugin.json")).jsonObject
        assertEquals(
            "With quotes \" and a newline\nand braces { }",
            metadata.getValue("description").jsonPrimitive.content
        )
        assertTrue(pack.toString().contains("files=2"))
        assertFalse(pack.toString().contains("quotes"))
    }


    @Test
    fun supportsMcpOnlyAndCombinedPackagesWithoutDuplicatedEndpoints() {
        val mcp = StudioMcpServer("docsApi", "https://docs.example.org:443/mcp")
        val only = StudioPackageBuilder.createPlugin(
            name = "docs-only", description = "Read documentation",
            skillSources = emptyList(), mcpServers = listOf(mcp)
        )
        assertEquals(setOf("plugin.json", "mcp.json"), only.files.keys)
        val server = Json.parseToJsonElement(only.files.getValue("mcp.json"))
            .jsonObject.getValue("mcpServers").jsonObject.getValue("docsApi").jsonObject
        assertEquals("streamable-http", server.getValue("type").jsonPrimitive.content)
        assertEquals(mcp.url, server.getValue("url").jsonPrimitive.content)
        val combined = StudioPackageBuilder.createPlugin(
            "doc-suite", "Docs + skill", listOf(skill("reader")), listOf(mcp)
        )
        assertEquals(3, combined.files.size)
        assertFalse(combined.files.getValue("plugin.json").contains("mcpServers"))
    }

    @Test
    fun rejectsUnsupportedMcpMappingsAndKeepsCredentialsOut() {
        val invalid = listOf(
            "http://docs.example.org/mcp",
            "https://alice:secret@docs.example.org/mcp",
            "https://docs.example.org/mcp?token=SECRET",
            "https://docs.example.org/mcp#fragment",
            "https://127.0.0.1/mcp",
            "https://localhost/mcp",
            "https://docs.example.org:99999/mcp",
            "https://docs.example.org/mcp\ninjection",
            "https://docs.example.org@127.0.0.1/mcp"
        )
        invalid.forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) {
                StudioPackageBuilder.createPlugin(
                    "docs", "Description", emptyList(), listOf(StudioMcpServer("docs", url))
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            StudioPackageBuilder.createPlugin(
                "docs", "Description", emptyList(),
                listOf(StudioMcpServer("same", "https://one.example.org/mcp"),
                    StudioMcpServer("same", "https://two.example.org/mcp"))
            )
        }
    }

    @Test
    fun rejectsLargeOrEmptySkillContents() {
        assertFailsWith<IllegalArgumentException> {
            StudioPackageBuilder.createPlugin(
                "example", "Description", listOf(AgentSkillDraftFactory.create("first", "Trigger", " "))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StudioPackageBuilder.createPlugin(
                "example", "Description", List(17) { skill("item" + it) }
            )
        }
    }
}

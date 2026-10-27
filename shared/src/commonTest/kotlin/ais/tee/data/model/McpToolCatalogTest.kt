package ais.tee.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpToolCatalogTest {
    private val firstDiscovery = "0123456789abcdef0123456789abcdef"
    private val nextDiscovery = "fedcba9876543210fedcba9876543210"

    private val toolsList = """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "tools": [
              {
                "name": "docs.search/v2",
                "title": "Search documents",
                "description": "Search private documents",
                "inputSchema": {
                  "type": "object",
                  "properties": { "query": { "type": "string" } },
                  "required": ["query"]
                }
              },
              {
                "name": "tools.save",
                "description": "Save an item",
                "inputSchema": { "type": "object" }
              }
            ]
          }
        }
    """.trimIndent()

    private fun parse(raw: String, generation: String = firstDiscovery): McpToolCatalog? =
        McpToolCatalog.fromToolsListResponse("docs", generation, raw)

    private fun pages(vararg raw: String): McpToolCatalog? =
        McpToolCatalog.fromToolsListResponses("docs", firstDiscovery, raw.toList())

    @Test
    fun browsingDefersSchemaAndPreservesRemoteNamesWithinSession() {
        val catalog = assertNotNull(parse(toolsList))
        assertEquals(2, catalog.count)
        val match = catalog.browse("documents").single()
        assertEquals("docs.search/v2", match.remoteName)
        assertEquals("mcp_docs_" + firstDiscovery + "_0", match.nativeName)
        assertEquals("docs.search/v2", catalog.resolveTarget(match.nativeName)?.remoteName)
        val selected = assertNotNull(catalog.loadDefinition(match.nativeName))
        assertEquals(match.nativeName, selected.name)
        assertEquals("object", selected.inputSchema["type"]?.toString()?.trim('"'))
        val spec = assertNotNull(catalog.exportToolSpec(match.nativeName))
        val copied = Json.parseToJsonElement(spec).jsonObject
        assertEquals("docs.search/v2", copied["name"]?.jsonPrimitive?.content)
        assertEquals("Search private documents", copied["description"]?.jsonPrimitive?.content)
        assertEquals("object", copied["inputSchema"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(spec.contains(firstDiscovery))
        assertNull(catalog.exportToolSpec("other"))
        assertNull(catalog.loadDefinition("other"))
        assertNull(catalog.resolveTarget("mcp_other_0"))
        assertEquals("tools.save", catalog.browse("SAVE", limit = 1).single().remoteName)
    }

    @Test
    fun rejectsMalformedAndUnboundedRemoteCatalogs() {
        assertNull(McpToolCatalog.fromToolsListResponse("invalid.server", firstDiscovery, toolsList))
        assertNull(parse("{}"))
        val tooLargeButValid = toolsList.dropLast(1) +
            ", \"padding\": \"" + "x".repeat(256_001) + "\"}"
        assertNull(parse(tooLargeButValid))
        assertNull(parse(toolsList.replace("\"object\"", "\"string\"")))
        assertNull(parse(toolsList.replace("\"tools.save\"", "\"docs.search/v2\"")))
        assertNull(parse(toolsList.replace("\"result\"", "\"error\"")))
        assertNull(parse(toolsList.replace("\"jsonrpc\": \"2.0\"", "\"jsonrpc\": \"1.0\"")))
        assertNull(parse(toolsList.replace("\"id\": 1,", "")))
        assertNull(parse(toolsList.replace("\"id\": 1,", "\"id\": null,")))
    }

    @Test
    fun requiresAllPagesBeforeExposingTools() {
        val firstPage = toolsList.replace("\"tools\": [", "\"nextCursor\": \"page-2\", \"tools\": [")
        assertNull(parse(firstPage))
        val secondPage = """
            {"jsonrpc":"2.0","id":2,"result":{"tools":[
              {"name":"tools.delete","description":"Delete an item","inputSchema":{"type":"object"}}
            ]}}
        """.trimIndent()
        val catalog = assertNotNull(pages(firstPage, secondPage))
        assertEquals(3, catalog.count)
        assertEquals("tools.delete", catalog.resolveTarget("mcp_docs_" + firstDiscovery + "_2")?.remoteName)
        assertNull(pages(firstPage, firstPage))
        assertNull(pages(firstPage, secondPage.replace("tools.delete", "tools.save")))
        assertNull(pages(firstPage, secondPage.replace("\"id\":2", "\"id\":1")))
    }

    @Test
    fun staleAliasesAndTargetsFailClosedAcrossDiscoveries() {
        val old = assertNotNull(parse(toolsList))
        val alias = old.browse().first().nativeName
        val oldTarget = assertNotNull(old.resolveTarget(alias))
        val refreshed = assertNotNull(parse(toolsList, nextDiscovery))
        assertNull(refreshed.loadDefinition(alias))
        assertNull(refreshed.resolveTarget(alias))
        assertFalse(refreshed.isCurrent(oldTarget))
        val target = assertNotNull(refreshed.resolveTarget(refreshed.browse().first().nativeName))
        assertTrue(refreshed.isCurrent(target))
        assertFalse(old.isCurrent(target))
        assertNull(parse(toolsList, generation = "predictable"))
    }

    @Test
    fun optionalBlankLabelsFallBackButMalformedLabelsFail() {
        val blank = toolsList.replace("Search private documents", " ")
        assertEquals("docs.search/v2", assertNotNull(parse(blank)).browse().first().description)
        assertNull(parse(toolsList.replace(
            "\"description\": \"Search private documents\"", "\"description\": 42"
        )))
        assertNull(parse(toolsList.replace(
            "\"title\": \"Search documents\"", "\"title\": null"
        )))
    }

    @Test
    fun rejectsMalformedNestedJsonSchema() {
        assertNull(parse(toolsList.replace(
            "\"query\": { \"type\": \"string\" }", "\"query\": { \"type\": 42 }"
        )))
        assertNull(parse(toolsList.replace(
            "\"required\": [\"query\"]", "\"required\": [42]"
        )))
    }

    @Test
    fun debugStringsExcludeProviderSuppliedDescriptions() {
        val catalog = assertNotNull(parse(toolsList))
        val summary = catalog.browse().first()
        val definition = assertNotNull(catalog.loadDefinition(summary.nativeName))
        assertFalse(catalog.toString().contains("private documents"))
        assertFalse(summary.toString().contains("private documents"))
        assertFalse(catalog.resolveTarget(summary.nativeName).toString().contains("docs.search/v2"))
        assertFalse(definition.toString().contains("query"))
    }
}

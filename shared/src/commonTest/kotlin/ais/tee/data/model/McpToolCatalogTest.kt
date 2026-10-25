package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpToolCatalogTest {
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

    @Test
    fun browsingDefersSchemaAndPreservesRemoteNamesWithinSession() {
        val catalog = assertNotNull(McpToolCatalog.fromToolsListResponse("docs", toolsList))
        assertEquals(2, catalog.count)
        val match = catalog.browse("documents").single()
        assertEquals("docs.search/v2", match.remoteName)
        assertEquals("mcp_docs_0", match.nativeName)
        assertEquals("docs.search/v2", catalog.resolveTarget(match.nativeName)?.remoteName)
        val selected = assertNotNull(catalog.loadDefinition(match.nativeName))
        assertEquals("mcp_docs_0", selected.name)
        assertEquals("object", selected.inputSchema["type"]?.toString()?.trim('"'))
        assertNull(catalog.loadDefinition("other"))
        assertNull(catalog.resolveTarget("mcp_other_0"))
        assertEquals(1, catalog.browse("SAVE", limit = 1).size)
    }

    @Test
    fun rejectsMalformedAndUnboundedRemoteCatalogs() {
        assertNull(McpToolCatalog.fromToolsListResponse("invalid.server", toolsList))
        assertNull(McpToolCatalog.fromToolsListResponse("docs", "{}"))
        assertNull(McpToolCatalog.fromToolsListResponse("docs", "{" + "a".repeat(256_001) + "}"))
        assertNull(McpToolCatalog.fromToolsListResponse("docs", toolsList.replace("\"object\"", "\"string\"")))
        assertNull(McpToolCatalog.fromToolsListResponse("docs", toolsList.replace("\"tools.save\"", "\"docs.search/v2\"")))
        assertNull(McpToolCatalog.fromToolsListResponse("docs", toolsList.replace("\"result\"", "\"error\"")))
        assertNull(McpToolCatalog.fromToolsListResponse("docs", toolsList.replace("\"jsonrpc\": \"2.0\"", "\"jsonrpc\": \"1.0\"")))
    }

    @Test
    fun requiresAllPagesBeforeExposingTools() {
        val firstPage = toolsList.replace("\"tools\": [", "\"nextCursor\": \"page-2\", \"tools\": [")
        assertNull(McpToolCatalog.fromToolsListResponse("docs", firstPage))
        val secondPage = """
            {"jsonrpc":"2.0","id":2,"result":{"tools":[
              {"name":"tools.delete","description":"Delete an item","inputSchema":{"type":"object"}}
            ]}}
        """.trimIndent()
        val catalog = assertNotNull(McpToolCatalog.fromToolsListResponses("docs", listOf(firstPage, secondPage)))
        assertEquals(3, catalog.count)
        assertEquals("tools.delete", catalog.resolveTarget("mcp_docs_2")?.remoteName)
        assertNull(McpToolCatalog.fromToolsListResponses("docs", listOf(firstPage, firstPage)))
    }

    @Test
    fun neitherRawSchemaNorServerSuppliedDescriptionsAppearInDebugStrings() {
        val catalog = assertNotNull(McpToolCatalog.fromToolsListResponse("docs", toolsList))
        val summary = catalog.browse().first()
        val definition = assertNotNull(catalog.loadDefinition(summary.nativeName))
        assertFalse(catalog.toString().contains("private documents"))
        assertFalse(summary.toString().contains("private documents"))
        assertFalse(catalog.resolveTarget(summary.nativeName).toString().contains("docs.search/v2"))
        assertFalse(definition.toString().contains("query"))
        assertTrue(catalog.browse(limit = 1).size == 1)
    }
}

package ais.tee.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceTextFileContractTest {
    @Test
    fun importedNamesRetainTheirExtensionWhenExported() {
        assertEquals("settings.json", workspaceExportFileName("settings.json"))
        assertEquals("data.JSON5", workspaceExportFileName("data.JSON5"))
        assertEquals("workflow.yaml", workspaceExportFileName("workflow.yaml"))
        assertEquals("config.yml", workspaceExportFileName("config.yml"))
        assertEquals(".env", workspaceExportFileName(".env"))
        assertEquals("config", workspaceExportFileName("config"))
        assertEquals("untitled.md", workspaceExportFileName("  "))
    }

    @Test
    fun mimeMatchesTheRealFileType() {
        assertEquals("application/json", workspaceExportMimeType("settings.json"))
        assertEquals("application/json5", workspaceExportMimeType("settings.JSON5"))
        assertEquals("application/yaml", workspaceExportMimeType("settings.yaml"))
        assertEquals("application/yaml", workspaceExportMimeType("settings.YML"))
        assertEquals("application/xml", workspaceExportMimeType("schema.xml"))
        assertEquals("text/markdown", workspaceExportMimeType("prompt.md"))
        assertEquals("text/markdown", workspaceExportMimeType("prompt.markdown"))
        assertEquals("text/plain", workspaceExportMimeType("config.toml"))
        assertEquals("text/plain", workspaceExportMimeType(".env"))
        assertEquals("text/plain", workspaceExportMimeType("config"))
    }
}

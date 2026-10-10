package ais.tee.data.document

import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocbenchWorkspaceTransformActionTest {
    private val grant = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)

    private fun execute(
        source: String,
        kind: DocbenchWorkspaceTransformKind,
        enabled: Boolean = true,
        permissions: Set<BenchToolPermission> = grant,
        sourceIds: List<String> = emptyList(),
    ) = DocbenchWorkspaceTransformAction.execute(
        text = source,
        kind = kind,
        surface = BenchToolSurface.COMPANION_UI,
        isEnabled = enabled,
        grantedPermissions = permissions,
        sourceIds = sourceIds,
    )

    @Test
    fun jsonFormatsWithLibraryProvenanceWithoutMutatingInput() {
        val input = """{"items":[1,2],"large":123456789012345678901234567890}"""
        val sources = mutableListOf("library-asset-1")
        val result = assertIs<DocbenchWorkspaceTransformResult.Completed>(
            execute(input, DocbenchWorkspaceTransformKind.FORMAT_JSON, sourceIds = sources)
        )
        sources.clear()
        assertEquals(DocbenchWorkspaceTransformKind.FORMAT_JSON, result.kind)
        assertTrue(result.changed)
        assertTrue(result.content.contains("123456789012345678901234567890"))
        assertTrue(result.content.contains("\n"))
        assertEquals(listOf("library-asset-1"), result.sourceIds)
        assertTrue(result.warnings.isEmpty())
        assertEquals("""{"items":[1,2],"large":123456789012345678901234567890}""", input)
    }

    @Test
    fun json5AndYamlUseExistingLosslessFormatters() {
        val json5 = assertIs<DocbenchWorkspaceTransformResult.Completed>(
            execute("{port:0x1f90,// keep\nenabled:true,}", DocbenchWorkspaceTransformKind.FORMAT_JSON5)
        )
        assertTrue(json5.content.contains("0x1f90"))
        assertTrue(json5.content.contains("// keep"))

        val yaml = assertIs<DocbenchWorkspaceTransformResult.Completed>(
            execute("# keep\nbase: &item {name: Aistee}\ncopy: *item\n", DocbenchWorkspaceTransformKind.FORMAT_YAML)
        )
        assertTrue(yaml.content.contains("# keep"))
        assertTrue(yaml.content.contains("&item"))
        assertTrue(yaml.content.contains("*item"))
    }

    @Test
    fun repairRetainsResultKindAndIssueCount() {
        val completed = assertIs<DocbenchWorkspaceTransformResult.Completed>(
            execute("~~~kotlin\nval a = 1", DocbenchWorkspaceTransformKind.REPAIR_MARKDOWN_FENCES)
        )
        assertEquals(1, completed.repairedIssueCount)
        assertTrue(completed.content.endsWith("\n~~~"))
    }

    @Test
    fun malformedInputAndMissingPermissionCannotSupplyReplacement() {
        assertIs<DocbenchWorkspaceTransformResult.Rejected>(
            execute("{\"broken\":}", DocbenchWorkspaceTransformKind.FORMAT_JSON)
        )
        assertIs<DocbenchWorkspaceTransformResult.Blocked>(
            execute("{}", DocbenchWorkspaceTransformKind.FORMAT_JSON, permissions = emptySet())
        )
        assertIs<DocbenchWorkspaceTransformResult.Blocked>(
            execute("{}", DocbenchWorkspaceTransformKind.FORMAT_JSON, enabled = false)
        )
    }

    @Test
    fun completedDebugOutputRedactsContentAndAssetId() {
        val result = assertIs<DocbenchWorkspaceTransformResult.Completed>(
            execute("{\"secret\":\"never-log-this\"}", DocbenchWorkspaceTransformKind.FORMAT_JSON,
                sourceIds = listOf("private-asset-id"))
        )
        assertFalse(result.toString().contains("never-log-this"))
        assertFalse(result.toString().contains("private-asset-id"))
    }
}

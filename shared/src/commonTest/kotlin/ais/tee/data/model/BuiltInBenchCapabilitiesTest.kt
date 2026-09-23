package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuiltInBenchCapabilitiesTest {
    @Test
    fun accountWebChatToolsKeepExplicitUserActionRoute() {
        builtInBenchToolsForSurface(BenchToolSurface.ACCOUNT_WEB_CHAT).forEach { tool ->
            assertTrue(
                BenchToolInvocationMode.EXPLICIT_USER_ACTION in tool.capabilities().invocationModes
            )
        }
    }

    @Test
    fun onlyLocalTextBenchToolsClaimNativeModelInvocation() {
        val modelCallable = BuiltInBenchTool.entries.filter { tool ->
            BenchToolInvocationMode.MODEL_TOOL_CALL in tool.capabilities().invocationModes
        }.toSet()

        assertEquals(
            setOf(
                BuiltInBenchTool.DOCBENCH_DOCUMENT,
                BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR,
                BuiltInBenchTool.CODEBENCH_QR_BARCODE,
            ),
            modelCallable,
        )
        assertFalse(
            BenchToolInvocationMode.MODEL_TOOL_CALL in
                BuiltInBenchTool.STREAMBENCH_PLAYER.capabilities().invocationModes
        )
    }

    @Test
    fun stableToolIdsAreUniqueAndUnknownIdsFailClosed() {
        val ids = BuiltInBenchTool.entries.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals(
            BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR,
            BuiltInBenchTool.fromId("DOCBENCH-TEXT-INSPECTOR")
        )
        assertNull(BuiltInBenchTool.fromId("retired-tool"))
        assertNull(BuiltInBenchTool.fromId(null))
    }

    @Test
    fun localBenchToolsDoNotRequestNetwork() {
        listOf(
            BuiltInBenchTool.DOCBENCH_DOCUMENT,
            BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR,
            BuiltInBenchTool.CODEBENCH_QR_BARCODE
        ).forEach { tool ->
            val capabilities = tool.capabilities()
            assertEquals(BenchToolNetworkBehavior.LOCAL_ONLY, capabilities.networkBehavior)
            assertFalse(BenchToolPermission.NETWORK in capabilities.requiredPermissions)
            assertFalse(BenchToolPermission.NETWORK in capabilities.optionalPermissions)
        }
    }

    @Test
    fun localOnlyCapabilityRejectsOptionalNetworkPermission() {
        assertFailsWith<IllegalArgumentException> {
            capability(
                optionalPermissions = setOf(BenchToolPermission.NETWORK),
                networkBehavior = BenchToolNetworkBehavior.LOCAL_ONLY
            )
        }
    }

    @Test
    fun optionalNetworkCapabilityRequiresNetworkAsOptionalOnly() {
        capability(
            optionalPermissions = setOf(BenchToolPermission.NETWORK),
            networkBehavior = BenchToolNetworkBehavior.NETWORK_OPTIONAL
        )

        assertFailsWith<IllegalArgumentException> {
            capability(networkBehavior = BenchToolNetworkBehavior.NETWORK_OPTIONAL)
        }
        assertFailsWith<IllegalArgumentException> {
            capability(
                requiredPermissions = setOf(BenchToolPermission.NETWORK),
                networkBehavior = BenchToolNetworkBehavior.NETWORK_OPTIONAL
            )
        }
    }

    @Test
    fun streambenchRemainsACompanionSurfaceWithActionScopedNetwork() {
        val capabilities = BuiltInBenchTool.STREAMBENCH_PLAYER.capabilities()

        assertEquals(setOf(BenchToolSurface.COMPANION_UI), capabilities.surfaces)
        assertEquals(BenchToolNetworkBehavior.NETWORK_OPTIONAL, capabilities.networkBehavior)
        assertFalse(BenchToolPermission.NETWORK in capabilities.requiredPermissions)
        assertTrue(BenchToolPermission.NETWORK in capabilities.optionalPermissions)
        assertEquals(
            setOf(BenchToolInvocationMode.EXPLICIT_USER_ACTION),
            capabilities.invocationModes
        )
    }

    @Test
    fun docbenchToolsExposeCompanionUi() {
        assertTrue(
            BenchToolSurface.COMPANION_UI in
                BuiltInBenchTool.DOCBENCH_DOCUMENT.capabilities().surfaces
        )
        assertTrue(
            BenchToolSurface.COMPANION_UI in
                BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR.capabilities().surfaces
        )
    }

    @Test
    fun capabilityCallsReturnIndependentCollectionSnapshots() {
        val first = BuiltInBenchTool.DOCBENCH_DOCUMENT.capabilities()
        val second = BuiltInBenchTool.DOCBENCH_DOCUMENT.capabilities()

        assertEquals(first.inputs, second.inputs)
        assertEquals(first.surfaces, second.surfaces)
        assertNotSame(first.inputs, second.inputs)
        assertNotSame(first.surfaces, second.surfaces)
    }

    private fun capability(
        requiredPermissions: Set<BenchToolPermission> = emptySet(),
        optionalPermissions: Set<BenchToolPermission> = emptySet(),
        networkBehavior: BenchToolNetworkBehavior = BenchToolNetworkBehavior.LOCAL_ONLY,
        surfaces: Set<BenchToolSurface> = setOf(BenchToolSurface.NATIVE_CHAT),
        invocationModes: Set<BenchToolInvocationMode> =
            setOf(BenchToolInvocationMode.EXPLICIT_USER_ACTION)
    ) = BuiltInBenchToolCapabilities(
        displayName = "Test tool",
        inputs = setOf(BenchToolDataKind.TEXT),
        outputs = setOf(BenchToolDataKind.TEXT),
        requiredPermissions = requiredPermissions,
        optionalPermissions = optionalPermissions,
        networkBehavior = networkBehavior,
        surfaces = surfaces,
        invocationModes = invocationModes
    )
}

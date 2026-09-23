package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuiltInBenchAvailabilityTest {
    @Test
    fun decisionDistinguishesConsentFromExplicitInteractionAndHardDenial() {
        fun inspect(
            enabled: Boolean = true,
            mode: BenchToolInvocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            input: BenchToolDataKind = BenchToolDataKind.TEXT,
            grants: Set<BenchToolPermission> = emptySet(),
        ) = BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR.availability(
            surface = BenchToolSurface.ACCOUNT_WEB_CHAT,
            invocationMode = mode,
            inputKind = input,
            isEnabled = enabled,
            grantedPermissions = grants,
            networkAvailable = false,
        )

        assertEquals(CapabilityDecision.ASK, inspect().decision)
        assertFalse(inspect().canOffer)
        val grants = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)
        assertEquals(CapabilityDecision.ALLOW, inspect(grants = grants).decision)
        assertTrue(inspect(grants = grants).canOffer)

        // A content grant never upgrades an explicit-user bridge to model execution.
        for (permissions in listOf(emptySet(), grants)) {
            val automatic = inspect(mode = BenchToolInvocationMode.MODEL_TOOL_CALL, grants = permissions)
            assertEquals(CapabilityDecision.REQUIRES_USER_INTERACTION, automatic.decision)
            assertFalse(automatic.canOffer)
        }
        assertEquals(CapabilityDecision.DENY, inspect(enabled = false).decision)
        assertEquals(CapabilityDecision.DENY, inspect(input = BenchToolDataKind.IMAGE).decision)
        assertEquals(CapabilityDecision.DENY, inspect(enabled = false, mode = BenchToolInvocationMode.MODEL_TOOL_CALL).decision)
    }

    @Test
    fun offlineRequiredNetworkDeniesBeforeRequestingPermission() {
        val availability = BuiltInBenchTool.STREAMBENCH_PLAYER.availability(
            surface = BenchToolSurface.COMPANION_UI,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.MEDIA_STREAM,
            isEnabled = true,
            grantedPermissions = emptySet(),
            networkAvailable = false,
            actionRequiredPermissions = setOf(BenchToolPermission.NETWORK),
        )
        assertEquals(CapabilityDecision.DENY, availability.decision)
        assertFalse(availability.canOffer)
    }

    @Test
    fun localDocbenchRequiresContentGrantButNotNetworkAvailability() {
        val blocked = BuiltInBenchTool.DOCBENCH_DOCUMENT.availability(
            surface = BenchToolSurface.NATIVE_CHAT,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.DOCUMENT,
            isEnabled = true,
            grantedPermissions = emptySet(),
            networkAvailable = false
        )

        assertFalse(blocked.canOffer)
        assertEquals(
            setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
            blocked.missingRequiredPermissions
        )
        assertEquals(
            setOf(BenchToolAvailabilityBlocker.MISSING_REQUIRED_PERMISSION),
            blocked.blockers
        )

        val available = BuiltInBenchTool.DOCBENCH_DOCUMENT.availability(
            surface = BenchToolSurface.NATIVE_CHAT,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.DOCUMENT,
            isEnabled = true,
            grantedPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
            networkAvailable = false
        )

        assertTrue(available.canOffer)
    }

    @Test
    fun nativeLocalToolsCanBeModelCalledButAccountWebStillRequiresInteraction() {
        listOf(
            BuiltInBenchTool.DOCBENCH_DOCUMENT,
            BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR,
            BuiltInBenchTool.CODEBENCH_QR_BARCODE,
        ).forEach { tool ->
            val input = BenchToolDataKind.TEXT
            val native = tool.availability(
                surface = BenchToolSurface.NATIVE_CHAT,
                invocationMode = BenchToolInvocationMode.MODEL_TOOL_CALL,
                inputKind = input,
                isEnabled = true,
                grantedPermissions = BenchToolPermission.entries.toSet(),
                networkAvailable = false,
            )
            assertTrue(native.canOffer)

            val web = tool.availability(
                surface = BenchToolSurface.ACCOUNT_WEB_CHAT,
                invocationMode = BenchToolInvocationMode.MODEL_TOOL_CALL,
                inputKind = input,
                isEnabled = true,
                grantedPermissions = BenchToolPermission.entries.toSet(),
                networkAvailable = false,
            )
            assertFalse(web.canOffer)
            assertEquals(CapabilityDecision.REQUIRES_USER_INTERACTION, web.decision)
        }

        val streambench = BuiltInBenchTool.STREAMBENCH_PLAYER.availability(
            surface = BenchToolSurface.COMPANION_UI,
            invocationMode = BenchToolInvocationMode.MODEL_TOOL_CALL,
            inputKind = BenchToolDataKind.MEDIA_STREAM,
            isEnabled = true,
            grantedPermissions = BenchToolPermission.entries.toSet(),
            networkAvailable = true,
        )
        assertFalse(streambench.canOffer)
        assertTrue(
            BenchToolAvailabilityBlocker.UNSUPPORTED_INVOCATION_MODE in streambench.blockers
        )
    }

    @Test
    fun streambenchBaseCapabilityLeavesNetworkToConcreteActions() {
        val availability = BuiltInBenchTool.STREAMBENCH_PLAYER.availability(
            surface = BenchToolSurface.COMPANION_UI,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.PLAYLIST,
            isEnabled = true,
            grantedPermissions = emptySet(),
            networkAvailable = false
        )

        assertTrue(availability.canOffer)
        assertTrue(availability.missingRequiredPermissions.isEmpty())
        assertTrue(availability.blockers.isEmpty())
    }

    @Test
    fun concreteActionCanRequireDeclaredNetworkScopeAndConnectivity() {
        val missingScopeAndOffline = BuiltInBenchTool.STREAMBENCH_PLAYER.availability(
            surface = BenchToolSurface.COMPANION_UI,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.MEDIA_STREAM,
            isEnabled = true,
            grantedPermissions = emptySet(),
            networkAvailable = false,
            actionRequiredPermissions = setOf(BenchToolPermission.NETWORK)
        )

        assertEquals(
            setOf(BenchToolPermission.NETWORK),
            missingScopeAndOffline.missingRequiredPermissions
        )
        assertEquals(
            setOf(
                BenchToolAvailabilityBlocker.MISSING_REQUIRED_PERMISSION,
                BenchToolAvailabilityBlocker.NETWORK_UNAVAILABLE
            ),
            missingScopeAndOffline.blockers
        )

        val grantedButOffline = BuiltInBenchTool.STREAMBENCH_PLAYER.availability(
            surface = BenchToolSurface.COMPANION_UI,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.MEDIA_STREAM,
            isEnabled = true,
            grantedPermissions = setOf(BenchToolPermission.NETWORK),
            networkAvailable = false,
            actionRequiredPermissions = setOf(BenchToolPermission.NETWORK)
        )

        assertEquals(
            setOf(BenchToolAvailabilityBlocker.NETWORK_UNAVAILABLE),
            grantedButOffline.blockers
        )

        val online = BuiltInBenchTool.STREAMBENCH_PLAYER.availability(
            surface = BenchToolSurface.COMPANION_UI,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.MEDIA_STREAM,
            isEnabled = true,
            grantedPermissions = setOf(BenchToolPermission.NETWORK),
            networkAvailable = true,
            actionRequiredPermissions = setOf(BenchToolPermission.NETWORK)
        )

        assertTrue(online.canOffer)
    }

    @Test
    fun unsupportedSurfaceAndDisabledStateAreIndependentBlockers() {
        val availability = BuiltInBenchTool.STREAMBENCH_PLAYER.availability(
            surface = BenchToolSurface.NATIVE_CHAT,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.MEDIA_STREAM,
            isEnabled = false,
            grantedPermissions = setOf(BenchToolPermission.NETWORK),
            networkAvailable = true
        )

        assertFalse(availability.canOffer)
        assertEquals(
            setOf(
                BenchToolAvailabilityBlocker.DISABLED,
                BenchToolAvailabilityBlocker.UNSUPPORTED_SURFACE
            ),
            availability.blockers
        )
    }

    @Test
    fun unsupportedInputIsAnIndependentBlocker() {
        val unsupported = BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR.availability(
            surface = BenchToolSurface.NATIVE_CHAT,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.IMAGE,
            isEnabled = true,
            grantedPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
            networkAvailable = true
        )

        assertFalse(unsupported.canOffer)
        assertEquals(
            setOf(BenchToolAvailabilityBlocker.UNSUPPORTED_INPUT),
            unsupported.blockers
        )

        val supported = BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR.availability(
            surface = BenchToolSurface.NATIVE_CHAT,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.TEXT,
            isEnabled = true,
            grantedPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
            networkAvailable = true
        )

        assertTrue(supported.canOffer)
    }

    @Test
    fun optionalPermissionsNeverBlockTheBaseAction() {
        val availability = BuiltInBenchTool.CODEBENCH_QR_BARCODE.availability(
            surface = BenchToolSurface.ACCOUNT_WEB_CHAT,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.TEXT,
            isEnabled = true,
            grantedPermissions = emptySet(),
            networkAvailable = false
        )

        assertTrue(availability.canOffer)
        assertTrue(availability.missingRequiredPermissions.isEmpty())
        assertTrue(availability.blockers.isEmpty())
    }

    @Test
    fun concreteActionCanPromoteADeclaredOptionalPermission() {
        val blocked = BuiltInBenchTool.CODEBENCH_QR_BARCODE.availability(
            surface = BenchToolSurface.NATIVE_CHAT,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.IMAGE,
            isEnabled = true,
            grantedPermissions = emptySet(),
            networkAvailable = false,
            actionRequiredPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)
        )

        assertFalse(blocked.canOffer)
        assertEquals(
            setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
            blocked.missingRequiredPermissions
        )
        assertEquals(
            setOf(BenchToolAvailabilityBlocker.MISSING_REQUIRED_PERMISSION),
            blocked.blockers
        )

        val available = BuiltInBenchTool.CODEBENCH_QR_BARCODE.availability(
            surface = BenchToolSurface.NATIVE_CHAT,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.IMAGE,
            isEnabled = true,
            grantedPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
            networkAvailable = false,
            actionRequiredPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)
        )

        assertTrue(available.canOffer)
    }

    @Test
    fun qrModelActionRequiresScopedProjectLibraryWrite() {
        val blocked = BuiltInBenchTool.CODEBENCH_QR_BARCODE.availability(
            surface = BenchToolSurface.NATIVE_CHAT,
            invocationMode = BenchToolInvocationMode.MODEL_TOOL_CALL,
            inputKind = BenchToolDataKind.TEXT,
            isEnabled = true,
            grantedPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
            networkAvailable = false,
            actionRequiredPermissions = setOf(BenchToolPermission.WRITE_PROJECT_LIBRARY)
        )

        assertFalse(blocked.canOffer)
        assertEquals(CapabilityDecision.ASK, blocked.decision)
        assertEquals(
            setOf(BenchToolPermission.WRITE_PROJECT_LIBRARY),
            blocked.missingRequiredPermissions
        )

        val available = BuiltInBenchTool.CODEBENCH_QR_BARCODE.availability(
            surface = BenchToolSurface.NATIVE_CHAT,
            invocationMode = BenchToolInvocationMode.MODEL_TOOL_CALL,
            inputKind = BenchToolDataKind.TEXT,
            isEnabled = true,
            grantedPermissions = setOf(
                BenchToolPermission.READ_USER_SELECTED_CONTENT,
                BenchToolPermission.WRITE_PROJECT_LIBRARY,
            ),
            networkAvailable = false,
            actionRequiredPermissions = setOf(BenchToolPermission.WRITE_PROJECT_LIBRARY)
        )

        assertTrue(available.canOffer)
        assertEquals(CapabilityDecision.ALLOW, available.decision)
    }

    @Test
    fun concreteActionCannotRequireAnUndeclaredPermission() {
        assertFailsWith<IllegalArgumentException> {
            BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR.availability(
                surface = BenchToolSurface.NATIVE_CHAT,
                invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
                inputKind = BenchToolDataKind.TEXT,
                isEnabled = true,
                grantedPermissions = emptySet(),
                networkAvailable = false,
                actionRequiredPermissions = setOf(BenchToolPermission.CAMERA)
            )
        }
    }
}

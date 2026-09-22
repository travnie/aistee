package ais.tee.data.model

/** Why a first-party Bench action cannot currently be offered on a requested route. */
enum class BenchToolAvailabilityBlocker {
    DISABLED,
    UNSUPPORTED_SURFACE,
    UNSUPPORTED_INVOCATION_MODE,
    UNSUPPORTED_INPUT,
    MISSING_REQUIRED_PERMISSION,
    NETWORK_UNAVAILABLE
}

/**
 * Derived, side-effect-free availability for one requested first-party Bench action.
 *
 * This does not grant permissions, perform network checks or execute a tool. Callers provide the
 * current state and receive the policy blockers that must be cleared before an action is offered.
 */
data class BuiltInBenchToolAvailability(
    val blockers: Set<BenchToolAvailabilityBlocker>,
    val missingRequiredPermissions: Set<BenchToolPermission>,
    private val supportsExplicitUserAction: Boolean = false,
) {
    /** Denial wins over consent; a user-only route never becomes model-callable after a grant. */
    val decision: CapabilityDecision
        get() = when {
            blockers.any {
                it != BenchToolAvailabilityBlocker.MISSING_REQUIRED_PERMISSION &&
                    it != BenchToolAvailabilityBlocker.UNSUPPORTED_INVOCATION_MODE
            } -> CapabilityDecision.DENY
            BenchToolAvailabilityBlocker.UNSUPPORTED_INVOCATION_MODE in blockers ->
                if (supportsExplicitUserAction) CapabilityDecision.REQUIRES_USER_INTERACTION
                else CapabilityDecision.DENY
            missingRequiredPermissions.isNotEmpty() -> CapabilityDecision.ASK
            blockers.isEmpty() -> CapabilityDecision.ALLOW
            else -> CapabilityDecision.DENY
        }

    val canOffer: Boolean
        get() = decision == CapabilityDecision.ALLOW
}

/** Evaluate the canonical registry policy for one concrete tool route and input kind. */
fun BuiltInBenchTool.availability(
    surface: BenchToolSurface,
    invocationMode: BenchToolInvocationMode,
    inputKind: BenchToolDataKind,
    isEnabled: Boolean,
    grantedPermissions: Set<BenchToolPermission>,
    networkAvailable: Boolean,
    actionRequiredPermissions: Set<BenchToolPermission> = emptySet()
): BuiltInBenchToolAvailability {
    val capabilities = capabilities()
    val declaredPermissions = capabilities.requiredPermissions + capabilities.optionalPermissions
    require(actionRequiredPermissions.all { it in declaredPermissions }) {
        "Action-required permissions must be declared by the Bench tool registry"
    }
    val requiredPermissions = capabilities.requiredPermissions + actionRequiredPermissions
    val missingPermissions = requiredPermissions - grantedPermissions
    val blockers = buildSet {
        if (!isEnabled) add(BenchToolAvailabilityBlocker.DISABLED)
        if (surface !in capabilities.surfaces) {
            add(BenchToolAvailabilityBlocker.UNSUPPORTED_SURFACE)
        }
        if (invocationMode !in capabilities.invocationModes) {
            add(BenchToolAvailabilityBlocker.UNSUPPORTED_INVOCATION_MODE)
        }
        if (inputKind !in capabilities.inputs) {
            add(BenchToolAvailabilityBlocker.UNSUPPORTED_INPUT)
        }
        if (missingPermissions.isNotEmpty()) {
            add(BenchToolAvailabilityBlocker.MISSING_REQUIRED_PERMISSION)
        }
        if (BenchToolPermission.NETWORK in requiredPermissions && !networkAvailable) {
            add(BenchToolAvailabilityBlocker.NETWORK_UNAVAILABLE)
        }
    }
    return BuiltInBenchToolAvailability(
        blockers = blockers,
        missingRequiredPermissions = missingPermissions,
        supportsExplicitUserAction = BenchToolInvocationMode.EXPLICIT_USER_ACTION in capabilities.invocationModes,
    )
}

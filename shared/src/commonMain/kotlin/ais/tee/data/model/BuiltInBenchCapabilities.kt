package ais.tee.data.model

/** First-party Bench capabilities that Aistee can expose without copying a whole web app. */
enum class BuiltInBenchTool(val id: String) {
    DOCBENCH_DOCUMENT("docbench-document"),
    DOCBENCH_TEXT_INSPECTOR("docbench-text-inspector"),
    CODEBENCH_QR_BARCODE("codebench-qr-barcode"),
    STREAMBENCH_PLAYER("streambench-player");

    companion object {
        fun fromId(id: String?): BuiltInBenchTool? = entries.firstOrNull { tool ->
            id?.equals(tool.id, ignoreCase = true) == true
        }
    }
}

/** Coarse data classes used to keep tool routing explicit without binding the core to one UI. */
enum class BenchToolDataKind {
    TEXT,
    DOCUMENT,
    PDF,
    IMAGE,
    DIAGNOSTIC_REPORT,
    PLAYLIST,
    GUIDE,
    MEDIA_STREAM,
    MEDIA_METADATA
}

/** Sensitive capabilities a first-party tool may need before an invocation can be offered. */
enum class BenchToolPermission {
    READ_USER_SELECTED_CONTENT,
    WRITE_USER_EXPORT,
    CAMERA,
    NETWORK
}

enum class BenchToolNetworkBehavior {
    LOCAL_ONLY,
    NETWORK_OPTIONAL,
    NETWORK_REQUIRED
}

/** Product surfaces where an explicit first-party action may be exposed. */
enum class BenchToolSurface {
    NATIVE_CHAT,
    ACCOUNT_WEB_CHAT,
    COMPANION_UI
}

/**
 * Invocation policy is intentionally separate from surface support.
 *
 * Local Docbench text tools and Codebench QR generation may be model-callable on native/API chat
 * because the typed transport boundary is implemented. Account WebViews remain explicit user
 * bridges rather than hidden page automation.
 */
enum class BenchToolInvocationMode {
    EXPLICIT_USER_ACTION,
    MODEL_TOOL_CALL
}

data class BuiltInBenchToolCapabilities(
    val displayName: String,
    val inputs: Set<BenchToolDataKind>,
    val outputs: Set<BenchToolDataKind>,
    val requiredPermissions: Set<BenchToolPermission>,
    val optionalPermissions: Set<BenchToolPermission> = emptySet(),
    val networkBehavior: BenchToolNetworkBehavior,
    val surfaces: Set<BenchToolSurface>,
    val invocationModes: Set<BenchToolInvocationMode>
) {
    init {
        require(displayName.isNotBlank()) { "Built-in Bench tool display name must not be blank" }
        require(inputs.isNotEmpty()) { "Built-in Bench tool must declare at least one input" }
        require(outputs.isNotEmpty()) { "Built-in Bench tool must declare at least one output" }
        require(surfaces.isNotEmpty()) { "Built-in Bench tool must declare at least one surface" }
        require(invocationModes.isNotEmpty()) { "Built-in Bench tool must declare an invocation mode" }
        require(requiredPermissions.intersect(optionalPermissions).isEmpty()) {
            "A Bench tool permission cannot be both required and optional"
        }
        when (networkBehavior) {
            BenchToolNetworkBehavior.NETWORK_REQUIRED -> require(
                BenchToolPermission.NETWORK in requiredPermissions &&
                    BenchToolPermission.NETWORK !in optionalPermissions
            ) {
                "Network-required Bench tools must require NETWORK"
            }

            BenchToolNetworkBehavior.NETWORK_OPTIONAL -> require(
                BenchToolPermission.NETWORK !in requiredPermissions &&
                    BenchToolPermission.NETWORK in optionalPermissions
            ) {
                "Network-optional Bench tools must declare NETWORK as optional only"
            }

            BenchToolNetworkBehavior.LOCAL_ONLY -> require(
                BenchToolPermission.NETWORK !in requiredPermissions &&
                    BenchToolPermission.NETWORK !in optionalPermissions
            ) {
                "Local-only Bench tools cannot request NETWORK"
            }
        }
        if (BenchToolSurface.ACCOUNT_WEB_CHAT in surfaces) {
            require(BenchToolInvocationMode.EXPLICIT_USER_ACTION in invocationModes) {
                "Account Web chat Bench bridges must keep an explicit user-action route"
            }
        }
    }
}

/**
 * Canonical first-party Bench policy. Each call returns fresh capability collections, so callers can
 * shape local UI state without mutating the registry's source of truth.
 */
fun BuiltInBenchTool.capabilities(): BuiltInBenchToolCapabilities = when (this) {
    BuiltInBenchTool.DOCBENCH_DOCUMENT -> BuiltInBenchToolCapabilities(
        displayName = "Docbench document tools",
        inputs = setOf(
            BenchToolDataKind.TEXT,
            BenchToolDataKind.DOCUMENT,
            BenchToolDataKind.PDF
        ),
        outputs = setOf(
            BenchToolDataKind.TEXT,
            BenchToolDataKind.DOCUMENT,
            BenchToolDataKind.PDF,
            BenchToolDataKind.DIAGNOSTIC_REPORT
        ),
        requiredPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
        optionalPermissions = setOf(BenchToolPermission.WRITE_USER_EXPORT),
        networkBehavior = BenchToolNetworkBehavior.LOCAL_ONLY,
        surfaces = setOf(
            BenchToolSurface.NATIVE_CHAT,
            BenchToolSurface.ACCOUNT_WEB_CHAT,
            BenchToolSurface.COMPANION_UI
        ),
        invocationModes = setOf(
            BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            BenchToolInvocationMode.MODEL_TOOL_CALL
        )
    )

    BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR -> BuiltInBenchToolCapabilities(
        displayName = "Docbench Text Inspector",
        inputs = setOf(BenchToolDataKind.TEXT, BenchToolDataKind.DOCUMENT),
        outputs = setOf(BenchToolDataKind.DIAGNOSTIC_REPORT),
        requiredPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
        networkBehavior = BenchToolNetworkBehavior.LOCAL_ONLY,
        surfaces = setOf(
            BenchToolSurface.NATIVE_CHAT,
            BenchToolSurface.ACCOUNT_WEB_CHAT,
            BenchToolSurface.COMPANION_UI
        ),
        invocationModes = setOf(
            BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            BenchToolInvocationMode.MODEL_TOOL_CALL
        )
    )

    BuiltInBenchTool.CODEBENCH_QR_BARCODE -> BuiltInBenchToolCapabilities(
        displayName = "Codebench QR & barcode",
        inputs = setOf(BenchToolDataKind.TEXT, BenchToolDataKind.IMAGE),
        outputs = setOf(BenchToolDataKind.TEXT, BenchToolDataKind.IMAGE),
        requiredPermissions = emptySet(),
        optionalPermissions = setOf(
            BenchToolPermission.READ_USER_SELECTED_CONTENT,
            BenchToolPermission.WRITE_USER_EXPORT,
            BenchToolPermission.CAMERA
        ),
        networkBehavior = BenchToolNetworkBehavior.LOCAL_ONLY,
        surfaces = setOf(
            BenchToolSurface.NATIVE_CHAT,
            BenchToolSurface.ACCOUNT_WEB_CHAT,
            BenchToolSurface.COMPANION_UI
        ),
        invocationModes = setOf(
            BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            BenchToolInvocationMode.MODEL_TOOL_CALL
        )
    )

    BuiltInBenchTool.STREAMBENCH_PLAYER -> BuiltInBenchToolCapabilities(
        displayName = "Streambench player",
        inputs = setOf(
            BenchToolDataKind.MEDIA_STREAM,
            BenchToolDataKind.PLAYLIST,
            BenchToolDataKind.GUIDE
        ),
        outputs = setOf(
            BenchToolDataKind.MEDIA_STREAM,
            BenchToolDataKind.MEDIA_METADATA,
            BenchToolDataKind.PLAYLIST
        ),
        requiredPermissions = emptySet(),
        optionalPermissions = setOf(
            BenchToolPermission.READ_USER_SELECTED_CONTENT,
            BenchToolPermission.WRITE_USER_EXPORT,
            BenchToolPermission.NETWORK
        ),
        networkBehavior = BenchToolNetworkBehavior.NETWORK_OPTIONAL,
        surfaces = setOf(BenchToolSurface.COMPANION_UI),
        invocationModes = setOf(BenchToolInvocationMode.EXPLICIT_USER_ACTION)
    )
}

fun builtInBenchToolsForSurface(surface: BenchToolSurface): List<BuiltInBenchTool> =
    BuiltInBenchTool.entries.filter { tool -> surface in tool.capabilities().surfaces }

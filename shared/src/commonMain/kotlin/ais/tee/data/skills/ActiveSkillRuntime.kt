package ais.tee.data.skills

import ais.tee.data.model.CapabilityDecision

/**
 * Contract for active skills (`docs/skills-runtime.md`). Everything here is pure policy; the
 * platform sandbox only executes what these functions allow, and only while the feature flag is on.
 */
const val ACTIVE_SKILL_RUNTIME_WEBVIEW_V1 = "webview-v1"
const val ACTIVE_SKILL_MAX_NETWORK_ORIGINS = 4
const val ACTIVE_SKILL_MAX_OUTPUT_BYTES = 64 * 1024
const val ACTIVE_SKILL_MAX_TOOL_REQUESTS = 8
const val ACTIVE_SKILL_MAX_CARD_TITLE_CHARS = 200
const val ACTIVE_SKILL_TIMEOUT_MS = 10_000L

internal const val ACTIVE_SKILL_METADATA_RUNTIME = "aistee-runtime"
internal const val ACTIVE_SKILL_METADATA_TOOLS = "aistee-tools"
internal const val ACTIVE_SKILL_METADATA_NETWORK = "aistee-network"

/** Closed allowlist of native tools. The host owns every implementation. */
enum class ActiveSkillTool(val id: String, val changesSomethingOutsideAistee: Boolean) {
    CURRENT_DATETIME("current_datetime", changesSomethingOutsideAistee = false),
    CREATE_CALENDAR_EVENT("create_calendar_event", changesSomethingOutsideAistee = true),
    COMPOSE_EMAIL("compose_email", changesSomethingOutsideAistee = true),
    SCHEDULE_NOTIFICATION("schedule_notification", changesSomethingOutsideAistee = true),
    COPY_TO_CLIPBOARD("copy_to_clipboard", changesSomethingOutsideAistee = true);

    companion object {
        fun fromId(id: String): ActiveSkillTool? = entries.firstOrNull { it.id == id }
    }
}

/** What an active skill declared in its `metadata:` map. Anything undeclared is denied. */
data class ActiveSkillDeclaration(
    val runtime: String,
    val tools: Set<ActiveSkillTool>,
    val networkOrigins: List<String>,
)

data class ActiveSkillDeclarationResult(
    /** Null for a plain (inert) skill or when [issues] is not empty. */
    val declaration: ActiveSkillDeclaration?,
    val issues: List<AgentSkillValidationIssue>,
)

fun activeSkillDeclaration(manifest: AgentSkillManifest): ActiveSkillDeclarationResult {
    val runtime = manifest.metadata[ACTIVE_SKILL_METADATA_RUNTIME]?.trim()
    if (runtime == null) {
        val stray = listOf(ACTIVE_SKILL_METADATA_TOOLS, ACTIVE_SKILL_METADATA_NETWORK)
            .filter(manifest.metadata::containsKey)
            .map { AgentSkillValidationIssue("metadata.$it", "metadata.$it needs metadata.$ACTIVE_SKILL_METADATA_RUNTIME") }
        return ActiveSkillDeclarationResult(null, stray)
    }
    val issues = mutableListOf<AgentSkillValidationIssue>()
    if (runtime != ACTIVE_SKILL_RUNTIME_WEBVIEW_V1) {
        issues += AgentSkillValidationIssue(
            "metadata.$ACTIVE_SKILL_METADATA_RUNTIME",
            "Unsupported runtime '$runtime'; expected $ACTIVE_SKILL_RUNTIME_WEBVIEW_V1",
        )
    }
    val tools = linkedSetOf<ActiveSkillTool>()
    manifest.metadata[ACTIVE_SKILL_METADATA_TOOLS].words().forEach { id ->
        val tool = ActiveSkillTool.fromId(id)
        if (tool == null) {
            issues += AgentSkillValidationIssue("metadata.$ACTIVE_SKILL_METADATA_TOOLS", "Unknown tool '$id'")
        } else {
            tools += tool
        }
    }
    val origins = mutableListOf<String>()
    manifest.metadata[ACTIVE_SKILL_METADATA_NETWORK].words().forEach { value ->
        val origin = normalizeActiveSkillNetworkOrigin(value)
        if (origin == null) {
            issues += AgentSkillValidationIssue(
                "metadata.$ACTIVE_SKILL_METADATA_NETWORK",
                "'$value' is not a public https:// origin",
            )
        } else if (origin !in origins) {
            origins += origin
        }
    }
    if (origins.size > ACTIVE_SKILL_MAX_NETWORK_ORIGINS) {
        issues += AgentSkillValidationIssue(
            "metadata.$ACTIVE_SKILL_METADATA_NETWORK",
            "At most $ACTIVE_SKILL_MAX_NETWORK_ORIGINS network origins are allowed",
        )
    }
    return if (issues.isEmpty()) {
        ActiveSkillDeclarationResult(ActiveSkillDeclaration(runtime, tools, origins), emptyList())
    } else {
        ActiveSkillDeclarationResult(null, issues)
    }
}

private fun String?.words(): List<String> =
    this?.split(Regex("\\s+"))?.filter(String::isNotEmpty).orEmpty()

/** Special-use and private names (RFC 6761, 6762, 7686, 8375, 9476 and ICANN's `.internal`). */
private val blockedHostSuffixes = listOf(
    ".local", ".localhost", ".internal", ".invalid", ".test", ".example", ".arpa", ".onion", ".alt",
)
private val hostLabel = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

/**
 * Validates an `aistee-network` entry for preview and trust. Network stays off in v1: declaring
 * origins never grants access, and a skill that declares any cannot run yet.
 *
 * Returns `https://host[:port]` for a public DNS origin, or null. IP literals, single-label names,
 * userinfo, paths, queries and local/reserved suffixes are rejected so a skill cannot reach the
 * device or the local network.
 */
fun normalizeActiveSkillNetworkOrigin(value: String): String? {
    val authority = parseHttpsAuthority(value.trim(), allowPath = false) ?: return null
    return authority.origin
}

private data class HttpsAuthority(val host: String, val port: Int?) {
    val origin: String get() = if (port == null) "https://$host" else "https://$host:$port"
}

private fun parseHttpsAuthority(value: String, allowPath: Boolean): HttpsAuthority? {
    if (!value.startsWith("https://", ignoreCase = true)) return null
    val rest = value.substring("https://".length)
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, end)
    val tail = rest.substring(end)
    if (!allowPath && tail.isNotEmpty() && tail != "/") return null
    if ('@' in authority || authority.isEmpty() || authority.startsWith('[')) return null
    val host: String
    var port: Int? = null
    val colon = authority.lastIndexOf(':')
    if (colon >= 0) {
        host = authority.substring(0, colon)
        port = authority.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (port == 443) port = null
    } else {
        host = authority
    }
    val normalizedHost = host.lowercase().removeSuffix(".")
    val labels = normalizedHost.split('.')
    if (labels.size < 2 || labels.any { !hostLabel.matches(it) }) return null
    if (labels.last().all(Char::isDigit)) return null
    if (normalizedHost == "localhost" || blockedHostSuffixes.any { normalizedHost.endsWith(it) }) return null
    return HttpsAuthority(normalizedHost, port)
}

/** Only named, declared tools can run; anything that changes state outside Aistee needs the user. */
fun activeSkillToolDecision(toolName: String, declaration: ActiveSkillDeclaration): CapabilityDecision {
    val tool = ActiveSkillTool.fromId(toolName) ?: return CapabilityDecision.DENY
    if (tool !in declaration.tools) return CapabilityDecision.DENY
    return if (tool.changesSomethingOutsideAistee) {
        CapabilityDecision.REQUIRES_USER_INTERACTION
    } else {
        CapabilityDecision.ALLOW
    }
}

enum class ActiveSkillInvoker { USER, MODEL }

data class ActiveSkillInvocationContext(
    val featureEnabled: Boolean,
    val executionTrusted: Boolean,
    val isIncognitoChat: Boolean,
    val isQuickPrivacyOn: Boolean,
)

/** ALLOW runs the skill; DENY never runs. V1 runs offline skills only, for the user or the model. */
fun activeSkillInvocationDecision(
    invoker: ActiveSkillInvoker,
    declaration: ActiveSkillDeclaration,
    context: ActiveSkillInvocationContext,
): CapabilityDecision = when {
    !context.featureEnabled || !context.executionTrusted -> CapabilityDecision.DENY
    context.isIncognitoChat -> CapabilityDecision.DENY
    declaration.networkOrigins.isNotEmpty() -> CapabilityDecision.DENY
    invoker == ActiveSkillInvoker.MODEL && context.isQuickPrivacyOn -> CapabilityDecision.DENY
    else -> CapabilityDecision.ALLOW
}

/**
 * Execution trust is bound to the exact bundle digest and the network origins the user saw.
 * Editing any file or changing the declared origins clears it.
 */
data class ActiveSkillTrust(
    val skillName: String,
    val bundleDigest: String,
    val networkOrigins: List<String>,
)

fun isActiveSkillTrusted(
    trust: ActiveSkillTrust?,
    skillName: String,
    bundleDigest: String,
    declaration: ActiveSkillDeclaration,
): Boolean = trust != null &&
    trust.skillName == skillName &&
    trust.bundleDigest == bundleDigest &&
    trust.networkOrigins == declaration.networkOrigins

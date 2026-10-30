package ais.tee.data.engine

import ais.tee.data.model.BenchToolDataKind
import ais.tee.data.model.BenchToolInvocationMode
import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import ais.tee.data.model.BuiltInBenchTool
import ais.tee.data.model.CapabilityDecision
import ais.tee.data.model.availability
import ais.tee.data.tokenizer.LocalTokenCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local result shared by consent-gated adapters. */
internal sealed interface SystemAssistantTokenCountResult {
    data class Count(val tokens: Int, val encoding: String) : SystemAssistantTokenCountResult
    data object Blocked : SystemAssistantTokenCountResult
    data object InvalidInput : SystemAssistantTokenCountResult
}

/**
 * The first narrow, device-independent boundary for future Android system assistant calls.
 *
 * The caller must have a fresh, explicit user request and a separate system-assistant opt-in.
 * An enabled Bench tool alone is never sufficient. Treat text as caller-supplied inline input;
 * do not fetch Aistee conversations, files, session state or any provider-owned data.
 *
 * This adapter deliberately reuses Docbench's canonical capability policy and tokenizer. It
 * does not expose an OS service, request permissions or retain/log text.
 */
internal object SystemAssistantTokenCountAction {
    suspend fun execute(
        text: String?,
        systemAssistantEnabled: Boolean = false,
        userInitiated: Boolean = false,
        enabledBenchTools: Set<BuiltInBenchTool> = emptySet(),
        grantedPermissions: Set<BenchToolPermission> = emptySet(),
    ): SystemAssistantTokenCountResult {
        // Fail closed before inspecting caller content or invoking the tokenizer.
        if (!systemAssistantEnabled || !userInitiated) return SystemAssistantTokenCountResult.Blocked
        val availability = BuiltInBenchTool.DOCBENCH_DOCUMENT.availability(
            surface = BenchToolSurface.COMPANION_UI,
            invocationMode = BenchToolInvocationMode.EXPLICIT_USER_ACTION,
            inputKind = BenchToolDataKind.TEXT,
            isEnabled = BuiltInBenchTool.DOCBENCH_DOCUMENT in enabledBenchTools,
            grantedPermissions = grantedPermissions,
            networkAvailable = false,
        )
        if (availability.decision != CapabilityDecision.ALLOW) {
            return SystemAssistantTokenCountResult.Blocked
        }
        if (text.isNullOrBlank() || text.length > MAX_INLINE_TOOL_TEXT_CHARS) {
            return SystemAssistantTokenCountResult.InvalidInput
        }

        // Never run a potentially large tokenizer pass on the Binder / UI thread.
        return withContext(Dispatchers.Default) {
            SystemAssistantTokenCountResult.Count(
                tokens = LocalTokenCounter.count(text),
                encoding = LocalTokenCounter.ENCODING_LABEL,
            )
        }
    }
}

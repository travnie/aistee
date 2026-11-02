package ais.tee.data.engine

import ais.tee.data.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable review; approval and dispatch each consumed once. No persisted grant. */
internal class McpToolCallReview private constructor(
    val endpoint: String,
    val catalog: McpToolCatalog,
    val target: McpToolTarget,
    val call: NativeToolCall,
    val rawArguments: String,
    val epoch: Int,
) {
    private val approved = AtomicBoolean(false)
    private val dispatched = AtomicBoolean(false)

    fun approveOnce(): Boolean = approved.compareAndSet(false, true)

    fun takeForExecution(): Boolean =
        catalog.isCurrent(target) && catalog.callDecision(call.name, rawArguments,
            BenchToolInvocationMode.EXPLICIT_USER_ACTION, approved.get()) == CapabilityDecision.ALLOW &&
            dispatched.compareAndSet(false, true)

    override fun toString() = "McpToolCallReview([redacted])"

    companion object {
        fun prepare(endpoint: String, catalog: McpToolCatalog, nativeName: String,
                    arguments: String, epoch: Int): McpToolCallReview? {
            val url = validatedMcpEndpoint(endpoint)?.toString() ?: return null
            if (catalog.callDecision(nativeName, arguments, BenchToolInvocationMode.EXPLICIT_USER_ACTION,
                    approved = false) != CapabilityDecision.ASK) return null
            val target = catalog.resolveTarget(nativeName) ?: return null
            val args = parseMcpArguments(arguments) ?: return null
            return McpToolCallReview(url, catalog, target,
                NativeToolCall(UUID.randomUUID().toString(), nativeName, args), arguments, epoch)
        }
    }
}

internal enum class McpToolCallFailure { BLOCKED, AUTHORIZATION_REQUIRED, INVALID_RESPONSE, NETWORK_FAILURE, TIMEOUT, CANCELLED, DENIED }

internal sealed interface McpToolCallOutcome {
    data class Completed(val result: NativeToolResult) : McpToolCallOutcome
    data class Failure(val reason: McpToolCallFailure) : McpToolCallOutcome
}

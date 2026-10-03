package ais.tee.data.model

/**
 * Provider-reported size of one assembled native-chat input and the selected model's input limit.
 * Accuracy follows each provider's counting contract; some providers explicitly return estimates.
 *
 * Transport-specific counting stays in the platform module; these budget semantics are portable.
 */
data class ProviderInputBudgetPreflight(
    val provider: AiProvider,
    val model: String,
    val inputTokens: Int,
    val inputTokenLimit: Int,
    /** Optional caller-owned correlation tag; providers never interpret it. */
    val requestTag: String? = null,
    /** Optional fingerprint of the actual request context used for this count. */
    val requestContextFingerprint: String? = null,
) {
    val remainingTokens: Int get() = inputTokenLimit - inputTokens
    val fits: Boolean get() = remainingTokens >= 0
}

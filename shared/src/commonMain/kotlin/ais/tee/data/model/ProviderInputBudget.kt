package ais.tee.data.model

/**
 * Provider-exact size of one assembled native-chat input and the selected model's input limit.
 *
 * Transport-specific counting stays in the platform module; these budget semantics are portable.
 */
data class ProviderInputBudgetPreflight(
    val provider: AiProvider,
    val model: String,
    val inputTokens: Int,
    val inputTokenLimit: Int,
) {
    val remainingTokens: Int get() = inputTokenLimit - inputTokens
    val fits: Boolean get() = remainingTokens >= 0
}

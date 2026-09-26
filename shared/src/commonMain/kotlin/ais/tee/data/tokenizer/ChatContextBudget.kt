package ais.tee.data.tokenizer

import ais.tee.data.model.AiProvider

/** Output space kept free when checking whether a native chat request fits the context window. */
const val CHAT_CONTEXT_OUTPUT_RESERVE_TOKENS = 4_096

/**
 * Input context windows that are published for the model IDs in the catalog. Models missing here
 * get no context warning: the budget is never computed against a guessed window.
 */
private val knownContextWindows: Map<AiProvider, Map<String, Int>> = mapOf(
    AiProvider.CLAUDE to mapOf(
        "claude-sonnet-5" to 1_000_000,
        "claude-fable-5" to 1_000_000,
        "claude-opus-5" to 1_000_000,
        "claude-haiku-4-5-20251001" to 200_000,
    ),
)

fun knownChatContextWindowTokens(provider: AiProvider, model: String): Int? =
    knownContextWindows[provider]?.get(model.trim())

/**
 * Pre-send budget for what a native chat request carries besides the new prompt: the system
 * instruction (profile and enabled skills) and the earlier turns, in that order.
 */
fun planChatContextBudget(
    counter: TokenCounter,
    contextTokens: Int,
    prompt: String,
    systemInstruction: String?,
    history: List<String>,
    reservedOutputTokens: Int = CHAT_CONTEXT_OUTPUT_RESERVE_TOKENS,
): AttachmentTokenBudget {
    val sources = buildList {
        systemInstruction?.takeIf(String::isNotBlank)?.let {
            add(AttachmentTokens("Profile and skills", counter.count(it)))
        }
        if (history.isNotEmpty()) {
            add(AttachmentTokens("Earlier messages", history.sumOf { counter.count(it).toLong() }.toSaturatedInt()))
        }
    }
    return planAttachmentTokenBudget(
        encodingLabel = counter.encodingLabel,
        contextTokens = contextTokens,
        promptTokens = counter.count(prompt),
        reservedOutputTokens = reservedOutputTokens,
        attachments = sources,
    )
}

/** Inline warning shown above the composer when the chat context does not fit completely. */
data class ChatContextWarning(
    val readablePercent: Int,
    val encodingLabel: String,
    /** The new message plus the output reserve alone does not fit the window. */
    val promptExceedsWindow: Boolean = false,
) {
    val message: String
        get() = if (promptExceedsWindow) {
            "This message alone is larger than the model can read " +
                "(counted with $encodingLabel, not the provider's own count)."
        } else {
            "The model can read about $readablePercent% of this chat's context " +
                "(counted with $encodingLabel, not the provider's own count)."
        }
}

fun chatContextWarning(budget: AttachmentTokenBudget): ChatContextWarning? {
    val promptExceedsWindow =
        budget.promptTokens.toLong() + budget.reservedOutputTokens > budget.contextTokens
    if (budget.fitsCompletely && !promptExceedsWindow) return null
    return ChatContextWarning(
        readablePercent = if (promptExceedsWindow) 0 else budget.readablePercent,
        encodingLabel = budget.encodingLabel,
        promptExceedsWindow = promptExceedsWindow,
    )
}

private fun Long.toSaturatedInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

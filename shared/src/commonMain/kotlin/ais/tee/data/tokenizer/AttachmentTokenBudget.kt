package ais.tee.data.tokenizer

/** Token size of one attachment as counted by a local [TokenCounter]. */
data class AttachmentTokens(
    val label: String,
    val tokens: Int,
) {
    override fun toString(): String = "AttachmentTokens(label=<redacted>, tokens=$tokens)"
}

/** How much of one attachment fits into the remaining context. */
data class AttachmentAllowance(
    val attachment: AttachmentTokens,
    val includedTokens: Int,
) {
    val isComplete: Boolean get() = includedTokens >= attachment.tokens
    val isExcluded: Boolean get() = includedTokens == 0 && attachment.tokens > 0
}

/**
 * Pre-send budget for attachments. [readablePercent] is the share of attachment tokens that fits
 * after the prompt and the reserved output are subtracted from the context window; 100 when there
 * is nothing to read. [encodingLabel] names the counter so the UI can say which tokenizer the
 * numbers come from instead of presenting them as the provider's own count.
 */
data class AttachmentTokenBudget(
    val encodingLabel: String,
    val contextTokens: Int,
    val promptTokens: Int,
    val reservedOutputTokens: Int,
    val allowances: List<AttachmentAllowance>,
) {
    val availableTokens: Int get() = spaceLeft(contextTokens, promptTokens, reservedOutputTokens)
    val attachmentTokens: Long get() = allowances.sumOf { it.attachment.tokens.toLong() }
    val includedTokens: Long get() = allowances.sumOf { it.includedTokens.toLong() }
    val fitsCompletely: Boolean get() = includedTokens >= attachmentTokens
    val readablePercent: Int
        get() = if (attachmentTokens == 0L) 100 else ((includedTokens * 100) / attachmentTokens).toInt()
}

/**
 * Fills the space left after the prompt and output reserve with attachments in the given order.
 * The first attachment that does not fit is cut to the remaining space; later ones are excluded.
 * Negative inputs are treated as zero.
 */
fun planAttachmentTokenBudget(
    encodingLabel: String,
    contextTokens: Int,
    promptTokens: Int,
    reservedOutputTokens: Int,
    attachments: List<AttachmentTokens>,
): AttachmentTokenBudget {
    val context = contextTokens.coerceAtLeast(0)
    val prompt = promptTokens.coerceAtLeast(0)
    val reserve = reservedOutputTokens.coerceAtLeast(0)
    var remaining = spaceLeft(context, prompt, reserve)
    val allowances = attachments.map { raw ->
        val attachment = raw.copy(tokens = raw.tokens.coerceAtLeast(0))
        val included = minOf(attachment.tokens, remaining)
        remaining -= included
        AttachmentAllowance(attachment, included)
    }
    return AttachmentTokenBudget(
        encodingLabel = encodingLabel,
        contextTokens = context,
        promptTokens = prompt,
        reservedOutputTokens = reserve,
        allowances = allowances,
    )
}

private fun spaceLeft(context: Int, prompt: Int, reserve: Int): Int =
    (context.toLong() - prompt - reserve).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

package ais.tee.data.model

private const val DEFAULT_HISTORY_CHARACTER_BUDGET = 96_000
private const val DEFAULT_HISTORY_TURN_LIMIT = 24
private const val MESSAGE_OVERHEAD_CHARACTERS = 32

const val CHAT_ROLE_USER = "user"
const val CHAT_ROLE_ASSISTANT = "assistant"

data class ProviderTextTurn(
    val role: String,
    val text: String,
    val providerReplayState: String? = null,
    val modelName: String? = null
) {
    /** Provider replay payloads and conversation text are sensitive debug content. */
    override fun toString(): String {
        val safeRole = when (role) {
            CHAT_ROLE_USER -> CHAT_ROLE_USER
            CHAT_ROLE_ASSISTANT -> CHAT_ROLE_ASSISTANT
            else -> "other"
        }
        return "ProviderTextTurn(role=$safeRole, text=<redacted>, " +
            "providerReplayState=<redacted>, modelName=<redacted>)"
    }
}

fun ModelChatMessage.isCompletedAssistantResponse(): Boolean =
    sender == CHAT_ROLE_ASSISTANT &&
        provider != null &&
        provider != AiProvider.ALL &&
        !isPartial &&
        !isError &&
        text.isNotBlank()

private fun ModelChatMessage.isReplayableAssistantFor(provider: AiProvider): Boolean =
    sender == CHAT_ROLE_ASSISTANT &&
        (this.provider == provider || (isImported && this.provider == null)) &&
        !isError &&
        !isSimulated &&
        !isPartial

/**
 * Builds a provider-scoped conversation suffix for stateless chat APIs.
 *
 * Real assistant responses from the selected provider are replayed. Explicitly imported assistant
 * turns with no provider identity are portable text context and may replay to the provider the user
 * chooses next. Ordinary provider-scoped user prompts still replay only when that provider produced
 * a real response to the same turn, so switching providers does not disclose unrelated prior prompts. The newest complete user/assistant turns are
 * retained within conservative transport limits so a long chat does not
 * grow without bound and eventually make every subsequent request fail. The current prompt is
 * always appended and is never duplicated when the caller already inserted it into history.
 */
fun buildBoundedProviderTextTurns(
    prompt: String,
    conversationHistory: List<ModelChatMessage>,
    provider: AiProvider,
    systemInstruction: String? = null,
    replayStateModelName: String? = null,
    replayStateValidator: ((String) -> Boolean)? = null,
    maxHistoryCharacters: Int = DEFAULT_HISTORY_CHARACTER_BUDGET,
    maxHistoryTurns: Int = DEFAULT_HISTORY_TURN_LIMIT
): List<ProviderTextTurn> {
    val priorMessages = if (
        conversationHistory.lastOrNull()?.let { it.sender == CHAT_ROLE_USER && it.text == prompt } == true
    ) {
        conversationHistory.dropLast(1)
    } else {
        conversationHistory
    }

    val segments = mutableListOf<MutableList<ProviderTextTurn>>()
    priorMessages.forEach { message ->
        when {
            message.sender == CHAT_ROLE_USER -> {
                segments += mutableListOf(ProviderTextTurn(CHAT_ROLE_USER, message.text))
            }
            message.isReplayableAssistantFor(provider) && segments.isNotEmpty() -> {
                segments.last() += ProviderTextTurn(
                    CHAT_ROLE_ASSISTANT,
                    message.text,
                    message.providerReplayState
                        .takeIf { replayStateModelName == null || message.modelName == replayStateModelName }
                        ?.takeIf { state -> replayStateValidator?.invoke(state) != false },
                    message.modelName
                )
            }
        }
    }

    val reservedCharacters = prompt.length + systemInstruction.orEmpty().length
    var remainingCharacters = (maxHistoryCharacters - reservedCharacters).coerceAtLeast(0)
    var remainingTurns = maxHistoryTurns.coerceAtLeast(0)
    val completeSegments = segments.filter { segment ->
        segment.any { it.role == CHAT_ROLE_ASSISTANT }
    }
    val retainedSegments = mutableListOf<List<ProviderTextTurn>>()

    for (segment in completeSegments.asReversed()) {
        if (remainingTurns == 0) break
        val segmentCost = segment.sumOf {
            maxOf(it.text.length, it.providerReplayState?.length ?: 0) + MESSAGE_OVERHEAD_CHARACTERS
        }
        if (segmentCost > remainingCharacters) break
        retainedSegments.add(0, segment)
        remainingCharacters -= segmentCost
        remainingTurns--
    }

    return buildList {
        retainedSegments.forEach { addAll(it) }
        add(ProviderTextTurn(CHAT_ROLE_USER, prompt))
    }
}

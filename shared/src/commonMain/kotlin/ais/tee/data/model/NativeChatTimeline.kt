package ais.tee.data.model

private const val TIMELINE_PREVIEW_CHARS = 80

/** One jump target on a native chat's timeline rail. */
data class NativeChatTimelineMarker(
    /** Index into the conversation's messages, which is also the chat list item index. */
    val messageIndex: Int,
    val messageId: String,
    /** 1-based number of the user turn this marker belongs to. */
    val turnNumber: Int,
    val isUserTurn: Boolean,
    val isStarred: Boolean,
    val preview: String,
) {
    override fun toString(): String =
        "NativeChatTimelineMarker(messageIndex=$messageIndex, turnNumber=$turnNumber, " +
            "isUserTurn=$isUserTurn, isStarred=$isStarred, preview=<redacted>)"
}

/**
 * Timeline markers for a locally owned native chat: every user turn, plus starred replies. Derived
 * from the archive only; nothing is fetched or scraped. Long chats are thinned to [maxMarkers],
 * keeping starred messages first and spreading the remaining user turns evenly.
 */
fun nativeChatTimeline(
    messages: List<ModelChatMessage>,
    starredMessageIds: Collection<String>,
    maxMarkers: Int = 32,
): List<NativeChatTimelineMarker> {
    if (maxMarkers <= 0) return emptyList()
    val starred = starredMessageIds.toSet()
    var turn = 0
    val all = buildList {
        messages.forEachIndexed { index, message ->
            val isUser = message.sender == CHAT_ROLE_USER
            if (isUser) turn++
            if (message.id == NATIVE_CHAT_WELCOME_MESSAGE_ID) return@forEachIndexed
            val isStarred = message.id in starred
            if (!isUser && !isStarred) return@forEachIndexed
            add(
                NativeChatTimelineMarker(
                    messageIndex = index,
                    messageId = message.id,
                    turnNumber = turn.coerceAtLeast(1),
                    isUserTurn = isUser,
                    isStarred = isStarred,
                    preview = message.text.trim().replace(Regex("\\s+"), " ").take(TIMELINE_PREVIEW_CHARS),
                )
            )
        }
    }
    if (all.size <= maxMarkers) return all
    val (starredMarkers, others) = all.partition { it.isStarred }
    val keptStarred = starredMarkers.take(maxMarkers)
    val slots = maxMarkers - keptStarred.size
    val sampled = if (slots <= 0 || others.isEmpty()) {
        emptyList()
    } else if (slots == 1) {
        listOf(others.first())
    } else {
        (0 until slots).map { slot -> others[slot * (others.size - 1) / (slots - 1)] }.distinct()
    }
    return (keptStarred + sampled).sortedBy { it.messageIndex }
}

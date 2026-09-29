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
 * always keeping both ends, then starred messages, then spreading the remaining user turns evenly.
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
                    preview = timelinePreview(message.text),
                )
            )
        }
    }
    return thinNativeChatTimeline(all, maxMarkers)
}

/**
 * Thins [all] (sorted by message index) to at most [maxMarkers]: both ends of the conversation
 * first, then starred messages, then the remaining user turns spread evenly.
 */
fun thinNativeChatTimeline(
    all: List<NativeChatTimelineMarker>,
    maxMarkers: Int,
): List<NativeChatTimelineMarker> {
    if (maxMarkers <= 0) return emptyList()
    if (all.size <= maxMarkers) return all
    // Both ends of the conversation are reserved first, then stars, then evenly spread turns.
    val endpoints = listOf(all.first(), all.last()).distinct().take(maxMarkers)
    val endpointIndexes = endpoints.map { it.messageIndex }.toSet()
    val budget = maxMarkers - endpoints.size
    val keptStarred = all.filter { it.isStarred && it.messageIndex !in endpointIndexes }.take(budget)
    val slots = budget - keptStarred.size
    val others = all.filter { !it.isStarred && it.messageIndex !in endpointIndexes }
    val sampled = if (slots <= 0 || others.isEmpty()) {
        emptyList()
    } else {
        (0 until slots).map { slot -> others[(2 * slot + 1) * others.size / (2 * slots)] }.distinct()
    }
    return (endpoints + keptStarred + sampled).sortedBy { it.messageIndex }
}

/** Normalizes only a bounded prefix, so huge prompts do not cost a full-text regex pass. */
private fun timelinePreview(text: String): String =
    text.trimStart().take(TIMELINE_PREVIEW_CHARS * 4).trim()
        .replace(Regex("\\s+"), " ").take(TIMELINE_PREVIEW_CHARS)

/**
 * Pixel offsets for timeline markers of size [minGap] on a rail [extent] long. Each marker stays as
 * close to its [ideal] offset (ascending) as possible while no two markers overlap, so every jump
 * target keeps a full-size tap area. Callers bound the marker count to `extent / minGap`.
 */
fun spreadTimelineOffsets(ideal: List<Float>, minGap: Float, extent: Float): List<Float> {
    if (ideal.isEmpty()) return emptyList()
    val maxOffset = (extent - minGap).coerceAtLeast(0f)
    val offsets = ideal.map { it.coerceIn(0f, maxOffset) }.toMutableList()
    for (i in 1 until offsets.size) {
        offsets[i] = maxOf(offsets[i], offsets[i - 1] + minGap)
    }
    offsets[offsets.lastIndex] = minOf(offsets.last(), maxOffset)
    for (i in offsets.lastIndex - 1 downTo 0) {
        offsets[i] = minOf(offsets[i], offsets[i + 1] - minGap)
    }
    return offsets.map { it.coerceAtLeast(0f) }
}

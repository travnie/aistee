package ais.tee.data.model

/** Applies a foreground snapshot's changes without overwriting background turns. */
fun mergeNativeChatChanges(
    base: NativeChatArchive,
    incoming: NativeChatArchive,
    current: NativeChatArchive,
): NativeChatArchive {
    val before = base.conversations.associateBy { it.id }
    val live = current.conversations.associateBy { it.id }
    val incomingIds = incoming.conversations.mapTo(mutableSetOf()) { it.id }
    val conversations = incoming.conversations.mapNotNull { edited ->
        val original = before[edited.id] ?: return@mapNotNull edited
        val latest = live[edited.id] ?: return@mapNotNull null
        if (edited == original) return@mapNotNull latest
        latest.copy(
            title = if (edited.title != original.title) edited.title else latest.title,
            draft = if (edited.draft != original.draft) edited.draft else latest.draft,
            selectedProvider = if (edited.selectedProvider != original.selectedProvider) edited.selectedProvider else latest.selectedProvider,
            selectedModel = if (edited.selectedModel != original.selectedModel) edited.selectedModel else latest.selectedModel,
            includeSystemProfile = if (edited.includeSystemProfile != original.includeSystemProfile) edited.includeSystemProfile else latest.includeSystemProfile,
            updatedAtEpochMs = maxOf(edited.updatedAtEpochMs, latest.updatedAtEpochMs),
            replyEpoch = maxOf(edited.replyEpoch, latest.replyEpoch),
            messages = when {
                edited.replyEpoch != original.replyEpoch -> edited.messages
                latest.replyEpoch != original.replyEpoch -> latest.messages
                else -> mergeMessages(original.messages, edited.messages, latest.messages)
            },
        )
    } + current.conversations.filter { it.id !in before && it.id !in incomingIds }
    return current.copy(
        activeConversationId = if (incoming.activeConversationId != base.activeConversationId) {
            incoming.activeConversationId
        } else current.activeConversationId,
        conversations = conversations,
    ).normalized() ?: current
}

private fun mergeMessages(
    base: List<ModelChatMessage>,
    incoming: List<ModelChatMessage>,
    current: List<ModelChatMessage>,
): List<ModelChatMessage> {
    // Clearing a conversation removes background turns too, including ones not yet
    // visible in the foreground. A later worker merge then rejects the missing user.
    if (base.any { it.id != NATIVE_CHAT_WELCOME_MESSAGE_ID } &&
        incoming.all { it.id == NATIVE_CHAT_WELCOME_MESSAGE_ID }
    ) return incoming
    val before = base.associateBy { it.id }
    val live = current.associateBy { it.id }
    val merged = incoming.mapNotNull { edited ->
        val original = before[edited.id] ?: return@mapNotNull edited
        val latest = live[edited.id] ?: return@mapNotNull null
        if (edited == original) latest else edited
    }.toMutableList()
    val retainedIds = merged.mapTo(mutableSetOf()) { it.id }
    var precedingIndex = -1
    current.forEach { message ->
        val existingIndex = merged.indexOfFirst { it.id == message.id }
        if (existingIndex >= 0) {
            precedingIndex = existingIndex
        } else if (message.id !in before && retainedIds.add(message.id)) {
            merged.add(++precedingIndex, message)
        }
    }
    return merged
}

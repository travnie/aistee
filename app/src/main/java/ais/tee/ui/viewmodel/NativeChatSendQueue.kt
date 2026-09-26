package ais.tee.ui.viewmodel

import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NativeChatConversation
import ais.tee.data.model.nativeConversationTitle
import ais.tee.notifications.MAX_REPLY_TEXT_CHARS
import ais.tee.notifications.nativeChatReplyUserMessageId

/**
 * Queue only when offline and the conversation can be answered in the background (native/API
 * provider with a key, same rule as Direct Reply). Account-backed Web providers never use this path.
 */
internal fun shouldQueueNativeChatSend(isOnline: Boolean, canSendInBackground: Boolean, text: String): Boolean =
    !isOnline && canSendInBackground && text.isNotBlank() && text.trim().length <= MAX_REPLY_TEXT_CHARS

/** Appends the queued user turn; the background job later adds the answers after it. */
internal fun NativeChatConversation.withQueuedMessage(replyId: String, text: String, now: Long): NativeChatConversation {
    val trimmed = text.trim()
    val firstUserTurn = messages.none { it.sender == CHAT_ROLE_USER }
    return copy(
        title = if (firstUserTurn) nativeConversationTitle(trimmed) else title,
        updatedAtEpochMs = now,
        messages = messages + ModelChatMessage(
            id = nativeChatReplyUserMessageId(replyId),
            sender = CHAT_ROLE_USER,
            text = trimmed,
            timestamp = now,
            isQueued = true,
        ),
        draft = if (draft.trim() == trimmed) "" else draft,
    )
}

/** Removes a still-queued turn (cancel or edit); returns null when it is no longer queued. */
internal fun NativeChatConversation.withoutQueuedMessage(messageId: String): Pair<NativeChatConversation, ModelChatMessage>? {
    val message = messages.firstOrNull { it.id == messageId && it.isQueued } ?: return null
    return copy(messages = messages.filterNot { it.id == messageId }) to message
}

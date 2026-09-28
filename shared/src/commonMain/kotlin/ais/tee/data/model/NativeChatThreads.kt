package ais.tee.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val NATIVE_CHAT_ARCHIVE_VERSION = 1
const val DEFAULT_NATIVE_CONVERSATION_TITLE = "New conversation"
const val NATIVE_CHAT_WELCOME_MESSAGE_ID = "welcome_assistant_intro"
private const val MAX_NATIVE_CONVERSATION_TITLE_CHARS = 56

@Serializable
data class NativeChatConversation(
    val id: String,
    val title: String = DEFAULT_NATIVE_CONVERSATION_TITLE,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long = createdAtEpochMs,
    val messages: List<ModelChatMessage> = emptyList(),
    val draft: String = "",
    val selectedProvider: AiProvider = AiProvider.ALL,
    val selectedModel: String = "all",
    val apiProcessingMode: ApiProcessingMode = ApiProcessingMode.AUTO,
    val includeSystemProfile: Boolean = true,
    val projectId: String = DEFAULT_PROJECT_ID,
    val replyEpoch: Long = 0L,
    /** Set on a branch made from another local conversation; the source is never modified. */
    val forkedFrom: NativeChatForkOrigin? = null,
) {
    override fun toString(): String =
        "NativeChatConversation(id=<redacted>, title=<redacted>, messages=${messages.size}, " +
            "selectedProvider=${selectedProvider.id}, selectedModel=<redacted>, projectId=<redacted>, " +
            "forked=${forkedFrom != null})"
}

/** Where a branch came from: its first [inheritedMessageCount] messages are copies from the source. */
@Serializable
data class NativeChatForkOrigin(
    val sourceConversationId: String,
    val sourceMessageId: String,
    val inheritedMessageCount: Int,
    val forkedAtEpochMs: Long,
) {
    override fun toString(): String =
        "NativeChatForkOrigin(sourceConversationId=<redacted>, sourceMessageId=<redacted>, " +
            "inheritedMessageCount=$inheritedMessageCount)"
}

@Serializable
data class NativeChatArchive(
    val version: Int = NATIVE_CHAT_ARCHIVE_VERSION,
    val activeConversationId: String = "",
    val conversations: List<NativeChatConversation> = emptyList()
) {
    val activeConversation: NativeChatConversation?
        get() {
            if (conversations.isEmpty()) return null
            return conversations.firstOrNull { it.id == activeConversationId }
                ?: conversations.maxBy { it.updatedAtEpochMs }
        }

    override fun toString(): String =
        "NativeChatArchive(version=$version, activeConversationId=<redacted>, " +
            "conversations=${conversations.size})"
}

fun nativeConversationTitle(prompt: String): String {
    val normalized = prompt.trim().replace(Regex("\\s+"), " ")
    if (normalized.isEmpty()) return DEFAULT_NATIVE_CONVERSATION_TITLE
    if (normalized.length <= MAX_NATIVE_CONVERSATION_TITLE_CHARS) return normalized
    return normalized.take(MAX_NATIVE_CONVERSATION_TITLE_CHARS - 1).trimEnd() + "…"
}

/** Only a finished real provider reply can start a branch; simulated replies are never replayed. */
fun ModelChatMessage.canStartNativeChatFork(): Boolean =
    id != NATIVE_CHAT_WELCOME_MESSAGE_ID && !isSimulated && isCompletedAssistantResponse()

fun NativeChatConversation.canForkAt(messageId: String): Boolean =
    messages.any { it.id == messageId && it.canStartNativeChatFork() }

/**
 * Branches this conversation at a completed assistant reply. The branch keeps every earlier turn,
 * the reply's own prompt and that reply only (compare-mode siblings from the same turn are
 * dropped), and continues with the reply's provider. Copied messages get fresh ids so the two
 * conversations never share message identity; the welcome message keeps its well-known id.
 * History replay stays provider-scoped: whichever provider the branch uses replays only the
 * inherited turns that provider itself answered, so branching discloses nothing new.
 */
fun NativeChatConversation.forkAt(
    messageId: String,
    newConversationId: String,
    nowEpochMs: Long,
    newMessageId: () -> String,
): NativeChatConversation? {
    if (!canForkAt(messageId)) return null
    val targetIndex = messages.indexOfFirst { it.id == messageId }
    val target = messages[targetIndex]
    val provider = target.provider ?: return null
    val promptIndex = messages.subList(0, targetIndex).indexOfLast { it.sender == CHAT_ROLE_USER }
    val inherited = buildList {
        if (promptIndex >= 0) addAll(messages.subList(0, promptIndex + 1))
        add(target)
    }.filterNot { it.isQueued }
    val model = when {
        selectedProvider == provider && selectedModel.isNotBlank() -> selectedModel
        target.modelName in provider.availableModels -> target.modelName.orEmpty()
        else -> provider.defaultModel
    }
    return copy(
        id = newConversationId,
        title = nativeConversationTitle("Branch: ${title.removePrefix("Branch: ")}"),
        createdAtEpochMs = nowEpochMs,
        updatedAtEpochMs = nowEpochMs,
        messages = inherited.map { message ->
            if (message.id == NATIVE_CHAT_WELCOME_MESSAGE_ID) message else message.copy(id = newMessageId())
        },
        draft = "",
        selectedProvider = provider,
        selectedModel = model,
        apiProcessingMode = provider.normalizeApiProcessingMode(apiProcessingMode),
        replyEpoch = 0L,
        forkedFrom = NativeChatForkOrigin(
            sourceConversationId = id,
            sourceMessageId = messageId,
            inheritedMessageCount = inherited.size,
            forkedAtEpochMs = nowEpochMs,
        ),
    )
}

/**
 * This conversation's messages minus copies it inherited from a source that is also in
 * [conversations], so a feed across several chats shows each turn once. Copies are matched by
 * content rather than [NativeChatForkOrigin.inheritedMessageCount], which a clear or edit can
 * outdate. A branch shown on its own keeps its inherited turns.
 */
fun NativeChatConversation.messagesWithoutInheritedCopies(
    conversations: List<NativeChatConversation>,
): List<ModelChatMessage> {
    val sourceId = forkedFrom?.sourceConversationId ?: return messages
    val source = conversations.firstOrNull { it.id == sourceId && it.id != id } ?: return messages
    val sourceKeys = source.messages.mapTo(HashSet()) { Triple(it.sender, it.timestamp, it.text) }
    return messages.filterNot { Triple(it.sender, it.timestamp, it.text) in sourceKeys }
}

fun NativeChatArchive.normalized(): NativeChatArchive? {
    if (version != NATIVE_CHAT_ARCHIVE_VERSION) return null
    val seenIds = mutableSetOf<String>()
    val retained = conversations.mapNotNull { conversation ->
        val id = conversation.id.trim()
        if (id.isEmpty() || !seenIds.add(id)) return@mapNotNull null
        val provider = conversation.selectedProvider
        conversation.copy(
            id = id,
            title = conversation.title.trim().ifEmpty { DEFAULT_NATIVE_CONVERSATION_TITLE },
            selectedModel = when {
                provider == AiProvider.ALL -> "all"
                conversation.selectedModel.isNotBlank() -> conversation.selectedModel
                else -> provider.defaultModel
            },
            apiProcessingMode = provider.normalizeApiProcessingMode(conversation.apiProcessingMode),
            projectId = conversation.projectId.trim().ifEmpty { DEFAULT_PROJECT_ID }
        )
    }
    if (retained.isEmpty()) return copy(activeConversationId = "", conversations = emptyList())
    val activeId = activeConversationId.takeIf { candidate ->
        retained.any { it.id == candidate }
    } ?: retained.maxByOrNull { it.updatedAtEpochMs }?.id.orEmpty()
    return copy(activeConversationId = activeId, conversations = retained)
}

fun NativeChatArchive.updateActiveConversation(
    transform: (NativeChatConversation) -> NativeChatConversation
): NativeChatArchive {
    val active = activeConversation ?: return this
    val updated = transform(active)
    return copy(
        activeConversationId = updated.id,
        conversations = conversations.map { conversation ->
            if (conversation.id == active.id) updated else conversation
        }
    )
}

object NativeChatArchiveCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(archive: NativeChatArchive): ByteArray =
        json.encodeToString(NativeChatArchive.serializer(), archive).encodeToByteArray()

    fun decode(bytes: ByteArray): NativeChatArchive? = runCatching {
        json.decodeFromString(
            NativeChatArchive.serializer(),
            bytes.decodeToString(throwOnInvalidSequence = true)
        ).normalized()
    }.getOrNull()
}

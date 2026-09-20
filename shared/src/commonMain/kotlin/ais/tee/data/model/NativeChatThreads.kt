package ais.tee.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val NATIVE_CHAT_ARCHIVE_VERSION = 1
const val DEFAULT_NATIVE_CONVERSATION_TITLE = "New conversation"
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
    val includeSystemProfile: Boolean = true
) {
    override fun toString(): String =
        "NativeChatConversation(id=<redacted>, title=<redacted>, messages=${messages.size}, " +
            "selectedProvider=${selectedProvider.id}, selectedModel=<redacted>)"
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
            }
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

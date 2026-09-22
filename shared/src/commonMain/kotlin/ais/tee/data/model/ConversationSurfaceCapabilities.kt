package ais.tee.data.model

/**
 * User-visible conversation features that depend on what Aistee actually owns and can safely act on.
 *
 * This registry describes shipped Aistee behavior, not everything a remote provider might support
 * on its own website. UI should only promise capabilities present here.
 */
enum class ConversationSurfaceCapability {
    MESSAGE_HISTORY,
    COMPLETION_NOTIFICATION,
    DIRECT_REPLY,
    DRAFT_REPLY,
    DEEP_LINK
}

class ConversationSurfaceCapabilities internal constructor(
    supported: Set<ConversationSurfaceCapability>
) {
    private val supported = supported.toSet()

    fun supports(capability: ConversationSurfaceCapability): Boolean = capability in supported
}

private val NATIVE_CONVERSATION_CAPABILITIES = ConversationSurfaceCapabilities(
    supported = setOf(
        ConversationSurfaceCapability.MESSAGE_HISTORY,
        ConversationSurfaceCapability.COMPLETION_NOTIFICATION,
        ConversationSurfaceCapability.DIRECT_REPLY,
        ConversationSurfaceCapability.DEEP_LINK
    )
)

private val ACCOUNT_WEB_CONVERSATION_CAPABILITIES = ConversationSurfaceCapabilities(
    supported = setOf(ConversationSurfaceCapability.DEEP_LINK)
)

/**
 * Native/API conversations are locally owned by Aistee. Runtime prerequisites such as a configured
 * provider key are checked separately; this function describes the transport feature boundary.
 */
fun AiProvider.conversationSurfaceCapabilities(): ConversationSurfaceCapabilities =
    NATIVE_CONVERSATION_CAPABILITIES

/**
 * Account-backed WebViews keep provider-owned history and sessions. Aistee can reopen the provider
 * surface, but it must not promise history, background send, Direct Reply or staged draft delivery
 * until those bridges are explicitly implemented and verified.
 */
fun WebAiService.conversationSurfaceCapabilities(): ConversationSurfaceCapabilities =
    ACCOUNT_WEB_CONVERSATION_CAPABILITIES

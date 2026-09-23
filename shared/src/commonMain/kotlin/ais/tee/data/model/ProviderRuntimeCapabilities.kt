package ais.tee.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class NativeChatTransport {
    COMPARE_FAN_OUT,
    GEMINI_GENERATE_CONTENT,
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES,
    OPENAI_COMPATIBLE_CHAT_COMPLETIONS
}

enum class SystemInstructionPlacement {
    PER_PROVIDER,
    NATIVE_FIELD,
    SYSTEM_MESSAGE
}

enum class ConversationStateStrategy {
    PROVIDER_FAN_OUT,
    BOUNDED_PROVIDER_TEXT_REPLAY,
    BOUNDED_PROVIDER_CONTENT_REPLAY
}

enum class ReasoningControlStrategy {
    PER_PROVIDER,
    PROVIDER_DEFAULT,
    MODEL_CAPABILITY_METADATA
}

enum class ClientToolCallingStrategy {
    PER_PROVIDER,
    NONE,
    GEMINI_FUNCTIONS,
    OPENAI_RESPONSES_FUNCTIONS,
    ANTHROPIC_CLIENT_TOOLS,
}

fun AiProvider.reasoningControlStrategy(): ReasoningControlStrategy = when (this) {
    AiProvider.ALL -> ReasoningControlStrategy.PER_PROVIDER
    AiProvider.CLAUDE -> ReasoningControlStrategy.MODEL_CAPABILITY_METADATA
    else -> ReasoningControlStrategy.PROVIDER_DEFAULT
}

private const val CLAUDE_CAPABILITIES_KEY = "capabilities"
private const val CLAUDE_THINKING_KEY = "thinking"
private const val CLAUDE_TYPES_KEY = "types"
private const val CLAUDE_SUPPORTED_KEY = "supported"
private const val CLAUDE_ADAPTIVE_KEY = "adaptive"
private const val CLAUDE_ENABLED_KEY = "enabled"
private const val CLAUDE_EFFORT_KEY = "effort"
private const val CLAUDE_HIGH_EFFORT_KEY = "high"
private const val CLAUDE_MIN_THINKING_BUDGET = 1024
private const val CLAUDE_DEFAULT_THINKING_BUDGET = 4096

data class ClaudeReasoningCapabilities(
    val supportsAdaptive: Boolean = false,
    val supportsEnabled: Boolean = false,
    val supportsHighEffort: Boolean = false
)

fun parseClaudeReasoningCapabilities(rawJson: String): ClaudeReasoningCapabilities? = runCatching {
    val root = Json.parseToJsonElement(rawJson).jsonObject
    val capabilities = root[CLAUDE_CAPABILITIES_KEY] as? JsonObject ?: return@runCatching null
    val thinking = capabilities[CLAUDE_THINKING_KEY] as? JsonObject ?: return@runCatching null
    if (thinking[CLAUDE_SUPPORTED_KEY]?.jsonPrimitive?.booleanOrNull != true) {
        return@runCatching ClaudeReasoningCapabilities()
    }
    val types = thinking[CLAUDE_TYPES_KEY] as? JsonObject
    val effort = capabilities[CLAUDE_EFFORT_KEY] as? JsonObject
    ClaudeReasoningCapabilities(
        supportsAdaptive = types.supportsClaudeCapability(CLAUDE_ADAPTIVE_KEY),
        supportsEnabled = types.supportsClaudeCapability(CLAUDE_ENABLED_KEY),
        supportsHighEffort = effort.supportsClaudeCapability(CLAUDE_HIGH_EFFORT_KEY)
    )
}.getOrNull()

private fun JsonObject?.supportsClaudeCapability(name: String): Boolean =
    ((this?.get(name) as? JsonObject)?.get(CLAUDE_SUPPORTED_KEY) as? JsonPrimitive)
        ?.booleanOrNull == true

fun resolveClaudeThinkingBudget(maxTokens: Int): Int? {
    val budget = minOf(CLAUDE_DEFAULT_THINKING_BUDGET, maxTokens / 2)
    return budget.takeIf { it >= CLAUDE_MIN_THINKING_BUDGET && it < maxTokens }
}

data class ProviderRuntimeCapabilities(
    val transport: NativeChatTransport,
    val systemInstructionPlacement: SystemInstructionPlacement,
    val conversationStateStrategy: ConversationStateStrategy,
    val clientToolCallingStrategy: ClientToolCallingStrategy,
    val streamsText: Boolean,
    val reportsResolvedModel: Boolean
)

fun AiProvider.runtimeCapabilities(): ProviderRuntimeCapabilities = when (this) {
    AiProvider.ALL -> ProviderRuntimeCapabilities(
        transport = NativeChatTransport.COMPARE_FAN_OUT,
        systemInstructionPlacement = SystemInstructionPlacement.PER_PROVIDER,
        conversationStateStrategy = ConversationStateStrategy.PROVIDER_FAN_OUT,
        clientToolCallingStrategy = ClientToolCallingStrategy.PER_PROVIDER,
        streamsText = true,
        reportsResolvedModel = false
    )
    AiProvider.GEMINI -> ProviderRuntimeCapabilities(
        transport = NativeChatTransport.GEMINI_GENERATE_CONTENT,
        systemInstructionPlacement = SystemInstructionPlacement.NATIVE_FIELD,
        conversationStateStrategy = ConversationStateStrategy.BOUNDED_PROVIDER_CONTENT_REPLAY,
        clientToolCallingStrategy = ClientToolCallingStrategy.GEMINI_FUNCTIONS,
        streamsText = true,
        reportsResolvedModel = false
    )
    AiProvider.CHATGPT -> ProviderRuntimeCapabilities(
        transport = NativeChatTransport.OPENAI_RESPONSES,
        systemInstructionPlacement = SystemInstructionPlacement.NATIVE_FIELD,
        conversationStateStrategy = ConversationStateStrategy.BOUNDED_PROVIDER_CONTENT_REPLAY,
        clientToolCallingStrategy = ClientToolCallingStrategy.OPENAI_RESPONSES_FUNCTIONS,
        streamsText = true,
        reportsResolvedModel = false
    )
    AiProvider.CLAUDE -> ProviderRuntimeCapabilities(
        transport = NativeChatTransport.ANTHROPIC_MESSAGES,
        systemInstructionPlacement = SystemInstructionPlacement.NATIVE_FIELD,
        conversationStateStrategy = ConversationStateStrategy.BOUNDED_PROVIDER_CONTENT_REPLAY,
        clientToolCallingStrategy = ClientToolCallingStrategy.ANTHROPIC_CLIENT_TOOLS,
        streamsText = true,
        reportsResolvedModel = false
    )
    AiProvider.DEEPSEEK,
    AiProvider.KIMI,
    AiProvider.OPENROUTER,
    AiProvider.AIHUBMIX,
    AiProvider.VERCEL -> ProviderRuntimeCapabilities(
        transport = NativeChatTransport.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
        systemInstructionPlacement = SystemInstructionPlacement.SYSTEM_MESSAGE,
        conversationStateStrategy = ConversationStateStrategy.BOUNDED_PROVIDER_TEXT_REPLAY,
        clientToolCallingStrategy = ClientToolCallingStrategy.NONE,
        streamsText = true,
        reportsResolvedModel = true
    )
}

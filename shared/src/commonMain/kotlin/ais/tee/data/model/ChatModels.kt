package ais.tee.data.model

import kotlin.time.Clock

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class WebAiService(
    val id: String,
    val displayName: String,
    val shortName: String,
    val url: String,
    val description: String,
    val brandHexColor: Long
) {
    CLAUDE(
        id = "claude",
        displayName = "Claude.ai",
        shortName = "Claude",
        url = "https://claude.ai",
        description = "Anthropic Claude account chat with Projects & Artifacts",
        brandHexColor = 0xFFD97706 // Warm Amber
    ),
    CHATGPT(
        id = "chatgpt",
        displayName = "ChatGPT",
        shortName = "ChatGPT",
        url = "https://chatgpt.com",
        description = "OpenAI ChatGPT account chat with tools and projects",
        brandHexColor = 0xFF10B981 // Emerald Green
    ),
    GEMINI(
        id = "gemini",
        displayName = "Gemini Chat",
        shortName = "Gemini",
        url = "https://gemini.google.com",
        description = "Google Gemini account chat with multimodal and Workspace tools",
        brandHexColor = 0xFF0EA5E9 // Sky Blue
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek Chat",
        shortName = "DeepSeek",
        url = "https://chat.deepseek.com",
        description = "DeepSeek web chat with reasoning, coding, and long context",
        brandHexColor = 0xFF2563EB // Deep Blue
    ),
    KIMI(
        id = "kimi",
        displayName = "Kimi AI",
        shortName = "Kimi",
        url = "https://www.kimi.com",
        description = "Moonshot AI Kimi chat with search, files, agents, and long context",
        brandHexColor = 0xFF8B5CF6 // Violet
    ),
    VIBE(
        id = "vibe",
        displayName = "Mistral Vibe",
        shortName = "Vibe",
        url = "https://chat.mistral.ai",
        description = "Mistral Vibe account chat, formerly Le Chat, with work and agent tools",
        brandHexColor = 0xFFFF7000 // Mistral Orange
    ),
    QWEN(
        id = "qwen",
        displayName = "Qwen",
        shortName = "Qwen",
        url = "https://chat.qwen.ai",
        description = "Alibaba Qwen account chat with multimodal, search, and agent capabilities",
        brandHexColor = 0xFF6366F1 // Indigo
    ),
    COPILOT(
        id = "copilot",
        displayName = "Microsoft Copilot",
        shortName = "Copilot",
        url = "https://copilot.microsoft.com",
        description = "Microsoft Copilot account chat with web-grounded assistance and Microsoft services",
        brandHexColor = 0xFF2563EB // Microsoft blue
    ),
    ZAI(
        id = "zai",
        displayName = "Z.ai",
        shortName = "Z.ai",
        url = "https://chat.z.ai",
        description = "Z.ai account chat powered by GLM models with chat and agent workflows",
        brandHexColor = 0xFF7C3AED // Violet
    ),
    GROK(
        id = "grok",
        displayName = "Grok",
        shortName = "Grok",
        url = "https://grok.com",
        description = "xAI Grok account chat with search, reasoning, files, and multimodal tools",
        brandHexColor = 0xFF64748B // Slate
    ),
    CHARACTER_AI(
        id = "character-ai",
        displayName = "Character.AI",
        shortName = "Character.AI",
        url = "https://character.ai",
        description = "Character.AI account chat for character-driven conversations",
        brandHexColor = 0xFF7C3AED // Violet
    ),
    VENICE(
        id = "venice",
        displayName = "Venice AI",
        shortName = "Venice",
        url = "https://venice.ai",
        description = "Venice account chat focused on private AI and creative workflows",
        brandHexColor = 0xFF0F766E // Teal
    ),
    META_AI(
        id = "meta-ai",
        displayName = "Meta AI",
        shortName = "Meta AI",
        url = "https://www.meta.ai",
        description = "Meta AI account chat hosted at meta.ai",
        brandHexColor = 0xFF0668E1 // Meta blue
    );

    companion object {
        val primaryChats: List<WebAiService> = listOf(
            CLAUDE,
            CHATGPT,
            GEMINI,
            DEEPSEEK,
            KIMI,
            VIBE
        )

        val additionalChats: List<WebAiService> = entries.filterNot(primaryChats::contains)

        fun fromId(id: String): WebAiService {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CLAUDE
        }
    }
}

data class WebChatSections(
    val favorites: List<WebAiService>,
    val primary: List<WebAiService>,
    val additional: List<WebAiService>
) {
    val all: List<WebAiService> get() = favorites + primary + additional
}

fun webChatSections(favorites: Set<WebAiService>): WebChatSections = WebChatSections(
    favorites = WebAiService.entries.filter(favorites::contains),
    primary = WebAiService.primaryChats.filterNot(favorites::contains),
    additional = WebAiService.additionalChats.filterNot(favorites::contains)
)

@Serializable
enum class AiProvider(
    val id: String,
    val displayName: String,
    val shortName: String,
    val defaultModel: String,
    val availableModels: List<String>,
    val description: String,
    val participatesInDefaultCompare: Boolean = true
) {
    ALL(
        id = "all",
        displayName = "All Models (Compare)",
        shortName = "All Models",
        defaultModel = "all",
        availableModels = listOf("all"),
        description = "Send one prompt concurrently to every configured direct provider"
    ),
    CLAUDE(
        id = "claude",
        displayName = "Anthropic Claude",
        shortName = "Claude",
        defaultModel = "claude-sonnet-5",
        availableModels = listOf(
            "claude-sonnet-5",
            "claude-fable-5",
            "claude-opus-5",
            "claude-haiku-4-5-20251001"
        ),
        description = "Anthropic models for coding, reasoning, writing, and agentic workflows"
    ),
    CHATGPT(
        id = "chatgpt",
        displayName = "OpenAI",
        shortName = "OpenAI",
        defaultModel = "gpt-5.6",
        availableModels = listOf(
            "gpt-5.6",
            "gpt-5.5"
        ),
        description = "OpenAI GPT models for general text generation, coding, and reasoning"
    ),
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        shortName = "Gemini",
        defaultModel = "gemini-3.7-flash",
        availableModels = listOf(
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.1-pro-preview"
        ),
        description = "Google multimodal models for coding, agents, reasoning, and long context"
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek AI",
        shortName = "DeepSeek",
        defaultModel = "deepseek-v4-flash",
        availableModels = listOf(
            "deepseek-v4-flash",
            "deepseek-v4-pro"
        ),
        description = "DeepSeek V4 models with long context, reasoning, coding, and agent capabilities"
    ),
    KIMI(
        id = "kimi",
        displayName = "Moonshot Kimi AI",
        shortName = "Kimi",
        defaultModel = "kimi-k2.6",
        availableModels = listOf(
            "kimi-k2.6",
            "kimi-k3",
            "kimi-k2.7-code"
        ),
        description = "Moonshot AI models for general chat, frontier reasoning, coding, and long context"
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter Free",
        shortName = "OpenRouter",
        defaultModel = "openrouter/free",
        availableModels = listOf("openrouter/free"),
        description = "OpenRouter gateway that routes requests across currently available free models",
        participatesInDefaultCompare = false
    ),
    AIHUBMIX(
        id = "aihubmix",
        displayName = "AIHubMix Free",
        shortName = "AIHubMix",
        defaultModel = "hy3-free",
        availableModels = listOf(
            "hy3-free",
            "coding-glm-5.3-free",
            "coding-glm-5.2-free"
        ),
        description = "AIHubMix gateway for subsidized zero-cost model variants",
        participatesInDefaultCompare = false
    ),
    VERCEL(
        id = "vercel",
        displayName = "Vercel AI Gateway",
        shortName = "Vercel",
        defaultModel = "alibaba/qwen3-coder-30b-a3b",
        availableModels = listOf("alibaba/qwen3-coder-30b-a3b"),
        description = "Vercel AI Gateway with a live text-model catalog and account budget controls",
        participatesInDefaultCompare = false
    );

    companion object {
        val concreteProviders: List<AiProvider> = entries.filter {
            it != ALL && it.participatesInDefaultCompare
        }

        fun fromId(id: String): AiProvider {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CLAUDE
        }
    }
}

@Serializable
data class ApiKeyConfig(
    val geminiKey: String = "",
    val openAiKey: String = "",
    val claudeKey: String = "",
    val deepseekKey: String = "",
    val kimiKey: String = "",
    val openRouterKey: String = "",
    val aiHubMixKey: String = "",
    val vercelAiGatewayKey: String = ""
) {
    /** API credentials must never appear in logs, crash breadcrumbs or debugger stringification. */
    override fun toString(): String = "ApiKeyConfig(<redacted>)"
}

fun ApiKeyConfig.hasKeyFor(provider: AiProvider): Boolean = when (provider) {
    AiProvider.GEMINI -> geminiKey.isNotBlank()
    AiProvider.CHATGPT -> openAiKey.isNotBlank()
    AiProvider.CLAUDE -> claudeKey.isNotBlank()
    AiProvider.DEEPSEEK -> deepseekKey.isNotBlank()
    AiProvider.KIMI -> kimiKey.isNotBlank()
    AiProvider.OPENROUTER -> openRouterKey.isNotBlank()
    AiProvider.AIHUBMIX -> aiHubMixKey.isNotBlank()
    AiProvider.VERCEL -> vercelAiGatewayKey.isNotBlank()
    AiProvider.ALL -> false
}

fun ApiKeyConfig.configuredDirectProviders(): List<AiProvider> =
    AiProvider.concreteProviders.filter { hasKeyFor(it) }

data class GatewayModelCatalogEntry(
    val id: String,
    val inputPriceUsd: Double?,
    val outputPriceUsd: Double?,
    val supportsTextOutput: Boolean
)

fun AiProvider.usesLiveGatewayModelCatalog(): Boolean =
    this == AiProvider.OPENROUTER || this == AiProvider.AIHUBMIX || this == AiProvider.VERCEL

fun gatewayModelOptions(
    provider: AiProvider,
    catalog: List<GatewayModelCatalogEntry>
): List<String> {
    if (!provider.usesLiveGatewayModelCatalog()) return provider.availableModels

    val textModels = catalog.asSequence()
        .filter { it.supportsTextOutput }
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id.trim() }

    val eligibleModels = if (provider == AiProvider.VERCEL) {
        textModels.sortedWith(
            compareBy<GatewayModelCatalogEntry> { entry ->
                val input = entry.inputPriceUsd ?: Double.POSITIVE_INFINITY
                val output = entry.outputPriceUsd ?: Double.POSITIVE_INFINITY
                input + output
            }.thenBy { it.id }
        )
    } else {
        textModels.filter { entry ->
            entry.inputPriceUsd == 0.0 && entry.outputPriceUsd == 0.0
        }
    }

    return eligibleModels.map { it.id.trim() }.toList()
}

@Serializable
data class ModelChatMessage(
    val id: String,
    val sender: String, // "user" or "assistant"
    val provider: AiProvider? = null,
    val modelName: String? = null,
    val text: String,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val isError: Boolean = false,
    val isSimulated: Boolean = false,
    val isPartial: Boolean = false,
    val isImported: Boolean = false,
    val latencyMs: Long? = null,
    val usage: ProviderUsage? = null,
    val activeProfileNotes: List<String> = emptyList(),
    @Transient val providerReplayState: String? = null
) {
    /** User text, identifiers, profile notes and opaque replay state are sensitive debug payloads. */
    override fun toString(): String =
        "ModelChatMessage(id=<redacted>, sender=$sender, provider=${provider?.id ?: "none"}, " +
            "text=<redacted>, activeProfileNotes=<redacted>, providerReplayState=<redacted>, " +
            "isError=$isError, isSimulated=$isSimulated, isPartial=$isPartial, isImported=$isImported)"
}

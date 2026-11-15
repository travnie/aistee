package ais.tee.data.model

import ais.tee.data.skills.ActiveSkillCard
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.time.Clock

@Serializable
enum class WebAiService(
    val id: String,
    val displayName: String,
    val shortName: String,
    val url: String,
    val description: String,
    val brandHexColor: Long,
) {
    CLAUDE(
        id = "claude",
        displayName = "Claude.ai",
        shortName = "Claude",
        url = "https://claude.ai",
        description = "Anthropic Claude account chat with Projects & Artifacts",
        brandHexColor = 0xFFD97706, // Warm Amber
    ),
    CHATGPT(
        id = "chatgpt",
        displayName = "ChatGPT",
        shortName = "ChatGPT",
        url = "https://chatgpt.com",
        description = "OpenAI ChatGPT account chat with tools and projects",
        brandHexColor = 0xFF10B981, // Emerald Green
    ),
    GEMINI(
        id = "gemini",
        displayName = "Gemini Chat",
        shortName = "Gemini",
        url = "https://gemini.google.com",
        description = "Google Gemini account chat with multimodal and Workspace tools",
        brandHexColor = 0xFF0EA5E9, // Sky Blue
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek Chat",
        shortName = "DeepSeek",
        url = "https://chat.deepseek.com",
        description = "DeepSeek web chat with reasoning, coding, and long context",
        brandHexColor = 0xFF2563EB, // Deep Blue
    ),
    KIMI(
        id = "kimi",
        displayName = "Kimi AI",
        shortName = "Kimi",
        url = "https://www.kimi.com",
        description = "Moonshot AI Kimi chat with search, files, agents, and long context",
        brandHexColor = 0xFF8B5CF6, // Violet
    ),
    VIBE(
        id = "vibe",
        displayName = "Mistral Vibe",
        shortName = "Vibe",
        url = "https://chat.mistral.ai",
        description = "Mistral Vibe account chat, formerly Le Chat, with work and agent tools",
        brandHexColor = 0xFFFF7000, // Mistral Orange
    ),
    QWEN(
        id = "qwen",
        displayName = "Qwen",
        shortName = "Qwen",
        url = "https://chat.qwen.ai",
        description = "Alibaba Qwen account chat with multimodal, search, and agent capabilities",
        brandHexColor = 0xFF6366F1, // Indigo
    ),
    COPILOT(
        id = "copilot",
        displayName = "Microsoft Copilot",
        shortName = "Copilot",
        url = "https://copilot.microsoft.com",
        description = "Microsoft Copilot account chat with web-grounded assistance and Microsoft services",
        brandHexColor = 0xFF2563EB, // Microsoft blue
    ),
    ZAI(
        id = "zai",
        displayName = "Z.ai",
        shortName = "Z.ai",
        url = "https://chat.z.ai",
        description = "Z.ai account chat powered by GLM models with chat and agent workflows",
        brandHexColor = 0xFF7C3AED, // Violet
    ),
    GROK(
        id = "grok",
        displayName = "Grok",
        shortName = "Grok",
        url = "https://grok.com",
        description = "xAI Grok account chat with search, reasoning, files, and multimodal tools",
        brandHexColor = 0xFF64748B, // Slate
    ),
    CHARACTER_AI(
        id = "character-ai",
        displayName = "Character.AI",
        shortName = "Character.AI",
        url = "https://character.ai",
        description = "Character.AI account chat for character-driven conversations",
        brandHexColor = 0xFF7C3AED, // Violet
    ),
    VENICE(
        id = "venice",
        displayName = "Venice AI",
        shortName = "Venice",
        url = "https://venice.ai",
        description = "Venice account chat focused on private AI and creative workflows",
        brandHexColor = 0xFF0F766E, // Teal
    ),
    META_AI(
        id = "meta-ai",
        displayName = "Meta AI",
        shortName = "Meta AI",
        url = "https://www.meta.ai",
        description = "Meta AI account chat hosted at meta.ai",
        brandHexColor = 0xFF0668E1, // Meta blue
    ),
    ;

    companion object {
        val primaryChats: List<WebAiService> = listOf(
            CLAUDE,
            CHATGPT,
            GEMINI,
            DEEPSEEK,
            KIMI,
            VIBE,
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
    val additional: List<WebAiService>,
) {
    val all: List<WebAiService> get() = favorites + primary + additional
}

fun webChatSections(favorites: Set<WebAiService>): WebChatSections = WebChatSections(
    favorites = WebAiService.entries.filter(favorites::contains),
    primary = WebAiService.primaryChats.filterNot(favorites::contains),
    additional = WebAiService.additionalChats.filterNot(favorites::contains),
)

@Serializable
enum class AiProvider(
    val id: String,
    val displayName: String,
    val shortName: String,
    val defaultModel: String,
    val availableModels: List<String>,
    val description: String,
    val participatesInDefaultCompare: Boolean = true,
) {
    ALL(
        id = "all",
        displayName = "All Models (Compare)",
        shortName = "All Models",
        defaultModel = "all",
        availableModels = listOf("all"),
        description = "Send one prompt concurrently to every configured direct provider",
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
            "claude-haiku-4-5-20251001",
        ),
        description = "Anthropic models for coding, reasoning, writing, and agentic workflows",
    ),
    CHATGPT(
        id = "chatgpt",
        displayName = "OpenAI",
        shortName = "OpenAI",
        defaultModel = "gpt-5.6",
        availableModels = listOf(
            "gpt-5.6",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.5",
        ),
        description = "OpenAI GPT models for general text generation, coding, and reasoning",
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
            "gemini-3.1-pro-preview",
        ),
        description = "Google multimodal models for coding, agents, reasoning, and long context",
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek AI",
        shortName = "DeepSeek",
        defaultModel = "deepseek-v4-flash",
        availableModels = listOf(
            "deepseek-v4-flash",
            "deepseek-v4-pro",
        ),
        description = "DeepSeek V4 models with long context, reasoning, coding, and agent capabilities",
    ),
    KIMI(
        id = "kimi",
        displayName = "Moonshot Kimi AI",
        shortName = "Kimi",
        defaultModel = "kimi-k2.6",
        availableModels = listOf(
            "kimi-k2.6",
            "kimi-k3",
            "kimi-k2.7-code",
        ),
        description = "Moonshot AI models for general chat, frontier reasoning, coding, and long context",
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter Free",
        shortName = "OpenRouter",
        defaultModel = "openrouter/free",
        availableModels = listOf("openrouter/free"),
        description = "OpenRouter gateway that routes requests across currently available free models",
        participatesInDefaultCompare = false,
    ),
    AIHUBMIX(
        id = "aihubmix",
        displayName = "AIHubMix Free",
        shortName = "AIHubMix",
        defaultModel = "hy3-free",
        availableModels = listOf(
            "hy3-free",
            "coding-glm-5.3-free",
            "coding-glm-5.2-free",
        ),
        description = "AIHubMix gateway for subsidized zero-cost model variants",
        participatesInDefaultCompare = false,
    ),
    VERCEL(
        id = "vercel",
        displayName = "Vercel AI Gateway",
        shortName = "Vercel",
        defaultModel = "alibaba/qwen3-coder-30b-a3b",
        availableModels = listOf("alibaba/qwen3-coder-30b-a3b"),
        description = "Vercel AI Gateway with a live text-model catalog and account budget controls",
        participatesInDefaultCompare = false,
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom endpoint",
        shortName = "Custom",
        defaultModel = "",
        availableModels = emptyList(),
        description = "Your own OpenAI-compatible HTTPS endpoint; models come from its live catalog",
        participatesInDefaultCompare = false,
    ),
    ;

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
    val vercelAiGatewayKey: String = "",
    /** User's own OpenAI-compatible endpoint; usable only through [customEndpointBaseUrl]. */
    val customEndpointUrl: String = "",
    /** Optional: many self-hosted endpoints take no key. */
    val customEndpointKey: String = "",
    /** Fallback model id for endpoints that expose no `/models` catalog. */
    val customEndpointModel: String = "",
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
    // The endpoint is the credential here; its key is optional.
    AiProvider.CUSTOM -> customEndpointBaseUrl() != null
    AiProvider.ALL -> false
}

private const val CUSTOM_ENDPOINT_SCHEME = "https://"
private const val MAX_CUSTOM_ENDPOINT_URL_LENGTH = 512
private const val MAX_PORT = 65535

/** In an object, not top level: this file's class loads at startup and must not compile regexes then. */
private object CustomEndpointUrlRules {
    val authority = Regex(
        """(\[[0-9A-Fa-f:.]+]|[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?)(:([0-9]{1,5}))?""",
    )
    val path = Regex("""(/[A-Za-z0-9._~!$&'()*+,;=:%-]+)*""")
    val pastedSuffixes = listOf("/chat/completions", "/models")
}

/**
 * Base URL (no trailing slash) of a custom OpenAI-compatible endpoint, or null when unusable.
 *
 * HTTPS only: prompts and the key never leave the device in cleartext. Userinfo, query and
 * fragment are refused, so the stored value cannot carry credentials or request state. The path is
 * kept as written: this also validates the stored value, where nothing may be stripped again.
 */
fun normalizeCustomEndpointBaseUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.length > MAX_CUSTOM_ENDPOINT_URL_LENGTH) return null
    if (!trimmed.startsWith(CUSTOM_ENDPOINT_SCHEME, ignoreCase = true)) return null
    val rest = trimmed.substring(CUSTOM_ENDPOINT_SCHEME.length)
    val authority = rest.substringBefore('/')
    val match = CustomEndpointUrlRules.authority.matchEntire(authority) ?: return null
    val port = match.groupValues[4]
    if (port.isNotEmpty() && port.toInt() !in 1..MAX_PORT) return null
    val path = rest.substring(authority.length).trimEnd('/')
    if (!CustomEndpointUrlRules.path.matches(path)) return null
    return CUSTOM_ENDPOINT_SCHEME + authority.lowercase() + path
}

/**
 * [normalizeCustomEndpointBaseUrl] for what the user typed: a pasted full `/chat/completions` or
 * `/models` URL is reduced to its base, once.
 */
fun customEndpointBaseUrlFromInput(raw: String): String? {
    val base = normalizeCustomEndpointBaseUrl(raw) ?: return null
    val pasted = CustomEndpointUrlRules.pastedSuffixes.firstOrNull { base.endsWith(it, ignoreCase = true) }
    return if (pasted == null) base else base.dropLast(pasted.length)
}

fun ApiKeyConfig.customEndpointBaseUrl(): String? = normalizeCustomEndpointBaseUrl(customEndpointUrl)

private const val FNV_OFFSET_BASIS = 0xcbf29ce484222325uL
private const val FNV_PRIME = 0x100000001b3uL
private const val CUSTOM_ENDPOINT_TAG_HEX_CHARS = 16

/**
 * Opaque, stable identity of a custom endpoint, stored on its replies to scope history replay.
 * A fingerprint, never the URL: chats and exports must not carry the endpoint address.
 */
fun customEndpointTag(baseUrl: String): String {
    var hash = FNV_OFFSET_BASIS
    for (byte in baseUrl.encodeToByteArray()) {
        hash = (hash xor byte.toUByte().toULong()) * FNV_PRIME
    }
    return hash.toString(radix = 16).padStart(CUSTOM_ENDPOINT_TAG_HEX_CHARS, '0')
}

fun ApiKeyConfig.customEndpointTag(): String? = customEndpointBaseUrl()?.let(::customEndpointTag)

fun ApiKeyConfig.configuredDirectProviders(): List<AiProvider> =
    AiProvider.concreteProviders.filter { hasKeyFor(it) }

/** One entry of a ChatGPT plan account's model catalog; [slug] goes into `model`. */
data class ChatGptPlanModel(val slug: String, val displayName: String)

/** A chat bound to a ChatGPT plan account runs ChatGPT on that plan, with or without an API key. */
fun ApiKeyConfig.canRunDirect(provider: AiProvider, chatGptPlanClientId: String?): Boolean =
    if (provider == AiProvider.CHATGPT && chatGptPlanClientId != null) true else hasKeyFor(provider)

fun ApiKeyConfig.directProvidersFor(chatGptPlanClientId: String?): List<AiProvider> =
    AiProvider.concreteProviders.filter { canRunDirect(it, chatGptPlanClientId) }

data class GatewayModelCatalogEntry(
    val id: String,
    val inputPriceUsd: Double?,
    val outputPriceUsd: Double?,
    val supportsTextOutput: Boolean,
)

fun AiProvider.usesLiveGatewayModelCatalog(): Boolean =
    this == AiProvider.OPENROUTER || this == AiProvider.AIHUBMIX || this == AiProvider.VERCEL ||
        this == AiProvider.CUSTOM

fun gatewayModelOptions(
    provider: AiProvider,
    catalog: List<GatewayModelCatalogEntry>,
): List<String> {
    if (!provider.usesLiveGatewayModelCatalog()) return provider.availableModels

    val textModels = catalog.asSequence()
        .filter { it.supportsTextOutput }
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id.trim() }

    val eligibleModels = when (provider) {
        AiProvider.VERCEL -> textModels.sortedWith(
            compareBy<GatewayModelCatalogEntry> { entry ->
                val input = entry.inputPriceUsd ?: Double.POSITIVE_INFINITY
                val output = entry.outputPriceUsd ?: Double.POSITIVE_INFINITY
                input + output
            }.thenBy { it.id },
        )
        // The user's own endpoint: no price data, every listed model is theirs to pick.
        AiProvider.CUSTOM -> textModels.sortedBy { it.id.trim() }
        else -> textModels.filter { entry ->
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
    /** A user turn written while offline and waiting for the background send. */
    val isQueued: Boolean = false,
    val latencyMs: Long? = null,
    val usage: ProviderUsage? = null,
    val activeProfileNotes: List<String> = emptyList(),
    /** Local Project Library assets explicitly inserted into this user turn. Never sent by id. */
    val sourceAssetIds: List<String> = emptyList(),
    /** Coarse local receipts for client tools used while producing this reply. */
    val toolReceipts: List<NativeToolReceipt> = emptyList(),
    /** Cards from skills the model called for this reply; shown only to the user. */
    val skillCards: List<ActiveSkillCard> = emptyList(),
    @Transient val providerReplayState: String? = null,
    /** [customEndpointTag] of the custom endpoint that produced this reply; null for every other reply. */
    val endpointTag: String? = null,
) {
    /** User text, identifiers, profile notes and opaque replay state are sensitive debug payloads. */
    override fun toString(): String =
        "ModelChatMessage(id=<redacted>, sender=$sender, provider=${provider?.id ?: "none"}, " +
            "text=<redacted>, activeProfileNotes=<redacted>, sourceAssetIds=${sourceAssetIds.size}, " +
            "toolReceipts=${toolReceipts.size}, " +
            "skillCards=${skillCards.size}, providerReplayState=<redacted>, " +
            "isError=$isError, isSimulated=$isSimulated, isPartial=$isPartial, isImported=$isImported, isQueued=$isQueued)"
}

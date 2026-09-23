package ais.tee.data.engine

import ais.tee.data.model.AiProvider
import ais.tee.data.model.ApiKeyConfig
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ClaudeReasoningCapabilities
import ais.tee.data.model.parseClaudeReasoningCapabilities
import ais.tee.data.model.resolveClaudeThinkingBudget
import ais.tee.data.model.GatewayModelCatalogEntry
import ais.tee.data.model.ClientToolCallingStrategy
import ais.tee.data.model.MAX_NATIVE_TOOL_ROUNDS
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NativeToolCall
import ais.tee.data.model.NativeToolDefinition
import ais.tee.data.model.NativeToolResult
import ais.tee.data.model.NativeApiProcessingMode
import ais.tee.data.model.nativeApiProcessingModes
import ais.tee.data.model.runtimeCapabilities
import ais.tee.data.model.ProviderUsage
import ais.tee.data.model.buildBoundedProviderTextTurns
import ais.tee.data.model.gatewayModelOptions
import ais.tee.data.model.Profile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val JSON_ROLE_KEY = "role"
private const val JSON_CONTENT_KEY = "content"
private const val JSON_PARTS_KEY = "parts"
private const val JSON_TEXT_KEY = "text"
private const val JSON_MODEL_KEY = "model"
private const val JSON_MODEL_ID_KEY = "id"
private const val JSON_PRICING_KEY = "pricing"
private const val JSON_INPUT_KEY = "input"
private const val JSON_OUTPUT_KEY = "output"
private const val JSON_USAGE_KEY = "usage"
private const val JSON_TOTAL_TOKENS_KEY = "total_tokens"
private const val JSON_PROMPT_TOKENS_KEY = "prompt_tokens"
private const val JSON_COMPLETION_TOKENS_KEY = "completion_tokens"
private const val JSON_INPUT_TOKENS_KEY = "input_tokens"
private const val JSON_OUTPUT_TOKENS_KEY = "output_tokens"
private const val JSON_INPUT_TOKEN_DETAILS_KEY = "input_tokens_details"
private const val JSON_OUTPUT_TOKEN_DETAILS_KEY = "output_tokens_details"
private const val JSON_PROMPT_TOKEN_DETAILS_KEY = "prompt_tokens_details"
private const val JSON_COMPLETION_TOKEN_DETAILS_KEY = "completion_tokens_details"
private const val JSON_CACHED_TOKENS_KEY = "cached_tokens"
private const val JSON_REASONING_TOKENS_KEY = "reasoning_tokens"
private const val JSON_COST_KEY = "cost"
private const val GEMINI_USAGE_METADATA_KEY = "usageMetadata"
private const val GEMINI_PROMPT_TOKENS_KEY = "promptTokenCount"
private const val GEMINI_CANDIDATE_TOKENS_KEY = "candidatesTokenCount"
private const val GEMINI_TOTAL_TOKENS_KEY = "totalTokenCount"
private const val GEMINI_CACHED_TOKENS_KEY = "cachedContentTokenCount"
private const val GEMINI_THOUGHT_TOKENS_KEY = "thoughtsTokenCount"
private const val CLAUDE_CACHE_CREATION_TOKENS_KEY = "cache_creation_input_tokens"
private const val CLAUDE_CACHE_READ_TOKENS_KEY = "cache_read_input_tokens"
private const val CLAUDE_THINKING_TOKENS_KEY = "thinking_tokens"
private const val DEEPSEEK_CACHE_HIT_TOKENS_KEY = "prompt_cache_hit_tokens"
private const val JSON_STATUS_KEY = "status"
private const val JSON_INCLUDE_KEY = "include"
private const val JSON_CANDIDATES_KEY = "candidates"
private const val JSON_CHOICES_KEY = "choices"
private const val JSON_DELTA_KEY = "delta"
private const val JSON_FINISH_REASON_KEY = "finish_reason"
private const val JSON_SYSTEM_KEY = "system"
private const val JSON_MESSAGES_KEY = "messages"
private const val JSON_MAX_TOKENS_KEY = "max_tokens"
private const val JSON_STOP_REASON_KEY = "stop_reason"
private const val JSON_STORE_KEY = "store"
private const val JSON_STREAM_KEY = "stream"
private const val JSON_SERVICE_TIER_KEY = "service_tier"
private const val OPENAI_SERVICE_TIER_FLEX = "flex"
private const val JSON_INSTRUCTIONS_KEY = "instructions"
private const val JSON_THINKING_KEY = "thinking"
private const val JSON_ADAPTIVE_KEY = "adaptive"
private const val JSON_ENABLED_KEY = "enabled"
private const val JSON_EFFORT_KEY = "effort"
private const val JSON_HIGH_KEY = "high"
private const val JSON_OUTPUT_CONFIG_KEY = "output_config"
private const val JSON_BUDGET_TOKENS_KEY = "budget_tokens"
private const val JSON_SIGNATURE_KEY = "signature"
private const val JSON_INDEX_KEY = "index"
private const val JSON_CONTENT_BLOCK_KEY = "content_block"
private const val JSON_DATA_KEY = "data"
private const val JSON_TO_KEY = "to"
private const val JSON_MEDIA_TYPE = "application/json"
private const val HEADER_AUTHORIZATION = "Authorization"
private const val HEADER_CONTENT_TYPE = "Content-Type"
private const val HEADER_CONTENT_TYPE_LOWER = "content-type"
private const val SSE_DATA_PREFIX = "data:"
private const val SSE_DONE = "[DONE]"
private const val STREAM_TYPE_KEY = "type"
private const val STREAM_ERROR_KEY = "error"
private const val STREAM_MESSAGE_KEY = "message"
private const val STREAM_RESPONSE_KEY = "response"
private const val OPENAI_RESPONSE_FAILED = "response.failed"
private const val OPENAI_RESPONSE_COMPLETED = "response.completed"
private const val OPENAI_RESPONSE_INCOMPLETE = "response.incomplete"
private const val OPENAI_STATUS_FAILED = "failed"
private const val OPENAI_STATUS_INCOMPLETE = "incomplete"
private const val OPENAI_INCOMPLETE_DETAILS_KEY = "incomplete_details"
private const val OPENAI_INCOMPLETE_REASON_KEY = "reason"
private const val CLAUDE_MESSAGE_START = "message_start"
private const val CLAUDE_MESSAGE_DELTA = "message_delta"
private const val CLAUDE_MESSAGE_STOP = "message_stop"
private const val OPENAI_OUTPUT_TEXT = "output_text"
private const val OPENAI_OUTPUT_TEXT_DELTA = "response.output_text.delta"
private const val OPENAI_REASONING_ENCRYPTED_CONTENT = "reasoning.encrypted_content"
private const val CLAUDE_CONTENT_BLOCK_START = "content_block_start"
private const val CLAUDE_CONTENT_BLOCK_DELTA = "content_block_delta"
private const val CLAUDE_TEXT_DELTA = "text_delta"
private const val CLAUDE_THINKING_DELTA = "thinking_delta"
private const val CLAUDE_SIGNATURE_DELTA = "signature_delta"
private const val CLAUDE_THINKING_BLOCK = "thinking"
private const val CLAUDE_REDACTED_THINKING_BLOCK = "redacted_thinking"
private const val CLAUDE_FALLBACK_BLOCK = "fallback"
private const val CLAUDE_UNREPLAYABLE_BLOCK = "__unreplayable__"
private const val CLAUDE_STOP_MAX_TOKENS = "max_tokens"
private const val CLAUDE_STOP_CONTEXT_WINDOW_EXCEEDED = "model_context_window_exceeded"
private const val MALFORMED_STREAM_EVENT = "Malformed streaming event"
private const val CLAUDE_MESSAGES_API_URL = "https://api.anthropic.com/v1/messages"
private const val CLAUDE_MODELS_API_URL = "https://api.anthropic.com/v1/models"
private const val CLAUDE_MAX_TOKENS_COMPAT_FALLBACK = 2048
private const val HEADER_ANTHROPIC_API_KEY = "x-api-key"
private const val HEADER_ANTHROPIC_VERSION = "anthropic-version"
private const val ANTHROPIC_API_VERSION = "2023-06-01"
private const val CLAUDE_METADATA_TIMEOUT_SECONDS = 2L
private const val CLAUDE_ALIAS_CACHE_TTL_MILLIS = 5 * 60 * 1000L
private const val CLAUDE_METADATA_FAILURE_TTL_MILLIS = 30 * 1000L

internal fun buildClaudeMetadataHttpClient(baseClient: OkHttpClient): OkHttpClient =
    baseClient.newBuilder()
        .callTimeout(CLAUDE_METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

class AiChatService {

    private data class GeminiGenerationResult(
        val text: String,
        val replayState: String?,
        val usage: ProviderUsage?
    )

    private data class OpenAiGenerationResult(
        val text: String,
        val replayState: String?,
        val usage: ProviderUsage?
    )

    private data class ClaudeGenerationResult(
        val text: String,
        val replayState: String?,
        val resolvedModel: String,
        val isPartial: Boolean,
        val usage: ProviderUsage?
    )

    private data class OpenAiCompatibleGenerationResult(
        val text: String,
        val usage: ProviderUsage?
    )

    internal data class ClaudeRuntimeMetadata(
        val maxTokens: Int,
        val reasoningCapabilities: ClaudeReasoningCapabilities,
        val resolvedModel: String
    )

    private data class ClaudeMetadataCacheEntry(
        val metadata: ClaudeRuntimeMetadata,
        val expiresAtMillis: Long?
    )

    private data class ClaudeMetadataCacheKey(
        val model: String,
        val credentialFingerprint: String
    )

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val streamingHttpClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val claudeMetadataHttpClient: OkHttpClient = buildClaudeMetadataHttpClient(httpClient)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val claudeMetadataByModelAndCredential =
        ConcurrentHashMap<ClaudeMetadataCacheKey, ClaudeMetadataCacheEntry>()
    private val claudeMetadataMutex = Mutex()

    suspend fun fetchGatewayModels(
        provider: AiProvider,
        apiKeys: ApiKeyConfig
    ): List<String> = withContext(Dispatchers.IO) {
        val config = requireNotNull(OpenAiCompatibleProtocol.configFor(provider)) {
            "No OpenAI-compatible provider config for ${provider.id}"
        }
        val catalogUrl = requireNotNull(config.modelCatalogUrl) {
            "No live model catalog for ${provider.id}"
        }
        val requestBuilder = Request.Builder().url(catalogUrl).get()
        val apiKey = when (provider) {
            AiProvider.OPENROUTER -> apiKeys.openRouterKey
            AiProvider.AIHUBMIX -> apiKeys.aiHubMixKey
            AiProvider.VERCEL -> apiKeys.vercelAiGatewayKey
            else -> ""
        }.trim()
        if (apiKey.isNotBlank()) {
            requestBuilder.header(HEADER_AUTHORIZATION, bearerToken(apiKey))
        }
        config.extraHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }

        val responseBody = executeCancellableJson(
            request = requestBuilder.build(),
            httpErrorContext = "${provider.shortName} model catalog"
        )
        gatewayModelOptions(provider, parseGatewayModelCatalog(provider, responseBody))
    }

    internal fun parseGatewayModelCatalog(
        provider: AiProvider,
        rawJson: String
    ): List<GatewayModelCatalogEntry> {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val data = root["data"] as? JsonArray
            ?: error("Malformed ${provider.shortName} model catalog: missing data array")
        return data.mapNotNull { element ->
            val model = element.jsonObject
            when (provider) {
                AiProvider.OPENROUTER -> {
                    val id = model["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val pricing = model[JSON_PRICING_KEY]?.jsonObject
                    val outputModalities = model["architecture"]?.jsonObject
                        ?.get("output_modalities")?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    GatewayModelCatalogEntry(
                        id = id,
                        inputPriceUsd = pricing?.get("prompt").asDoubleOrNull(),
                        outputPriceUsd = pricing?.get("completion").asDoubleOrNull(),
                        supportsTextOutput = outputModalities?.contains(JSON_TEXT_KEY) == true
                    )
                }
                AiProvider.AIHUBMIX -> {
                    val id = model["model_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val pricing = model[JSON_PRICING_KEY]?.jsonObject
                    GatewayModelCatalogEntry(
                        id = id,
                        inputPriceUsd = pricing?.get(JSON_INPUT_KEY).asDoubleOrNull(),
                        outputPriceUsd = pricing?.get(JSON_OUTPUT_KEY).asDoubleOrNull(),
                        supportsTextOutput = model["types"]?.jsonPrimitive?.contentOrNull?.equals("llm", ignoreCase = true) == true
                    )
                }
                AiProvider.VERCEL -> {
                    val id = model[JSON_MODEL_ID_KEY]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val pricing = model[JSON_PRICING_KEY]?.jsonObject
                    val outputModalities = (model["modalities"] as? JsonObject)
                        ?.get(JSON_OUTPUT_KEY) as? JsonArray
                    val supportsText = outputModalities
                        ?.any { it.jsonPrimitive.contentOrNull == JSON_TEXT_KEY }
                        ?: true
                    GatewayModelCatalogEntry(
                        id = id,
                        inputPriceUsd = pricing?.get(JSON_INPUT_KEY).asDoubleOrNull(),
                        outputPriceUsd = pricing?.get(JSON_OUTPUT_KEY).asDoubleOrNull(),
                        supportsTextOutput =
                            model["type"]?.jsonPrimitive?.contentOrNull?.equals("language", ignoreCase = true) == true &&
                                supportsText
                    )
                }
                else -> return@mapNotNull null
            }
        }
    }

    private fun JsonElement?.asDoubleOrNull(): Double? = when (this) {
        is JsonPrimitive -> contentOrNull?.toDoubleOrNull()
        else -> null
    }

    private fun JsonElement?.asUsageLongOrNull(): Long? =
        (this as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0L }

    private fun sumUsageTokens(vararg values: Long?): Long? {
        val present = values.filterNotNull()
        return present.takeIf { it.isNotEmpty() }?.sum()
    }

    private fun providerUsage(
        inputTokens: Long? = null,
        outputTokens: Long? = null,
        totalTokens: Long? = null,
        cachedInputTokens: Long? = null,
        reasoningTokens: Long? = null,
        costUsd: Double? = null
    ): ProviderUsage? {
        val normalizedCost = costUsd?.takeIf { it >= 0.0 && it.isFinite() }
        return ProviderUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens ?: if (inputTokens != null && outputTokens != null) {
                inputTokens + outputTokens
            } else null,
            cachedInputTokens = cachedInputTokens,
            reasoningTokens = reasoningTokens,
            costUsd = normalizedCost
        ).takeIf { usage ->
            usage.inputTokens != null || usage.outputTokens != null || usage.totalTokens != null ||
                usage.cachedInputTokens != null || usage.reasoningTokens != null || usage.costUsd != null
        }
    }

    internal fun mergeProviderUsage(current: ProviderUsage?, update: ProviderUsage?): ProviderUsage? {
        if (update == null) return current
        if (current == null) return update
        val input = update.inputTokens ?: current.inputTokens
        val output = update.outputTokens ?: current.outputTokens
        val tokensChanged = update.inputTokens != null || update.outputTokens != null
        val total = update.totalTokens ?: if (tokensChanged && input != null && output != null) {
            input + output
        } else {
            current.totalTokens
        }
        return ProviderUsage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            cachedInputTokens = update.cachedInputTokens ?: current.cachedInputTokens,
            reasoningTokens = update.reasoningTokens ?: current.reasoningTokens,
            costUsd = update.costUsd ?: current.costUsd
        )
    }

    private fun bearerToken(apiKey: String): String = "Bearer $apiKey"

    suspend fun generateResponse(
        prompt: String,
        provider: AiProvider,
        modelName: String,
        apiKeys: ApiKeyConfig,
        systemInstruction: String?,
        profile: Profile?,
        conversationHistory: List<ModelChatMessage> = emptyList(),
        allowSimulationFallback: Boolean = true,
        apiProcessingMode: NativeApiProcessingMode = NativeApiProcessingMode.DEFAULT,
        onTextDelta: ((String) -> Unit)? = null,
        tools: List<NativeToolDefinition> = emptyList(),
        executeTool: (suspend (NativeToolCall) -> NativeToolResult)? = null,
    ): ModelChatMessage = withContext(Dispatchers.IO) {
        require(tools.isEmpty() || executeTool != null) {
            "A tool executor is required when client tools are offered"
        }
        if (tools.isNotEmpty()) {
            require(provider.runtimeCapabilities().clientToolCallingStrategy != ClientToolCallingStrategy.NONE) {
                "${provider.displayName} does not expose verified client tool calling"
            }
        }
        val startTime = System.currentTimeMillis()
        val effectiveModel = if (modelName == "all" || modelName.isBlank()) provider.defaultModel else modelName
        var resolvedModel = effectiveModel
        var providerReplayState: String? = null
        var providerUsage: ProviderUsage? = null
        var isPartial = false
        val effectiveApiProcessingMode = apiProcessingMode.takeIf {
            it in provider.nativeApiProcessingModes()
        } ?: NativeApiProcessingMode.DEFAULT

        val (key, isKeyProvided) = when (provider) {
            AiProvider.GEMINI -> Pair(apiKeys.geminiKey.trim(), apiKeys.geminiKey.isNotBlank())
            AiProvider.CHATGPT -> Pair(apiKeys.openAiKey.trim(), apiKeys.openAiKey.isNotBlank())
            AiProvider.CLAUDE -> Pair(apiKeys.claudeKey.trim(), apiKeys.claudeKey.isNotBlank())
            AiProvider.DEEPSEEK -> Pair(apiKeys.deepseekKey.trim(), apiKeys.deepseekKey.isNotBlank())
            AiProvider.KIMI -> Pair(apiKeys.kimiKey.trim(), apiKeys.kimiKey.isNotBlank())
            AiProvider.OPENROUTER -> Pair(apiKeys.openRouterKey.trim(), apiKeys.openRouterKey.isNotBlank())
            AiProvider.AIHUBMIX -> Pair(apiKeys.aiHubMixKey.trim(), apiKeys.aiHubMixKey.isNotBlank())
            AiProvider.VERCEL -> Pair(apiKeys.vercelAiGatewayKey.trim(), apiKeys.vercelAiGatewayKey.isNotBlank())
            AiProvider.ALL -> Pair("", false)
        }

        // If key is available, attempt real live REST call
        if (isKeyProvided) {
            try {
                val realResult = when (provider) {
                    AiProvider.GEMINI -> {
                        val result = when {
                            tools.isNotEmpty() -> callGeminiToolApi(
                                prompt = prompt,
                                model = effectiveModel,
                                apiKey = key,
                                systemInstruction = systemInstruction,
                                conversationHistory = conversationHistory,
                                tools = tools,
                                executeTool = checkNotNull(executeTool),
                            )
                            onTextDelta != null -> callGeminiStreamApi(
                                prompt, effectiveModel, key, systemInstruction, conversationHistory, onTextDelta
                            )
                            else -> callGeminiApi(
                                prompt, effectiveModel, key, systemInstruction, conversationHistory
                            )
                        }
                        providerReplayState = result.replayState
                        providerUsage = result.usage
                        result.text
                    }
                    AiProvider.CHATGPT -> {
                        val result = when {
                            tools.isNotEmpty() -> callOpenAiToolApi(
                                prompt = prompt,
                                model = effectiveModel,
                                apiKey = key,
                                systemInstruction = systemInstruction,
                                conversationHistory = conversationHistory,
                                apiProcessingMode = effectiveApiProcessingMode,
                                tools = tools,
                                executeTool = checkNotNull(executeTool),
                            )
                            onTextDelta != null -> callOpenAiStreamApi(
                                prompt, effectiveModel, key, systemInstruction, conversationHistory,
                                effectiveApiProcessingMode, onTextDelta
                            )
                            else -> callOpenAiApi(
                                prompt, effectiveModel, key, systemInstruction, conversationHistory,
                                effectiveApiProcessingMode
                            )
                        }
                        providerReplayState = result.replayState
                        providerUsage = result.usage
                        result.text
                    }
                    AiProvider.CLAUDE -> {
                        val result = when {
                            tools.isNotEmpty() -> callClaudeToolApi(
                                prompt = prompt,
                                model = effectiveModel,
                                apiKey = key,
                                systemInstruction = systemInstruction,
                                conversationHistory = conversationHistory,
                                tools = tools,
                                executeTool = checkNotNull(executeTool),
                            )
                            onTextDelta != null -> callClaudeStreamApi(
                                prompt, effectiveModel, key, systemInstruction, conversationHistory, onTextDelta
                            )
                            else -> callClaudeApi(
                                prompt, effectiveModel, key, systemInstruction, conversationHistory
                            )
                        }
                        providerReplayState = result.replayState
                        resolvedModel = result.resolvedModel
                        isPartial = result.isPartial
                        providerUsage = result.usage
                        result.text
                    }
                    AiProvider.DEEPSEEK, AiProvider.KIMI, AiProvider.OPENROUTER, AiProvider.AIHUBMIX, AiProvider.VERCEL -> {
                        val config = checkNotNull(OpenAiCompatibleProtocol.configFor(provider))
                        val result = if (onTextDelta != null) {
                            callOpenAiCompatibleStreamApi(
                                config, prompt, effectiveModel, key, systemInstruction,
                                conversationHistory, provider, onTextDelta,
                                onResolvedModel = { resolvedModel = it }
                            )
                        } else {
                            callOpenAiCompatibleApi(
                                config, prompt, effectiveModel, key, systemInstruction,
                                conversationHistory, provider,
                                onResolvedModel = { resolvedModel = it }
                            )
                        }
                        providerUsage = result.usage
                        result.text
                    }
                    AiProvider.ALL -> null
                }

                if (realResult != null) {
                    val latency = System.currentTimeMillis() - startTime
                    val activeNotes = extractProfileNotes(profile)
                    return@withContext ModelChatMessage(
                        id = "msg_${System.currentTimeMillis()}_${provider.id}",
                        sender = "assistant",
                        provider = provider,
                        modelName = resolvedModel,
                        text = realResult,
                        isError = false,
                        isSimulated = false,
                        isPartial = isPartial,
                        latencyMs = latency,
                        usage = providerUsage,
                        activeProfileNotes = activeNotes,
                        providerReplayState = providerReplayState
                    )
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val latency = System.currentTimeMillis() - startTime
                val errorDetails = e.localizedMessage ?: e.message ?: "Unknown error"
                if (!allowSimulationFallback) {
                    return@withContext ModelChatMessage(
                        id = "msg_${System.currentTimeMillis()}_${provider.id}",
                        sender = "assistant",
                        provider = provider,
                        modelName = effectiveModel,
                        text = "⚠️ Error communicating with ${provider.displayName} API ($effectiveModel):\n\n$errorDetails",
                        isError = true,
                        isSimulated = false,
                        latencyMs = latency,
                        activeProfileNotes = listOf("API Request Failed - Simulation disabled for All Models")
                    )
                }
                return@withContext ModelChatMessage(
                    id = "msg_${System.currentTimeMillis()}_${provider.id}",
                    sender = "assistant",
                    provider = provider,
                    modelName = effectiveModel,
                    text = "⚠️ Error communicating with ${provider.displayName} API ($effectiveModel):\n\n$errorDetails\n\nFalling back to profile persona simulation below:\n\n" +
                            generatePersonaResponse(prompt, provider, effectiveModel, profile),
                    isError = true,
                    isSimulated = true,
                    latencyMs = latency,
                    activeProfileNotes = listOf("API Request Failed - Returned fallback simulation")
                )
            }
        }

        // No key provided or in demonstration mode: generate high-fidelity persona simulation
        kotlinx.coroutines.delay(450) // Realistic interactive delay
        val simulatedText = generatePersonaResponse(prompt, provider, effectiveModel, profile)
        val latency = System.currentTimeMillis() - startTime
        val notes = extractProfileNotes(profile).toMutableList()
        notes.add(0, "⚡ Mode: Ready for Live Keys (tap Key icon to connect ${provider.shortName} API)")

        ModelChatMessage(
            id = "msg_${System.currentTimeMillis()}_${provider.id}",
            sender = "assistant",
            provider = provider,
            modelName = effectiveModel,
            text = simulatedText,
            isError = false,
            isSimulated = true,
            latencyMs = latency,
            activeProfileNotes = notes
        )
    }

    private fun extractProfileNotes(profile: Profile?): List<String> {
        if (profile == null) return emptyList()
        val notes = mutableListOf<String>()
        notes.add("Base Voice: ${profile.personality.base} (lvl ${profile.personality.intensity ?: 1})")
        profile.personality.modifiers.filter { (it.value ?: 0) > 0 }.forEach { (k, v) ->
            notes.add("$k: $v")
        }
        notes.add("Initiative: ${profile.collaboration.initiative} • Verification: ${profile.collaboration.verification}")
        return notes
    }

    internal fun buildGeminiContents(
        prompt: String,
        conversationHistory: List<ModelChatMessage>,
        modelName: String,
        systemInstruction: String? = null
    ): JsonArray = buildJsonArray {
        buildBoundedProviderTextTurns(
            prompt = prompt,
            conversationHistory = conversationHistory,
            provider = AiProvider.GEMINI,
            systemInstruction = systemInstruction,
            replayStateModelName = modelName,
            replayStateValidator = { state -> parseGeminiReplayState(state).isNotEmpty() }
        ).forEach { turn ->
            if (turn.role == CHAT_ROLE_ASSISTANT) {
                val replayContents = turn.providerReplayState
                    .takeIf { turn.modelName == modelName }
                    ?.let(::parseGeminiReplayState)
                    .orEmpty()
                if (replayContents.isNotEmpty()) {
                    replayContents.forEach(::add)
                } else {
                    addJsonObject {
                        put(JSON_ROLE_KEY, JSON_MODEL_KEY)
                        putJsonArray(JSON_PARTS_KEY) {
                            addJsonObject { put(JSON_TEXT_KEY, turn.text) }
                        }
                    }
                }
            } else {
                addJsonObject {
                    put(JSON_ROLE_KEY, CHAT_ROLE_USER)
                    putJsonArray(JSON_PARTS_KEY) {
                        addJsonObject { put(JSON_TEXT_KEY, turn.text) }
                    }
                }
            }
        }
    }

    internal fun buildOpenAiResponseInput(
        prompt: String,
        conversationHistory: List<ModelChatMessage>,
        systemInstruction: String? = null,
        modelName: String? = null
    ): JsonArray = buildJsonArray {
        buildBoundedProviderTextTurns(
            prompt = prompt,
            conversationHistory = conversationHistory,
            provider = AiProvider.CHATGPT,
            systemInstruction = systemInstruction,
            replayStateModelName = modelName,
            replayStateValidator = { state -> parseOpenAiReplayState(state).isNotEmpty() }
        ).forEach { turn ->
            if (turn.role == CHAT_ROLE_ASSISTANT) {
                val replayItems = turn.providerReplayState
                    ?.let(::parseOpenAiReplayState)
                    .orEmpty()
                if (replayItems.isNotEmpty()) {
                    replayItems.forEach(::add)
                } else {
                    addJsonObject {
                        put(JSON_ROLE_KEY, CHAT_ROLE_ASSISTANT)
                        put(JSON_CONTENT_KEY, turn.text)
                    }
                }
            } else {
                addJsonObject {
                    put(JSON_ROLE_KEY, CHAT_ROLE_USER)
                    put(JSON_CONTENT_KEY, turn.text)
                }
            }
        }
    }

    internal fun buildOpenAiRequestPayload(
        model: String,
        stream: Boolean,
        systemInstruction: String?,
        input: JsonArray,
        apiProcessingMode: NativeApiProcessingMode = NativeApiProcessingMode.DEFAULT,
    ): JsonObject = buildJsonObject {
        put(JSON_MODEL_KEY, model)
        put(JSON_INPUT_KEY, input)
        put(JSON_STORE_KEY, false)
        putJsonArray(JSON_INCLUDE_KEY) {
            add(OPENAI_REASONING_ENCRYPTED_CONTENT)
        }
        if (stream) put(JSON_STREAM_KEY, true)
        if (apiProcessingMode == NativeApiProcessingMode.FLEX) {
            put(JSON_SERVICE_TIER_KEY, OPENAI_SERVICE_TIER_FLEX)
        }
        if (!systemInstruction.isNullOrBlank()) {
            put(JSON_INSTRUCTIONS_KEY, systemInstruction)
        }
    }

    internal fun buildClaudeMessages(
        prompt: String,
        conversationHistory: List<ModelChatMessage>,
        systemInstruction: String? = null,
        modelName: String? = null
    ): JsonArray = buildJsonArray {
        buildBoundedProviderTextTurns(
            prompt = prompt,
            conversationHistory = conversationHistory,
            provider = AiProvider.CLAUDE,
            systemInstruction = systemInstruction,
            replayStateModelName = modelName,
            replayStateValidator = { state -> parseClaudeReplayState(state).isNotEmpty() }
        ).forEach { turn ->
            addJsonObject {
                put(JSON_ROLE_KEY, turn.role)
                val replayBlocks = turn.providerReplayState
                    ?.let(::parseClaudeReplayState)
                    .orEmpty()
                if (turn.role == CHAT_ROLE_ASSISTANT && replayBlocks.isNotEmpty()) {
                    put(JSON_CONTENT_KEY, JsonArray(replayBlocks))
                } else {
                    put(JSON_CONTENT_KEY, turn.text)
                }
            }
        }
    }

    internal fun extractGeminiUsage(response: JsonObject): ProviderUsage? {
        val usage = response[GEMINI_USAGE_METADATA_KEY] as? JsonObject ?: return null
        return providerUsage(
            inputTokens = usage[GEMINI_PROMPT_TOKENS_KEY].asUsageLongOrNull(),
            outputTokens = usage[GEMINI_CANDIDATE_TOKENS_KEY].asUsageLongOrNull(),
            totalTokens = usage[GEMINI_TOTAL_TOKENS_KEY].asUsageLongOrNull(),
            cachedInputTokens = usage[GEMINI_CACHED_TOKENS_KEY].asUsageLongOrNull(),
            reasoningTokens = usage[GEMINI_THOUGHT_TOKENS_KEY].asUsageLongOrNull()
        )
    }

    internal fun extractOpenAiUsage(response: JsonObject): ProviderUsage? {
        val usage = response[JSON_USAGE_KEY] as? JsonObject ?: return null
        val inputDetails = usage[JSON_INPUT_TOKEN_DETAILS_KEY] as? JsonObject
        val outputDetails = usage[JSON_OUTPUT_TOKEN_DETAILS_KEY] as? JsonObject
        return providerUsage(
            inputTokens = usage[JSON_INPUT_TOKENS_KEY].asUsageLongOrNull(),
            outputTokens = usage[JSON_OUTPUT_TOKENS_KEY].asUsageLongOrNull(),
            totalTokens = usage[JSON_TOTAL_TOKENS_KEY].asUsageLongOrNull(),
            cachedInputTokens = inputDetails?.get(JSON_CACHED_TOKENS_KEY).asUsageLongOrNull(),
            reasoningTokens = outputDetails?.get(JSON_REASONING_TOKENS_KEY).asUsageLongOrNull()
        )
    }

    internal fun extractOpenAiCompletedUsage(event: JsonObject): ProviderUsage? =
        if (event[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull == OPENAI_RESPONSE_COMPLETED) {
            (event[STREAM_RESPONSE_KEY] as? JsonObject)?.let(::extractOpenAiUsage)
        } else null

    private fun parseClaudeUsageObject(usage: JsonObject?): ProviderUsage? {
        usage ?: return null
        val directInput = usage[JSON_INPUT_TOKENS_KEY].asUsageLongOrNull()
        val cacheCreation = usage[CLAUDE_CACHE_CREATION_TOKENS_KEY].asUsageLongOrNull()
        val cacheRead = usage[CLAUDE_CACHE_READ_TOKENS_KEY].asUsageLongOrNull()
        val output = usage[JSON_OUTPUT_TOKENS_KEY].asUsageLongOrNull()
        val outputDetails = usage[JSON_OUTPUT_TOKEN_DETAILS_KEY] as? JsonObject
        return providerUsage(
            inputTokens = sumUsageTokens(directInput, cacheCreation, cacheRead),
            outputTokens = output,
            cachedInputTokens = cacheRead,
            reasoningTokens = outputDetails?.get(CLAUDE_THINKING_TOKENS_KEY).asUsageLongOrNull()
        )
    }

    internal fun extractClaudeUsage(response: JsonObject): ProviderUsage? =
        parseClaudeUsageObject(response[JSON_USAGE_KEY] as? JsonObject)

    internal fun extractClaudeStreamUsage(event: JsonObject): ProviderUsage? =
        when (event[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull) {
            CLAUDE_MESSAGE_START -> (event[STREAM_MESSAGE_KEY] as? JsonObject)
                ?.get(JSON_USAGE_KEY)
                ?.let { it as? JsonObject }
                ?.let(::parseClaudeUsageObject)
            CLAUDE_MESSAGE_DELTA -> parseClaudeUsageObject(event[JSON_USAGE_KEY] as? JsonObject)
            else -> null
        }

    internal fun extractOpenAiCompatibleUsage(response: JsonObject): ProviderUsage? {
        val usage = response[JSON_USAGE_KEY] as? JsonObject ?: return null
        val promptDetails = usage[JSON_PROMPT_TOKEN_DETAILS_KEY] as? JsonObject
        val completionDetails = usage[JSON_COMPLETION_TOKEN_DETAILS_KEY] as? JsonObject
        return providerUsage(
            inputTokens = usage[JSON_PROMPT_TOKENS_KEY].asUsageLongOrNull(),
            outputTokens = usage[JSON_COMPLETION_TOKENS_KEY].asUsageLongOrNull(),
            totalTokens = usage[JSON_TOTAL_TOKENS_KEY].asUsageLongOrNull(),
            cachedInputTokens = promptDetails?.get(JSON_CACHED_TOKENS_KEY).asUsageLongOrNull()
                ?: usage[DEEPSEEK_CACHE_HIT_TOKENS_KEY].asUsageLongOrNull(),
            reasoningTokens = completionDetails?.get(JSON_REASONING_TOKENS_KEY).asUsageLongOrNull(),
            costUsd = usage[JSON_COST_KEY].asDoubleOrNull()
        )
    }

    internal fun buildOpenAiCompatibleRequestPayload(
        provider: AiProvider,
        model: String,
        messages: JsonArray,
        stream: Boolean
    ): JsonObject = OpenAiCompatibleProtocol.buildRequestPayload(provider, model, messages, stream)

    // --- Google Gemini REST API ---
    private suspend fun callGeminiApi(
        prompt: String,
        model: String,
        apiKey: String,
        systemInstruction: String?,
        conversationHistory: List<ModelChatMessage>
    ): GeminiGenerationResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val contentsArray = buildGeminiContents(prompt, conversationHistory, model, systemInstruction)

        val requestPayload = buildJsonObject {
            put("contents", contentsArray)
            if (!systemInstruction.isNullOrBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray(JSON_PARTS_KEY) {
                        addJsonObject { put(JSON_TEXT_KEY, systemInstruction) }
                    }
                }
            }
        }

        val body = requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        val responseBody = executeCancellableJson(request, "Empty response from Gemini server")
        val parsed = json.parseToJsonElement(responseBody).jsonObject
        val content = extractGeminiReplayContent(parsed)
        return GeminiGenerationResult(
            text = extractGeminiStreamText(parsed)
                ?: "Received empty content response from Gemini.",
            replayState = content?.let { encodeGeminiReplayState(listOf(it)) },
            usage = extractGeminiUsage(parsed)
        )
    }

    private suspend fun callGeminiToolApi(
        prompt: String,
        model: String,
        apiKey: String,
        systemInstruction: String?,
        conversationHistory: List<ModelChatMessage>,
        tools: List<NativeToolDefinition>,
        executeTool: suspend (NativeToolCall) -> NativeToolResult,
    ): GeminiGenerationResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val contents = buildGeminiContents(
            prompt = prompt,
            conversationHistory = conversationHistory,
            modelName = model,
            systemInstruction = systemInstruction,
        ).toMutableList()
        val toolDefinitions = buildGeminiToolDefinitions(tools)
        var usage: ProviderUsage? = null

        repeat(MAX_NATIVE_TOOL_ROUNDS) { round ->
            val requestPayload = buildJsonObject {
                put("contents", JsonArray(contents))
                put("tools", toolDefinitions)
                if (!systemInstruction.isNullOrBlank()) {
                    putJsonObject("systemInstruction") {
                        putJsonArray(JSON_PARTS_KEY) {
                            addJsonObject { put(JSON_TEXT_KEY, systemInstruction) }
                        }
                    }
                }
            }
            val request = Request.Builder()
                .url(url)
                .post(requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()
            val responseBody = executeCancellableJson(request, "Empty response from Gemini server")
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            usage = mergeProviderUsage(usage, extractGeminiUsage(parsed))
            val modelContent = extractGeminiReplayContent(parsed)
                ?: throw IOException("Gemini response is missing model content")
            val calls = parseGeminiToolCalls(modelContent, round)
            if (calls.isEmpty()) {
                return GeminiGenerationResult(
                    text = extractGeminiStreamText(parsed)
                        ?: "Received empty content response from Gemini.",
                    replayState = encodeGeminiReplayState(listOf(modelContent)),
                    usage = usage,
                )
            }
            if (round == MAX_NATIVE_TOOL_ROUNDS - 1) {
                throw IOException("Gemini exceeded the local tool-call round limit")
            }

            contents += modelContent
            val results = executeNativeToolBatch(calls, tools, executeTool)
            contents += buildGeminiToolResultContent(calls, results, json)
        }
        throw IOException("Gemini tool-call loop ended unexpectedly")
    }

    // --- OpenAI Responses API ---
    private suspend fun callOpenAiApi(
        prompt: String,
        model: String,
        apiKey: String,
        systemInstruction: String?,
        conversationHistory: List<ModelChatMessage>,
        apiProcessingMode: NativeApiProcessingMode,
    ): OpenAiGenerationResult {
        val url = "https://api.openai.com/v1/responses"
        val input = buildOpenAiResponseInput(
            prompt = prompt,
            conversationHistory = conversationHistory,
            systemInstruction = systemInstruction,
            modelName = model
        )
        val requestPayload = buildOpenAiRequestPayload(
            model = model,
            stream = false,
            systemInstruction = systemInstruction,
            input = input,
            apiProcessingMode = apiProcessingMode,
        )

        val body = requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader(HEADER_AUTHORIZATION, bearerToken(apiKey))
            .addHeader(HEADER_CONTENT_TYPE, JSON_MEDIA_TYPE)
            .post(body)
            .build()

        val responseBody = executeCancellableJson(request, "Empty response from OpenAI server")
        val parsed = json.parseToJsonElement(responseBody).jsonObject
        ensureOpenAiBufferedResponseCompleted(parsed)
        return OpenAiGenerationResult(
            text = extractOpenAiResponseText(parsed)
                ?: "Received empty message content from OpenAI.",
            replayState = extractOpenAiReplayState(parsed),
            usage = extractOpenAiUsage(parsed)
        )
    }

    private suspend fun callOpenAiToolApi(
        prompt: String,
        model: String,
        apiKey: String,
        systemInstruction: String?,
        conversationHistory: List<ModelChatMessage>,
        apiProcessingMode: NativeApiProcessingMode,
        tools: List<NativeToolDefinition>,
        executeTool: suspend (NativeToolCall) -> NativeToolResult,
    ): OpenAiGenerationResult {
        val url = "https://api.openai.com/v1/responses"
        val input = buildOpenAiResponseInput(
            prompt = prompt,
            conversationHistory = conversationHistory,
            systemInstruction = systemInstruction,
            modelName = model,
        ).toMutableList()
        val replayItems = mutableListOf<JsonObject>()
        val toolDefinitions = buildOpenAiToolDefinitions(tools)
        var usage: ProviderUsage? = null

        repeat(MAX_NATIVE_TOOL_ROUNDS) { round ->
            val basePayload = buildOpenAiRequestPayload(
                model = model,
                stream = false,
                systemInstruction = systemInstruction,
                input = JsonArray(input),
                apiProcessingMode = apiProcessingMode,
            )
            val requestPayload = JsonObject(basePayload + ("tools" to toolDefinitions))
            val request = Request.Builder()
                .url(url)
                .addHeader(HEADER_AUTHORIZATION, bearerToken(apiKey))
                .addHeader(HEADER_CONTENT_TYPE, JSON_MEDIA_TYPE)
                .post(requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()

            val responseBody = executeCancellableJson(request, "Empty response from OpenAI server")
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            ensureOpenAiBufferedResponseCompleted(parsed)
            usage = mergeProviderUsage(usage, extractOpenAiUsage(parsed))
            val output = (parsed[JSON_OUTPUT_KEY] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?: throw IOException("OpenAI response is missing output")
            replayItems += output

            val calls = parseOpenAiToolCalls(parsed, json)
            if (calls.isEmpty()) {
                return OpenAiGenerationResult(
                    text = extractOpenAiResponseText(parsed)
                        ?: "Received empty message content from OpenAI.",
                    replayState = encodeOpenAiReplayState(replayItems),
                    usage = usage,
                )
            }
            if (round == MAX_NATIVE_TOOL_ROUNDS - 1) {
                throw IOException("OpenAI exceeded the local tool-call round limit")
            }

            input += output
            val results = executeNativeToolBatch(calls, tools, executeTool)
            val resultItems = buildOpenAiToolResultItems(results)
                .mapNotNull { it as? JsonObject }
            input += resultItems
            replayItems += resultItems
        }
        throw IOException("OpenAI tool-call loop ended unexpectedly")
    }

    internal fun parseClaudeModelMaxTokens(rawJson: String): Int? = runCatching {
        json.parseToJsonElement(rawJson).jsonObject[JSON_MAX_TOKENS_KEY]?.jsonPrimitive?.intOrNull
    }.getOrNull()?.takeIf { it > 0 }

    internal fun buildClaudeModelMetadataRequest(
        model: String,
        apiKey: String
    ): Request = Request.Builder()
        .url(CLAUDE_MODELS_API_URL.toHttpUrl().newBuilder().addPathSegment(model).build())
        .addHeader(HEADER_ANTHROPIC_API_KEY, apiKey)
        .addHeader(HEADER_ANTHROPIC_VERSION, ANTHROPIC_API_VERSION)
        .get()
        .build()

    private fun claudeMetadataCacheKey(model: String, apiKey: String): ClaudeMetadataCacheKey {
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(apiKey.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return ClaudeMetadataCacheKey(model, fingerprint)
    }

    internal fun parseClaudeModelId(rawJson: String): String? = runCatching {
        json.parseToJsonElement(rawJson).jsonObject[JSON_MODEL_ID_KEY]
            ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

    internal fun readClaudeMetadataCache(
        model: String,
        apiKey: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ClaudeRuntimeMetadata? {
        val cacheKey = claudeMetadataCacheKey(model, apiKey)
        while (true) {
            val entry = claudeMetadataByModelAndCredential[cacheKey] ?: return null
            if (entry.expiresAtMillis == null || nowMillis < entry.expiresAtMillis) {
                return entry.metadata
            }
            if (claudeMetadataByModelAndCredential.remove(cacheKey, entry)) return null
        }
    }

    internal fun rememberClaudeMetadata(
        requestedModel: String,
        resolvedModel: String,
        apiKey: String,
        reportedMaxTokens: Int?,
        reportedReasoning: ClaudeReasoningCapabilities?,
        nowMillis: Long = System.currentTimeMillis()
    ): ClaudeRuntimeMetadata {
        val metadata = ClaudeRuntimeMetadata(
            maxTokens = reportedMaxTokens?.takeIf { it > 0 } ?: CLAUDE_MAX_TOKENS_COMPAT_FALLBACK,
            reasoningCapabilities = reportedReasoning ?: ClaudeReasoningCapabilities(),
            resolvedModel = resolvedModel
        )
        val metadataIncomplete = reportedMaxTokens == null || reportedReasoning == null
        val concreteExpiry = if (metadataIncomplete) nowMillis + CLAUDE_METADATA_FAILURE_TTL_MILLIS else null
        claudeMetadataByModelAndCredential[claudeMetadataCacheKey(resolvedModel, apiKey)] =
            ClaudeMetadataCacheEntry(metadata, concreteExpiry)

        val requestedExpiry = when {
            metadataIncomplete -> nowMillis + CLAUDE_METADATA_FAILURE_TTL_MILLIS
            requestedModel != resolvedModel -> nowMillis + CLAUDE_ALIAS_CACHE_TTL_MILLIS
            else -> null
        }
        claudeMetadataByModelAndCredential[claudeMetadataCacheKey(requestedModel, apiKey)] =
            ClaudeMetadataCacheEntry(metadata, requestedExpiry)
        return metadata
    }

    internal fun rememberClaudeMetadataFailure(
        model: String,
        apiKey: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ClaudeRuntimeMetadata {
        val metadata = ClaudeRuntimeMetadata(
            maxTokens = CLAUDE_MAX_TOKENS_COMPAT_FALLBACK,
            reasoningCapabilities = ClaudeReasoningCapabilities(),
            resolvedModel = model
        )
        claudeMetadataByModelAndCredential[claudeMetadataCacheKey(model, apiKey)] =
            ClaudeMetadataCacheEntry(metadata, nowMillis + CLAUDE_METADATA_FAILURE_TTL_MILLIS)
        return metadata
    }

    private fun invalidateClaudeMetadata(apiKey: String, vararg models: String) {
        models.distinct().forEach { model ->
            claudeMetadataByModelAndCredential.remove(claudeMetadataCacheKey(model, apiKey))
        }
    }

    private suspend fun resolveClaudeMetadata(model: String, apiKey: String): ClaudeRuntimeMetadata {
        readClaudeMetadataCache(model, apiKey)?.let { return it }
        claudeMetadataMutex.lock()
        try {
            readClaudeMetadataCache(model, apiKey)?.let { return it }
            return try {
                val request = buildClaudeModelMetadataRequest(model, apiKey)
                val responseBody = executeCancellableJson(
                    request,
                    httpErrorContext = "Anthropic model metadata",
                    client = claudeMetadataHttpClient
                )
                val resolvedModel = parseClaudeModelId(responseBody) ?: model
                rememberClaudeMetadata(
                    requestedModel = model,
                    resolvedModel = resolvedModel,
                    apiKey = apiKey,
                    reportedMaxTokens = parseClaudeModelMaxTokens(responseBody),
                    reportedReasoning = parseClaudeReasoningCapabilities(responseBody)
                )
            } catch (_: IOException) {
                rememberClaudeMetadataFailure(model, apiKey)
            }
        } finally {
            claudeMetadataMutex.unlock()
        }
    }

    internal fun buildClaudeRequestPayload(
        model: String,
        maxTokens: Int,
        stream: Boolean,
        systemInstruction: String?,
        messages: JsonArray,
        reasoningCapabilities: ClaudeReasoningCapabilities
    ): JsonObject = buildJsonObject {
        put(JSON_MODEL_KEY, model)
        put(JSON_MAX_TOKENS_KEY, maxTokens)
        if (stream) put(JSON_STREAM_KEY, true)
        if (!systemInstruction.isNullOrBlank()) put(JSON_SYSTEM_KEY, systemInstruction)
        when {
            reasoningCapabilities.supportsAdaptive -> {
                putJsonObject(JSON_THINKING_KEY) {
                    put(STREAM_TYPE_KEY, JSON_ADAPTIVE_KEY)
                }
                if (reasoningCapabilities.supportsHighEffort) {
                    putJsonObject(JSON_OUTPUT_CONFIG_KEY) {
                        put(JSON_EFFORT_KEY, JSON_HIGH_KEY)
                    }
                }
            }
            reasoningCapabilities.supportsEnabled -> resolveClaudeThinkingBudget(maxTokens)?.let { budget ->
                putJsonObject(JSON_THINKING_KEY) {
                    put(STREAM_TYPE_KEY, JSON_ENABLED_KEY)
                    put(JSON_BUDGET_TOKENS_KEY, budget)
                }
            }
        }
        put(JSON_MESSAGES_KEY, messages)
    }

    // --- Anthropic Claude REST API ---
    private suspend fun callClaudeApi(
        prompt: String,
        model: String,
        apiKey: String,
        systemInstruction: String?,
        conversationHistory: List<ModelChatMessage>
    ): ClaudeGenerationResult {
        val metadata = resolveClaudeMetadata(model, apiKey)
        val messagesArray = buildClaudeMessages(
            prompt, conversationHistory, systemInstruction, metadata.resolvedModel
        )
        val requestPayload = buildClaudeRequestPayload(
            metadata.resolvedModel, metadata.maxTokens, stream = false, systemInstruction, messagesArray,
            metadata.reasoningCapabilities
        )

        val body = requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType())
        val request = Request.Builder()
            .url(CLAUDE_MESSAGES_API_URL)
            .addHeader(HEADER_ANTHROPIC_API_KEY, apiKey)
            .addHeader(HEADER_ANTHROPIC_VERSION, ANTHROPIC_API_VERSION)
            .addHeader(HEADER_CONTENT_TYPE_LOWER, JSON_MEDIA_TYPE)
            .post(body)
            .build()

        val responseBody = executeCancellableJson(request, "Empty response from Anthropic server")
        val parsed = json.parseToJsonElement(responseBody).jsonObject
        val resolvedModel = extractClaudeResponseModel(parsed) ?: metadata.resolvedModel
        val isPartial = isClaudePartialStopReason(extractClaudeStopReason(parsed))
        if (resolvedModel != metadata.resolvedModel) {
            invalidateClaudeMetadata(apiKey, model, metadata.resolvedModel)
        }
        return ClaudeGenerationResult(
            text = extractClaudeResponseText(parsed) ?: "Received empty content block from Claude.",
            replayState = if (isPartial) null else extractClaudeReplayState(parsed),
            resolvedModel = resolvedModel,
            isPartial = isPartial,
            usage = extractClaudeUsage(parsed)
        )
    }

    private suspend fun callClaudeToolApi(
        prompt: String,
        model: String,
        apiKey: String,
        systemInstruction: String?,
        conversationHistory: List<ModelChatMessage>,
        tools: List<NativeToolDefinition>,
        executeTool: suspend (NativeToolCall) -> NativeToolResult,
    ): ClaudeGenerationResult {
        val metadata = resolveClaudeMetadata(model, apiKey)
        val messages = buildClaudeMessages(
            prompt,
            conversationHistory,
            systemInstruction,
            metadata.resolvedModel,
        ).toMutableList()
        val toolDefinitions = buildClaudeToolDefinitions(tools)
        var usage: ProviderUsage? = null
        var resolvedModel = metadata.resolvedModel

        repeat(MAX_NATIVE_TOOL_ROUNDS) { round ->
            val basePayload = buildClaudeRequestPayload(
                model = resolvedModel,
                maxTokens = metadata.maxTokens,
                stream = false,
                systemInstruction = systemInstruction,
                messages = JsonArray(messages),
                reasoningCapabilities = metadata.reasoningCapabilities,
            )
            val requestPayload = JsonObject(basePayload + ("tools" to toolDefinitions))
            val request = Request.Builder()
                .url(CLAUDE_MESSAGES_API_URL)
                .addHeader(HEADER_ANTHROPIC_API_KEY, apiKey)
                .addHeader(HEADER_ANTHROPIC_VERSION, ANTHROPIC_API_VERSION)
                .addHeader(HEADER_CONTENT_TYPE_LOWER, JSON_MEDIA_TYPE)
                .post(requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()

            val responseBody = executeCancellableJson(request, "Empty response from Anthropic server")
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            val responseModel = extractClaudeResponseModel(parsed) ?: resolvedModel
            if (responseModel != resolvedModel) {
                invalidateClaudeMetadata(apiKey, model, resolvedModel)
                resolvedModel = responseModel
            }
            usage = mergeProviderUsage(usage, extractClaudeUsage(parsed))
            val stopReason = extractClaudeStopReason(parsed)
            val isPartial = isClaudePartialStopReason(stopReason)
            val calls = parseClaudeToolCalls(parsed)

            if (calls.isEmpty()) {
                return ClaudeGenerationResult(
                    text = extractClaudeResponseText(parsed)
                        ?: "Received empty content block from Claude.",
                    replayState = if (isPartial) null else extractClaudeReplayState(parsed),
                    resolvedModel = resolvedModel,
                    isPartial = isPartial,
                    usage = usage,
                )
            }
            if (isPartial) {
                throw IOException("Claude stopped before completing requested tool calls")
            }
            if (stopReason != "tool_use") {
                throw IOException("Claude returned tool_use blocks without tool_use stop reason")
            }
            if (round == MAX_NATIVE_TOOL_ROUNDS - 1) {
                throw IOException("Claude exceeded the local tool-call round limit")
            }

            val assistantContent = parsed[JSON_CONTENT_KEY] as? JsonArray
                ?: throw IOException("Claude tool response is missing content")
            messages += buildJsonObject {
                put(JSON_ROLE_KEY, CHAT_ROLE_ASSISTANT)
                put(JSON_CONTENT_KEY, assistantContent)
            }
            val results = executeNativeToolBatch(calls, tools, executeTool)
            messages += buildJsonObject {
                put(JSON_ROLE_KEY, CHAT_ROLE_USER)
                put(JSON_CONTENT_KEY, buildClaudeToolResultContent(results))
            }
        }
        throw IOException("Claude tool-call loop ended unexpectedly")
    }

    private fun JsonObject.hasClaudeStringField(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.isString == true

    private fun isSupportedClaudeReplayBlockStart(block: JsonObject): Boolean =
        when ((block[STREAM_TYPE_KEY] as? JsonPrimitive)?.contentOrNull) {
            JSON_TEXT_KEY -> block.hasClaudeStringField(JSON_TEXT_KEY)
            CLAUDE_THINKING_BLOCK -> block.hasClaudeStringField(JSON_THINKING_KEY)
            CLAUDE_REDACTED_THINKING_BLOCK -> block.hasClaudeStringField(JSON_DATA_KEY)
            else -> false
        }

    private fun isValidClaudeReplayBlock(block: JsonObject): Boolean =
        when ((block[STREAM_TYPE_KEY] as? JsonPrimitive)?.contentOrNull) {
            JSON_TEXT_KEY -> block.hasClaudeStringField(JSON_TEXT_KEY)
            CLAUDE_THINKING_BLOCK ->
                block.hasClaudeStringField(JSON_THINKING_KEY) && block.hasClaudeStringField(JSON_SIGNATURE_KEY)
            CLAUDE_REDACTED_THINKING_BLOCK -> block.hasClaudeStringField(JSON_DATA_KEY)
            else -> false
        }

    internal fun parseClaudeReplayState(state: String?): List<JsonObject> {
        if (state.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = json.parseToJsonElement(state) as? JsonArray
                ?: return@runCatching emptyList()
            val blocks = array.mapNotNull { it as? JsonObject }
            if (blocks.size != array.size || blocks.isEmpty() || blocks.any { !isValidClaudeReplayBlock(it) }) {
                emptyList()
            } else {
                blocks
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeClaudeReplayState(blocks: List<JsonObject>): String? =
        blocks.takeIf { it.isNotEmpty() && it.all(::isValidClaudeReplayBlock) }
            ?.let { JsonArray(it).toString() }

    internal fun extractClaudeReplayState(response: JsonObject): String? {
        val content = response[JSON_CONTENT_KEY] as? JsonArray ?: return null
        val blocks = content.mapNotNull { it as? JsonObject }
        if (blocks.size != content.size) return null
        return encodeClaudeReplayState(blocks)
    }

    internal fun extractClaudeResponseModel(response: JsonObject): String? =
        response[JSON_MODEL_KEY]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    internal fun extractClaudeStopReason(response: JsonObject): String? =
        (response[JSON_STOP_REASON_KEY] as? JsonPrimitive)?.contentOrNull

    internal fun extractClaudeStreamStopReason(event: JsonObject): String? {
        if (event[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull != CLAUDE_MESSAGE_DELTA) return null
        return ((event[JSON_DELTA_KEY] as? JsonObject)?.get(JSON_STOP_REASON_KEY) as? JsonPrimitive)
            ?.contentOrNull
    }

    internal fun isClaudePartialStopReason(stopReason: String?): Boolean =
        stopReason == CLAUDE_STOP_MAX_TOKENS || stopReason == CLAUDE_STOP_CONTEXT_WINDOW_EXCEEDED

    internal fun extractClaudeStreamResolvedModel(event: JsonObject): String? = when (
        event[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull
    ) {
        CLAUDE_MESSAGE_START -> (event[STREAM_MESSAGE_KEY] as? JsonObject)
            ?.get(JSON_MODEL_KEY)?.jsonPrimitive?.contentOrNull
        CLAUDE_CONTENT_BLOCK_START -> (event[JSON_CONTENT_BLOCK_KEY] as? JsonObject)
            ?.takeIf { it[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull == CLAUDE_FALLBACK_BLOCK }
            ?.get(JSON_TO_KEY)?.jsonObject
            ?.get(JSON_MODEL_KEY)?.jsonPrimitive?.contentOrNull
        else -> null
    }?.takeIf { it.isNotBlank() }

    internal fun extractClaudeResponseText(response: JsonObject): String? =
        (response[JSON_CONTENT_KEY] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull == JSON_TEXT_KEY }
            .mapNotNull { it[JSON_TEXT_KEY]?.jsonPrimitive?.contentOrNull }
            .joinToString(separator = "")
            .takeIf { it.isNotEmpty() }

    internal fun applyClaudeReplayEvent(
        blocks: MutableMap<Int, JsonObject>,
        event: JsonObject
    ) {
        val index = event[JSON_INDEX_KEY]?.jsonPrimitive?.intOrNull ?: return
        when (event[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull) {
            CLAUDE_CONTENT_BLOCK_START -> {
                val block = event[JSON_CONTENT_BLOCK_KEY] as? JsonObject ?: return
                blocks[index] = if (isSupportedClaudeReplayBlockStart(block)) {
                    block
                } else {
                    buildJsonObject { put(STREAM_TYPE_KEY, CLAUDE_UNREPLAYABLE_BLOCK) }
                }
            }
            CLAUDE_CONTENT_BLOCK_DELTA -> {
                val delta = event[JSON_DELTA_KEY] as? JsonObject ?: return
                val field = when (delta[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull) {
                    CLAUDE_TEXT_DELTA -> JSON_TEXT_KEY
                    CLAUDE_THINKING_DELTA -> JSON_THINKING_KEY
                    CLAUDE_SIGNATURE_DELTA -> JSON_SIGNATURE_KEY
                    else -> return
                }
                val fragment = delta[field]?.jsonPrimitive?.contentOrNull ?: return
                val block = blocks[index] ?: return
                val existing = block[field]?.jsonPrimitive?.contentOrNull.orEmpty()
                blocks[index] = JsonObject(block + (field to JsonPrimitive(existing + fragment)))
            }
        }
    }

    internal fun encodeClaudeStreamReplayState(blocks: Map<Int, JsonObject>): String? =
        encodeClaudeReplayState(blocks.toSortedMap().values.toList())

    private fun isValidGeminiReplayContent(content: JsonObject): Boolean {
        val role = (content[JSON_ROLE_KEY] as? JsonPrimitive)?.contentOrNull
        val parts = content[JSON_PARTS_KEY] as? JsonArray ?: return false
        return role == JSON_MODEL_KEY && parts.isNotEmpty() && parts.all { part ->
            part is JsonObject && part.isNotEmpty()
        }
    }

    internal fun extractGeminiReplayContent(event: JsonObject): JsonObject? {
        val candidate = (event[JSON_CANDIDATES_KEY] as? JsonArray)
            ?.firstOrNull() as? JsonObject ?: return null
        val content = candidate[JSON_CONTENT_KEY] as? JsonObject ?: return null
        return content.takeIf(::isValidGeminiReplayContent)
    }

    internal fun mergeGeminiReplayContents(contents: List<JsonObject>): JsonObject? {
        if (contents.isEmpty() || contents.any { !isValidGeminiReplayContent(it) }) return null
        val parts = contents.flatMap { content ->
            (content[JSON_PARTS_KEY] as JsonArray).toList()
        }
        return buildJsonObject {
            put(JSON_ROLE_KEY, JSON_MODEL_KEY)
            put(JSON_PARTS_KEY, JsonArray(parts))
        }
    }

    private fun encodeGeminiReplayState(contents: List<JsonObject>): String? =
        mergeGeminiReplayContents(contents)?.let { JsonArray(listOf(it)).toString() }

    private fun parseGeminiReplayState(state: String?): List<JsonObject> {
        if (state.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = json.parseToJsonElement(state) as? JsonArray
                ?: return@runCatching emptyList()
            val contents = array.mapNotNull { it as? JsonObject }
            if (contents.size != array.size) {
                emptyList()
            } else {
                mergeGeminiReplayContents(contents)?.let(::listOf).orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    internal fun extractGeminiStreamText(event: JsonObject): String? =
        event[JSON_CANDIDATES_KEY]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get(JSON_CONTENT_KEY)?.jsonObject
            ?.get(JSON_PARTS_KEY)?.jsonArray
            .orEmpty()
            .mapNotNull { it.jsonObject[JSON_TEXT_KEY]?.jsonPrimitive?.contentOrNull }
            .joinToString(separator = "")
            .takeIf { it.isNotEmpty() }

    private fun isValidOpenAiReplayItem(item: JsonObject): Boolean =
        (item[STREAM_TYPE_KEY] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.isNotBlank() == true

    internal fun parseOpenAiReplayState(state: String?): List<JsonObject> {
        if (state.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = json.parseToJsonElement(state) as? JsonArray
                ?: return@runCatching emptyList()
            val items = array.mapNotNull { it as? JsonObject }
            if (items.size != array.size || items.isEmpty() || items.any { !isValidOpenAiReplayItem(it) }) {
                emptyList()
            } else {
                items
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeOpenAiReplayState(items: List<JsonObject>): String? =
        items.takeIf { it.isNotEmpty() && it.all(::isValidOpenAiReplayItem) }
            ?.let { JsonArray(it).toString() }

    internal fun extractOpenAiReplayState(response: JsonObject): String? {
        val output = response[JSON_OUTPUT_KEY] as? JsonArray ?: return null
        val items = output.mapNotNull { it as? JsonObject }
        if (items.size != output.size) return null
        return encodeOpenAiReplayState(items)
    }

    internal fun extractOpenAiCompletedReplayState(event: JsonObject): String? =
        if (event[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull == OPENAI_RESPONSE_COMPLETED) {
            (event[STREAM_RESPONSE_KEY] as? JsonObject)?.let(::extractOpenAiReplayState)
        } else {
            null
        }

    internal fun ensureOpenAiBufferedResponseCompleted(response: JsonObject) {
        when (response[JSON_STATUS_KEY]?.jsonPrimitive?.contentOrNull) {
            OPENAI_STATUS_FAILED -> {
                val message = (response[STREAM_ERROR_KEY] as? JsonObject)
                    ?.get(STREAM_MESSAGE_KEY)?.jsonPrimitive?.contentOrNull
                    ?: "OpenAI response failed"
                throw IOException(message)
            }
            OPENAI_STATUS_INCOMPLETE -> {
                val reason = (response[OPENAI_INCOMPLETE_DETAILS_KEY] as? JsonObject)
                    ?.get(OPENAI_INCOMPLETE_REASON_KEY)?.jsonPrimitive?.contentOrNull
                throw IOException(
                    reason?.let { "OpenAI response incomplete: $it" }
                        ?: "OpenAI response incomplete"
                )
            }
        }
    }

    internal fun extractOpenAiResponseText(response: JsonObject): String? =
        (response[JSON_OUTPUT_KEY] as? JsonArray).orEmpty().asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { it[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull == STREAM_MESSAGE_KEY }
            .flatMap { message -> (message[JSON_CONTENT_KEY] as? JsonArray).orEmpty().asSequence() }
            .mapNotNull { it as? JsonObject }
            .filter { it[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull == OPENAI_OUTPUT_TEXT }
            .mapNotNull { it[JSON_TEXT_KEY]?.jsonPrimitive?.contentOrNull }
            .joinToString(separator = "")
            .takeIf { it.isNotEmpty() }

    internal fun extractOpenAiStreamText(event: JsonObject): String? =
        if (event[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull == OPENAI_OUTPUT_TEXT_DELTA) {
            event[JSON_DELTA_KEY]?.jsonPrimitive?.contentOrNull
        } else {
            null
        }

    internal fun extractClaudeStreamText(event: JsonObject): String? =
        if (event[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull == CLAUDE_CONTENT_BLOCK_DELTA) {
            event[JSON_DELTA_KEY]?.jsonObject
                ?.takeIf { it[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull == CLAUDE_TEXT_DELTA }
                ?.get(JSON_TEXT_KEY)?.jsonPrimitive?.contentOrNull
        } else {
            null
        }

    internal fun extractOpenAiCompatibleStreamText(event: JsonObject): String? =
        OpenAiCompatibleProtocol.extractStreamText(event)

    internal fun extractOpenAiCompatibleModel(event: JsonObject): String? =
        OpenAiCompatibleProtocol.extractModel(event)

    private suspend fun callGeminiStreamApi(
        prompt: String,
        model: String,
        apiKey: String,
        systemInstruction: String?,
        conversationHistory: List<ModelChatMessage>,
        onTextDelta: (String) -> Unit
    ): GeminiGenerationResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
        val requestPayload = buildJsonObject {
            put("contents", buildGeminiContents(prompt, conversationHistory, model, systemInstruction))
            if (!systemInstruction.isNullOrBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray(JSON_PARTS_KEY) {
                        addJsonObject { put(JSON_TEXT_KEY, systemInstruction) }
                    }
                }
            }
        }
        val request = Request.Builder()
            .url(url)
            .post(requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
        val replayContents = mutableListOf<JsonObject>()
        var usage: ProviderUsage? = null
        val text = executeSse(
            request = request,
            extractText = ::extractGeminiStreamText,
            isComplete = ::isGeminiStreamComplete,
            onTextDelta = onTextDelta,
            onEvent = { event ->
                extractGeminiReplayContent(event)?.let(replayContents::add)
                usage = mergeProviderUsage(usage, extractGeminiUsage(event))
            }
        )
        return GeminiGenerationResult(
            text = text.ifEmpty { "Received empty content response from Gemini." },
            replayState = encodeGeminiReplayState(replayContents),
            usage = usage
        )
    }

    private suspend fun callOpenAiStreamApi(
        prompt: String,
        model: String,
        apiKey: String,
        systemInstruction: String?,
        conversationHistory: List<ModelChatMessage>,
        apiProcessingMode: NativeApiProcessingMode,
        onTextDelta: (String) -> Unit
    ): OpenAiGenerationResult {
        val input = buildOpenAiResponseInput(
            prompt = prompt,
            conversationHistory = conversationHistory,
            systemInstruction = systemInstruction,
            modelName = model
        )
        val requestPayload = buildOpenAiRequestPayload(
            model = model,
            stream = true,
            systemInstruction = systemInstruction,
            input = input,
            apiProcessingMode = apiProcessingMode,
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .addHeader(HEADER_AUTHORIZATION, bearerToken(apiKey))
            .addHeader(HEADER_CONTENT_TYPE, JSON_MEDIA_TYPE)
            .post(requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
        var replayState: String? = null
        var usage: ProviderUsage? = null
        val text = executeSse(
            request = request,
            extractText = ::extractOpenAiStreamText,
            isComplete = ::isOpenAiStreamComplete,
            onTextDelta = onTextDelta,
            onEvent = { event ->
                extractOpenAiCompletedReplayState(event)?.let { replayState = it }
                usage = mergeProviderUsage(usage, extractOpenAiCompletedUsage(event))
            }
        )
        return OpenAiGenerationResult(
            text = text.ifEmpty { "Received empty message content from OpenAI." },
            replayState = replayState,
            usage = usage
        )
    }

    private suspend fun callClaudeStreamApi(
        prompt: String,
        model: String,
        apiKey: String,
        systemInstruction: String?,
        conversationHistory: List<ModelChatMessage>,
        onTextDelta: (String) -> Unit
    ): ClaudeGenerationResult {
        val metadata = resolveClaudeMetadata(model, apiKey)
        val requestPayload = buildClaudeRequestPayload(
            metadata.resolvedModel, metadata.maxTokens, stream = true, systemInstruction,
            buildClaudeMessages(prompt, conversationHistory, systemInstruction, metadata.resolvedModel),
            metadata.reasoningCapabilities
        )
        val request = Request.Builder()
            .url(CLAUDE_MESSAGES_API_URL)
            .addHeader(HEADER_ANTHROPIC_API_KEY, apiKey)
            .addHeader(HEADER_ANTHROPIC_VERSION, ANTHROPIC_API_VERSION)
            .addHeader(HEADER_CONTENT_TYPE_LOWER, JSON_MEDIA_TYPE)
            .post(requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
        val replayBlocks = mutableMapOf<Int, JsonObject>()
        var resolvedModel = metadata.resolvedModel
        var stopReason: String? = null
        var usage: ProviderUsage? = null
        val text = executeSse(
            request,
            ::extractClaudeStreamText,
            ::isClaudeStreamComplete,
            onTextDelta,
            onEvent = { event ->
                extractClaudeStreamResolvedModel(event)?.let { resolvedModel = it }
                extractClaudeStreamStopReason(event)?.let { stopReason = it }
                usage = mergeProviderUsage(usage, extractClaudeStreamUsage(event))
                applyClaudeReplayEvent(replayBlocks, event)
            }
        )
        val isPartial = isClaudePartialStopReason(stopReason)
        if (resolvedModel != metadata.resolvedModel) {
            invalidateClaudeMetadata(apiKey, model, metadata.resolvedModel)
        }
        return ClaudeGenerationResult(
            text = text.ifEmpty { "Received empty content block from Claude." },
            replayState = if (isPartial) null else encodeClaudeStreamReplayState(replayBlocks),
            resolvedModel = resolvedModel,
            isPartial = isPartial,
            usage = usage
        )
    }

    internal fun extractStreamError(event: JsonObject): String? =
        extractGeminiStreamError(event)
            ?: extractTypedStreamError(event)
            ?: event.takeIf { it.containsKey(STREAM_ERROR_KEY) }?.let(::extractGenericStreamError)

    private fun extractGeminiStreamError(event: JsonObject): String? {
        val candidate = event[JSON_CANDIDATES_KEY]?.jsonArray?.firstOrNull() as? JsonObject
        val finishReason = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull ?: return null
        if (finishReason == "STOP") return null
        val finishMessage = candidate["finishMessage"]?.jsonPrimitive?.contentOrNull
        return buildString {
            append("Gemini stopped with ").append(finishReason)
            if (!finishMessage.isNullOrBlank()) append(": ").append(finishMessage)
        }
    }

    private fun extractTypedStreamError(event: JsonObject): String? {
        return when (event[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull) {
            OPENAI_RESPONSE_FAILED -> event[STREAM_RESPONSE_KEY]?.jsonObject
                ?.get(STREAM_ERROR_KEY)?.jsonObject
                ?.get(STREAM_MESSAGE_KEY)?.jsonPrimitive?.contentOrNull
                ?: "OpenAI response failed"
            OPENAI_RESPONSE_INCOMPLETE -> event[STREAM_RESPONSE_KEY]?.jsonObject
                ?.get(OPENAI_INCOMPLETE_DETAILS_KEY)?.jsonObject
                ?.get(OPENAI_INCOMPLETE_REASON_KEY)?.jsonPrimitive?.contentOrNull
                ?.let { "OpenAI response incomplete: $it" }
                ?: "OpenAI response incomplete"
            STREAM_ERROR_KEY -> extractGenericStreamError(event)
            else -> null
        }
    }

    private fun extractGenericStreamError(event: JsonObject): String? {
        return when (val error = event[STREAM_ERROR_KEY]) {
            is JsonObject -> error[STREAM_MESSAGE_KEY]?.jsonPrimitive?.contentOrNull ?: error.toString()
            is JsonPrimitive -> error.contentOrNull
            else -> event[STREAM_MESSAGE_KEY]?.jsonPrimitive?.contentOrNull ?: "Streaming API error"
        }
    }

    internal fun isGeminiStreamComplete(event: JsonObject): Boolean =
        event[JSON_CANDIDATES_KEY]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("finishReason")?.jsonPrimitive?.contentOrNull == "STOP"

    internal fun isOpenAiStreamComplete(event: JsonObject): Boolean =
        event[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull == OPENAI_RESPONSE_COMPLETED

    internal fun isOpenAiCompatibleStreamComplete(event: JsonObject): Boolean =
        OpenAiCompatibleProtocol.isStreamComplete(event)

    internal fun isClaudeStreamComplete(event: JsonObject): Boolean =
        event[STREAM_TYPE_KEY]?.jsonPrimitive?.contentOrNull == CLAUDE_MESSAGE_STOP

    private suspend fun executeSse(
        request: Request,
        extractText: (JsonObject) -> String?,
        isComplete: (JsonObject) -> Boolean,
        onTextDelta: (String) -> Unit,
        completeOnDoneSentinel: Boolean = false,
        onEvent: ((JsonObject) -> Unit)? = null
    ): String = suspendCancellableCoroutine { continuation ->
        val call = streamingHttpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                completeSseContinuation(
                    continuation, response, extractText, isComplete, onTextDelta,
                    completeOnDoneSentinel, onEvent
                )
            }
        })
    }

    private fun completeSseContinuation(
        continuation: CancellableContinuation<String>,
        response: Response,
        extractText: (JsonObject) -> String?,
        isComplete: (JsonObject) -> Boolean,
        onTextDelta: (String) -> Unit,
        completeOnDoneSentinel: Boolean,
        onEvent: ((JsonObject) -> Unit)?
    ) {
        val result = runCatching {
            readSseResponse(
                response, extractText, isComplete, onTextDelta,
                completeOnDoneSentinel, onEvent
            )
        }
        if (!continuation.isActive) return

        val failure = result.exceptionOrNull()
        when {
            failure == null -> continuation.resume(result.getOrThrow())
            failure is CancellationException -> continuation.cancel(failure)
            failure is IOException -> continuation.resumeWithException(failure)
            else -> continuation.resumeWithException(IOException("Streaming callback failed", failure))
        }
    }

    internal fun readSseResponse(
        response: Response,
        extractText: (JsonObject) -> String?,
        isComplete: (JsonObject) -> Boolean,
        onTextDelta: (String) -> Unit,
        completeOnDoneSentinel: Boolean = false,
        onEvent: ((JsonObject) -> Unit)? = null
    ): String {
        response.use {
            ensureSuccessfulStreamingResponse(response)
            val source = response.body?.source() ?: throw IOException("Empty streaming response body")
            return consumeSseSource(
                source, extractText, isComplete, onTextDelta,
                completeOnDoneSentinel, onEvent
            )
        }
    }

    private fun ensureSuccessfulStreamingResponse(response: Response) {
        if (response.isSuccessful) return
        val responseBody = response.body?.string().orEmpty()
        throw IOException(parseErrorMessage(responseBody) ?: "HTTP ${response.code}: ${response.message}")
    }

    private fun consumeSseSource(
        source: BufferedSource,
        extractText: (JsonObject) -> String?,
        isComplete: (JsonObject) -> Boolean,
        onTextDelta: (String) -> Unit,
        completeOnDoneSentinel: Boolean,
        onEvent: ((JsonObject) -> Unit)?
    ): String {
        val collected = StringBuilder()
        val dataLines = mutableListOf<String>()
        var completed = false
        var stopped = false

        fun dispatchEvent() {
            if (dataLines.isEmpty()) return
            val payload = dataLines.joinToString("\n")
            dataLines.clear()
            if (payload == SSE_DONE) {
                if (completeOnDoneSentinel) completed = true
                stopped = true
                return
            }
            val event = parseSseEvent(payload) ?: return
            onEvent?.invoke(event)
            val (delta, eventComplete) = decodeSseEvent(event, extractText, isComplete)
            appendStreamingDelta(collected, delta, onTextDelta)
            if (eventComplete) {
                completed = true
                stopped = true
            }
        }

        while (!source.exhausted() && !stopped) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> dispatchEvent()
                line == "data" -> dataLines += ""
                line.startsWith(SSE_DATA_PREFIX) -> {
                    val value = line.removePrefix(SSE_DATA_PREFIX).removePrefix(" ")
                    dataLines += value
                }
            }
        }
        if (!stopped && dataLines.isNotEmpty()) dispatchEvent()
        if (!completed) throw IOException("Streaming response ended before completion")
        return collected.toString()
    }

    private fun decodeSseEvent(
        event: JsonObject,
        extractText: (JsonObject) -> String?,
        isComplete: (JsonObject) -> Boolean
    ): Pair<String?, Boolean> {
        return try {
            extractStreamError(event)?.let { throw IOException(it) }
            extractText(event) to isComplete(event)
        } catch (e: IllegalArgumentException) {
            throw IOException(MALFORMED_STREAM_EVENT, e)
        }
    }

    private fun appendStreamingDelta(
        collected: StringBuilder,
        delta: String?,
        onTextDelta: (String) -> Unit
    ) {
        val text = delta?.takeIf { it.isNotEmpty() } ?: return
        collected.append(text)
        val failure = runCatching { onTextDelta(text) }.exceptionOrNull()
        when (failure) {
            null -> Unit
            is CancellationException -> throw failure
            else -> throw IOException("Streaming text callback failed", failure)
        }
    }

    private fun parseSseEvent(payload: String): JsonObject? {
        if (payload.isBlank() || payload == SSE_DONE) return null
        val element = try {
            json.parseToJsonElement(payload)
        } catch (e: SerializationException) {
            throw IOException(MALFORMED_STREAM_EVENT, e)
        }
        return element as? JsonObject ?: throw IOException(MALFORMED_STREAM_EVENT)
    }

    private suspend fun executeCancellableJson(
        request: Request,
        emptyResponseMessage: String = "Empty response from server",
        httpErrorContext: String? = null,
        client: OkHttpClient = httpClient
    ): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val responseBody = response.body?.string() ?: throw IOException(emptyResponseMessage)
                        if (!response.isSuccessful) {
                            val fallbackError = httpErrorContext?.let { context ->
                                "$context HTTP ${response.code}: ${responseBody.take(160)}"
                            } ?: "HTTP ${response.code}: ${response.message}"
                            throw IOException(parseErrorMessage(responseBody) ?: fallbackError)
                        }
                        if (continuation.isActive) continuation.resume(responseBody)
                    }
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
        }

    // --- OpenAI-compatible provider/gateway boundary ---
    private suspend fun callOpenAiCompatibleStreamApi(
        config: OpenAiCompatibleProviderConfig, prompt: String, model: String, apiKey: String,
        systemInstruction: String?, conversationHistory: List<ModelChatMessage>,
        provider: AiProvider, onTextDelta: (String) -> Unit,
        onResolvedModel: (String) -> Unit = {}
    ): OpenAiCompatibleGenerationResult {
        val requestPayload = buildOpenAiCompatibleRequestPayload(
            provider = provider,
            model = model,
            messages = buildOpenAiCompatibleMessages(
                prompt, systemInstruction, conversationHistory, provider
            ),
            stream = true
        )
        val requestBuilder = Request.Builder().url(config.endpointUrl)
            .addHeader(HEADER_AUTHORIZATION, bearerToken(apiKey))
            .addHeader(HEADER_CONTENT_TYPE, JSON_MEDIA_TYPE)
        config.extraHeaders.forEach { (name, value) -> requestBuilder.addHeader(name, value) }
        val request = requestBuilder.post(
            requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType())
        ).build()

        var usage: ProviderUsage? = null
        val waitForDoneSentinel = provider == AiProvider.OPENROUTER
        val text = executeSse(
            request,
            extractText = ::extractOpenAiCompatibleStreamText,
            isComplete = if (waitForDoneSentinel) { { false } } else ::isOpenAiCompatibleStreamComplete,
            onTextDelta = onTextDelta,
            completeOnDoneSentinel = true,
            onEvent = { event ->
                extractOpenAiCompatibleModel(event)?.let(onResolvedModel)
                usage = mergeProviderUsage(usage, extractOpenAiCompatibleUsage(event))
            }
        ).ifEmpty { "Received empty message content." }
        return OpenAiCompatibleGenerationResult(text = text, usage = usage)
    }

    private suspend fun callOpenAiCompatibleApi(
        config: OpenAiCompatibleProviderConfig,
        prompt: String,
        model: String,
        apiKey: String,
        systemInstruction: String?,
        conversationHistory: List<ModelChatMessage>,
        provider: AiProvider,
        onResolvedModel: (String) -> Unit = {}
    ): OpenAiCompatibleGenerationResult {
        val messagesArray = buildOpenAiCompatibleMessages(
            prompt = prompt,
            systemInstruction = systemInstruction,
            conversationHistory = conversationHistory,
            provider = provider
        )

        val requestPayload = buildOpenAiCompatibleRequestPayload(
            provider = provider,
            model = model,
            messages = messagesArray,
            stream = false
        )

        val body = requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType())
        val requestBuilder = Request.Builder()
            .url(config.endpointUrl)
            .addHeader(HEADER_AUTHORIZATION, bearerToken(apiKey))
            .addHeader(HEADER_CONTENT_TYPE, JSON_MEDIA_TYPE)
        config.extraHeaders.forEach { (name, value) -> requestBuilder.addHeader(name, value) }
        val request = requestBuilder.post(body).build()

        val responseBody = executeCancellableJson(request)
        val parsed = json.parseToJsonElement(responseBody).jsonObject
        extractOpenAiCompatibleModel(parsed)?.let(onResolvedModel)
        val choices = parsed[JSON_CHOICES_KEY]?.jsonArray
        val firstChoice = choices?.getOrNull(0)?.jsonObject
        val message = firstChoice?.get(STREAM_MESSAGE_KEY)?.jsonObject
        val content = message?.get(JSON_CONTENT_KEY)?.jsonPrimitive?.contentOrNull

        return OpenAiCompatibleGenerationResult(
            text = content ?: "Received empty message content.",
            usage = extractOpenAiCompatibleUsage(parsed)
        )
    }

    internal fun buildOpenAiCompatibleMessages(
        prompt: String,
        systemInstruction: String?,
        conversationHistory: List<ModelChatMessage>,
        provider: AiProvider
    ): JsonArray = OpenAiCompatibleProtocol.buildMessages(
        prompt, systemInstruction, conversationHistory, provider
    )

    private fun parseErrorMessage(rawJson: String): String? {
        return try {
            val obj = json.parseToJsonElement(rawJson).jsonObject
            when {
                obj.containsKey(STREAM_ERROR_KEY) -> {
                    val err = obj[STREAM_ERROR_KEY]
                    if (err is JsonObject) {
                        err[STREAM_MESSAGE_KEY]?.jsonPrimitive?.contentOrNull ?: err.toString()
                    } else {
                        err?.jsonPrimitive?.contentOrNull
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // --- High Fidelity Persona & Style Adaptation Simulator ---
    private fun generatePersonaResponse(
        prompt: String,
        provider: AiProvider,
        model: String,
        profile: Profile?
    ): String {
        val baseVoice = profile?.personality?.base ?: "friendly"
        val conciseLvl = profile?.personality?.modifiers?.get("concise") ?: 0
        val technicalLvl = profile?.personality?.modifiers?.get("technical") ?: 0
        val cynicalLvl = profile?.personality?.modifiers?.get("cynical") ?: 0
        val lowerPrompt = prompt.lowercase()

        // Distinguish provider traits while respecting active style profile
        return when (provider) {
            AiProvider.GEMINI -> {
                when {
                    lowerPrompt.contains("compare") || lowerPrompt.contains("difference") -> {
                        """
                        ### 🔮 Google Gemini ($model) Analysis
                        
                        **Multimodal & Context Strengths:**
                        1. **Long-context reasoning**: Designed for large documents, codebases, and multi-step research tasks.
                        2. **Native multimodality**: Handles text, image, audio, video, and code in one model family.
                        3. **Structured outputs**: Supports schemas, tool calling, and agentic workflows.
                        
                        *Style Profile applied: Voice '$baseVoice', Initiative '${profile?.collaboration?.initiative ?: "balanced"}'.*
                        """.trimIndent()
                    }
                    lowerPrompt.contains("code") || lowerPrompt.contains("function") || lowerPrompt.contains("kotlin") -> {
                        """
                        ### 🔮 Google Gemini Solution ($model)
                        
                        Here is the idiomatic implementation utilizing modern coroutines and Compose state:
                        
                        ```kotlin
                        // Modern asynchronous pipeline
                        suspend fun processPrompt(query: String): Result<String> = withContext(Dispatchers.IO) {
                            runCatching {
                                GeminiClient.generateContent(query)
                            }
                        }
                        ```
                        
                        **Key architectural advantages:**
                        - Non-blocking I/O execution on background dispatchers.
                        - Preserves active personality constraints (`$baseVoice`).
                        """.trimIndent()
                    }
                    else -> {
                        if (conciseLvl >= 2) {
                            "**Gemini ($model)**: Directly addressing '$prompt'. In summary: verified input parameters, structured context bounds, and executed inference with 0-shot precision."
                        } else {
                            "Hello from **Google Gemini** ($model)! Analyzing your request regarding '$prompt':\n\nGemini models are tuned for high-velocity synthesis, multi-step problem solving, and contextual reasoning. Let me know if you would like me to drill into technical implementation details or expand with concrete code snippets."
                        }
                    }
                }
            }

            AiProvider.CHATGPT -> {
                when {
                    lowerPrompt.contains("compare") || lowerPrompt.contains("difference") -> {
                        """
                        ### 🟢 OpenAI ($model) Perspective
                        
                        Here is how the OpenAI model approaches conversational intelligence:
                        
                        - **Instruction following**: Strong adherence to explicit system and user constraints.
                        - **Reasoning and coding**: Designed for multi-step technical and creative work.
                        - **Tool integration**: Modern API workflows can combine tools, files, and structured outputs.
                        
                        *Active Profile Voice: $baseVoice (concise level: $conciseLvl, technical level: $technicalLvl).*
                        """.trimIndent()
                    }
                    lowerPrompt.contains("code") || lowerPrompt.contains("function") || lowerPrompt.contains("kotlin") -> {
                        """
                        ### 🟢 OpenAI Code Solution ($model)
                        
                        Here is a clean and modular solution:
                        
                        ```kotlin
                        data class ChatState(
                            val messages: List<ChatMessage> = emptyList(),
                            val isTyping: Boolean = false
                        )
                        ```
                        
                        **Notes:**
                        1. Encapsulates message state cleanly for unidirectional data flow.
                        2. Easily testable in unit test suites.
                        """.trimIndent()
                    }
                    else -> {
                        if (cynicalLvl >= 2) {
                            "**OpenAI ($model)**: Regarding '$prompt': Let's skip the corporate marketing speak. If your premise lacks sound architecture, no prompt engineering will save it. Fix the core invariants first."
                        } else if (conciseLvl >= 2) {
                            "**OpenAI ($model)**: Understood. Action items for '$prompt':\n1. Define schema contracts\n2. Stream token chunks\n3. Render message bubbles."
                        } else {
                            "Hi! I'm the **OpenAI** model $model. Regarding '$prompt':\n\nI can help you build, refine, and debug your ideas. I adapt to your chosen tone ('$baseVoice') while keeping solutions practical and conversational. What would you like to explore next?"
                        }
                    }
                }
            }

            AiProvider.CLAUDE -> {
                when {
                    lowerPrompt.contains("compare") || lowerPrompt.contains("difference") -> {
                        """
                        ### 🟣 Anthropic Claude ($model) Perspective
                        
                        When examining architectural differences between LLM providers:
                        
                        1. **Nuance and artifact quality**: Strong long-form writing and structured output.
                        2. **Agentic workflows**: Current Claude models are designed for coding, tools, and sustained tasks.
                        3. **Complex code synthesis**: Handles cross-module dependencies and edge-case analysis.
                        
                        *System Policy: Tone calibrated to '$baseVoice'.*
                        """.trimIndent()
                    }
                    lowerPrompt.contains("code") || lowerPrompt.contains("function") || lowerPrompt.contains("kotlin") -> {
                        """
                        ### 🟣 Anthropic Claude Code Formulation ($model)
                        
                        Let's structure this cleanly with proper error boundaries:
                        
                        ```kotlin
                        sealed interface ChatEvent {
                            data class MessageSent(val text: String) : ChatEvent
                            data class ProviderChanged(val provider: AiProvider) : ChatEvent
                            object Cleared : ChatEvent
                        }
                        ```
                        
                        This algebraic data type structure ensures complete compile-time pattern matching in your Compose `when` expressions.
                        """.trimIndent()
                    }
                    else -> {
                        if (conciseLvl >= 2) {
                            "**Claude ($model)**: Direct response to '$prompt': Core principle is rigorous composability. We apply semantic overlays sequentially without mutating baseline contracts."
                        } else {
                            "Greetings! I'm **Claude** ($model) by Anthropic. In response to '$prompt':\n\nI strive to provide thoughtful, articulate, and well-reasoned answers, aligned with your current profile ($baseVoice style, verification set to ${profile?.collaboration?.verification ?: "balanced"}). Let me know how I can best support your work."
                        }
                    }
                }
            }

            AiProvider.DEEPSEEK -> {
                when {
                    lowerPrompt.contains("compare") || lowerPrompt.contains("difference") -> {
                        """
                        ### 🔷 DeepSeek ($model) Analysis
                        
                        **Key Architecture & Strengths:**
                        1. **Thinking and non-thinking modes**: Current V4 models support both interaction styles.
                        2. **Long context**: Built for large coding, research, and reasoning workloads.
                        3. **API compatibility**: Supports OpenAI-compatible and Anthropic-compatible interfaces.
                        
                        *Style Profile applied: Voice '$baseVoice'.*
                        """.trimIndent()
                    }
                    lowerPrompt.contains("code") || lowerPrompt.contains("function") || lowerPrompt.contains("kotlin") -> {
                        """
                        ### 🔷 DeepSeek Code Solution ($model)
                        
                        Here is an optimized implementation focusing on runtime efficiency:
                        
                        ```kotlin
                        inline fun <T, R> Sequence<T>.concurrentMap(
                            crossinline transform: suspend (T) -> R
                        ): Flow<R> = flow {
                            coroutineScope {
                                map { item -> async { transform(item) } }
                                    .toList()
                                    .awaitAll()
                                    .forEach { emit(it) }
                            }
                        }
                        ```
                        
                        **Analysis:**
                        - Preserves backpressure while maintaining bounded concurrency.
                        - Adheres to active personality guidelines (`$baseVoice`).
                        """.trimIndent()
                    }
                    else -> {
                        if (conciseLvl >= 2) {
                            "**DeepSeek ($model)**: '$prompt': Breakdown:\n1. Formulate problem space\n2. Minimize computational complexity\n3. Execute with verified test cases."
                        } else {
                            "Hello! I am **DeepSeek** ($model). In addressing '$prompt':\n\nI specialize in deep logical reasoning, coding, and mathematical analysis. I am configured with your active profile ($baseVoice tone, ${profile?.collaboration?.initiative ?: "balanced"} initiative). How can I assist with your development or research today?"
                        }
                    }
                }
            }

            AiProvider.KIMI -> {
                when {
                    lowerPrompt.contains("compare") || lowerPrompt.contains("difference") -> {
                        """
                        ### ⚡ Moonshot Kimi ($model) Analysis
                        
                        **Key Strengths:**
                        1. **Long-context work**: Designed for large documents, repositories, and sustained conversations.
                        2. **Multimodal and agent capabilities**: Current Kimi models support modern tool and media workflows.
                        3. **Coding specialization**: Dedicated code-focused models complement the general Kimi family.
                        
                        *Style Profile applied: Voice '$baseVoice'.*
                        """.trimIndent()
                    }
                    lowerPrompt.contains("code") || lowerPrompt.contains("function") || lowerPrompt.contains("kotlin") -> {
                        """
                        ### ⚡ Kimi Code Generation ($model)
                        
                        Here is the clean, production-ready implementation:
                        
                        ```kotlin
                        class DocumentProcessor(private val maxChunkSize: Int = 8192) {
                            fun chunkText(content: String): List<String> {
                                return content.chunked(maxChunkSize)
                            }
                        }
                        ```
                        
                        **Features:**
                        - Handles long context streams gracefully.
                        - Follows '$baseVoice' style constraints.
                        """.trimIndent()
                    }
                    else -> {
                        if (conciseLvl >= 2) {
                            "**Kimi ($model)**: Response to '$prompt': Processing long-context input with precision. Ready to ingest and summarize comprehensive documentation."
                        } else {
                            "Hello! I am **Kimi** ($model) from Moonshot AI. Regarding '$prompt':\n\nI can read and analyze large documents, codebases, and conversations, customized to your '$baseVoice' personality profile. What would you like to process?"
                        }
                    }
                }
            }

            AiProvider.OPENROUTER -> {
                "**OpenRouter ($model)**: Free-router simulation for '$prompt'. Add an OpenRouter key to send this through the live `openrouter/free` gateway while keeping it outside the default multi-provider compare."
            }

            AiProvider.AIHUBMIX -> {
                "**AIHubMix ($model)**: Free-gateway simulation for '$prompt'. Add an AIHubMix key to use a live subsidized `-free` model while keeping gateway traffic outside the default multi-provider compare."
            }

            AiProvider.VERCEL -> {
                "**Vercel AI Gateway ($model)**: Gateway simulation for '$prompt'. Add a Vercel AI Gateway key to use the live model catalog while keeping gateway traffic outside the default multi-provider compare."
            }

            AiProvider.ALL -> "Multi-provider dispatch."
        }
    }
}

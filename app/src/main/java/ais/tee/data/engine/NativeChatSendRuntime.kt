package ais.tee.data.engine

import ais.tee.data.model.AiProvider
import ais.tee.data.model.ApiProcessingMode
import ais.tee.data.model.ApiKeyConfig
import ais.tee.data.model.ClientToolCallingStrategy
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NativeToolCall
import ais.tee.data.model.NativeToolDefinition
import ais.tee.data.model.NativeToolResult
import ais.tee.data.model.NativeToolReceipt
import ais.tee.data.model.normalizeApiProcessingMode
import ais.tee.data.model.runtimeCapabilities
import ais.tee.data.model.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal data class NativeToolExecution(
    val result: NativeToolResult,
    val receipt: NativeToolReceipt,
)

internal data class NativeChatSendRequest(
    val prompt: String,
    val targetProvider: AiProvider,
    val providersToRun: List<AiProvider>,
    val selectedModel: String,
    val apiProcessingMode: ApiProcessingMode = ApiProcessingMode.AUTO,
    val apiKeys: ApiKeyConfig,
    val systemInstruction: String?,
    val profile: Profile?,
    val conversationHistory: List<ModelChatMessage>,
    val allowSingleProviderSimulationFallback: Boolean,
    val tools: List<NativeToolDefinition> = emptyList(),
    val executeTool: (suspend (NativeToolCall) -> NativeToolExecution)? = null,
    /** Chat's ChatGPT plan binding; ChatGPT then runs only on that plan, never on the API key. */
    val chatGptPlanClientId: String? = null,
) {
    init {
        require(tools.isEmpty() || executeTool != null) {
            "A tool executor is required when native tools are offered"
        }
        require(tools.map { it.name }.distinct().size == tools.size) {
            "Native tool names must be unique"
        }
    }
}

internal data class NativeChatGeneratedResponse(
    val provider: AiProvider,
    val model: String,
    val message: ModelChatMessage,
    val toolReceipts: List<NativeToolReceipt> = emptyList(),
)

internal suspend fun executeNativeChatSend(
    request: NativeChatSendRequest,
    aiChatService: AiChatService,
    onTextDelta: (provider: AiProvider, model: String, delta: String) -> Unit = { _, _, _ -> },
    onToolReceipt: (provider: AiProvider, model: String, receipt: NativeToolReceipt) -> Unit = { _, _, _ -> },
    onResponse: (NativeChatGeneratedResponse) -> Unit = {},
    chatGptPlanTokens: ChatGptPlanTokens? = null,
): List<NativeChatGeneratedResponse> = coroutineScope {
    suspend fun runProvider(provider: AiProvider): NativeChatGeneratedResponse {
        val model = if (request.targetProvider == provider && provider != AiProvider.ALL) {
            request.selectedModel.ifBlank { provider.defaultModel }
        } else {
            provider.defaultModel
        }
        val planClientId = request.chatGptPlanClientId?.takeIf { provider == AiProvider.CHATGPT }
        val planAuth = planClientId?.let { clientId ->
            try {
                checkNotNull(chatGptPlanTokens) { "No ChatGPT plan token source" }.auth(clientId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Bound to a plan: report it, never retry on the API key or simulate.
                val reason = (error as? ChatGptPlanUnavailable)?.reason ?: ChatGptPlanUnavailable.Reason.TEMPORARY
                return NativeChatGeneratedResponse(
                    provider = provider,
                    model = model,
                    message = ModelChatMessage(
                        id = "msg_${System.currentTimeMillis()}_${provider.id}",
                        sender = "assistant",
                        provider = provider,
                        modelName = model,
                        text = "⚠️ ${chatGptPlanUnavailableText(reason)}",
                        isError = true,
                    ),
                ).also(onResponse)
            }
        }
        // Plan route: client tools need the namespaced format, not wired yet; reply without them.
        val providerTools = if (planAuth != null) emptyList() else when (provider.runtimeCapabilities().clientToolCallingStrategy) {
            ClientToolCallingStrategy.GEMINI_FUNCTIONS,
            ClientToolCallingStrategy.OPENAI_RESPONSES_FUNCTIONS,
            ClientToolCallingStrategy.ANTHROPIC_CLIENT_TOOLS -> request.tools
            ClientToolCallingStrategy.NONE,
            ClientToolCallingStrategy.PER_PROVIDER -> emptyList()
        }
        val processingMode = provider.normalizeApiProcessingMode(request.apiProcessingMode)
        val providerReceipts = mutableListOf<NativeToolReceipt>()
        val providerToolExecutor = request.executeTool?.let { execute ->
            suspend { call: NativeToolCall ->
                val execution = execute(call)
                providerReceipts += execution.receipt
                onToolReceipt(provider, model, execution.receipt)
                execution.result
            }
        }
        val response = aiChatService.generateResponse(
            prompt = request.prompt,
            provider = provider,
            modelName = model,
            apiProcessingMode = processingMode,
            apiKeys = request.apiKeys,
            systemInstruction = request.systemInstruction,
            profile = request.profile,
            conversationHistory = request.conversationHistory,
            allowSimulationFallback =
                request.allowSingleProviderSimulationFallback && request.targetProvider != AiProvider.ALL,
            onTextDelta = { delta -> onTextDelta(provider, model, delta) },
            tools = providerTools,
            executeTool = providerToolExecutor,
            chatGptPlan = planAuth,
        )
        return NativeChatGeneratedResponse(
            provider = provider,
            model = model,
            message = response,
            toolReceipts = providerReceipts.toList(),
        ).also(onResponse)
    }

    if (request.targetProvider == AiProvider.ALL) {
        request.providersToRun.map { provider -> async { runProvider(provider) } }.awaitAll()
    } else {
        listOf(runProvider(request.targetProvider))
    }
}

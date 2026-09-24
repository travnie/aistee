package ais.tee.data.engine

import ais.tee.data.model.AiProvider
import ais.tee.data.model.ApiKeyConfig
import ais.tee.data.model.ClientToolCallingStrategy
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NativeApiMode
import ais.tee.data.model.NativeToolCall
import ais.tee.data.model.NativeToolDefinition
import ais.tee.data.model.NativeToolResult
import ais.tee.data.model.runtimeCapabilities
import ais.tee.data.model.sanitizeNativeApiMode
import ais.tee.data.model.Profile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal data class NativeChatSendRequest(
    val prompt: String,
    val targetProvider: AiProvider,
    val providersToRun: List<AiProvider>,
    val selectedModel: String,
    val apiMode: NativeApiMode = NativeApiMode.AUTO,
    val apiKeys: ApiKeyConfig,
    val systemInstruction: String?,
    val profile: Profile?,
    val conversationHistory: List<ModelChatMessage>,
    val allowSingleProviderSimulationFallback: Boolean,
    val tools: List<NativeToolDefinition> = emptyList(),
    val executeTool: (suspend (NativeToolCall) -> NativeToolResult)? = null,
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
)

internal suspend fun executeNativeChatSend(
    request: NativeChatSendRequest,
    aiChatService: AiChatService,
    onTextDelta: (provider: AiProvider, model: String, delta: String) -> Unit = { _, _, _ -> },
    onResponse: (NativeChatGeneratedResponse) -> Unit = {},
): List<NativeChatGeneratedResponse> = coroutineScope {
    suspend fun runProvider(provider: AiProvider): NativeChatGeneratedResponse {
        val model = if (request.targetProvider == provider && provider != AiProvider.ALL) {
            request.selectedModel.ifBlank { provider.defaultModel }
        } else {
            provider.defaultModel
        }
        val providerTools = when (provider.runtimeCapabilities().clientToolCallingStrategy) {
            ClientToolCallingStrategy.GEMINI_FUNCTIONS,
            ClientToolCallingStrategy.OPENAI_RESPONSES_FUNCTIONS,
            ClientToolCallingStrategy.ANTHROPIC_CLIENT_TOOLS -> request.tools
            ClientToolCallingStrategy.NONE,
            ClientToolCallingStrategy.PER_PROVIDER -> emptyList()
        }
        val response = aiChatService.generateResponse(
            prompt = request.prompt,
            provider = provider,
            modelName = model,
            apiKeys = request.apiKeys,
            systemInstruction = request.systemInstruction,
            profile = request.profile,
            conversationHistory = request.conversationHistory,
            allowSimulationFallback =
                request.allowSingleProviderSimulationFallback && request.targetProvider != AiProvider.ALL,
            onTextDelta = { delta -> onTextDelta(provider, model, delta) },
            tools = providerTools,
            executeTool = request.executeTool,
            apiMode = provider.sanitizeNativeApiMode(request.apiMode),
        )
        return NativeChatGeneratedResponse(provider, model, response).also(onResponse)
    }

    if (request.targetProvider == AiProvider.ALL) {
        request.providersToRun.map { provider -> async { runProvider(provider) } }.awaitAll()
    } else {
        listOf(runProvider(request.targetProvider))
    }
}

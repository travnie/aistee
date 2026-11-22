package ais.tee.data.engine

import ais.tee.data.model.AiProvider
import ais.tee.data.model.ApiKeyConfig
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.buildBoundedProviderTextTurns
import ais.tee.data.model.customEndpointBaseUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class OpenAiCompatibleProviderConfig(
    val endpointUrl: String,
    val modelCatalogUrl: String? = null,
    val extraHeaders: Map<String, String> = emptyMap()
)

object OpenAiCompatibleProtocol {
    private val providerConfigs = mapOf(
        AiProvider.DEEPSEEK to OpenAiCompatibleProviderConfig(
            endpointUrl = "https://api.deepseek.com/chat/completions"
        ),
        AiProvider.KIMI to OpenAiCompatibleProviderConfig(
            endpointUrl = "https://api.moonshot.ai/v1/chat/completions"
        ),
        AiProvider.MISTRAL to OpenAiCompatibleProviderConfig(
            endpointUrl = "https://api.mistral.ai/v1/chat/completions",
            modelCatalogUrl = "https://api.mistral.ai/v1/models"
        ),
        AiProvider.OPENROUTER to OpenAiCompatibleProviderConfig(
            endpointUrl = "https://openrouter.ai/api/v1/chat/completions",
            modelCatalogUrl = "https://openrouter.ai/api/v1/models?output_modalities=text",
            extraHeaders = mapOf(
                "HTTP-Referer" to "https://github.com/travnie/aistee",
                "X-Title" to "Aistee"
            )
        ),
        AiProvider.AIHUBMIX to OpenAiCompatibleProviderConfig(
            endpointUrl = "https://aihubmix.com/v1/chat/completions",
            modelCatalogUrl = "https://aihubmix.com/api/v1/models?type=llm"
        ),
        AiProvider.VERCEL to OpenAiCompatibleProviderConfig(
            endpointUrl = "https://ai-gateway.vercel.sh/v1/chat/completions",
            modelCatalogUrl = "https://ai-gateway.vercel.sh/v1/models"
        )
    )

    fun configFor(provider: AiProvider): OpenAiCompatibleProviderConfig? = providerConfigs[provider]

    /** As [configFor], plus the user's own endpoint, which exists only in [apiKeys]. */
    fun configFor(provider: AiProvider, apiKeys: ApiKeyConfig): OpenAiCompatibleProviderConfig? =
        if (provider == AiProvider.CUSTOM) {
            apiKeys.customEndpointBaseUrl()?.let { baseUrl ->
                OpenAiCompatibleProviderConfig(
                    endpointUrl = "$baseUrl/chat/completions",
                    modelCatalogUrl = "$baseUrl/models"
                )
            }
        } else {
            configFor(provider)
        }

    fun buildMessages(
        prompt: String,
        systemInstruction: String?,
        conversationHistory: List<ModelChatMessage>,
        provider: AiProvider
    ): JsonArray = buildJsonArray {
        if (!systemInstruction.isNullOrBlank()) {
            addJsonObject {
                put("role", "system")
                put("content", systemInstruction)
            }
        }

        buildBoundedProviderTextTurns(
            prompt = prompt,
            conversationHistory = conversationHistory,
            provider = provider,
            systemInstruction = systemInstruction
        ).forEach { turn ->
            addJsonObject {
                put("role", turn.role)
                put("content", turn.text)
            }
        }
    }

    fun buildRequestPayload(
        provider: AiProvider,
        model: String,
        messages: JsonArray,
        stream: Boolean
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", messages)
        if (stream) put("stream", true)
        if (provider == AiProvider.OPENROUTER) {
            putJsonObject("usage") {
                put("include", true)
            }
        }
    }

    fun extractStreamText(event: JsonObject): String? =
        event["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("delta")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull

    fun extractModel(event: JsonObject): String? =
        (event["model"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun isStreamComplete(event: JsonObject): Boolean =
        event["choices"]?.jsonArray.orEmpty().any { choice ->
            choice.jsonObject["finish_reason"]?.jsonPrimitive?.contentOrNull != null
        }
}

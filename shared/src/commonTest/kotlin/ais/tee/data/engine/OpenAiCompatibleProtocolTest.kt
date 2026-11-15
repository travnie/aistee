package ais.tee.data.engine

import ais.tee.data.model.AiProvider
import ais.tee.data.model.ApiKeyConfig
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ModelChatMessage
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCompatibleProtocolTest {
    @Test
    fun exposesProviderSpecificEndpointsAndHeaders() {
        val deepSeek = requireNotNull(OpenAiCompatibleProtocol.configFor(AiProvider.DEEPSEEK))
        val openRouter = requireNotNull(OpenAiCompatibleProtocol.configFor(AiProvider.OPENROUTER))
        val vercel = requireNotNull(OpenAiCompatibleProtocol.configFor(AiProvider.VERCEL))

        assertEquals("https://api.deepseek.com/chat/completions", deepSeek.endpointUrl)
        assertEquals("https://openrouter.ai/api/v1/models?output_modalities=text", openRouter.modelCatalogUrl)
        assertEquals("https://github.com/travnie/aistee", openRouter.extraHeaders["HTTP-Referer"])
        assertEquals("https://ai-gateway.vercel.sh/v1/chat/completions", vercel.endpointUrl)
        assertEquals("https://ai-gateway.vercel.sh/v1/models", vercel.modelCatalogUrl)
        assertNull(OpenAiCompatibleProtocol.configFor(AiProvider.GEMINI))
    }

    @Test
    fun customEndpointConfigComesOnlyFromAValidatedBaseUrl() {
        val config = requireNotNull(
            OpenAiCompatibleProtocol.configFor(
                AiProvider.CUSTOM,
                ApiKeyConfig(customEndpointUrl = "https://llm.example.com/v1/")
            )
        )

        assertEquals("https://llm.example.com/v1/chat/completions", config.endpointUrl)
        assertEquals("https://llm.example.com/v1/models", config.modelCatalogUrl)
        assertTrue(config.extraHeaders.isEmpty())
        assertNull(OpenAiCompatibleProtocol.configFor(AiProvider.CUSTOM))
        assertNull(
            OpenAiCompatibleProtocol.configFor(
                AiProvider.CUSTOM,
                ApiKeyConfig(customEndpointUrl = "http://llm.example.com/v1")
            )
        )
        assertEquals(
            OpenAiCompatibleProtocol.configFor(AiProvider.VERCEL),
            OpenAiCompatibleProtocol.configFor(AiProvider.VERCEL, ApiKeyConfig())
        )
    }

    @Test
    fun buildsProviderScopedConversationMessages() {
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = "first"),
            ModelChatMessage(
                id = "or1",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.OPENROUTER,
                text = "router answer"
            ),
            ModelChatMessage(
                id = "ds1",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.DEEPSEEK,
                text = "deepseek answer"
            )
        )

        val messages = OpenAiCompatibleProtocol.buildMessages(
            prompt = "next",
            systemInstruction = "system",
            conversationHistory = history,
            provider = AiProvider.OPENROUTER
        )

        assertEquals(listOf("system", "first", "router answer", "next"), messages.map {
            it.jsonObject.getValue("content").jsonPrimitive.content
        })
    }

    @Test
    fun addsUsageRequestOnlyForOpenRouter() {
        val messages = buildJsonArray { }
        val openRouter = OpenAiCompatibleProtocol.buildRequestPayload(
            AiProvider.OPENROUTER, "openrouter/free", messages, stream = true
        )
        val deepSeek = OpenAiCompatibleProtocol.buildRequestPayload(
            AiProvider.DEEPSEEK, "deepseek-chat", messages, stream = false
        )

        assertTrue(openRouter.getValue("stream").jsonPrimitive.content.toBoolean())
        assertTrue("usage" in openRouter)
        assertFalse("usage" in deepSeek)
    }

    @Test
    fun decodesCompatibleStreamingMetadata() {
        val event = buildJsonObject {
            put("model", "anthropic/claude-sonnet-5")
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("delta", buildJsonObject { put("content", "hello") })
                    put("finish_reason", "stop")
                })
            })
        }

        assertEquals("hello", OpenAiCompatibleProtocol.extractStreamText(event))
        assertEquals("anthropic/claude-sonnet-5", OpenAiCompatibleProtocol.extractModel(event))
        assertTrue(OpenAiCompatibleProtocol.isStreamComplete(event))
    }
}

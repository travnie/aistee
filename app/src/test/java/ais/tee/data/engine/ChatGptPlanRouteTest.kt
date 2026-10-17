package ais.tee.data.engine

import ais.tee.data.model.AiProvider
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ChatGptPlanModel
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.ProviderExecutionSource
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptPlanRouteTest {
    private val service = AiChatService()
    private val auth = ChatGptPlanAuth("oaiapp_a", "access-secret")

    @Test
    fun accountPlanToolRoundsAccumulateReportedTokensWithoutGuessingMissingFields() {
        val first = ais.tee.data.model.ProviderUsage(
            inputTokens = 10, outputTokens = 5, totalTokens = 15,
            cachedInputTokens = 2, reasoningTokens = 1,
        )
        val second = ais.tee.data.model.ProviderUsage(
            inputTokens = 20, outputTokens = 8, totalTokens = 28,
            cachedInputTokens = 4, reasoningTokens = 3,
        )
        val result = service.sumCompletedPlanToolUsage(first, second)
        assertEquals(30L, result.inputTokens)
        assertEquals(13L, result.outputTokens)
        assertEquals(43L, result.totalTokens)
        assertEquals(6L, result.cachedInputTokens)
        assertEquals(4L, result.reasoningTokens)
        assertEquals(null, result.costUsd)
        val incomplete = service.sumCompletedPlanToolUsage(
            first, ais.tee.data.model.ProviderUsage(inputTokens = 1),
        )
        assertEquals(null, incomplete.outputTokens)
        assertEquals(null, incomplete.totalTokens)
    }

    @Test
    fun accountPlanPayloadCarriesOnlyPreviewSupportedFields() {
        val input = service.buildOpenAiResponseInput("hi", emptyList(), SYSTEM, MODEL)
        val payload = service.buildOpenAiRequestPayload(
            model = MODEL,
            stream = false,
            systemInstruction = SYSTEM,
            input = input,
            executionSource = ProviderExecutionSource.ACCOUNT_PLAN,
        )
        assertEquals(setOf("model", "input", "store", "include", "stream", "instructions"), payload.keys)
        assertEquals("false", payload.getValue("store").jsonPrimitive.content)
        assertEquals("true", payload.getValue("stream").jsonPrimitive.content)
        assertTrue(input.none { (it as? JsonObject)?.get("role")?.jsonPrimitive?.content == "system" })
    }

    @Test
    fun replayStateStaysScopedToItsRouteAndAccount() {
        val scope = service.chatGptPlanReplayScope("oaiapp_a")
        val scoped = """{"scope":"$scope","items":$ITEMS}"""
        fun inputFor(state: String, replayScope: String?) = service.buildOpenAiResponseInput(
            prompt = "next",
            conversationHistory = listOf(
                ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = "first"),
                ModelChatMessage(
                    id = "a1",
                    sender = CHAT_ROLE_ASSISTANT,
                    provider = AiProvider.CHATGPT,
                    modelName = MODEL,
                    text = "answer",
                    providerReplayState = state,
                ),
            ),
            modelName = MODEL,
            replayScope = replayScope,
        )

        assertEquals(4, inputFor(scoped, scope).size) // reasoning + message items replayed
        assertEquals(3, inputFor(scoped, service.chatGptPlanReplayScope("oaiapp_b")).size)
        assertEquals(3, inputFor(scoped, null).size) // API key route never replays plan state
        assertEquals(3, inputFor(ITEMS, scope).size) // plan route never replays API-key state
        assertEquals(
            "answer",
            inputFor(scoped, null)[1].jsonObject.getValue("content").jsonPrimitive.content,
        )
    }

    @Test
    fun modelCatalogKeepsListedModelsInServerOrder() {
        val models = service.parseChatGptPlanModels(
            """{"models":[
              {"slug":"gpt-b","display_name":"GPT B","visibility":"list"},
              {"slug":"hidden","display_name":"Hidden","visibility":"hide"},
              {"slug":"gpt-a","visibility":"list"},
              {"slug":"gpt-b","display_name":"dup","visibility":"list"},
              {"display_name":"no slug","visibility":"list"}
            ]}""",
        )
        assertEquals(listOf(ChatGptPlanModel("gpt-b", "GPT B"), ChatGptPlanModel("gpt-a", "gpt-a")), models)
        assertThrows(IOException::class.java) { service.parseChatGptPlanModels("""{"data":[]}""") }
    }

    @Test
    fun usageLimitErrorKeepsCodeAndLinksUsageSettings() {
        val error = streamError(
            429,
            """{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"Limit reached"}}""",
        )
        assertEquals(429, error.status)
        assertEquals("subscription_sharing_usage_limit_exceeded", error.code)
        assertEquals("Limit reached", error.message)
        assertTrue(describeChatGptPlanError(error).contains("https://chatgpt.com/settings/usage"))
    }

    @Test
    fun admissionDetailBodyIsShownWithSignInHint() {
        val error = streamError(401, """{"detail":"Missing direct permission"}""")
        assertEquals("Missing direct permission", error.message)
        assertEquals(null, error.code)
        val text = describeChatGptPlanError(error)
        assertTrue(text.contains("Sign in to the account again"))
        assertTrue(text.contains("Missing direct permission"))
    }

    @Test
    fun failedEventMidStreamCarriesItsCode() {
        val body = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hel\"}\n\n" +
            "data: {\"type\":\"response.failed\",\"response\":{\"error\":" +
            "{\"code\":\"subscription_sharing_usage_unavailable\",\"message\":\"Try later\"}}}\n\n"
        val error = assertThrows(ProviderApiException::class.java) {
            service.readSseResponse(
                response(200, body),
                service::extractOpenAiStreamText,
                service::isOpenAiStreamComplete,
                onTextDelta = {},
            )
        }
        assertEquals("subscription_sharing_usage_unavailable", error.code)
        assertTrue(describeChatGptPlanError(error).contains("temporarily unavailable"))
    }

    @Test
    fun boundPlanWithToolsStillFailsClosedWhenAccountIsUnavailable() = runBlocking {
        val responses = executeNativeChatSend(
            request = NativeChatSendRequest(
                prompt = "hi",
                targetProvider = AiProvider.CHATGPT,
                providersToRun = listOf(AiProvider.CHATGPT),
                selectedModel = MODEL,
                apiKeys = ais.tee.data.model.ApiKeyConfig(openAiKey = "sk-should-not-be-used"),
                systemInstruction = null,
                profile = null,
                conversationHistory = emptyList(),
                allowSingleProviderSimulationFallback = true,
                chatGptPlanClientId = "oaiapp_a",
                tools = listOf(
                ais.tee.data.model.NativeToolDefinition(
                    name = "noop",
                    description = "noop",
                    inputSchema = kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                    },
                ),
            ),
                executeTool = { error("must not run without plan account") },
            ),
            aiChatService = service,
            chatGptPlanTokens = { throw ChatGptPlanUnavailable(ChatGptPlanUnavailable.Reason.SIGNED_OUT) },
        )
        val reply = responses.single().message
        assertTrue(reply.isError)
        assertFalse(reply.isSimulated)
        assertTrue(reply.text.contains("signed out"))
    }

    @Test
    fun planAuthIsRejectedForOtherProviders() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.generateResponse(
                    prompt = "hi",
                    provider = AiProvider.CLAUDE,
                    modelName = "",
                    apiKeys = ais.tee.data.model.ApiKeyConfig(),
                    systemInstruction = null,
                    profile = null,
                    chatGptPlan = auth,
                )
            }
        }
        assertFalse(auth.toString().contains("access-secret"))
    }

    @Test
    fun routeNotSupportedIsNotReportedAsPolicyBlock() {
        val text = describeChatGptPlanError(
            ProviderApiException("Route not supported", 403, "subscription_sharing_route_not_supported"),
        )
        assertTrue(text.contains("endpoint or method"))
        assertFalse(text.contains("policy or region"))
    }

    @Test
    fun boundChatWithUnavailableAccountRepliesWithErrorNotApiKey() = runBlocking {
        val responses = executeNativeChatSend(
            request = NativeChatSendRequest(
                prompt = "hi",
                targetProvider = AiProvider.CHATGPT,
                providersToRun = listOf(AiProvider.CHATGPT),
                selectedModel = MODEL,
                apiKeys = ais.tee.data.model.ApiKeyConfig(openAiKey = "sk-should-not-be-used"),
                systemInstruction = null,
                profile = null,
                conversationHistory = emptyList(),
                allowSingleProviderSimulationFallback = true,
                chatGptPlanClientId = "oaiapp_a",
            ),
            aiChatService = service,
            chatGptPlanTokens = { throw ChatGptPlanUnavailable(ChatGptPlanUnavailable.Reason.SIGNED_OUT) },
        )
        val message = responses.single().message
        assertTrue(message.isError)
        assertFalse(message.isSimulated)
        assertTrue(message.text.contains("signed out"))
    }

    private fun streamError(code: Int, body: String): ProviderApiException =
        assertThrows(ProviderApiException::class.java) {
            service.readSseResponse(
                response(code, body),
                service::extractOpenAiStreamText,
                service::isOpenAiStreamComplete,
                onTextDelta = {},
            )
        }

    private fun response(code: Int, body: String): Response = Response.Builder()
        .request(Request.Builder().url("https://api.openai.com/v1/responses").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Error")
        .body(body.toResponseBody("text/event-stream".toMediaType()))
        .build()

    private companion object {
        const val MODEL = "gpt-test"
        const val SYSTEM = "Be brief."
        val ITEMS = JsonArray(
            listOf(
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("reasoning"))
                    put("encrypted_content", kotlinx.serialization.json.JsonPrimitive("opaque"))
                },
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("message"))
                    put("role", kotlinx.serialization.json.JsonPrimitive("assistant"))
                    put("content", kotlinx.serialization.json.JsonPrimitive("answer"))
                },
            ),
        ).toString()
    }
}

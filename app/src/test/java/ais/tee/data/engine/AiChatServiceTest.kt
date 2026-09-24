package ais.tee.data.engine

import ais.tee.data.model.AiProvider
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ClaudeReasoningCapabilities
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NativeApiMode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_ROLE_KEY = "role"
private const val TEST_CONTENT_KEY = "content"
private const val FIRST_QUESTION = "first question"
private const val FOLLOW_UP = "follow up"
private const val OPENAI_ANSWER = "openai answer"
private const val CLAUDE_ANSWER = "claude answer"
private const val GEMINI_ANSWER = "gemini answer"
private const val SIMULATED_ANSWER = "simulated answer"
private const val SYSTEM_PROMPT = "system"
private const val STREAM_HELLO = "hello"
private const val TEST_STREAM_URL = "https://example.test/stream"
private const val TEST_EVENT_STREAM_TYPE = "text/event-stream"
private const val TEST_GEMINI_MODEL = "gemini-test-model"
private const val TEST_GEMINI_ROLE = "model"
private const val TEST_OPAQUE_SIGNATURE = "opaque-signature"
private const val TEST_THOUGHT_SIGNATURE_KEY = "thoughtSignature"
private const val TEST_CLAUDE_MAX_TOKENS = 128000
private const val TEST_CLAUDE_MAX_TOKENS_TEXT = "128000"
private const val TEST_CLAUDE_SAME_KEY = "same-key"
private const val TEST_CLAUDE_OUTAGE_MODEL = "claude-outage"
private const val TEST_CLAUDE_CONCRETE_MODEL = "claude-sonnet-4-5-20250929"
private const val TEST_BAD_API_KEY = "bad-key"
private const val TEST_CLAUDE_USER_MESSAGES_JSON = "[{\"role\":\"user\",\"content\":\"hello\"}]"
private const val TEST_CLAUDE_CONTEXT_STOP = "model_context_window_exceeded"
private const val TEST_CLAUDE_MODEL = "claude-sonnet-5"
private const val TEST_CLAUDE_MESSAGE_ID = "claude"
private const val TEST_CLAUDE_LEGACY_MODEL = "claude-haiku-4-5-20251001"
private const val TEST_CLAUDE_SIGNATURE = "claude-signature"
private const val TEST_CLAUDE_REDACTED_DATA = "redacted-data"
private const val TEST_TYPE_KEY = "type"
private const val TEST_TEXT_KEY = "text"
private const val TEST_SIGNATURE_KEY = "signature"
private const val TEST_DATA_KEY = "data"
private const val TEST_CLAUDE_THINKING = "thinking"
private const val TEST_CLAUDE_REDACTED_THINKING = "redacted_thinking"
private const val TEST_OPENAI_MODEL = "gpt-test-model"
private const val TEST_OPENAI_REASONING_TYPE = "reasoning"
private const val TEST_OPENAI_MESSAGE_TYPE = "message"
private const val TEST_OPENAI_ENCRYPTED_CONTENT_KEY = "encrypted_content"
private const val TEST_OPENAI_ENCRYPTED_REASONING = "encrypted-reasoning"
private const val TEST_OPENAI_REASONING_INCLUDE = "reasoning.encrypted_content"
private const val TEST_OPENAI_TEXT_DELTA_SSE = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n"

private fun claudeReplayStateJson(): String = """
    [
      {"type":"thinking","thinking":"reasoning summary","signature":"$TEST_CLAUDE_SIGNATURE"},
      {"type":"redacted_thinking","data":"$TEST_CLAUDE_REDACTED_DATA"},
      {"type":"text","text":"$CLAUDE_ANSWER"}
    ]
""".trimIndent()

private fun openAiReplayStateJson(): String = """
    [
      {
        "id": "rs_1",
        "type": "$TEST_OPENAI_REASONING_TYPE",
        "$TEST_OPENAI_ENCRYPTED_CONTENT_KEY": "$TEST_OPENAI_ENCRYPTED_REASONING",
        "summary": []
      },
      {
        "id": "msg_1",
        "type": "$TEST_OPENAI_MESSAGE_TYPE",
        "status": "completed",
        "role": "assistant",
        "content": [
          {
            "type": "output_text",
            "text": "$OPENAI_ANSWER",
            "annotations": []
          }
        ]
      }
    ]
""".trimIndent()

class AiChatServiceTest {
    @Test
    fun parsesClaudeReportedMaxTokensAndRejectsInvalidMetadata() {
        val service = AiChatService()
        assertEquals(TEST_CLAUDE_MAX_TOKENS, service.parseClaudeModelMaxTokens("""{"max_tokens":128000}"""))
        assertEquals(null, service.parseClaudeModelMaxTokens("""{"max_tokens":0}"""))
        assertEquals(null, service.parseClaudeModelMaxTokens("""{"id":"claude-sonnet-5"}"""))
        assertEquals(null, service.parseClaudeModelMaxTokens("not json"))
    }

    @Test
    fun claudeModelMetadataRequestEncodesModelAndUsesAnthropicHeaders() {
        val request = AiChatService().buildClaudeModelMetadataRequest("claude/custom model", "test-key")
        assertEquals("/v1/models/claude%2Fcustom%20model", request.url.encodedPath)
        assertEquals("test-key", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertEquals("GET", request.method)
    }

    @Test
    fun claudeMetadataCacheIsAtomicShortLivedForAliasesAndRecoverableAfterOutage() {
        val service = AiChatService()
        val alias = "claude-sonnet-4-5"
        val concrete = TEST_CLAUDE_CONCRETE_MODEL
        val apiKey = "alias-key"
        val now = 10_000L
        val capabilities = ClaudeReasoningCapabilities(supportsEnabled = true)

        val metadata = service.rememberClaudeMetadata(
            alias, concrete, apiKey, TEST_CLAUDE_MAX_TOKENS, capabilities, now
        )
        assertEquals(concrete, metadata.resolvedModel)
        assertEquals(
            concrete,
            service.readClaudeMetadataCache(alias, apiKey, now + 1)?.resolvedModel
        )
        assertEquals(
            TEST_CLAUDE_MAX_TOKENS,
            service.readClaudeMetadataCache(concrete, apiKey, now + 301_000)?.maxTokens
        )
        assertEquals(null, service.readClaudeMetadataCache(alias, apiKey, now + 301_000))

        val failure = service.rememberClaudeMetadataFailure(TEST_CLAUDE_OUTAGE_MODEL, TEST_BAD_API_KEY, now)
        assertEquals(2048, failure.maxTokens)
        assertEquals(ClaudeReasoningCapabilities(), failure.reasoningCapabilities)
        assertEquals(
            2048,
            service.readClaudeMetadataCache(TEST_CLAUDE_OUTAGE_MODEL, TEST_BAD_API_KEY, now + 29_000)?.maxTokens
        )
        assertEquals(
            null,
            service.readClaudeMetadataCache(TEST_CLAUDE_OUTAGE_MODEL, TEST_BAD_API_KEY, now + 31_000)
        )
        assertEquals(concrete, service.parseClaudeModelId("""{"id":"$concrete"}"""))
        assertEquals(null, service.parseClaudeModelId("""{"model":"$concrete"}"""))
    }

    @Test
    fun claudePayloadUsesResolvedOutputLimitInBufferedAndStreamingModes() {
        val service = AiChatService()
        val messages = Json.parseToJsonElement(TEST_CLAUDE_USER_MESSAGES_JSON).jsonArray
        val noReasoning = ClaudeReasoningCapabilities()
        val buffered = service.buildClaudeRequestPayload(
            TEST_CLAUDE_MODEL, TEST_CLAUDE_MAX_TOKENS, false, SYSTEM_PROMPT, messages, noReasoning
        )
        val streaming = service.buildClaudeRequestPayload(
            TEST_CLAUDE_MODEL, TEST_CLAUDE_MAX_TOKENS, true, null, messages, noReasoning
        )

        assertEquals(TEST_CLAUDE_MAX_TOKENS_TEXT, buffered.getValue("max_tokens").jsonPrimitive.content)
        assertEquals(SYSTEM_PROMPT, buffered.getValue("system").jsonPrimitive.content)
        assertFalse("stream" in buffered)
        assertEquals(TEST_CLAUDE_MAX_TOKENS_TEXT, streaming.getValue("max_tokens").jsonPrimitive.content)
        assertFalse("thinking" in streaming)
        assertFalse("output_config" in streaming)
        assertEquals("true", streaming.getValue("stream").jsonPrimitive.content)
        assertFalse("system" in streaming)
    }

    @Test
    fun claudePayloadUsesModelAwareAdaptiveAndLegacyThinking() {
        val service = AiChatService()
        val messages = Json.parseToJsonElement(TEST_CLAUDE_USER_MESSAGES_JSON).jsonArray
        val adaptive = service.buildClaudeRequestPayload(
            TEST_CLAUDE_MODEL,
            TEST_CLAUDE_MAX_TOKENS,
            false,
            null,
            messages,
            ClaudeReasoningCapabilities(supportsAdaptive = true, supportsHighEffort = true)
        )
        val legacy = service.buildClaudeRequestPayload(
            TEST_CLAUDE_LEGACY_MODEL,
            64_000,
            false,
            null,
            messages,
            ClaudeReasoningCapabilities(supportsEnabled = true)
        )

        assertEquals("adaptive", adaptive.getValue(TEST_CLAUDE_THINKING).jsonObject.getValue(TEST_TYPE_KEY).jsonPrimitive.content)
        assertEquals("high", adaptive.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("enabled", legacy.getValue(TEST_CLAUDE_THINKING).jsonObject.getValue(TEST_TYPE_KEY).jsonPrimitive.content)
        assertEquals("4096", legacy.getValue(TEST_CLAUDE_THINKING).jsonObject.getValue("budget_tokens").jsonPrimitive.content)
    }

    @Test
    fun claudeHistoryReplaysOpaqueThinkingBlocksOnlyForMatchingModel() {
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = FIRST_QUESTION),
            ModelChatMessage(
                id = TEST_CLAUDE_MESSAGE_ID,
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.CLAUDE,
                modelName = TEST_CLAUDE_MODEL,
                text = CLAUDE_ANSWER,
                providerReplayState = claudeReplayStateJson()
            ),
            ModelChatMessage(id = "u2", sender = CHAT_ROLE_USER, text = FOLLOW_UP)
        )
        val service = AiChatService()

        val replayed = service.buildClaudeMessages(FOLLOW_UP, history, modelName = TEST_CLAUDE_MODEL)
        val blocks = replayed[1].jsonObject.getValue(TEST_CONTENT_KEY).jsonArray
        assertEquals(listOf(TEST_CLAUDE_THINKING, TEST_CLAUDE_REDACTED_THINKING, TEST_TEXT_KEY), blocks.map {
            it.jsonObject.getValue(TEST_TYPE_KEY).jsonPrimitive.content
        })
        assertEquals(
            TEST_CLAUDE_SIGNATURE,
            blocks[0].jsonObject.getValue(TEST_SIGNATURE_KEY).jsonPrimitive.content
        )
        assertEquals(
            TEST_CLAUDE_REDACTED_DATA,
            blocks[1].jsonObject.getValue(TEST_DATA_KEY).jsonPrimitive.content
        )

        val switched = service.buildClaudeMessages(FOLLOW_UP, history, modelName = "claude-opus-5")
        assertEquals(
            CLAUDE_ANSWER,
            switched[1].jsonObject.getValue(TEST_CONTENT_KEY).jsonPrimitive.content
        )
    }

    @Test
    fun claudeStreamingReconstructsThinkingSignatureRedactionAndTextInOrder() {
        val service = AiChatService()
        val blocks = mutableMapOf<Int, JsonObject>()
        listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"reasoning summary"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"$TEST_CLAUDE_SIGNATURE"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"$TEST_CLAUDE_REDACTED_DATA"}}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"$CLAUDE_ANSWER"}}"""
        ).forEach { raw ->
            service.applyClaudeReplayEvent(blocks, Json.parseToJsonElement(raw).jsonObject)
        }

        val replay = service.parseClaudeReplayState(service.encodeClaudeStreamReplayState(blocks))
        assertEquals(listOf(TEST_CLAUDE_THINKING, TEST_CLAUDE_REDACTED_THINKING, TEST_TEXT_KEY), replay.map {
            it.getValue(TEST_TYPE_KEY).jsonPrimitive.content
        })
        assertEquals(TEST_CLAUDE_SIGNATURE, replay[0].getValue(TEST_SIGNATURE_KEY).jsonPrimitive.content)
        assertEquals(TEST_CLAUDE_REDACTED_DATA, replay[1].getValue(TEST_DATA_KEY).jsonPrimitive.content)
        assertEquals(CLAUDE_ANSWER, replay[2].getValue(TEST_TEXT_KEY).jsonPrimitive.content)
    }

    @Test
    fun claudeMaxTokenStopReasonMarksBufferedAndStreamingResponsesPartial() {
        val service = AiChatService()
        val buffered = Json.parseToJsonElement(
            """{"stop_reason":"max_tokens"}"""
        ).jsonObject
        val streamed = Json.parseToJsonElement(
            """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""
        ).jsonObject

        assertTrue(service.isClaudePartialStopReason(service.extractClaudeStopReason(buffered)))
        assertTrue(service.isClaudePartialStopReason(service.extractClaudeStreamStopReason(streamed)))
        assertTrue(service.isClaudePartialStopReason(TEST_CLAUDE_CONTEXT_STOP))
        assertFalse(service.isClaudePartialStopReason("end_turn"))
    }

    @Test
    fun claudeRefusalBlockDisablesOpaqueReplayAndFallsBackToVisibleText() {
        val service = AiChatService()
        val refusalState = """[{"type":"refusal","refusal":"no"}]"""
        val response = Json.parseToJsonElement(
            """{"model":"$TEST_CLAUDE_MODEL","content":[{"type":"refusal","refusal":"no"}]}"""
        ).jsonObject
        assertEquals(null, service.extractClaudeReplayState(response))

        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = FIRST_QUESTION),
            ModelChatMessage(
                id = TEST_CLAUDE_MESSAGE_ID,
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.CLAUDE,
                modelName = TEST_CLAUDE_MODEL,
                text = CLAUDE_ANSWER,
                providerReplayState = refusalState
            ),
            ModelChatMessage(id = "u2", sender = CHAT_ROLE_USER, text = FOLLOW_UP)
        )
        val messages = service.buildClaudeMessages(FOLLOW_UP, history, modelName = TEST_CLAUDE_MODEL)
        assertEquals(CLAUDE_ANSWER, messages[1].jsonObject.getValue(TEST_CONTENT_KEY).jsonPrimitive.content)
    }

    @Test
    fun claudeStreamingUnsupportedBlockInvalidatesReplay() {
        val service = AiChatService()
        val blocks = mutableMapOf<Int, JsonObject>()
        service.applyClaudeReplayEvent(
            blocks,
            Json.parseToJsonElement(
                """{"type":"content_block_start","index":0,"content_block":{"type":"refusal","refusal":"no"}}"""
            ).jsonObject
        )
        assertEquals(null, service.encodeClaudeStreamReplayState(blocks))
    }

    @Test
    fun claudeResolvedModelComesFromBufferedAndStreamingResponses() {
        val service = AiChatService()
        val concrete = TEST_CLAUDE_CONCRETE_MODEL
        val buffered = Json.parseToJsonElement("""{"model":"$concrete"}""").jsonObject
        val streamStart = Json.parseToJsonElement(
            """{"type":"message_start","message":{"model":"$concrete"}}"""
        ).jsonObject
        val fallback = Json.parseToJsonElement(
            """{"type":"content_block_start","index":0,"content_block":{"type":"fallback","to":{"model":"claude-haiku-4-5-20251001"}}}"""
        ).jsonObject

        assertEquals(concrete, service.extractClaudeResponseModel(buffered))
        assertEquals(concrete, service.extractClaudeStreamResolvedModel(streamStart))
        assertEquals(TEST_CLAUDE_LEGACY_MODEL, service.extractClaudeStreamResolvedModel(fallback))
    }

    @Test
    fun compatibleHistoryKeepsOnlyCurrentProviderAssistantTurns() {
        val history = listOf(
            ModelChatMessage(
                id = "welcome",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.ALL,
                text = "welcome"
            ),
            ModelChatMessage(id = "user-1", sender = CHAT_ROLE_USER, text = "first"),
            ModelChatMessage(
                id = "or-1",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.OPENROUTER,
                text = "openrouter answer"
            ),
            ModelChatMessage(
                id = "ds-1",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.DEEPSEEK,
                text = "deepseek answer"
            ),
            ModelChatMessage(id = "user-2", sender = CHAT_ROLE_USER, text = FOLLOW_UP)
        )

        val messages = AiChatService().buildOpenAiCompatibleMessages(
            prompt = FOLLOW_UP,
            systemInstruction = SYSTEM_PROMPT,
            conversationHistory = history,
            provider = AiProvider.OPENROUTER
        )

        assertEquals(listOf(SYSTEM_PROMPT, CHAT_ROLE_USER, CHAT_ROLE_ASSISTANT, CHAT_ROLE_USER), messages.map {
            it.jsonObject.getValue(TEST_ROLE_KEY).jsonPrimitive.content
        })
        assertEquals(listOf(SYSTEM_PROMPT, "first", "openrouter answer", FOLLOW_UP), messages.map {
            it.jsonObject.getValue(TEST_CONTENT_KEY).jsonPrimitive.content
        })
    }

    @Test
    fun providerHistoryExcludesDiagnosticsAndSimulations() {
        val userSender = "user"
        val assistantSender = "assistant"
        val kimiModel = "kimi-k2.6"
        val liveAnswer = "live answer"
        val apiFailure = "API failed"
        val simulatedAnswer = SIMULATED_ANSWER
        val otherProviderAnswer = "other provider answer"
        val nextQuestion = "next question"
        val history = listOf(
            ModelChatMessage(id = "u1", sender = userSender, text = FIRST_QUESTION),
            ModelChatMessage(
                id = "live", sender = assistantSender, provider = AiProvider.KIMI,
                modelName = kimiModel, text = liveAnswer
            ),
            ModelChatMessage(
                id = "error", sender = assistantSender, provider = AiProvider.KIMI,
                modelName = kimiModel, text = apiFailure, isError = true
            ),
            ModelChatMessage(
                id = "sim", sender = assistantSender, provider = AiProvider.KIMI,
                modelName = kimiModel, text = simulatedAnswer, isSimulated = true
            ),
            ModelChatMessage(
                id = "other", sender = assistantSender, provider = AiProvider.DEEPSEEK,
                modelName = "deepseek-v4-flash", text = otherProviderAnswer
            ),
            ModelChatMessage(id = "u2", sender = userSender, text = nextQuestion)
        )

        val messages = AiChatService().buildOpenAiCompatibleMessages(
            prompt = nextQuestion,
            systemInstruction = null,
            conversationHistory = history,
            provider = AiProvider.KIMI
        )
        val contents = messages.map { it.jsonObject.getValue(TEST_CONTENT_KEY).jsonPrimitive.content }
        val roles = messages.map { it.jsonObject.getValue(TEST_ROLE_KEY).jsonPrimitive.content }

        assertEquals(listOf(userSender, assistantSender, userSender), roles)
        assertEquals(listOf(FIRST_QUESTION, liveAnswer, nextQuestion), contents)
        assertFalse(apiFailure in contents)
        assertFalse(simulatedAnswer in contents)
        assertFalse(otherProviderAnswer in contents)
        assertTrue(liveAnswer in contents)
    }

    @Test
    fun directProviderHistoryUsesNativeRolesAndProviderScopedAnswers() {
        val prompt = FOLLOW_UP
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = FIRST_QUESTION),
            ModelChatMessage(
                id = "gpt", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CHATGPT, text = OPENAI_ANSWER
            ),
            ModelChatMessage(
                id = TEST_CLAUDE_MESSAGE_ID, sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CLAUDE, text = CLAUDE_ANSWER
            ),
            ModelChatMessage(
                id = "gemini", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.GEMINI, text = GEMINI_ANSWER
            ),
            ModelChatMessage(
                id = "simulated", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CHATGPT,
                text = SIMULATED_ANSWER, isSimulated = true
            ),
            ModelChatMessage(
                id = "error", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CLAUDE,
                text = "error answer", isError = true
            ),
            ModelChatMessage(id = "u2", sender = CHAT_ROLE_USER, text = prompt)
        )
        val service = AiChatService()

        val gemini = service.buildGeminiContents(prompt, history, TEST_GEMINI_MODEL)
        assertEquals(listOf(CHAT_ROLE_USER, TEST_GEMINI_ROLE, CHAT_ROLE_USER), gemini.map {
            it.jsonObject.getValue(TEST_ROLE_KEY).jsonPrimitive.content
        })
        assertEquals(listOf(FIRST_QUESTION, GEMINI_ANSWER, prompt), gemini.map {
            it.jsonObject.getValue("parts").jsonArray.first().jsonObject.getValue(TEST_TEXT_KEY).jsonPrimitive.content
        })

        val openAi = service.buildOpenAiResponseInput(prompt, history)
        assertEquals(listOf(CHAT_ROLE_USER, CHAT_ROLE_ASSISTANT, CHAT_ROLE_USER), openAi.map {
            it.jsonObject.getValue(TEST_ROLE_KEY).jsonPrimitive.content
        })
        assertEquals(listOf(FIRST_QUESTION, OPENAI_ANSWER, prompt), openAi.map {
            it.jsonObject.getValue(TEST_CONTENT_KEY).jsonPrimitive.content
        })

        val claude = service.buildClaudeMessages(prompt, history)
        assertEquals(listOf(CHAT_ROLE_USER, CHAT_ROLE_ASSISTANT, CHAT_ROLE_USER), claude.map {
            it.jsonObject.getValue(TEST_ROLE_KEY).jsonPrimitive.content
        })
        assertEquals(listOf(FIRST_QUESTION, CLAUDE_ANSWER, prompt), claude.map {
            it.jsonObject.getValue(TEST_CONTENT_KEY).jsonPrimitive.content
        })
    }

    @Test
    fun parsesOpenRouterCatalogPricingAndModalities() {
        val raw = """
            {
              "data": [
                {
                  "id": "vendor/free-model:free",
                  "pricing": {"prompt": "0", "completion": "0"},
                  "architecture": {"output_modalities": ["text"]}
                },
                {
                  "id": "vendor/image-only",
                  "pricing": {"prompt": "0", "completion": "0"},
                  "architecture": {"output_modalities": ["image"]}
                },
                {
                  "id": "vendor/unknown-output",
                  "pricing": {"prompt": "0", "completion": "0"}
                }
              ]
            }
        """.trimIndent()

        val entries = AiChatService().parseGatewayModelCatalog(AiProvider.OPENROUTER, raw)

        assertEquals(3, entries.size)
        assertEquals("vendor/free-model:free", entries.first().id)
        assertEquals(0.0, entries.first().inputPriceUsd ?: -1.0, 0.0)
        assertTrue(entries.first().supportsTextOutput)
        assertFalse(entries[1].supportsTextOutput)
        assertFalse(entries.last().supportsTextOutput)
    }

    @Test
    fun rejectsCatalogWithoutDataArray() {
        val result = runCatching {
            AiChatService().parseGatewayModelCatalog(AiProvider.OPENROUTER, "{}")
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun parsesAiHubMixCatalogPricingAndType() {
        val raw = """
            {
              "data": [
                {
                  "model_id": "coding-model-free",
                  "types": "LLM",
                  "pricing": {"input": 0, "output": 0}
                },
                {
                  "model_id": "image-model",
                  "types": "image_generation",
                  "pricing": {"input": 0, "output": 0}
                }
              ]
            }
        """.trimIndent()

        val entries = AiChatService().parseGatewayModelCatalog(AiProvider.AIHUBMIX, raw)

        assertEquals(2, entries.size)
        assertEquals("coding-model-free", entries.first().id)
        assertEquals(0.0, entries.first().outputPriceUsd ?: -1.0, 0.0)
        assertTrue(entries.first().supportsTextOutput)
        assertFalse(entries.last().supportsTextOutput)
    }
    @Test
    fun parsesVercelCatalogPricingAndLanguageType() {
        val raw = """
            {
              "data": [
                {
                  "id": "vendor/text-model",
                  "type": "language",
                  "modalities": {"output": ["text"]},
                  "pricing": {"input": "0.0000001", "output": "0.0000002"}
                },
                {
                  "id": "vendor/image-model",
                  "type": "image",
                  "modalities": {"output": ["image"]},
                  "pricing": {"input": "0", "output": "0"}
                }
              ]
            }
        """.trimIndent()

        val entries = AiChatService().parseGatewayModelCatalog(AiProvider.VERCEL, raw)

        assertEquals(2, entries.size)
        assertEquals("vendor/text-model", entries.first().id)
        assertEquals(0.0000001, entries.first().inputPriceUsd ?: -1.0, 0.0)
        assertTrue(entries.first().supportsTextOutput)
        assertFalse(entries.last().supportsTextOutput)
    }

    @Test
    fun geminiHistoryReplaysOpaqueModelContentsIncludingSignatureOnlyChunk() {
        val replayState = "[{\"role\":\"model\",\"parts\":[{\"text\":\"gemini answer\"}]},{\"role\":\"model\",\"parts\":[{\"text\":\"\",\"thoughtSignature\":\"opaque-signature\"}]}]"
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = FIRST_QUESTION),
            ModelChatMessage(
                id = "gemini",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.GEMINI,
                modelName = TEST_GEMINI_MODEL,
                text = GEMINI_ANSWER,
                providerReplayState = replayState
            ),
            ModelChatMessage(id = "u2", sender = CHAT_ROLE_USER, text = FOLLOW_UP)
        )

        val contents = AiChatService().buildGeminiContents(FOLLOW_UP, history, TEST_GEMINI_MODEL)

        assertEquals(listOf(CHAT_ROLE_USER, TEST_GEMINI_ROLE, CHAT_ROLE_USER), contents.map {
            it.jsonObject.getValue(TEST_ROLE_KEY).jsonPrimitive.content
        })
        val replayParts = contents[1].jsonObject.getValue("parts").jsonArray
        assertEquals(2, replayParts.size)
        val signaturePart = replayParts[1].jsonObject
        assertEquals("", signaturePart.getValue(TEST_TEXT_KEY).jsonPrimitive.content)
        assertEquals(TEST_OPAQUE_SIGNATURE, signaturePart.getValue(TEST_THOUGHT_SIGNATURE_KEY).jsonPrimitive.content)
    }

    @Test
    fun geminiModelSwitchFallsBackToVisibleTextReplay() {
        val replayState = "[{\"role\":\"model\",\"parts\":[{\"text\":\"gemini answer\"},{\"text\":\"\",\"thoughtSignature\":\"opaque-signature\"}]}]"
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = FIRST_QUESTION),
            ModelChatMessage(
                id = "gemini",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.GEMINI,
                modelName = "gemini-old-model",
                text = GEMINI_ANSWER,
                providerReplayState = replayState
            ),
            ModelChatMessage(id = "u2", sender = CHAT_ROLE_USER, text = FOLLOW_UP)
        )

        val contents = AiChatService().buildGeminiContents(FOLLOW_UP, history, "gemini-new-model")

        assertEquals(listOf(CHAT_ROLE_USER, TEST_GEMINI_ROLE, CHAT_ROLE_USER), contents.map {
            it.jsonObject.getValue(TEST_ROLE_KEY).jsonPrimitive.content
        })
        val replayParts = contents[1].jsonObject.getValue("parts").jsonArray
        assertEquals(1, replayParts.size)
        val visiblePart = replayParts.single().jsonObject
        assertEquals(GEMINI_ANSWER, visiblePart.getValue(TEST_TEXT_KEY).jsonPrimitive.content)
        assertFalse(TEST_THOUGHT_SIGNATURE_KEY in visiblePart)
    }

    @Test
    fun geminiStreamingKeepsEmptyTextSignatureCarrier() {
        val service = AiChatService()
        val replayContents = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val response = Response.Builder()
            .request(Request.Builder().url(TEST_STREAM_URL).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body((
                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hello\"}]}}]}\n\n" +
                    "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"\",\"thoughtSignature\":\"opaque-signature\"}]},\"finishReason\":\"STOP\"}]}\n\n"
                ).toResponseBody(TEST_EVENT_STREAM_TYPE.toMediaType()))
            .build()

        val text = service.readSseResponse(
            response = response,
            extractText = service::extractGeminiStreamText,
            isComplete = service::isGeminiStreamComplete,
            onTextDelta = {},
            onEvent = { event ->
                service.extractGeminiReplayContent(event)?.let(replayContents::add)
            }
        )

        assertEquals(STREAM_HELLO, text)
        assertEquals(2, replayContents.size)
        val merged = service.mergeGeminiReplayContents(replayContents)
        val replayParts = requireNotNull(merged).getValue("parts").jsonArray
        assertEquals(2, replayParts.size)
        assertEquals("hello", replayParts[0].jsonObject.getValue(TEST_TEXT_KEY).jsonPrimitive.content)
        val signaturePart = replayParts[1].jsonObject
        assertEquals("", signaturePart.getValue(TEST_TEXT_KEY).jsonPrimitive.content)
        assertEquals(TEST_OPAQUE_SIGNATURE, signaturePart.getValue(TEST_THOUGHT_SIGNATURE_KEY).jsonPrimitive.content)
    }

    @Test
    fun openAiHistoryReplaysEncryptedReasoningItemsInOrder() {
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = FIRST_QUESTION),
            ModelChatMessage(
                id = "gpt",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.CHATGPT,
                modelName = TEST_OPENAI_MODEL,
                text = OPENAI_ANSWER,
                providerReplayState = openAiReplayStateJson()
            ),
            ModelChatMessage(id = "u2", sender = CHAT_ROLE_USER, text = FOLLOW_UP)
        )

        val input = AiChatService().buildOpenAiResponseInput(
            prompt = FOLLOW_UP,
            conversationHistory = history,
            modelName = TEST_OPENAI_MODEL
        )

        assertEquals(4, input.size)
        assertEquals(CHAT_ROLE_USER, input[0].jsonObject.getValue(TEST_ROLE_KEY).jsonPrimitive.content)
        assertEquals(FIRST_QUESTION, input[0].jsonObject.getValue(TEST_CONTENT_KEY).jsonPrimitive.content)
        assertEquals(TEST_OPENAI_REASONING_TYPE, input[1].jsonObject.getValue(TEST_TYPE_KEY).jsonPrimitive.content)
        assertEquals(
            TEST_OPENAI_ENCRYPTED_REASONING,
            input[1].jsonObject.getValue(TEST_OPENAI_ENCRYPTED_CONTENT_KEY).jsonPrimitive.content
        )
        assertEquals(TEST_OPENAI_MESSAGE_TYPE, input[2].jsonObject.getValue(TEST_TYPE_KEY).jsonPrimitive.content)
        assertEquals(CHAT_ROLE_ASSISTANT, input[2].jsonObject.getValue(TEST_ROLE_KEY).jsonPrimitive.content)
        assertEquals(CHAT_ROLE_USER, input[3].jsonObject.getValue(TEST_ROLE_KEY).jsonPrimitive.content)
        assertEquals(FOLLOW_UP, input[3].jsonObject.getValue(TEST_CONTENT_KEY).jsonPrimitive.content)
    }

    @Test
    fun openAiModelSwitchFallsBackToVisibleTextReplay() {
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = FIRST_QUESTION),
            ModelChatMessage(
                id = "gpt",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.CHATGPT,
                modelName = "gpt-old-model",
                text = OPENAI_ANSWER,
                providerReplayState = openAiReplayStateJson()
            ),
            ModelChatMessage(id = "u2", sender = CHAT_ROLE_USER, text = FOLLOW_UP)
        )

        val input = AiChatService().buildOpenAiResponseInput(
            prompt = FOLLOW_UP,
            conversationHistory = history,
            modelName = TEST_OPENAI_MODEL
        )

        assertEquals(listOf(CHAT_ROLE_USER, CHAT_ROLE_ASSISTANT, CHAT_ROLE_USER), input.map {
            it.jsonObject.getValue(TEST_ROLE_KEY).jsonPrimitive.content
        })
        assertEquals(listOf(FIRST_QUESTION, OPENAI_ANSWER, FOLLOW_UP), input.map {
            it.jsonObject.getValue(TEST_CONTENT_KEY).jsonPrimitive.content
        })
    }

    @Test
    fun openAiRequestKeepsStoreFalseAndRequestsEncryptedReasoning() {
        val service = AiChatService()
        val input = service.buildOpenAiResponseInput(
            prompt = FOLLOW_UP,
            conversationHistory = emptyList(),
            modelName = TEST_OPENAI_MODEL
        )
        val buffered = service.buildOpenAiRequestPayload(
            model = TEST_OPENAI_MODEL,
            stream = false,
            systemInstruction = SYSTEM_PROMPT,
            input = input
        )
        val streaming = service.buildOpenAiRequestPayload(
            model = TEST_OPENAI_MODEL,
            stream = true,
            systemInstruction = null,
            input = input
        )

        assertEquals("false", buffered.getValue("store").jsonPrimitive.content)
        assertEquals(
            TEST_OPENAI_REASONING_INCLUDE,
            buffered.getValue("include").jsonArray.single().jsonPrimitive.content
        )
        assertEquals(SYSTEM_PROMPT, buffered.getValue("instructions").jsonPrimitive.content)
        assertFalse("stream" in buffered)
        assertEquals("false", streaming.getValue("store").jsonPrimitive.content)
        assertEquals("true", streaming.getValue("stream").jsonPrimitive.content)
    }

    @Test
    fun openAiFlexModeAddsServiceTierWithoutChangingDefaultRequests() {
        val service = AiChatService()
        val input = service.buildOpenAiResponseInput(
            prompt = FOLLOW_UP,
            conversationHistory = emptyList(),
            modelName = TEST_OPENAI_MODEL
        )
        val automatic = service.buildOpenAiRequestPayload(
            model = TEST_OPENAI_MODEL,
            stream = false,
            systemInstruction = null,
            input = input,
            apiMode = NativeApiMode.AUTO,
        )
        val flex = service.buildOpenAiRequestPayload(
            model = TEST_OPENAI_MODEL,
            stream = false,
            systemInstruction = null,
            input = input,
            apiMode = NativeApiMode.FLEX,
        )

        assertFalse("service_tier" in automatic)
        assertEquals("flex", flex.getValue("service_tier").jsonPrimitive.content)
        assertEquals("false", flex.getValue("store").jsonPrimitive.content)
    }

    @Test
    fun openAiBufferedTerminalFailuresAreRejectedBeforeReplayCapture() {
        val service = AiChatService()
        val incomplete = Json.parseToJsonElement(
            """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":${openAiReplayStateJson()}}"""
        ).jsonObject
        val failed = Json.parseToJsonElement(
            """{"status":"failed","error":{"message":"server exploded"},"output":${openAiReplayStateJson()}}"""
        ).jsonObject

        val incompleteFailure = runCatching {
            service.ensureOpenAiBufferedResponseCompleted(incomplete)
        }.exceptionOrNull()
        val failedFailure = runCatching {
            service.ensureOpenAiBufferedResponseCompleted(failed)
        }.exceptionOrNull()

        assertTrue(incompleteFailure is java.io.IOException)
        assertEquals("OpenAI response incomplete: max_output_tokens", incompleteFailure?.message)
        assertTrue(failedFailure is java.io.IOException)
        assertEquals("server exploded", failedFailure?.message)
    }

    @Test
    fun openAiStreamingCapturesCompletedEncryptedReasoningOutput() {
        val service = AiChatService()
        var replayState: String? = null
        val completedOutput = openAiReplayStateJson().lineSequence().joinToString(separator = "") { it.trim() }
        val response = Response.Builder()
            .request(Request.Builder().url(TEST_STREAM_URL).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body((
                TEST_OPENAI_TEXT_DELTA_SSE +
                    "data: {\"type\":\"response.completed\",\"response\":{\"output\":$completedOutput}}\n\n"
                ).toResponseBody(TEST_EVENT_STREAM_TYPE.toMediaType()))
            .build()

        val text = service.readSseResponse(
            response = response,
            extractText = service::extractOpenAiStreamText,
            isComplete = service::isOpenAiStreamComplete,
            onTextDelta = {},
            onEvent = { event ->
                service.extractOpenAiCompletedReplayState(event)?.let { replayState = it }
            }
        )

        assertEquals(STREAM_HELLO, text)
        val replayItems = service.parseOpenAiReplayState(replayState)
        assertEquals(listOf(TEST_OPENAI_REASONING_TYPE, TEST_OPENAI_MESSAGE_TYPE), replayItems.map {
            it.getValue(TEST_TYPE_KEY).jsonPrimitive.content
        })
        assertEquals(
            TEST_OPENAI_ENCRYPTED_REASONING,
            replayItems.first().getValue(TEST_OPENAI_ENCRYPTED_CONTENT_KEY).jsonPrimitive.content
        )
    }

    @Test
    fun extractsNativeStreamingTextDeltas() {
        val service = AiChatService()
        val gemini = Json.parseToJsonElement(
            """{"candidates":[{"content":{"parts":[{"text":"hel"},{"text":"lo"}]}}]}"""
        ).jsonObject
        val openAi = Json.parseToJsonElement(
            """{"type":"response.output_text.delta","delta":"hello"}"""
        ).jsonObject
        val claude = Json.parseToJsonElement(
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"hello"}}"""
        ).jsonObject

        assertEquals(STREAM_HELLO, service.extractGeminiStreamText(gemini))
        assertEquals(STREAM_HELLO, service.extractOpenAiStreamText(openAi))
        assertEquals(STREAM_HELLO, service.extractClaudeStreamText(claude))
    }

    @Test
    fun extractsResolvedOpenAiCompatibleModel() {
        val service = AiChatService()
        val routed = Json.parseToJsonElement(
            """{"model":"anthropic/claude-sonnet-5","choices":[]}"""
        ).jsonObject
        val blank = Json.parseToJsonElement(
            """{"model":"   ","choices":[]}"""
        ).jsonObject
        val malformed = Json.parseToJsonElement(
            """{"model":{},"choices":[]}"""
        ).jsonObject

        assertEquals("anthropic/claude-sonnet-5", service.extractOpenAiCompatibleModel(routed))
        assertEquals(null, service.extractOpenAiCompatibleModel(blank))
        assertEquals(null, service.extractOpenAiCompatibleModel(malformed))
    }

    @Test
    fun ignoresNonTextNativeStreamingEvents() {
        val service = AiChatService()
        val openAi = Json.parseToJsonElement(
            """{"type":"response.completed","response":{"id":"resp_1"}}"""
        ).jsonObject
        val openAiIncomplete = Json.parseToJsonElement(
            """{"type":"response.incomplete","response":{"id":"resp_2","incomplete_details":{"reason":"max_output_tokens"}}}"""
        ).jsonObject
        val claude = Json.parseToJsonElement(
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""
        ).jsonObject

        assertEquals(null, service.extractOpenAiStreamText(openAi))
        assertEquals(null, service.extractClaudeStreamText(claude))
    }

    @Test
    fun recognizesNativeStreamingCompletionEvents() {
        val service = AiChatService()
        val gemini = Json.parseToJsonElement(
            """{"candidates":[{"finishReason":"STOP"}]}"""
        ).jsonObject
        val openAi = Json.parseToJsonElement(
            """{"type":"response.completed","response":{"id":"resp_1"}}"""
        ).jsonObject
        val openAiIncomplete = Json.parseToJsonElement(
            """{"type":"response.incomplete","response":{"id":"resp_2","incomplete_details":{"reason":"max_output_tokens"}}}"""
        ).jsonObject
        val claude = Json.parseToJsonElement(
            """{"type":"message_stop"}"""
        ).jsonObject

        assertTrue(service.isGeminiStreamComplete(gemini))
        assertTrue(service.isOpenAiStreamComplete(openAi))
        assertFalse(service.isOpenAiStreamComplete(openAiIncomplete))
        assertEquals("OpenAI response incomplete: max_output_tokens", service.extractStreamError(openAiIncomplete))
        assertTrue(service.isClaudeStreamComplete(claude))
    }

    @Test
    fun extractsOpenAiResponseFailedError() {
        val service = AiChatService()
        val failed = Json.parseToJsonElement(
            """{"type":"response.failed","response":{"error":{"message":"quota exhausted"}}}"""
        ).jsonObject

        assertEquals("quota exhausted", service.extractStreamError(failed))
        assertFalse(service.isOpenAiStreamComplete(failed))
    }

    @Test
    fun rejectsNonStopGeminiFinishReasons() {
        val service = AiChatService()
        val limited = Json.parseToJsonElement(
            """{"candidates":[{"finishReason":"MAX_TOKENS","finishMessage":"token limit reached"}]}"""
        ).jsonObject

        assertFalse(service.isGeminiStreamComplete(limited))
        assertEquals("Gemini stopped with MAX_TOKENS: token limit reached", service.extractStreamError(limited))
    }

    @Test
    fun readsDoneTerminatedOpenAiCompatibleStream() {
        val service = AiChatService()
        val deltas = mutableListOf<String>()
        val response = Response.Builder()
            .request(Request.Builder().url(TEST_STREAM_URL).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                (": keep-alive\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"hel\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
                    "data: [DONE]\n\n")
                    .toResponseBody(TEST_EVENT_STREAM_TYPE.toMediaType())
            )
            .build()

        val text = service.readSseResponse(
            response = response,
            extractText = service::extractOpenAiCompatibleStreamText,
            isComplete = { false },
            onTextDelta = { deltas.add(it) },
            completeOnDoneSentinel = true
        )

        assertEquals(STREAM_HELLO, text)
        assertEquals(listOf("hel", "lo"), deltas)
    }

    @Test
    fun rejectsTruncatedOpenAiCompatibleStream() {
        val service = AiChatService()
        val response = Response.Builder()
            .request(Request.Builder().url(TEST_STREAM_URL).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
                    .toResponseBody(TEST_EVENT_STREAM_TYPE.toMediaType())
            )
            .build()

        val result = runCatching {
            service.readSseResponse(
                response = response,
                extractText = service::extractOpenAiCompatibleStreamText,
                isComplete = { false },
                onTextDelta = {},
                completeOnDoneSentinel = true
            )
        }

        assertTrue(result.isFailure)
        assertEquals("Streaming response ended before completion", result.exceptionOrNull()?.message)
    }

    @Test
    fun propagatesUntypedGatewayStreamError() {
        val service = AiChatService()
        val response = Response.Builder()
            .request(Request.Builder().url(TEST_STREAM_URL).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body((
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n" +
                    "data: {\"error\":{\"message\":\"gateway failed\"}}\n\n" +
                    "data: [DONE]\n\n"
                ).toResponseBody(TEST_EVENT_STREAM_TYPE.toMediaType()))
            .build()

        val result = runCatching {
            service.readSseResponse(
                response, service::extractOpenAiCompatibleStreamText,
                service::isOpenAiCompatibleStreamComplete, {}, completeOnDoneSentinel = true
            )
        }

        assertTrue(result.isFailure)
        assertEquals("gateway failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun acceptsGatewayFinishReasonWithoutDoneSentinel() {
        val service = AiChatService()
        val response = Response.Builder()
            .request(Request.Builder().url(TEST_STREAM_URL).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body((
                "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                ).toResponseBody(TEST_EVENT_STREAM_TYPE.toMediaType()))
            .build()

        val text = service.readSseResponse(
            response, service::extractOpenAiCompatibleStreamText,
            service::isOpenAiCompatibleStreamComplete, {}, completeOnDoneSentinel = true
        )

        assertEquals(STREAM_HELLO, text)
    }

    @Test
    fun stopsReadingAfterGatewayCompletionEvent() {
        val service = AiChatService()
        val response = Response.Builder()
            .request(Request.Builder().url(TEST_STREAM_URL).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body((
                "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: definitely-not-json\n\n"
                ).toResponseBody(TEST_EVENT_STREAM_TYPE.toMediaType()))
            .build()

        val text = service.readSseResponse(
            response, service::extractOpenAiCompatibleStreamText,
            service::isOpenAiCompatibleStreamComplete, {}, completeOnDoneSentinel = true
        )

        assertEquals(STREAM_HELLO, text)
    }

    @Test
    fun joinsMultiLineSseDataFieldsAtEventBoundary() {
        val service = AiChatService()
        val response = Response.Builder()
            .request(Request.Builder().url(TEST_STREAM_URL).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body((
                "data: {\"choices\":[\n" +
                    "data: {\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}\n" +
                    "data: ]}\n\n"
                ).toResponseBody(TEST_EVENT_STREAM_TYPE.toMediaType()))
            .build()

        val text = service.readSseResponse(
            response, service::extractOpenAiCompatibleStreamText,
            service::isOpenAiCompatibleStreamComplete, {}, completeOnDoneSentinel = true
        )

        assertEquals(STREAM_HELLO, text)
    }

    @Test
    fun streamingDeltaCallbackFailurePropagates() {
        val service = AiChatService()
        val response = Response.Builder()
            .request(Request.Builder().url(TEST_STREAM_URL).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                TEST_OPENAI_TEXT_DELTA_SSE
                    .toResponseBody(TEST_EVENT_STREAM_TYPE.toMediaType())
            )
            .build()

        val result = runCatching {
            service.readSseResponse(
                response = response,
                extractText = service::extractOpenAiStreamText,
                isComplete = service::isOpenAiStreamComplete,
                onTextDelta = { error("UI callback failed") }
            )
        }

        assertTrue(result.isFailure)
        assertEquals("Streaming text callback failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun streamingDeltaCallbackCancellationIsPreserved() {
        val service = AiChatService()
        val response = Response.Builder()
            .request(Request.Builder().url(TEST_STREAM_URL).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                TEST_OPENAI_TEXT_DELTA_SSE
                    .toResponseBody(TEST_EVENT_STREAM_TYPE.toMediaType())
            )
            .build()

        val result = runCatching {
            service.readSseResponse(
                response = response,
                extractText = service::extractOpenAiStreamText,
                isComplete = service::isOpenAiStreamComplete,
                onTextDelta = { throw CancellationException("stopped") }
            )
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals("stopped", result.exceptionOrNull()?.message)
    }

}

package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderRuntimeCapabilitiesTest {
    @Test
    fun openAiExposesLightweightProcessingModesWhileOtherTransportsStayAutomatic() {
        assertEquals(
            listOf(
                ApiProcessingMode.AUTO,
                ApiProcessingMode.STANDARD,
                ApiProcessingMode.FLEX,
                ApiProcessingMode.FAST,
            ),
            AiProvider.CHATGPT.supportedApiProcessingModes(),
        )
        assertEquals(
            listOf(ApiProcessingMode.AUTO),
            AiProvider.GEMINI.supportedApiProcessingModes(),
        )
        assertEquals(
            ApiProcessingMode.AUTO,
            AiProvider.CLAUDE.normalizeApiProcessingMode(ApiProcessingMode.FLEX),
        )
    }

    @Test
    fun nativeProvidersKeepNativeSystemInstructionFields() {
        assertEquals(
            SystemInstructionPlacement.NATIVE_FIELD,
            AiProvider.GEMINI.runtimeCapabilities().systemInstructionPlacement
        )
        assertEquals(
            SystemInstructionPlacement.NATIVE_FIELD,
            AiProvider.CHATGPT.runtimeCapabilities().systemInstructionPlacement
        )
        assertEquals(
            SystemInstructionPlacement.NATIVE_FIELD,
            AiProvider.CLAUDE.runtimeCapabilities().systemInstructionPlacement
        )
    }

    @Test
    fun directProvidersExposeVerifiedClientToolStrategies() {
        assertEquals(
            ClientToolCallingStrategy.GEMINI_FUNCTIONS,
            AiProvider.GEMINI.runtimeCapabilities().clientToolCallingStrategy
        )
        assertEquals(
            ClientToolCallingStrategy.OPENAI_RESPONSES_FUNCTIONS,
            AiProvider.CHATGPT.runtimeCapabilities().clientToolCallingStrategy
        )
        assertEquals(
            ClientToolCallingStrategy.ANTHROPIC_CLIENT_TOOLS,
            AiProvider.CLAUDE.runtimeCapabilities().clientToolCallingStrategy
        )
        assertEquals(
            ClientToolCallingStrategy.PER_PROVIDER,
            AiProvider.ALL.runtimeCapabilities().clientToolCallingStrategy
        )
    }

    @Test
    fun compatibleGatewaysUseSystemMessagesAndExposeResolvedModels() {
        listOf(
            AiProvider.DEEPSEEK,
            AiProvider.KIMI,
            AiProvider.OPENROUTER,
            AiProvider.AIHUBMIX,
            AiProvider.VERCEL
        ).forEach { provider ->
            val capabilities = provider.runtimeCapabilities()
            assertEquals(
                NativeChatTransport.OPENAI_COMPATIBLE_CHAT_COMPLETIONS,
                capabilities.transport
            )
            assertEquals(SystemInstructionPlacement.SYSTEM_MESSAGE, capabilities.systemInstructionPlacement)
            assertTrue(capabilities.streamsText)
            assertTrue(capabilities.reportsResolvedModel)
            assertEquals(ClientToolCallingStrategy.NONE, capabilities.clientToolCallingStrategy)
        }
    }

    @Test
    fun geminiPreservesOpaqueProviderContentState() {
        assertEquals(
            ConversationStateStrategy.BOUNDED_PROVIDER_CONTENT_REPLAY,
            AiProvider.GEMINI.runtimeCapabilities().conversationStateStrategy
        )
    }

    @Test
    fun openAiPreservesOpaqueStatelessResponseItems() {
        assertEquals(
            ConversationStateStrategy.BOUNDED_PROVIDER_CONTENT_REPLAY,
            AiProvider.CHATGPT.runtimeCapabilities().conversationStateStrategy
        )
    }

    @Test
    fun claudeUsesModelCapabilityMetadataAndOpaqueContentReplay() {
        assertEquals(
            ReasoningControlStrategy.MODEL_CAPABILITY_METADATA,
            AiProvider.CLAUDE.reasoningControlStrategy()
        )
        assertEquals(
            ConversationStateStrategy.BOUNDED_PROVIDER_CONTENT_REPLAY,
            AiProvider.CLAUDE.runtimeCapabilities().conversationStateStrategy
        )
    }

    @Test
    fun claudeReasoningMetadataParserUsesReportedThinkingAndEffortLevels() {
        val raw = """
            {
              "capabilities": {
                "thinking": {
                  "supported": true,
                  "types": {
                    "adaptive": {"supported": true},
                    "enabled": {"supported": false}
                  }
                },
                "effort": {
                  "supported": true,
                  "high": {"supported": true}
                }
              }
            }
        """.trimIndent()

        assertEquals(
            ClaudeReasoningCapabilities(
                supportsAdaptive = true,
                supportsHighEffort = true
            ),
            parseClaudeReasoningCapabilities(raw)
        )
    }

    @Test
    fun claudeLegacyThinkingBudgetIsBoundedByOutputLimit() {
        assertEquals(4096, resolveClaudeThinkingBudget(64_000))
        assertEquals(1024, resolveClaudeThinkingBudget(2048))
        assertEquals(null, resolveClaudeThinkingBudget(1024))
    }

    @Test
    fun compareModeIsFanOutRatherThanAProviderTransport() {
        val capabilities = AiProvider.ALL.runtimeCapabilities()

        assertEquals(NativeChatTransport.COMPARE_FAN_OUT, capabilities.transport)
        assertEquals(ConversationStateStrategy.PROVIDER_FAN_OUT, capabilities.conversationStateStrategy)
        assertFalse(capabilities.reportsResolvedModel)
    }
}

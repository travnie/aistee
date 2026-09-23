package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConversationHistoryTest {
    @Test
    fun completedAssistantResponseClassificationIsPortable() {
        val response = ModelChatMessage(
            id = "response",
            sender = CHAT_ROLE_ASSISTANT,
            provider = AiProvider.CHATGPT,
            text = "Useful answer"
        )

        assertTrue(response.isCompletedAssistantResponse())
    }

    @Test
    fun providerReplayStateIsTransientInSerialization() {
        val encoded = Json.encodeToString(
            ModelChatMessage(
                id = "serialized",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.GEMINI,
                modelName = "gemini-test",
                text = "visible",
                providerReplayState = "opaque-provider-state"
            )
        )

        assertFalse("providerReplayState" in encoded)
        assertFalse("opaque-provider-state" in encoded)
    }

    @Test
    fun incompleteOrNonResponseMessagesAreNotCompletedAssistantResponses() {
        val partial = ModelChatMessage(
            id = "partial",
            sender = CHAT_ROLE_ASSISTANT,
            provider = AiProvider.CHATGPT,
            text = "Half an answer",
            isPartial = true
        )
        val error = ModelChatMessage(
            id = "error",
            sender = CHAT_ROLE_ASSISTANT,
            provider = AiProvider.CHATGPT,
            text = "Provider failed",
            isError = true
        )
        val onboarding = ModelChatMessage(
            id = "welcome",
            sender = CHAT_ROLE_ASSISTANT,
            provider = AiProvider.ALL,
            text = "Welcome"
        )
        val user = ModelChatMessage(
            id = "user",
            sender = CHAT_ROLE_USER,
            provider = AiProvider.CHATGPT,
            text = "Prompt"
        )
        val blank = ModelChatMessage(
            id = "blank",
            sender = CHAT_ROLE_ASSISTANT,
            provider = AiProvider.CHATGPT,
            text = "   "
        )

        listOf(partial, error, onboarding, user, blank).forEach { message ->
            assertFalse(message.isCompletedAssistantResponse())
        }
    }

    @Test
    fun keepsOnlyCurrentProviderLiveAssistantTurns() {
        val prompt = "follow up"
        val first = "first"
        val openAiAnswer = "openai answer"
        val claudeAnswer = "claude answer"
        val simulated = "simulated"
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = first),
            ModelChatMessage(
                id = "gpt", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CHATGPT,
                text = openAiAnswer
            ),
            ModelChatMessage(
                id = "claude", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CLAUDE,
                text = claudeAnswer
            ),
            ModelChatMessage(
                id = "sim", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CHATGPT,
                text = simulated, isSimulated = true
            ),
            ModelChatMessage(id = "u2", sender = CHAT_ROLE_USER, text = prompt)
        )

        val turns = buildBoundedProviderTextTurns(prompt, history, AiProvider.CHATGPT)

        assertEquals(
            listOf(CHAT_ROLE_USER, CHAT_ROLE_ASSISTANT, CHAT_ROLE_USER),
            turns.map { it.role }
        )
        assertEquals(listOf(first, openAiAnswer, prompt), turns.map { it.text })
        assertFalse(turns.any { it.text == claudeAnswer || it.text == simulated })
    }

    @Test
    fun importedAssistantTurnsReplayAsPortableTextWithoutProviderIdentity() {
        val history = listOf(
            ModelChatMessage(
                id = "import-user",
                sender = CHAT_ROLE_USER,
                text = "imported question",
                isImported = true,
            ),
            ModelChatMessage(
                id = "import-assistant",
                sender = CHAT_ROLE_ASSISTANT,
                provider = null,
                text = "imported answer",
                isImported = true,
            ),
            ModelChatMessage(id = "current", sender = CHAT_ROLE_USER, text = "continue"),
        )

        listOf(AiProvider.CHATGPT, AiProvider.CLAUDE).forEach { provider ->
            assertEquals(
                listOf("imported question", "imported answer", "continue"),
                buildBoundedProviderTextTurns(
                    prompt = "continue",
                    conversationHistory = history,
                    provider = provider,
                ).map { it.text },
            )
        }
    }

    @Test
    fun providerlessAssistantWithoutImportedProvenanceIsNotReplayed() {
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = "private"),
            ModelChatMessage(
                id = "a1",
                sender = CHAT_ROLE_ASSISTANT,
                provider = null,
                text = "untrusted provenance",
            ),
            ModelChatMessage(id = "u2", sender = CHAT_ROLE_USER, text = "next"),
        )

        assertEquals(
            listOf("next"),
            buildBoundedProviderTextTurns(
                prompt = "next",
                conversationHistory = history,
                provider = AiProvider.CHATGPT,
            ).map { it.text },
        )
    }

    @Test
    fun switchingProviderDoesNotReplayUnseenUserPrompts() {
        val prompt = "ask claude now"
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = "private chatgpt prompt"),
            ModelChatMessage(
                id = "gpt-previous", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CHATGPT,
                text = "chatgpt answer"
            ),
            ModelChatMessage(id = "u2", sender = CHAT_ROLE_USER, text = prompt)
        )

        val turns = buildBoundedProviderTextTurns(prompt, history, AiProvider.CLAUDE)

        assertEquals(listOf(prompt), turns.map { it.text })
    }

    @Test
    fun truncationKeepsNewestCompleteTurnsWithoutOrphanAssistant() {
        val prompt = "new prompt"
        val recentUser = "recent user"
        val recentAssistant = "recent assistant"
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = "old user"),
            ModelChatMessage(
                id = "a1", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CLAUDE,
                text = "old assistant"
            ),
            ModelChatMessage(id = "u2", sender = CHAT_ROLE_USER, text = recentUser),
            ModelChatMessage(
                id = "a2", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CLAUDE,
                text = recentAssistant
            ),
            ModelChatMessage(id = "u3", sender = CHAT_ROLE_USER, text = prompt)
        )

        val recentSegmentCost = recentUser.length + recentAssistant.length + (2 * 32)
        val turns = buildBoundedProviderTextTurns(
            prompt = prompt,
            conversationHistory = history,
            provider = AiProvider.CLAUDE,
            maxHistoryCharacters = prompt.length + recentSegmentCost,
            maxHistoryTurns = 8
        )

        assertEquals(
            listOf(recentUser, recentAssistant, prompt),
            turns.map { it.text }
        )
        assertEquals(CHAT_ROLE_USER, turns.first().role)
    }

    @Test
    fun systemInstructionConsumesHistoryBudget() {
        val prompt = "next"
        val previous = "previous"
        val answer = "answer"
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = previous),
            ModelChatMessage(
                id = "a1", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.GEMINI,
                text = answer
            )
        )
        val segmentCost = previous.length + answer.length + (2 * 32)

        val turns = buildBoundedProviderTextTurns(
            prompt = prompt,
            conversationHistory = history,
            provider = AiProvider.GEMINI,
            systemInstruction = "system",
            maxHistoryCharacters = prompt.length + segmentCost,
            maxHistoryTurns = 8
        )

        assertEquals(listOf(prompt), turns.map { it.text })
    }

    @Test
    fun crossModelReplayStateDoesNotConsumeVisibleFallbackBudget() {
        val prompt = "next"
        val previous = "previous"
        val answer = "answer"
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = previous),
            ModelChatMessage(
                id = "a1",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.GEMINI,
                modelName = "gemini-old",
                text = answer,
                providerReplayState = "x".repeat(4_096)
            )
        )
        val visibleSegmentCost = previous.length + answer.length + (2 * 32)

        val turns = buildBoundedProviderTextTurns(
            prompt = prompt,
            conversationHistory = history,
            provider = AiProvider.GEMINI,
            replayStateModelName = "gemini-new",
            maxHistoryCharacters = prompt.length + visibleSegmentCost,
            maxHistoryTurns = 8
        )

        assertEquals(listOf(previous, answer, prompt), turns.map { it.text })
        assertEquals(null, turns[1].providerReplayState)
    }

    @Test
    fun invalidSameModelReplayStateDoesNotConsumeVisibleFallbackBudget() {
        val prompt = "next"
        val previous = "previous"
        val answer = "answer"
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = previous),
            ModelChatMessage(
                id = "a1",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.GEMINI,
                modelName = "gemini-current",
                text = answer,
                providerReplayState = "broken".repeat(1_024)
            )
        )
        val visibleSegmentCost = previous.length + answer.length + (2 * 32)

        val turns = buildBoundedProviderTextTurns(
            prompt = prompt,
            conversationHistory = history,
            provider = AiProvider.GEMINI,
            replayStateModelName = "gemini-current",
            replayStateValidator = { false },
            maxHistoryCharacters = prompt.length + visibleSegmentCost,
            maxHistoryTurns = 8
        )

        assertEquals(listOf(previous, answer, prompt), turns.map { it.text })
        assertEquals(null, turns[1].providerReplayState)
    }

    @Test
    fun partialAssistantResponsesAreNotReplayed() {
        val history = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = "first"),
            ModelChatMessage(
                id = "partial",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.CHATGPT,
                text = "half an answer",
                isPartial = true
            ),
            ModelChatMessage(id = "u2", sender = CHAT_ROLE_USER, text = "next")
        )

        val turns = buildBoundedProviderTextTurns(
            prompt = "next",
            conversationHistory = history,
            provider = AiProvider.CHATGPT
        )

        assertEquals(listOf(ProviderTextTurn(CHAT_ROLE_USER, "next")), turns)
    }
}

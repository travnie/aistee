package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMarkdownExportTest {
    @Test
    fun exportsConversationFromFirstUserTurnWithProviderProvenance() {
        val markdown = renderChatMarkdown(
            listOf(
                ModelChatMessage(
                    id = "welcome",
                    sender = CHAT_ROLE_ASSISTANT,
                    provider = AiProvider.ALL,
                    text = "Welcome boilerplate"
                ),
                ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = "**Question**"),
                ModelChatMessage(
                    id = "gpt",
                    sender = CHAT_ROLE_ASSISTANT,
                    provider = AiProvider.CHATGPT,
                    modelName = "gpt-5.6",
                    text = "Answer"
                ),
                ModelChatMessage(
                    id = "claude",
                    sender = CHAT_ROLE_ASSISTANT,
                    provider = AiProvider.CLAUDE,
                    modelName = "claude-sonnet-5",
                    text = "Other answer",
                    isSimulated = true
                )
            )
        )

        assertEquals(
            "# Aistee chat\n\n" +
                "<!-- llmbench-chat:v1 -->\n\n" +
                "<!-- llmbench-message:v1 role=user bytes=12 -->\n" +
                "## You\n\n**Question**\n<!-- llmbench-message-end -->\n\n" +
                "<!-- llmbench-message:v1 role=assistant bytes=6 -->\n" +
                "## OpenAI · gpt-5.6\n\nAnswer\n<!-- llmbench-message-end -->\n\n" +
                "<!-- llmbench-message:v1 role=assistant bytes=12 -->\n" +
                "## Claude · claude-sonnet-5 · simulated\n\n" +
                "Other answer\n<!-- llmbench-message-end -->\n\n",
            assertNotNull(markdown)
        )
    }

    @Test
    fun excludesNonConversationRolesAndInternalDiagnostics() {
        val markdown = assertNotNull(
            renderChatMarkdown(
                listOf(
                    ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = "Keep me"),
                    ModelChatMessage(id = "system", sender = "system", text = "PRIVATE SYSTEM TEXT"),
                    ModelChatMessage(
                        id = "a1",
                        sender = CHAT_ROLE_ASSISTANT,
                        provider = AiProvider.GEMINI,
                        modelName = "gemini\nheading-injection",
                        text = "Visible response",
                        latencyMs = 987654321,
                        usage = ProviderUsage(
                            inputTokens = 1234567,
                            reasoningTokens = 7654321,
                            costUsd = 98.7654321,
                        ),
                        activeProfileNotes = listOf("PRIVATE PROFILE NOTE"),
                        providerReplayState = "PRIVATE HIDDEN REASONING HANDLE",
                    )
                )
            )
        )

        assertTrue(markdown.contains("Keep me"))
        assertTrue(markdown.contains("Visible response"))
        assertTrue(markdown.contains("## Gemini · gemini heading-injection"))
        assertFalse(markdown.contains("PRIVATE SYSTEM TEXT"))
        assertFalse(markdown.contains("PRIVATE PROFILE NOTE"))
        assertFalse(markdown.contains("PRIVATE HIDDEN REASONING HANDLE"))
        assertFalse(markdown.contains("987654321"))
        assertFalse(markdown.contains("1234567"))
        assertFalse(markdown.contains("7654321"))
        assertFalse(markdown.contains("98.7654321"))
        assertFalse(markdown.contains("## heading-injection"))
    }

    @Test
    fun preservesMarkdownMessageBody() {
        val body = "# Result\n\n- one\n- two\n\n```kotlin\nprintln(1)\n```"
        val markdown = assertNotNull(
            renderChatMarkdown(
                listOf(ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = body))
            )
        )

        assertTrue(markdown.contains(body))
        assertTrue(markdown.contains("bytes=${body.encodeToByteArray().size}"))
    }

    @Test
    fun respectsUtf8ByteLimitIncludingMultibyteText() {
        val body = "Zażółć 😀"
        val messages = listOf(
            ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = body)
        )
        val markdown = assertNotNull(renderChatMarkdown(messages))
        val exactUtf8Bytes = markdown.encodeToByteArray().size

        assertTrue(markdown.contains("bytes=${body.encodeToByteArray().size}"))
        assertEquals(markdown, renderChatMarkdown(messages, maxUtf8Bytes = exactUtf8Bytes))
        assertNull(renderChatMarkdown(messages, maxUtf8Bytes = exactUtf8Bytes - 1))
    }

    @Test
    fun doesNotExportWelcomeOnlyState() {
        assertNull(
            renderChatMarkdown(
                listOf(ModelChatMessage(id = "welcome", sender = CHAT_ROLE_ASSISTANT, text = "Hello"))
            )
        )
    }
}

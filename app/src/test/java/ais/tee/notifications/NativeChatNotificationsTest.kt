package ais.tee.notifications

import ais.tee.data.model.AiProvider
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NATIVE_CHAT_WELCOME_MESSAGE_ID
import ais.tee.data.model.NativeChatConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatNotificationsTest {
    private fun conversation(messages: List<ModelChatMessage>): NativeChatConversation =
        NativeChatConversation(
            id = "chat-1",
            title = "Secret project",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 10L,
            messages = messages,
        )

    private val userMessage = ModelChatMessage(
        id = "user-1",
        sender = CHAT_ROLE_USER,
        text = "Sensitive question",
        timestamp = 100L,
    )

    private val assistantMessage = ModelChatMessage(
        id = "assistant-1",
        sender = CHAT_ROLE_ASSISTANT,
        provider = AiProvider.CHATGPT,
        modelName = AiProvider.CHATGPT.defaultModel,
        text = "Sensitive answer",
        timestamp = 200L,
    )

    @Test
    fun notificationPreferencesArePrivacySafeByDefault() {
        val preferences = NativeChatNotificationPreferences()

        assertFalse(preferences.enabled)
        assertFalse(preferences.showConversationTitles)
        assertFalse(preferences.showMessagePreviews)
    }

    @Test
    fun notificationContentRedactsTitleAndBodiesByDefault() {
        val content = buildNativeChatNotificationContent(
            conversation = conversation(listOf(userMessage, assistantMessage)),
            preferences = NativeChatNotificationPreferences(enabled = true),
        )

        requireNotNull(content)
        assertEquals("AI conversation", content.title)
        assertEquals(
            listOf("Your message", "AI response"),
            content.messages.map { it.text },
        )
        assertEquals(
            listOf(false, true),
            content.messages.map { it.isAssistant },
        )
    }

    @Test
    fun notificationContentCanExposeTitleAndBodiesAfterExplicitOptIn() {
        val content = buildNativeChatNotificationContent(
            conversation = conversation(listOf(userMessage, assistantMessage)),
            preferences = NativeChatNotificationPreferences(
                enabled = true,
                showConversationTitles = true,
                showMessagePreviews = true,
            ),
        )

        requireNotNull(content)
        assertEquals("Secret project", content.title)
        assertEquals(
            listOf("Sensitive question", "Sensitive answer"),
            content.messages.map { it.text },
        )
    }

    @Test
    fun notificationContentSkipsUnsafeAssistantEntriesAndWelcomeMessage() {
        val messages = listOf(
            ModelChatMessage(
                id = NATIVE_CHAT_WELCOME_MESSAGE_ID,
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.ALL,
                text = "Welcome",
                timestamp = 10L,
            ),
            userMessage,
            ModelChatMessage(
                id = "partial",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.CHATGPT,
                text = "Streaming",
                timestamp = 120L,
                isPartial = true,
            ),
            ModelChatMessage(
                id = "error",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.CHATGPT,
                text = "Provider failed",
                timestamp = 130L,
                isError = true,
            ),
            ModelChatMessage(
                id = "simulated",
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.CHATGPT,
                text = "Simulated response",
                timestamp = 140L,
                isSimulated = true,
            ),
            assistantMessage,
        )

        val content = buildNativeChatNotificationContent(
            conversation = conversation(messages),
            preferences = NativeChatNotificationPreferences(
                enabled = true,
                showConversationTitles = true,
                showMessagePreviews = true,
            ),
        )

        requireNotNull(content)
        assertEquals(
            listOf("Sensitive question", "Sensitive answer"),
            content.messages.map { it.text },
        )
    }

    @Test
    fun notificationContentRequiresACompletedAssistantResponse() {
        assertNull(
            buildNativeChatNotificationContent(
                conversation = conversation(listOf(userMessage)),
                preferences = NativeChatNotificationPreferences(enabled = true),
            )
        )
    }

    @Test
    fun publisherGateRequiresOptInPermissionAndBackgroundState() {
        assertTrue(
            shouldPostNativeChatNotification(
                enabled = true,
                appVisible = false,
                systemNotificationsAllowed = true,
            )
        )
        assertFalse(
            shouldPostNativeChatNotification(
                enabled = false,
                appVisible = false,
                systemNotificationsAllowed = true,
            )
        )
        assertFalse(
            shouldPostNativeChatNotification(
                enabled = true,
                appVisible = true,
                systemNotificationsAllowed = true,
            )
        )
        assertFalse(
            shouldPostNativeChatNotification(
                enabled = true,
                appVisible = false,
                systemNotificationsAllowed = false,
            )
        )
    }
}

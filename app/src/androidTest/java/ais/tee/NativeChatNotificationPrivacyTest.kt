package ais.tee

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ais.tee.data.model.AiProvider
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NativeChatConversation
import ais.tee.data.model.ApiKeyConfig
import ais.tee.data.security.ApiKeyStore
import ais.tee.notifications.NativeChatNotificationPreferences
import ais.tee.notifications.NativeChatNotificationPreferencesStore
import ais.tee.notifications.NativeChatNotificationPublisher
import ais.tee.notifications.NativeChatNotificationVisibility
import ais.tee.security.QuickPrivacyModeController
import ais.tee.security.QuickPrivacyModeStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import ais.tee.notifications.buildNativeChatReplyInput
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeChatNotificationPrivacyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = NativeChatNotificationPreferencesStore(context)
    private val originalPreferences = preferences.load()
    private val apiKeyStore = ApiKeyStore(context)
    private val originalApiKeys = apiKeyStore.load()
    private val originalVisibility = NativeChatNotificationVisibility.isAppVisible()
    private val originalQuickPrivacy = QuickPrivacyModeStore.get(context).enabled.value
    private val disclosed = NativeChatNotificationPreferences(true, true, true)
    private val chatId = "privacy-test-conversation"
    private val unrelatedId = 9821
    private val unrelatedChannel = "privacy-test-unrelated"

    @Before
    fun setUp() {
        runBlocking { QuickPrivacyModeController.setEnabled(context, false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NativeChatNotificationVisibility.setAppVisible(false)
        manager.createNotificationChannel(
            NotificationChannel(unrelatedChannel, "Unrelated test notification", NotificationManager.IMPORTANCE_LOW)
        )
        manager.notify(
            unrelatedId,
            NotificationCompat.Builder(context, unrelatedChannel)
                .setSmallIcon(R.drawable.ic_quick_settings)
                .setContentTitle("Unrelated notification")
                .build(),
        )
    }

    @After
    fun tearDown() {
        NativeChatNotificationPublisher.cancelConversation(context, chatId)
        manager.cancel(unrelatedId)
        manager.deleteNotificationChannel(unrelatedChannel)
        preferences.save(originalPreferences)
        apiKeyStore.save(originalApiKeys)
        NativeChatNotificationVisibility.setAppVisible(originalVisibility)
        runBlocking { QuickPrivacyModeController.setEnabled(context, originalQuickPrivacy) }
    }

    @Test
    fun quickPrivacyRemovesPostedChatsOnlyAndPreservesStoredChoices() = runBlocking {
        preferences.save(disclosed)
        postConversation()
        awaitChatPresence(true)

        QuickPrivacyModeController.setEnabled(context, true)

        awaitChatPresence(false)
        assertTrue(manager.activeNotifications.any { it.id == unrelatedId })
        assertEquals(disclosed, preferences.load())
    }

    @Test
    fun replyPayloadUsesSerializedByteLimitWithoutCrashing() {
        assertNotNull(buildNativeChatReplyInput("reply", "chat", "a".repeat(4_000)))
        assertNotNull(buildNativeChatReplyInput("reply", "chat", "😀".repeat(100)))
        assertNull(buildNativeChatReplyInput("reply", "chat", "界".repeat(4_000)))
        assertNull(buildNativeChatReplyInput("reply", "x".repeat(12_000), "hello"))
    }

    @Test
    fun tighteningEitherPreviewSettingRemovesAlreadyPostedChatsOnly() {
        for (next in listOf(
            disclosed.copy(showConversationTitles = false),
            disclosed.copy(showMessagePreviews = false),
            disclosed.copy(enabled = false),
        )) {
            preferences.save(disclosed)
            postConversation()
            awaitChatPresence(true)

            // Use a new store, as settings and publisher use separate instances.
            NativeChatNotificationPreferencesStore(context).save(next)

            awaitChatPresence(false)
            assertTrue(manager.activeNotifications.any { it.id == unrelatedId })
        }
    }

    @Test
    fun unchangedOrRelaxedPreferencesKeepExistingNotifications() {
        val redacted = NativeChatNotificationPreferences(enabled = true)
        preferences.save(redacted)
        postConversation()
        awaitChatPresence(true)

        preferences.save(redacted)
        assertTrue(chatIsPosted())
        preferences.save(disclosed)
        assertTrue(chatIsPosted())
    }

    @Test
    fun configuredNativeChatNotificationOffersRemoteInputReply() {
        preferences.save(disclosed)
        apiKeyStore.save(ApiKeyConfig(openAiKey = "test-key"))
        postConversation()
        awaitChatPresence(true)

        val notification = manager.activeNotifications
            .first { it.tag == "native-chat:$chatId" }
            .notification
        val replyAction = notification.actions
            ?.firstOrNull { it.title?.toString() == context.getString(R.string.notification_reply_action) }

        assertNotNull(replyAction)
        val remoteInputs = replyAction!!.remoteInputs
        assertEquals(1, remoteInputs?.size)
        assertEquals(
            ais.tee.notifications.NATIVE_CHAT_DIRECT_REPLY_RESULT_KEY,
            remoteInputs?.firstOrNull()?.resultKey,
        )
    }

    private fun postConversation() {
        val now = System.currentTimeMillis()
        val conversation = NativeChatConversation(
            id = chatId,
            title = "Sensitive conversation title",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            messages = listOf(
                ModelChatMessage(id = "question", sender = CHAT_ROLE_USER, text = "Private question", timestamp = now),
                ModelChatMessage(
                    id = "answer", sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CHATGPT,
                    text = "Private answer", timestamp = now,
                ),
            ),
        )
        assertTrue(NativeChatNotificationPublisher.publishConversation(context, conversation))
    }

    private fun chatIsPosted(): Boolean =
        manager.activeNotifications.any { it.tag == "native-chat:$chatId" }

    private fun awaitChatPresence(expected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (chatIsPosted() != expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        assertTrue("Expected posted chat presence: $expected", chatIsPosted() == expected)
    }
}

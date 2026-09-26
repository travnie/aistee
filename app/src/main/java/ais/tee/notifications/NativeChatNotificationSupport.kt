package ais.tee.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import ais.tee.MainActivity
import ais.tee.R
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NATIVE_CHAT_WELCOME_MESSAGE_ID
import ais.tee.data.model.NativeChatConversation
import ais.tee.data.model.isCompletedAssistantResponse
import ais.tee.navigation.AisteeQuickActionNavigation
import ais.tee.security.QuickPrivacyModeStore
import ais.tee.security.effectivePrivacySurfaceVisibility
import java.util.concurrent.atomic.AtomicBoolean

// Keep preference revocation and background publication in one critical section.
private val notificationPrivacyLock = Any()

private const val PREFERENCES_NAME = "native_chat_notification_preferences"
private const val KEY_ENABLED = "enabled"
private const val KEY_SHOW_CONVERSATION_TITLES = "show_conversation_titles"
private const val KEY_SHOW_MESSAGE_PREVIEWS = "show_message_previews"
private const val CHANNEL_ID = "ai_conversations"
private const val NOTIFICATION_ID = 1
private const val NOTIFICATION_TAG_PREFIX = "native-chat:"
private const val MAX_NOTIFICATION_MESSAGES = 6
private const val MAX_NOTIFICATION_PREVIEW_CHARS = 240
private const val GENERIC_CONVERSATION_TITLE = "AI conversation"
private const val REDACTED_USER_MESSAGE = "Your message"
private const val REDACTED_ASSISTANT_MESSAGE = "AI response"

internal data class NativeChatNotificationPreferences(
    val enabled: Boolean = false,
    val showConversationTitles: Boolean = false,
    val showMessagePreviews: Boolean = false,
)

internal class NativeChatNotificationPreferencesStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): NativeChatNotificationPreferences =
        NativeChatNotificationPreferences(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            showConversationTitles = preferences.getBoolean(KEY_SHOW_CONVERSATION_TITLES, false),
            showMessagePreviews = preferences.getBoolean(KEY_SHOW_MESSAGE_PREVIEWS, false),
        )

    fun save(value: NativeChatNotificationPreferences) = synchronized(notificationPrivacyLock) {
        val previous = load()
        preferences.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putBoolean(KEY_SHOW_CONVERSATION_TITLES, value.showConversationTitles)
            .putBoolean(KEY_SHOW_MESSAGE_PREVIEWS, value.showMessagePreviews)
            .apply()
        if (!value.enabled ||
            (previous.showConversationTitles && !value.showConversationTitles) ||
            (previous.showMessagePreviews && !value.showMessagePreviews)
        ) {
            NativeChatNotificationPublisher.cancelPostedConversations(appContext)
        }
        if (previous.showConversationTitles && !value.showConversationTitles) {
            NativeChatConversationShortcuts.removeTitled(appContext)
        }
    }
}

internal fun effectiveNativeChatNotificationPreferences(
    context: Context,
): NativeChatNotificationPreferences {
    val stored = NativeChatNotificationPreferencesStore(context).load()
    val visibility = effectivePrivacySurfaceVisibility(
        showConversationTitles = stored.showConversationTitles,
        showMessagePreviews = stored.showMessagePreviews,
        quickPrivacyEnabled = QuickPrivacyModeStore.get(context).enabled.value,
    )
    return stored.copy(
        showConversationTitles = visibility.showConversationTitles,
        showMessagePreviews = visibility.showMessagePreviews,
    )
}

internal data class NativeChatNotificationMessage(
    val text: String,
    val timestamp: Long,
    val isAssistant: Boolean,
)

internal data class NativeChatNotificationContent(
    val conversationId: String,
    val title: String,
    val messages: List<NativeChatNotificationMessage>,
)

private fun String.notificationPreview(): String =
    trim().replace(Regex("\\s+"), " ").take(MAX_NOTIFICATION_PREVIEW_CHARS)

private fun ModelChatMessage.isNotifiableAssistantResponse(): Boolean =
    isCompletedAssistantResponse() &&
        !isSimulated &&
        id != NATIVE_CHAT_WELCOME_MESSAGE_ID

internal fun buildNativeChatNotificationContent(
    conversation: NativeChatConversation,
    preferences: NativeChatNotificationPreferences,
    limit: Int = MAX_NOTIFICATION_MESSAGES,
): NativeChatNotificationContent? {
    if (limit <= 0) return null
    val latestAssistantIndex = conversation.messages.indexOfLast { it.isNotifiableAssistantResponse() }
    val latestUserIndex = conversation.messages.indexOfLast { it.sender == CHAT_ROLE_USER }
    if (latestAssistantIndex < 0 || latestAssistantIndex < latestUserIndex) return null

    val visibleMessages = conversation.messages
        .take(latestAssistantIndex + 1)
        .mapNotNull { message ->
            when {
                message.sender == CHAT_ROLE_USER && message.text.isNotBlank() -> {
                    NativeChatNotificationMessage(
                        text = if (preferences.showMessagePreviews) {
                            message.text.notificationPreview()
                        } else {
                            REDACTED_USER_MESSAGE
                        },
                        timestamp = message.timestamp,
                        isAssistant = false,
                    )
                }
                message.isNotifiableAssistantResponse() -> {
                    NativeChatNotificationMessage(
                        text = if (preferences.showMessagePreviews) {
                            message.text.notificationPreview()
                        } else {
                            REDACTED_ASSISTANT_MESSAGE
                        },
                        timestamp = message.timestamp,
                        isAssistant = true,
                    )
                }
                else -> null
            }
        }
        .takeLast(limit)

    if (visibleMessages.none { it.isAssistant }) return null
    return NativeChatNotificationContent(
        conversationId = conversation.id,
        title = if (preferences.showConversationTitles) {
            conversation.title.trim().ifBlank { GENERIC_CONVERSATION_TITLE }
        } else {
            GENERIC_CONVERSATION_TITLE
        },
        messages = visibleMessages,
    )
}

internal fun shouldPostNativeChatNotification(
    enabled: Boolean,
    appVisible: Boolean,
    systemNotificationsAllowed: Boolean,
): Boolean = enabled && !appVisible && systemNotificationsAllowed

internal object NativeChatNotificationVisibility {
    private val appVisible = AtomicBoolean(false)

    fun setAppVisible(visible: Boolean) {
        appVisible.set(visible)
    }

    fun isAppVisible(): Boolean = appVisible.get()
}

internal object NativeChatNotificationPublisher {
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_ai_conversations_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_ai_conversations_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun systemNotificationsAllowed(context: Context): Boolean {
        val runtimePermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        return runtimePermissionGranted &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun publishConversation(context: Context, conversation: NativeChatConversation): Boolean = synchronized(notificationPrivacyLock) {
        val appContext = context.applicationContext
        val preferences = effectiveNativeChatNotificationPreferences(appContext)
        if (
            !shouldPostNativeChatNotification(
                enabled = preferences.enabled,
                appVisible = NativeChatNotificationVisibility.isAppVisible(),
                systemNotificationsAllowed = systemNotificationsAllowed(appContext),
            )
        ) {
            return@synchronized false
        }

        val content = buildNativeChatNotificationContent(conversation, preferences) ?: return@synchronized false
        ensureChannel(appContext)

        val you = Person.Builder()
            .setName("You")
            .build()
        val assistant = Person.Builder()
            .setName("Aistee")
            .setBot(true)
            .build()
        val style = NotificationCompat.MessagingStyle(you)
            .setGroupConversation(false)
        content.messages.forEach { message ->
            style.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    message.text,
                    message.timestamp,
                    if (message.isAssistant) assistant else you,
                )
            )
        }

        val launchIntent =
            AisteeQuickActionNavigation.nativeConversationLaunchIntent(appContext, content.conversationId)
                .setClass(appContext, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val latestMessage = content.messages.last()
        val shortcutId = NativeChatConversationShortcuts.publish(appContext, content.conversationId, content.title)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_settings)
            .setSubText(content.title)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(latestMessage.timestamp)
            .setShowWhen(true)
        shortcutId?.let(builder::setShortcutId)
        NativeChatDirectReply.action(appContext, conversation)?.let(builder::addAction)

        return@synchronized notify(
            appContext = appContext,
            conversationId = content.conversationId,
            notification = builder.build(),
        )
    }

    fun publishReplyInProgress(context: Context, conversation: NativeChatConversation): Boolean =
        publishReplyStatus(context, conversation, context.getString(R.string.notification_reply_sending))

    fun publishReplyFailed(context: Context, conversation: NativeChatConversation): Boolean =
        publishReplyStatus(context, conversation, context.getString(R.string.notification_reply_failed))

    private fun publishReplyStatus(
        context: Context,
        conversation: NativeChatConversation,
        status: String,
    ): Boolean = synchronized(notificationPrivacyLock) {
        val appContext = context.applicationContext
        val preferences = effectiveNativeChatNotificationPreferences(appContext)
        if (
            !shouldPostNativeChatNotification(
                enabled = preferences.enabled,
                appVisible = NativeChatNotificationVisibility.isAppVisible(),
                systemNotificationsAllowed = systemNotificationsAllowed(appContext),
            )
        ) return@synchronized false
        val latestAssistantIndex = conversation.messages.indexOfLast { it.isNotifiableAssistantResponse() }
        if (latestAssistantIndex < 0) return@synchronized false
        val statusConversation = conversation.copy(messages = conversation.messages.take(latestAssistantIndex + 1))
        val content = buildNativeChatNotificationContent(statusConversation, preferences) ?: return@synchronized false
        ensureChannel(appContext)

        val you = Person.Builder().setName("You").build()
        val assistant = Person.Builder().setName("Aistee").setBot(true).build()
        val style = NotificationCompat.MessagingStyle(you).setGroupConversation(false)
        content.messages.forEach { message ->
            style.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    message.text,
                    message.timestamp,
                    if (message.isAssistant) assistant else you,
                )
            )
        }
        val launchIntent =
            AisteeQuickActionNavigation.nativeConversationLaunchIntent(appContext, content.conversationId)
                .setClass(appContext, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val shortcutId = NativeChatConversationShortcuts.publish(appContext, content.conversationId, content.title)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_settings)
            .setSubText(content.title)
            .setContentText(status)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(content.messages.last().timestamp)
            .setShowWhen(true)
        shortcutId?.let(builder::setShortcutId)
        return@synchronized notify(appContext, content.conversationId, builder.build())
    }

    private fun notify(
        appContext: Context,
        conversationId: String,
        notification: Notification,
    ): Boolean = try {
        NotificationManagerCompat.from(appContext).notify(
            notificationTag(conversationId),
            NOTIFICATION_ID,
            notification,
        )
        true
    } catch (_: SecurityException) {
        false
    }

    fun cancelConversation(context: Context, conversationId: String) {
        val normalizedId = conversationId.trim()
        if (normalizedId.isEmpty()) return
        NotificationManagerCompat.from(context.applicationContext)
            .cancel(notificationTag(normalizedId), NOTIFICATION_ID)
    }

    fun cancelConversations(context: Context, conversationIds: Iterable<String>) {
        conversationIds.forEach { conversationId -> cancelConversation(context, conversationId) }
    }

    fun cancelPostedConversations(context: Context) = synchronized(notificationPrivacyLock) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { posted ->
                posted.id == NOTIFICATION_ID &&
                    posted.tag?.startsWith(NOTIFICATION_TAG_PREFIX) == true &&
                    posted.notification.channelId == CHANNEL_ID
            }
            .forEach { posted -> manager.cancel(posted.tag, posted.id) }
    }

    private fun notificationTag(conversationId: String): String =
        NOTIFICATION_TAG_PREFIX + conversationId
}

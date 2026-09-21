package ais.tee.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import ais.tee.R
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.NativeChatArchive
import ais.tee.data.preferences.AisteeWidgetMode
import ais.tee.data.preferences.AisteeWidgetPreferencesStore
import ais.tee.data.preferences.NativeChatStore
import ais.tee.navigation.AisteeQuickActionNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

private const val MAX_WIDGET_CONVERSATIONS = 3
private const val MAX_WIDGET_MESSAGES = 3
private const val MAX_WIDGET_MESSAGE_PREVIEW_CHARS = 180

internal data class NativeChatWidgetConversation(
    val id: String,
    val title: String,
)

internal data class NativeChatWidgetMessage(
    val conversationId: String,
    val messageId: String,
    val conversationTitle: String,
    val sender: String,
    val text: String,
    val timestamp: Long,
)

internal data class NativeChatWidgetArchiveFingerprint(
    val recentConversations: List<NativeChatWidgetConversation>,
    val conversationDirectory: List<NativeChatWidgetConversation>,
    val latestMessages: List<NativeChatWidgetMessage>,
)

internal fun privacySafeWidgetConversationTitle(
    title: String,
    hiddenTitle: String,
    showConversationTitles: Boolean,
): String = if (showConversationTitles) title else hiddenTitle

internal fun privacySafeWidgetMessagePreview(
    text: String,
    hiddenText: String,
    showMessagePreviews: Boolean,
): String = if (showMessagePreviews) text else hiddenText

internal fun recentNativeConversationsForWidget(
    archive: NativeChatArchive,
    limit: Int = MAX_WIDGET_CONVERSATIONS,
): List<NativeChatWidgetConversation> {
    if (limit <= 0) return emptyList()
    return archive.conversations
        .sortedWith(
            compareByDescending<ais.tee.data.model.NativeChatConversation> { it.updatedAtEpochMs }
                .thenByDescending { it.createdAtEpochMs }
                .thenBy { it.id }
        )
        .take(limit)
        .map { conversation ->
            NativeChatWidgetConversation(
                id = conversation.id,
                title = conversation.title,
            )
        }
}

internal fun latestNativeMessagesForWidget(
    archive: NativeChatArchive,
    limit: Int = MAX_WIDGET_MESSAGES,
): List<NativeChatWidgetMessage> {
    if (limit <= 0) return emptyList()
    return archive.conversations
        .asSequence()
        .flatMap { conversation ->
            conversation.messages.asSequence()
                .filter { message ->
                    (message.sender == CHAT_ROLE_USER || message.sender == CHAT_ROLE_ASSISTANT) &&
                        !message.isError &&
                        !message.isPartial &&
                        !message.isSimulated &&
                        message.text.isNotBlank()
                }
                .map { message ->
                    NativeChatWidgetMessage(
                        conversationId = conversation.id,
                        messageId = message.id,
                        conversationTitle = conversation.title,
                        sender = message.sender,
                        text = message.text.trim().take(MAX_WIDGET_MESSAGE_PREVIEW_CHARS),
                        timestamp = message.timestamp,
                    )
                }
        }
        .sortedWith(
            compareByDescending<NativeChatWidgetMessage> { it.timestamp }
                .thenBy { it.conversationId }
                .thenBy { it.messageId }
        )
        .take(limit)
        .toList()
}

internal fun pinnedNativeConversationForWidget(
    archive: NativeChatArchive,
    conversationId: String?,
): NativeChatWidgetConversation? {
    val id = conversationId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val conversation = archive.conversations.firstOrNull { it.id == id } ?: return null
    return NativeChatWidgetConversation(
        id = conversation.id,
        title = conversation.title,
    )
}

internal fun nativeChatWidgetArchiveFingerprint(
    archive: NativeChatArchive,
): NativeChatWidgetArchiveFingerprint =
    NativeChatWidgetArchiveFingerprint(
        recentConversations = recentNativeConversationsForWidget(archive),
        conversationDirectory = archive.conversations
            .map { conversation ->
                NativeChatWidgetConversation(
                    id = conversation.id,
                    title = conversation.title,
                )
            }
            .sortedBy { it.id },
        latestMessages = latestNativeMessagesForWidget(archive),
    )

private data class NativeChatWidgetRow(
    val id: String,
    val conversationId: String,
    val label: String,
)

private fun widgetItemId(itemId: String): Long =
    itemId.hashCode().toLong() and Long.MAX_VALUE

internal object NativeChatWidgetUpdater {
    private val lastFingerprint = AtomicReference<NativeChatWidgetArchiveFingerprint?>(null)

    suspend fun onArchiveSaved(context: Context, archive: NativeChatArchive) {
        val fingerprint = nativeChatWidgetArchiveFingerprint(archive)
        if (lastFingerprint.get() == fingerprint) return
        AisteeWidget().updateAll(context.applicationContext)
        lastFingerprint.set(fingerprint)
    }
}

class AisteeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val (archive, preferences) = withContext(Dispatchers.IO) {
            val loadedArchive = NativeChatStore(context.noBackupFilesDir).load() ?: NativeChatArchive()
            val widgetPreferences = AisteeWidgetPreferencesStore(context).load(appWidgetId)
            loadedArchive to widgetPreferences
        }

        val rows = when (preferences.mode) {
            AisteeWidgetMode.RECENT_CHATS ->
                recentNativeConversationsForWidget(archive).mapIndexed { index, conversation ->
                    NativeChatWidgetRow(
                        id = conversation.id,
                        conversationId = conversation.id,
                        label = privacySafeWidgetConversationTitle(
                            title = conversation.title,
                            hiddenTitle = context.getString(R.string.widget_recent_chat_hidden, index + 1),
                            showConversationTitles = preferences.showConversationTitles,
                        ),
                    )
                }
            AisteeWidgetMode.MESSAGES ->
                latestNativeMessagesForWidget(archive).map { message ->
                    val hiddenMessage = when (message.sender) {
                        CHAT_ROLE_USER -> context.getString(R.string.widget_user_message)
                        else -> context.getString(R.string.widget_assistant_message)
                    }
                    val preview = privacySafeWidgetMessagePreview(
                        text = message.text,
                        hiddenText = hiddenMessage,
                        showMessagePreviews = preferences.showMessagePreviews,
                    )
                    val label =
                        if (preferences.showConversationTitles) {
                            context.getString(
                                R.string.widget_message_with_conversation,
                                message.conversationTitle,
                                preview,
                            )
                        } else {
                            preview
                        }
                    NativeChatWidgetRow(
                        id = "${message.conversationId}:${message.messageId}",
                        conversationId = message.conversationId,
                        label = label,
                    )
                }
            AisteeWidgetMode.PINNED_CHAT ->
                listOfNotNull(
                    pinnedNativeConversationForWidget(
                        archive,
                        preferences.pinnedConversationId,
                    )?.let { conversation ->
                        NativeChatWidgetRow(
                            id = conversation.id,
                            conversationId = conversation.id,
                            label = privacySafeWidgetConversationTitle(
                                title = conversation.title,
                                hiddenTitle = context.getString(R.string.widget_pinned_chat),
                                showConversationTitles = preferences.showConversationTitles,
                            ),
                        )
                    }
                )
        }
        val sectionTitle = when (preferences.mode) {
            AisteeWidgetMode.RECENT_CHATS -> context.getString(R.string.widget_recent_chats)
            AisteeWidgetMode.MESSAGES -> context.getString(R.string.widget_messages)
            AisteeWidgetMode.PINNED_CHAT -> context.getString(R.string.widget_pinned_chat)
        }
        val emptyText = when (preferences.mode) {
            AisteeWidgetMode.RECENT_CHATS -> context.getString(R.string.widget_no_recent_chats)
            AisteeWidgetMode.MESSAGES -> context.getString(R.string.widget_no_messages)
            AisteeWidgetMode.PINNED_CHAT -> context.getString(R.string.widget_pinned_chat_unavailable)
        }

        provideContent {
            WidgetContent(
                context = context,
                sectionTitle = sectionTitle,
                emptyText = emptyText,
                rows = rows,
            )
        }
    }

    @Composable
    private fun WidgetContent(
        context: Context,
        sectionTitle: String,
        emptyText: String,
        rows: List<NativeChatWidgetRow>,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Aistee")
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WidgetButton(context, "Web AI", AisteeQuickActionNavigation.DESTINATION_WEB_AI)
                WidgetButton(context, "Compare", AisteeQuickActionNavigation.DESTINATION_COMPARE)
                WidgetButton(context, "Studio", AisteeQuickActionNavigation.DESTINATION_STUDIO)
            }
            Text(
                text = sectionTitle,
                modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (rows.isEmpty()) {
                Text(
                    text = emptyText,
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
                )
            } else {
                LazyColumn(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    items(
                        items = rows,
                        itemId = { row -> widgetItemId(row.id) },
                    ) { row ->
                        Button(
                            text = row.label,
                            onClick = actionStartActivity(
                                AisteeQuickActionNavigation.nativeConversationLaunchIntent(
                                    context,
                                    row.conversationId,
                                )
                            ),
                            modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun WidgetButton(context: Context, label: String, destination: String) {
        Button(
            text = label,
            onClick = actionStartActivity(AisteeQuickActionNavigation.launchIntent(context, destination)),
        )
    }
}

class AisteeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AisteeWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val preferences = AisteeWidgetPreferencesStore(context)
        appWidgetIds.forEach(preferences::remove)
    }
}

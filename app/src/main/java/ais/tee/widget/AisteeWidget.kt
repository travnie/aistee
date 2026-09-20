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
import ais.tee.data.model.NativeChatArchive
import ais.tee.data.preferences.AisteeWidgetMode
import ais.tee.data.preferences.AisteeWidgetPreferencesStore
import ais.tee.data.preferences.NativeChatStore
import ais.tee.navigation.AisteeQuickActionNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

private const val MAX_WIDGET_CONVERSATIONS = 3

internal data class NativeChatWidgetConversation(
    val id: String,
    val title: String,
)

internal data class NativeChatWidgetArchiveFingerprint(
    val recentConversations: List<NativeChatWidgetConversation>,
    val conversationDirectory: List<NativeChatWidgetConversation>,
)

internal fun privacySafeWidgetConversationTitle(
    title: String,
    hiddenTitle: String,
    showConversationTitles: Boolean,
): String = if (showConversationTitles) title else hiddenTitle

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
    )

private fun widgetItemId(conversationId: String): Long =
    conversationId.hashCode().toLong() and Long.MAX_VALUE

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

        val rawConversations = when (preferences.mode) {
            AisteeWidgetMode.RECENT_CHATS -> recentNativeConversationsForWidget(archive)
            AisteeWidgetMode.PINNED_CHAT ->
                listOfNotNull(
                    pinnedNativeConversationForWidget(
                        archive,
                        preferences.pinnedConversationId,
                    )
                )
        }
        val visibleConversations = rawConversations.mapIndexed { index, conversation ->
            val hiddenTitle = when (preferences.mode) {
                AisteeWidgetMode.RECENT_CHATS ->
                    context.getString(R.string.widget_recent_chat_hidden, index + 1)
                AisteeWidgetMode.PINNED_CHAT ->
                    context.getString(R.string.widget_pinned_chat)
            }
            conversation.copy(
                title = privacySafeWidgetConversationTitle(
                    title = conversation.title,
                    hiddenTitle = hiddenTitle,
                    showConversationTitles = preferences.showConversationTitles,
                )
            )
        }
        val sectionTitle = when (preferences.mode) {
            AisteeWidgetMode.RECENT_CHATS -> context.getString(R.string.widget_recent_chats)
            AisteeWidgetMode.PINNED_CHAT -> context.getString(R.string.widget_pinned_chat)
        }
        val emptyText = when (preferences.mode) {
            AisteeWidgetMode.RECENT_CHATS -> context.getString(R.string.widget_no_recent_chats)
            AisteeWidgetMode.PINNED_CHAT -> context.getString(R.string.widget_pinned_chat_unavailable)
        }

        provideContent {
            WidgetContent(
                context = context,
                sectionTitle = sectionTitle,
                emptyText = emptyText,
                conversations = visibleConversations,
            )
        }
    }

    @Composable
    private fun WidgetContent(
        context: Context,
        sectionTitle: String,
        emptyText: String,
        conversations: List<NativeChatWidgetConversation>,
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
            if (conversations.isEmpty()) {
                Text(
                    text = emptyText,
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
                )
            } else {
                LazyColumn(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    items(
                        items = conversations,
                        itemId = { conversation -> widgetItemId(conversation.id) },
                    ) { conversation ->
                        Button(
                            text = conversation.title,
                            onClick = actionStartActivity(
                                AisteeQuickActionNavigation.nativeConversationLaunchIntent(
                                    context,
                                    conversation.id,
                                )
                            ),
                            modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                            maxLines = 1,
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

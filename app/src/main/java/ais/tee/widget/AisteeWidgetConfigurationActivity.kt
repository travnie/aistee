package ais.tee.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import ais.tee.R
import ais.tee.data.model.NativeChatArchive
import ais.tee.data.preferences.AisteeWidgetMode
import ais.tee.data.preferences.AisteeWidgetPreferences
import ais.tee.data.preferences.AisteeWidgetPreferencesStore
import ais.tee.data.preferences.NativeChatStore
import ais.tee.ui.theme.AisteeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AisteeWidgetConfigurationActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val preferencesStore = AisteeWidgetPreferencesStore(this)
        val initialPreferences = preferencesStore.load(appWidgetId)
        lifecycleScope.launch {
            val conversations = withContext(Dispatchers.IO) {
                val archive = NativeChatStore(noBackupFilesDir).load() ?: NativeChatArchive()
                recentNativeConversationsForWidget(archive, Int.MAX_VALUE)
            }
            if (isFinishing || isDestroyed) return@launch

            val initialMode =
                if (initialPreferences.mode == AisteeWidgetMode.PINNED_CHAT && conversations.isEmpty()) {
                    AisteeWidgetMode.RECENT_CHATS
                } else {
                    initialPreferences.mode
                }
            val initialPinnedConversationId =
                initialPreferences.pinnedConversationId
                    ?.takeIf { savedId -> conversations.any { it.id == savedId } }
                    ?: conversations.firstOrNull()?.id

            setContent {
                AisteeTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        WidgetConfiguration(
                            conversations = conversations,
                            initialPreferences = initialPreferences.copy(
                                mode = initialMode,
                                pinnedConversationId = initialPinnedConversationId,
                            ),
                            onCancel = ::finish,
                            onSave = { preferences ->
                                saveAndFinish(preferencesStore, preferences)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun saveAndFinish(
        preferencesStore: AisteeWidgetPreferencesStore,
        preferences: AisteeWidgetPreferences,
    ) {
        preferencesStore.save(appWidgetId, preferences)
        lifecycleScope.launch {
            runCatching {
                val glanceId = GlanceAppWidgetManager(this@AisteeWidgetConfigurationActivity)
                    .getGlanceIdBy(appWidgetId)
                AisteeWidget().update(this@AisteeWidgetConfigurationActivity, glanceId)
            }
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            finish()
        }
    }
}

@Composable
private fun WidgetConfiguration(
    conversations: List<NativeChatWidgetConversation>,
    initialPreferences: AisteeWidgetPreferences,
    onCancel: () -> Unit,
    onSave: (AisteeWidgetPreferences) -> Unit,
) {
    var modeName by rememberSaveable { mutableStateOf(initialPreferences.mode.name) }
    var pinnedConversationId by rememberSaveable {
        mutableStateOf(initialPreferences.pinnedConversationId)
    }
    var showTitles by rememberSaveable {
        mutableStateOf(initialPreferences.showConversationTitles)
    }
    var showMessagePreviews by rememberSaveable {
        mutableStateOf(initialPreferences.showMessagePreviews)
    }
    val mode = AisteeWidgetMode.entries.firstOrNull { it.name == modeName }
        ?: AisteeWidgetMode.RECENT_CHATS
    val canSave = mode != AisteeWidgetMode.PINNED_CHAT || pinnedConversationId != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = stringResource(R.string.widget_config_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = stringResource(R.string.widget_config_mode),
            style = MaterialTheme.typography.titleMedium,
        )
        WidgetModeChoice(
            label = stringResource(R.string.widget_config_mode_recent),
            selected = mode == AisteeWidgetMode.RECENT_CHATS,
            enabled = true,
            onSelect = { modeName = AisteeWidgetMode.RECENT_CHATS.name },
        )
        WidgetModeChoice(
            label = stringResource(R.string.widget_config_mode_messages),
            selected = mode == AisteeWidgetMode.MESSAGES,
            enabled = true,
            onSelect = { modeName = AisteeWidgetMode.MESSAGES.name },
        )
        WidgetModeChoice(
            label = stringResource(R.string.widget_config_mode_pinned),
            selected = mode == AisteeWidgetMode.PINNED_CHAT,
            enabled = conversations.isNotEmpty(),
            onSelect = { modeName = AisteeWidgetMode.PINNED_CHAT.name },
        )

        if (mode == AisteeWidgetMode.PINNED_CHAT) {
            Text(
                text = stringResource(R.string.widget_config_pinned_conversation),
                style = MaterialTheme.typography.titleMedium,
            )
            if (conversations.isEmpty()) {
                Text(
                    text = stringResource(R.string.widget_config_no_conversations),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                conversations.forEach { conversation ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = pinnedConversationId == conversation.id,
                            onClick = { pinnedConversationId = conversation.id },
                        )
                        Text(
                            text = conversation.title,
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.widget_config_privacy_summary),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.widget_config_show_titles),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.widget_config_show_titles_summary),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = showTitles,
                onCheckedChange = { showTitles = it },
            )
        }

        if (mode == AisteeWidgetMode.MESSAGES) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.widget_config_show_message_previews),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.widget_config_show_message_previews_summary),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = showMessagePreviews,
                    onCheckedChange = { showMessagePreviews = it },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.widget_config_cancel))
            }
            Button(
                onClick = {
                    onSave(
                        AisteeWidgetPreferences(
                            mode = mode,
                            pinnedConversationId =
                                pinnedConversationId.takeIf {
                                    mode == AisteeWidgetMode.PINNED_CHAT
                                },
                            showConversationTitles = showTitles,
                            showMessagePreviews = showMessagePreviews,
                        )
                    )
                },
                enabled = canSave,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(stringResource(R.string.widget_config_save))
            }
        }
    }
}

@Composable
private fun WidgetModeChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

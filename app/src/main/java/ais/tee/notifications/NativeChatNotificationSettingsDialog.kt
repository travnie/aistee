package ais.tee.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import ais.tee.R

@Composable
internal fun NativeChatNotificationSettingsDialog(
    initialPreferences: NativeChatNotificationPreferences,
    onDismiss: () -> Unit,
    onSave: (NativeChatNotificationPreferences) -> Unit,
) {
    var enabled by rememberSaveable { mutableStateOf(initialPreferences.enabled) }
    var showTitles by rememberSaveable {
        mutableStateOf(initialPreferences.showConversationTitles)
    }
    var showPreviews by rememberSaveable {
        mutableStateOf(initialPreferences.showMessagePreviews)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notification_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NotificationSettingRow(
                    title = stringResource(R.string.notification_settings_enabled),
                    summary = stringResource(R.string.notification_settings_enabled_summary),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                NotificationSettingRow(
                    title = stringResource(R.string.notification_settings_show_titles),
                    summary = stringResource(R.string.notification_settings_show_titles_summary),
                    checked = showTitles,
                    enabled = enabled,
                    onCheckedChange = { showTitles = it },
                )
                NotificationSettingRow(
                    title = stringResource(R.string.notification_settings_show_previews),
                    summary = stringResource(R.string.notification_settings_show_previews_summary),
                    checked = showPreviews,
                    enabled = enabled,
                    onCheckedChange = { showPreviews = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        NativeChatNotificationPreferences(
                            enabled = enabled,
                            showConversationTitles = showTitles,
                            showMessagePreviews = showPreviews,
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.notification_settings_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.notification_settings_cancel))
            }
        },
    )
}

@Composable
private fun NotificationSettingRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(title)
            Text(summary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

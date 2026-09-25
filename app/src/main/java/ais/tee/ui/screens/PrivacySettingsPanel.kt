package ais.tee.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ais.tee.security.AppLock
import ais.tee.security.AppLockStore
import ais.tee.security.QuickPrivacyModeController
import ais.tee.security.QuickPrivacyModeStore
import ais.tee.security.ScreenPrivacyStore
import kotlinx.coroutines.launch

/** Device-local privacy controls. */
@Composable
internal fun PrivacySettingsPanel(modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val screenPrivacyStore = remember(appContext) { ScreenPrivacyStore.get(appContext) }
    val screenPrivacyEnabled by screenPrivacyStore.enabled.collectAsState()
    val appLockStore = remember(appContext) { AppLockStore.get(appContext) }
    val appLockEnabled by appLockStore.enabled.collectAsState()
    val quickPrivacyStore = remember(appContext) { QuickPrivacyModeStore.get(appContext) }
    val quickPrivacyEnabled by quickPrivacyStore.enabled.collectAsState()
    val deviceSecure = remember(appContext) { AppLock.isDeviceSecure(appContext) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PrivacySettingRow(
            title = "Screen privacy",
            summary = "Block screenshots, screen recording, casting and recents previews for all Aistee " +
                "screens, including account Web chats. Stays on this device.",
            checked = screenPrivacyEnabled,
            onCheckedChange = screenPrivacyStore::setEnabled,
            testTag = "switch_screen_privacy",
        )
        PrivacySettingRow(
            title = "Quick privacy",
            summary = "Temporarily hide conversation titles and message previews in home-screen widgets " +
                "and native chat notifications. Your individual preview choices stay saved.",
            checked = quickPrivacyEnabled,
            onCheckedChange = { enabled ->
                coroutineScope.launch {
                    QuickPrivacyModeController.setEnabled(appContext, enabled)
                }
            },
            testTag = "switch_quick_privacy",
        )
        PrivacySettingRow(
            title = "App lock",
            summary = if (deviceSecure) {
                "Require fingerprint, face or the device PIN/pattern when opening Aistee or returning " +
                    "after a minute away. Notifications and widgets follow their own privacy settings."
            } else {
                "Set a screen lock in Android settings to use App lock."
            },
            checked = appLockEnabled && deviceSecure,
            enabled = deviceSecure,
            onCheckedChange = { enabled ->
                // Whoever flips the switch is already inside the app, so the current session counts.
                if (enabled) AppLock.markUnlocked()
                appLockStore.setEnabled(enabled)
            },
            testTag = "switch_app_lock",
        )
    }
}

@Composable
private fun PrivacySettingRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    enabled: Boolean = true,
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
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag),
        )
    }
}

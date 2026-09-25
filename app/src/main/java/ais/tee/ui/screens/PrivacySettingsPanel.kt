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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ais.tee.security.ScreenPrivacyStore

/** Device-local privacy controls. Biometric app lock will join this panel. */
@Composable
internal fun PrivacySettingsPanel(modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val screenPrivacyStore = remember(appContext) { ScreenPrivacyStore.get(appContext) }
    val screenPrivacyEnabled by screenPrivacyStore.enabled.collectAsState()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                Text("Screen privacy", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Block screenshots, screen recording, casting and recents previews for all Aistee " +
                        "screens, including account Web chats. Stays on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = screenPrivacyEnabled,
                onCheckedChange = screenPrivacyStore::setEnabled,
                modifier = Modifier.testTag("switch_screen_privacy"),
            )
        }
    }
}

package ais.tee.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ais.tee.data.appfunctions.SystemAssistantAccess
import ais.tee.data.appfunctions.SystemAssistantConsentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only opt-in. Release UI has no experimental control. */
@Composable
internal fun SystemAssistantPrivacySettings() {
    val context = LocalContext.current.applicationContext
    val supported = remember(context) { SystemAssistantAccess.isSupported(context) }
    val store = remember(context) { SystemAssistantConsentStore }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        enabled = withContext(Dispatchers.IO) { store.isEnabled() }
        busy = false
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("AppFunctions token counting (local preview)", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !supported -> "Requires a supported Android 17+ device."
                    failed -> "Access is off. Could not update Android; try again."
                    else -> "Enable Aistee-only token-counting calls for this app session. " +
                        "Docbench must also be enabled and App lock off. " +
                        "External assistants, chats, files and accounts remain inaccessible."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            enabled = !busy && (supported || enabled),
            onCheckedChange = { value ->
                val generation = store.beginUpdate()
                enabled = false
                busy = true
                scope.launch {
                    try {
                        failed = !SystemAssistantAccess.setEnabled(context, value, generation)
                    } finally {
                        enabled = store.isEnabled()
                        busy = false
                    }
                }
            },
            modifier = Modifier
                .testTag("switch_system_assistant_token_count")
                .semantics { contentDescription = "AppFunctions local token counting preview" },
        )
    }
}

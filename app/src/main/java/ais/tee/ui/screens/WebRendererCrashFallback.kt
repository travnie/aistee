package ais.tee.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ais.tee.data.model.WebAiService

@Composable
internal fun WebRendererCrashFallback(
    service: WebAiService,
    onOpenHome: () -> Unit,
    onReopenConversation: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Text(
                text = "${service.shortName} page stopped",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "The web renderer crashed. Your provider session is unchanged. If the conversation crashes again, open the provider home page.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (onReopenConversation != null) {
                Button(onClick = onReopenConversation) { Text("Reopen conversation") }
                OutlinedButton(onClick = onOpenHome) { Text("Open provider home") }
            } else {
                Button(onClick = onOpenHome) { Text("Open provider home") }
            }
        }
    }
}

package ais.tee.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ais.tee.data.model.WebAiService
import ais.tee.share.IncomingSharePayload
import ais.tee.ui.theme.AccentCyan

@Composable
internal fun SharedContentBanner(
    service: WebAiService,
    payload: IncomingSharePayload,
    isTextClaimed: Boolean,
    onInsertText: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier
            .padding(12.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, tint = AccentCyan)
                Text(
                    "Shared content → ${service.shortName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onDismiss,
                    enabled = !isTextClaimed,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss shared content")
                }
            }
            payload.text?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (isTextClaimed) {
                        "Shared text insertion is in progress."
                    } else {
                        "Tap the provider composer, then Insert within 15 seconds."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onInsertText, enabled = !isTextClaimed) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isTextClaimed) "Inserting…" else "Insert shared text")
                }
            }
            if (payload.attachmentCount > 0) {
                Text(
                    "${payload.attachmentCount} shared attachment${if (payload.attachmentCount == 1) "" else "s"} ready. Tap Attach in ${service.shortName}; Aistee will ask before supplying matching shared files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun DraftReplyBanner(
    service: WebAiService,
    text: String,
    isClaimed: Boolean,
    onInsertText: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier
            .padding(12.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, tint = AccentCyan)
                Text(
                    "Draft reply → ${service.shortName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onDismiss,
                    enabled = !isClaimed,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss draft reply")
                }
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (isClaimed) {
                    "Draft insertion is in progress."
                } else {
                    "Tap the provider composer, then insert. Aistee will not send it for you."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onInsertText, enabled = !isClaimed) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isClaimed) "Inserting…" else "Insert draft")
            }
        }
    }
}

@Composable
internal fun SharedUploadConfirmationDialog(
    service: WebAiService,
    attachmentCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val noun = if (attachmentCount == 1) "attachment" else "attachments"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share with ${service.shortName}?") },
        text = {
            Text(
                "The embedded page requested $attachmentCount shared $noun. " +
                    "Android WebView does not reveal which frame triggered this file request, " +
                    "so continue only if you just tapped Attach in ${service.shortName}."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Share") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

package ais.tee.ui.screens

import ais.tee.data.model.WebAiService
import ais.tee.data.preferences.WebChatDraftJournal
import ais.tee.data.preferences.WebDraftVersion
import ais.tee.share.copyPlainTextToClipboard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local composer; no form-field inspection or provider DOM interception. */
@Composable
internal fun WebChatDraftJournalDialog(
    service: WebAiService,
    onStage: (String) -> Boolean,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    storeOverride: WebChatDraftJournal? = null,
) {
    val context = LocalContext.current
    val store = remember(context.applicationContext, storeOverride) {
        storeOverride ?: WebChatDraftJournal(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    var text by remember(service) { mutableStateOf("") }
    var versions by remember(service) { mutableStateOf<List<WebDraftVersion>>(emptyList()) }
    var loaded by remember(service) { mutableStateOf(false) }
    var readable by remember(service) { mutableStateOf(true) }
    var busy by remember(service) { mutableStateOf(false) }
    var dirty by remember(service) { mutableStateOf(false) }
    var error by remember(service) { mutableStateOf<String?>(null) }
    var restoreId by remember(service) { mutableStateOf<String?>(null) }
    var confirmReset by remember(service) { mutableStateOf(false) }

    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) { store.load(service) }
        text = snapshot.page.current
        versions = snapshot.page.versions.reversed()
        readable = snapshot.readable
        loaded = true
        dirty = false
    }

    LaunchedEffect(service) { refresh() }

    // Edit debounce never reads the provider editor. Explicit close flushes latest text.
    LaunchedEffect(service, text, loaded, readable) {
        if (!loaded || !readable || !dirty) return@LaunchedEffect
        delay(250)
        val snapshot = text
        val saved = withContext(Dispatchers.IO) { store.save(service, snapshot) }
        if (saved && text == snapshot) {
            dirty = false
            error = null
            versions = withContext(Dispatchers.IO) { store.load(service) }.page.versions.reversed()
        } else if (!saved) error = "Could not save encrypted draft. Keep this editor open."
    }

    fun flushThen(action: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) { store.save(service, text) }
            if (saved) {
                dirty = false
                action()
            } else error = "Could not save encrypted draft. No text was discarded."
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (loaded && readable) flushThen(onDismiss) else onDismiss() },
        icon = { Icon(Icons.Default.EditNote, contentDescription = null) },
        title = { Text("Draft Vault · " + service.shortName) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Aistee saves this composer independently of the provider's WebView. " +
                        "It does not capture anything typed directly into the website.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("web_draft_error"))
                }
                if (!loaded) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (!readable) {
                    Text("Encrypted journal cannot be read. Existing data will not be overwritten.",
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { confirmReset = true }) { Text("Reset unreadable journal") }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { value ->
                            if (value.length <= 48_000) {
                                text = value
                                dirty = true
                            } else error = "Draft limit: 48,000 characters."
                        },
                        label = { Text("Write here, safely outside the WebView") },
                        minLines = 6, maxLines = 12,
                        modifier = Modifier.fillMaxWidth().testTag("web_draft_editor"),
                    )
                    Text(
                        if (dirty) "Saving changes…" else "Saved locally · encrypted",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !busy && text.isNotBlank(),
                            onClick = {
                                flushThen {
                                    scope.launch {
                                        val checkpoint = withContext(Dispatchers.IO) {
                                            store.checkpoint(service)
                                        }
                                        if (!checkpoint) error = "Could not save a restore point."
                                        else {
                                            versions = withContext(Dispatchers.IO) {
                                                store.load(service)
                                            }.page.versions.reversed()
                                            onMessage("Saved draft version.")
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.testTag("web_draft_checkpoint"),
                        ) { Text("Save version") }
                        OutlinedButton(
                            enabled = text.isNotBlank(),
                            onClick = {
                                if (copyPlainTextToClipboard(context, "Draft Vault", text, sensitive = true)) {
                                    onMessage("Draft copied. Paste into the provider editor.")
                                } else error = "Could not copy the draft."
                            },
                            modifier = Modifier.testTag("web_draft_copy"),
                        ) { Text("Copy full text") }
                    }
                    Button(
                        enabled = text.isNotBlank() && !busy,
                        onClick = {
                            flushThen {
                                if (onStage(text)) {
                                    onMessage("Draft staged for manual insertion. Nothing was sent.")
                                    onDismiss()
                                } else error =
                                    "This draft is too large for direct insertion. Use Copy full text."
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("web_draft_stage"),
                    ) { Text("Insert into " + service.shortName + " (review first)") }

                    Text("Previous versions (" + versions.size + ")",
                        style = MaterialTheme.typography.titleSmall)
                    if (versions.isEmpty()) {
                        Text("Restore points appear here as you write longer drafts, or tap Save version.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    versions.forEach { version ->
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { restoreId = version.id },
                            modifier = Modifier.fillMaxWidth().testTag("web_draft_restore"),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT, DateFormat.SHORT,
                                ).format(Date(version.savedAtEpochMs)))
                                Text(version.text.take(100).replace('\n', ' '),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = { if (loaded && readable) flushThen(onDismiss) else onDismiss() },
            ) { Text("Close") }
        },
        modifier = Modifier.testTag("web_draft_vault_dialog"),
    )

    restoreId?.let { id ->
        AlertDialog(
            onDismissRequest = { restoreId = null },
            title = { Text("Restore this version?") },
            text = { Text("Your current draft is saved as a restore point before replacing it.") },
            confirmButton = {
                TextButton(onClick = {
                    flushThen {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { store.restore(service, id) }
                            restoreId = null
                            if (ok) {
                                refresh()
                                onMessage("Draft version restored.")
                            } else error = "Could not restore the selected version."
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { restoreId = null }) { Text("Cancel") }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset unreadable draft journal?") },
            text = { Text("All local Draft Vault entries will be lost. Provider chats remain unchanged.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val cleared = withContext(Dispatchers.IO) { store.reset() }
                        confirmReset = false
                        if (cleared) refresh() else error = "Could not reset encrypted journal."
                    }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

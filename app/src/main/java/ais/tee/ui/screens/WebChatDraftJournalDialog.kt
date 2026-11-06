package ais.tee.ui.screens

import ais.tee.data.model.WebAiService
import ais.tee.data.preferences.WebChatDraftJournal
import ais.tee.data.preferences.WebDraftVersion
import ais.tee.data.preferences.WebChatDraftStore
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
    val stagedDraftStore = remember(context.applicationContext) {
        WebChatDraftStore(context.applicationContext.noBackupFilesDir)
    }
    // Sensitive drafts live in memory or the encrypted journal, never SavedStateRegistry.
    var text by remember(service.id) { mutableStateOf("") }
    var versions by remember(service) { mutableStateOf<List<WebDraftVersion>>(emptyList()) }
    var loaded by remember(service) { mutableStateOf(false) }
    var readable by remember(service) { mutableStateOf(true) }
    var busy by remember(service) { mutableStateOf(false) }
    var dirty by remember(service.id) { mutableStateOf(false) }
    var error by remember(service) { mutableStateOf<String?>(null) }
    var restoreId by remember(service) { mutableStateOf<String?>(null) }
    var confirmReset by remember(service) { mutableStateOf(false) }

    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) { store.load(service) }
        if (!dirty) text = snapshot.page.current
        versions = snapshot.page.versions.reversed()
        readable = snapshot.readable
        loaded = true
    }

    LaunchedEffect(service) { refresh() }

    // Edit debounce never reads the provider editor. Explicit close flushes latest text.
    LaunchedEffect(service, text, loaded, readable, dirty, busy) {
        if (!loaded || !readable || !dirty || busy) return@LaunchedEffect
        val ticket = store.nextWriteTicket()
        delay(250)
        val snapshot = text
        val saved = withContext(Dispatchers.IO) { store.save(service, snapshot, ticket) }
        if (saved && text == snapshot) {
            val updatedVersions = withContext(Dispatchers.IO) {
                store.load(service).page.versions.reversed()
            }
            if (text == snapshot) {
                versions = updatedVersions
                error = null
                dirty = false
            }
        } else if (!saved) error = "Could not save encrypted draft. Keep this editor open."
    }

    fun flushThen(action: (String) -> Unit) {
        if (busy) return
        val latest = text
        val ticket = store.nextWriteTicket()
        busy = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.save(service, latest, ticket) }
                if (saved && text == latest) {
                    dirty = false
                    error = null
                    action(latest)
                } else if (!saved) {
                    error = "Could not save encrypted draft. No text was discarded."
                } else {
                    error = "Draft changed while saving. Retry before closing."
                }
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!loaded && dirty) return@AlertDialog
            if (loaded && readable) flushThen { onDismiss() } else onDismiss()
        },
        icon = { Icon(Icons.Default.EditNote, contentDescription = null) },
        title = { Text("Draft Vault · " + service.shortName) },
        text = {
            WebDraftVaultContent(
                service = service,
                text = text,
                versions = versions,
                loaded = loaded,
                readable = readable,
                busy = busy,
                dirty = dirty,
                error = error,
                onRetry = { flushThen { } },
                onResetRequest = { confirmReset = true },
                onTextChange = { value ->
                    if (value.length <= 48_000) {
                        text = value
                        dirty = true
                    } else error = "Draft limit: 48,000 characters."
                },
                onSaveVersion = {
                    if (!busy) {
                        val latest = text
                        val ticket = store.nextWriteTicket()
                        busy = true
                        scope.launch {
                            try {
                                val ok = withContext(Dispatchers.IO) {
                                    store.checkpoint(service, latest, ticket)
                                }
                                if (ok && text == latest) {
                                    dirty = false
                                    refresh()
                                    onMessage("Saved draft version.")
                                } else {
                                    error = "Could not save this version. Your editor text is intact."
                                }
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                onCopy = {
                    if (copyPlainTextToClipboard(context, "Draft Vault", text, sensitive = true)) {
                        onMessage("Draft copied. Paste into the provider editor.")
                    } else error = "Could not copy the draft."
                },
                onStage = {
                    flushThen { latest ->
                        if (stagedDraftStore.peek(service) != null) {
                            error = "Another Web reply is already staged. Finish or cancel it first."
                        } else if (onStage(latest)) {
                            onMessage("Draft staged for manual insertion. Nothing was sent.")
                            onDismiss()
                        } else error =
                            "Could not stage this draft. It may exceed the insertion limit or another reply is pending. Use Copy full text."
                    }
                },
                onRestoreRequest = { restoreId = it },
            )
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (loaded || !dirty),
                onClick = { if (loaded && readable) flushThen { onDismiss() } else onDismiss() },
            ) { Text("Close") }
        },
        modifier = Modifier.testTag("web_draft_vault_dialog"),
    )

    restoreId?.let { id ->
        AlertDialog(
            onDismissRequest = { if (!busy) restoreId = null },
            title = { Text("Restore this version?") },
            text = { Text("Your current draft is saved as a restore point before replacing it.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val latest = text
                    val ticket = store.nextWriteTicket()
                    busy = true
                    scope.launch {
                        try {
                            val ok = withContext(Dispatchers.IO) {
                                store.restore(service, id, latest, ticket)
                            }
                            if (ok && text == latest) {
                                restoreId = null
                                dirty = false
                                refresh()
                                onMessage("Draft version restored.")
                            } else {
                                error = "Could not restore the selected version. Editor text kept."
                            }
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { restoreId = null }) { Text("Cancel") }
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

/** Stateless content; persistence and lifecycle remain owned by the dialog. */
@Composable
private fun WebDraftVaultContent(
    service: WebAiService,
    text: String,
    versions: List<WebDraftVersion>,
    loaded: Boolean,
    readable: Boolean,
    busy: Boolean,
    dirty: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onResetRequest: () -> Unit,
    onTextChange: (String) -> Unit,
    onSaveVersion: () -> Unit,
    onCopy: () -> Unit,
    onStage: () -> Unit,
    onRestoreRequest: (String) -> Unit,
) {
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
            Text(error, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("web_draft_error"))
            if (loaded && readable && dirty) {
                TextButton(
                    enabled = !busy,
                    onClick = onRetry,
                    modifier = Modifier.testTag("web_draft_retry"),
                ) { Text("Retry encrypted save") }
            }
        }
        when {
            !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
            !readable -> {
                Text("Encrypted journal cannot be read. Existing data will not be overwritten.",
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onResetRequest) { Text("Reset unreadable journal") }
            }
            else -> {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = { Text("Write here, safely outside the WebView") },
                    enabled = !busy,
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
                        onClick = onSaveVersion,
                        modifier = Modifier.testTag("web_draft_checkpoint"),
                    ) { Text("Save version") }
                    OutlinedButton(
                        enabled = text.isNotBlank() && !busy,
                        onClick = onCopy,
                        modifier = Modifier.testTag("web_draft_copy"),
                    ) { Text("Copy full text") }
                }
                Button(
                    enabled = text.isNotBlank() && !busy,
                    onClick = onStage,
                    modifier = Modifier.fillMaxWidth().testTag("web_draft_stage"),
                ) { Text("Insert into " + service.shortName + " (review first)") }
                Text("Existing staged Web replies are never replaced automatically.",
                    style = MaterialTheme.typography.bodySmall)

                WebDraftVersionList(versions, busy, onRestoreRequest)
            }
        }
    }
}

@Composable
private fun WebDraftVersionList(
    versions: List<WebDraftVersion>,
    busy: Boolean,
    onRestoreRequest: (String) -> Unit,
) {
    Text("Previous versions (" + versions.size + ")",
        style = MaterialTheme.typography.titleSmall)
    if (versions.isEmpty()) {
        Text("Restore points appear here as you write longer drafts, or tap Save version.",
            style = MaterialTheme.typography.bodySmall)
    }
    versions.forEach { version ->
        OutlinedButton(
            enabled = !busy,
            onClick = { onRestoreRequest(version.id) },
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

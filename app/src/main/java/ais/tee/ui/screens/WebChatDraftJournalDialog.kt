package ais.tee.ui.screens

import ais.tee.data.model.WebAiService
import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Editor buffers remain in Compose memory or the encrypted journal, never Android saved state. */
private class WebDraftVaultEditor(
    private val service: WebAiService,
    private val store: WebChatDraftJournal,
    private val stagedDraftStore: WebChatDraftStore,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var text by mutableStateOf("")
    var versions by mutableStateOf<List<WebDraftVersion>>(emptyList())
    var loaded by mutableStateOf(false)
    var readable by mutableStateOf(true)
    var busy by mutableStateOf(false)
    var dirty by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var restoreId by mutableStateOf<String?>(null)
    var confirmReset by mutableStateOf(false)

    val canClose: Boolean get() = !busy && (loaded || !dirty)

    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) { store.load(service) }
        if (!dirty) text = snapshot.page.current
        versions = snapshot.page.versions.reversed()
        readable = snapshot.readable
        loaded = true
    }

    suspend fun autosave() {
        if (!loaded || !readable || !dirty || busy) return
        val ticket = store.nextWriteTicket()
        delay(250)
        val snapshot = text
        val saved = withContext(Dispatchers.IO) { store.save(service, snapshot, ticket) }
        if (saved && text == snapshot) {
            val updated = withContext(Dispatchers.IO) {
                store.load(service).page.versions.reversed()
            }
            if (text == snapshot) {
                versions = updated
                error = null
                dirty = false
            }
        } else if (!saved) error = "Could not save encrypted draft. Keep this editor open."
    }

    fun updateText(value: String) {
        if (value.length <= 48_000) {
            text = value
            dirty = true
        } else error = "Draft limit: 48,000 characters."
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

    fun dismiss(onDismiss: () -> Unit) {
        if (!loaded && dirty) return
        if (loaded && readable) flushThen { onDismiss() } else onDismiss()
    }

    fun checkpoint(onMessage: (String) -> Unit) {
        if (busy) return
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

    fun copy(onMessage: (String) -> Unit) {
        if (copyPlainTextToClipboard(context, "Draft Vault", text, sensitive = true)) {
            onMessage("Draft copied. Paste into the provider editor.")
        } else error = "Could not copy the draft."
    }

    fun stage(
        onStage: (String) -> Boolean,
        onDismiss: () -> Unit,
        onMessage: (String) -> Unit,
    ) {
        flushThen { latest ->
            if (stagedDraftStore.peek(service) != null) {
                error = "Another Web reply is already staged. Finish or cancel it first."
            } else if (onStage(latest)) {
                onMessage("Draft staged for manual insertion. Nothing was sent.")
                onDismiss()
            } else {
                error = "Could not stage this draft. It may exceed the insertion limit or another reply is pending. Use Copy full text."
            }
        }
    }

    fun restore(id: String, onMessage: (String) -> Unit) {
        if (busy) return
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
    }

    fun reset() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val cleared = withContext(Dispatchers.IO) { store.reset() }
                confirmReset = false
                if (cleared) refresh() else error = "Could not reset encrypted journal."
            } finally {
                busy = false
            }
        }
    }
}

/** Local composer; no form-field inspection or provider DOM interception. */
@Composable
internal fun WebChatDraftJournalDialog(
    service: WebAiService,
    onStage: (String) -> Boolean,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    storeOverride: WebChatDraftJournal? = null,
) {
    val context = LocalContext.current.applicationContext
    val store = remember(context, storeOverride) {
        storeOverride ?: WebChatDraftJournal(context)
    }
    val scope = rememberCoroutineScope()
    val stagedDraftStore = remember(context) {
        WebChatDraftStore(context.noBackupFilesDir)
    }
    val editor = remember(service, store, stagedDraftStore, scope) {
        WebDraftVaultEditor(service, store, stagedDraftStore, context, scope)
    }

    LaunchedEffect(editor) { editor.refresh() }
    LaunchedEffect(editor, editor.text, editor.loaded, editor.readable, editor.dirty, editor.busy) {
        editor.autosave()
    }

    AlertDialog(
        onDismissRequest = { editor.dismiss(onDismiss) },
        icon = { Icon(Icons.Default.EditNote, contentDescription = null) },
        title = { Text("Draft Vault · " + service.shortName) },
        text = {
            WebDraftVaultContent(
                service = service,
                text = editor.text,
                versions = editor.versions,
                loaded = editor.loaded,
                readable = editor.readable,
                busy = editor.busy,
                dirty = editor.dirty,
                error = editor.error,
                onRetry = { editor.flushThen { } },
                onResetRequest = { editor.confirmReset = true },
                onTextChange = editor::updateText,
                onSaveVersion = { editor.checkpoint(onMessage) },
                onCopy = { editor.copy(onMessage) },
                onStage = { editor.stage(onStage, onDismiss, onMessage) },
                onRestoreRequest = { editor.restoreId = it },
            )
        },
        confirmButton = {
            TextButton(
                enabled = editor.canClose,
                onClick = { editor.dismiss(onDismiss) },
            ) { Text("Close") }
        },
        modifier = Modifier.testTag("web_draft_vault_dialog"),
    )

    editor.restoreId?.let { id ->
        AlertDialog(
            onDismissRequest = { if (!editor.busy) editor.restoreId = null },
            title = { Text("Restore this version?") },
            text = { Text("Your current draft is saved as a restore point before replacing it.") },
            confirmButton = {
                TextButton(enabled = !editor.busy, onClick = { editor.restore(id, onMessage) }) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(enabled = !editor.busy, onClick = { editor.restoreId = null }) {
                    Text("Cancel")
                }
            },
        )
    }
    if (editor.confirmReset) {
        AlertDialog(
            onDismissRequest = { if (!editor.busy) editor.confirmReset = false },
            title = { Text("Reset unreadable draft journal?") },
            text = { Text("All local Draft Vault entries will be lost. Provider chats remain unchanged.") },
            confirmButton = {
                TextButton(enabled = !editor.busy, onClick = editor::reset) { Text("Reset") }
            },
            dismissButton = {
                TextButton(enabled = !editor.busy, onClick = { editor.confirmReset = false }) {
                    Text("Cancel")
                }
            },
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

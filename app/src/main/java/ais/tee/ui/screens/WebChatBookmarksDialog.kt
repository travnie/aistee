package ais.tee.ui.screens

import ais.tee.data.model.WebAiService
import ais.tee.data.preferences.WebChatBookmark
import ais.tee.data.preferences.WebChatBookmarkImportResult
import ais.tee.data.preferences.WebChatBookmarkSaveResult
import ais.tee.data.preferences.WebChatBookmarkStore
import ais.tee.data.preferences.readWebChatBookmarkBackup
import ais.tee.data.preferences.safeWebChatBookmarkUrl
import ais.tee.data.preferences.webBookmarkIdentityKey
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class WebBookmarkFolderHeading(val path: String, val name: String, val depth: Int)

internal fun webBookmarkFolderHeadings(folder: String): List<WebBookmarkFolderHeading> {
    val segments = if (folder.isEmpty()) listOf("Unfiled") else folder.split('/')
    return segments.indices.map { depth ->
        WebBookmarkFolderHeading(
            path = if (folder.isEmpty()) "" else segments.take(depth + 1).joinToString("/"),
            name = segments[depth],
            depth = depth,
        )
    }
}

internal data class WebChatBookmarkBackupActions(
    val busy: Boolean,
    val export: () -> Unit,
    val importBackup: () -> Unit,
)

@Composable
internal fun rememberWebChatBookmarkBackupActions(
    onMessage: (String) -> Unit,
): WebChatBookmarkBackupActions {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val showMessage by rememberUpdatedState(onMessage)
    var busy by remember { mutableStateOf(false) }

    fun transfer(uri: Uri, failureMessage: String, operation: (Uri) -> String) {
        busy = true
        scope.launch {
            val slow = launch {
                delay(15_000L)
                showMessage("Bookmark backup transfer is still in progress.")
            }
            val message = try {
                withContext(Dispatchers.IO) { operation(uri) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failureMessage
            } finally {
                slow.cancel()
                busy = false
            }
            showMessage(message)
        }
    }

    // Register both contracts even with the dialog closed. The registry restores
    // the picker result after activity recreation.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            transfer(uri, "Could not export bookmark backup") { destination ->
                val json = requireNotNull(WebChatBookmarkStore(context).exportPortableArchive())
                val output = requireNotNull(context.contentResolver.openOutputStream(destination))
                output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                "Exported bookmarks from all providers."
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            transfer(uri, "Could not read bookmark backup") { source ->
                val input = requireNotNull(context.contentResolver.openInputStream(source))
                val raw = requireNotNull(readWebChatBookmarkBackup(input))
                when (val result = WebChatBookmarkStore(context).importPortableArchive(raw)) {
                    is WebChatBookmarkImportResult.Imported -> {
                        val noun = if (result.added == 1) "bookmark" else "bookmarks"
                        "Imported ${result.added} $noun; skipped ${result.skipped} duplicate entries."
                    }
                    is WebChatBookmarkImportResult.Rejected -> result.reason
                }
            }
        }
    }

    fun launchPicker(launch: () -> Unit) {
        if (busy) return
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            showMessage("No document picker is available.")
        }
    }

    return WebChatBookmarkBackupActions(
        busy = busy,
        export = { launchPicker { exportLauncher.launch("aistee-web-bookmarks.json") } },
        importBackup = { launchPicker { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) } },
    )
}

/**
 * All data is user-authored or a user-approved provider URL. Never inspect page contents.
 * Opens only provider-bound, validated references in the already active WebView.
 */
@Composable
internal fun WebChatBookmarksDialog(
    service: WebAiService,
    currentUrl: String?,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    backup: WebChatBookmarkBackupActions,
    storeOverride: WebChatBookmarkStore? = null,
) {
    val context = LocalContext.current
    val store = remember(context.applicationContext, storeOverride) {
        storeOverride ?: WebChatBookmarkStore(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    var bookmarks by remember(service) { mutableStateOf<List<WebChatBookmark>>(emptyList()) }
    var readable by remember(service) { mutableStateOf(true) }
    var loading by remember(service) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val safeCurrentUrl = safeWebChatBookmarkUrl(service, currentUrl)
    val currentKey = safeCurrentUrl?.let { webBookmarkIdentityKey(service.id, it) }
    val existing = currentKey?.let { key ->
        bookmarks.firstOrNull { webBookmarkIdentityKey(it.providerId, it.url) == key }
    }
    var adding by remember(service, safeCurrentUrl) { mutableStateOf(false) }
    var title by remember(service, safeCurrentUrl) { mutableStateOf("") }
    var folder by remember(service, safeCurrentUrl) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<WebChatBookmark?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val actionsEnabled = !busy && !backup.busy

    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) { store.load() }
        bookmarks = snapshot.bookmarks.filter { it.providerId == service.id }
        readable = snapshot.readable
        loading = false
    }

    LaunchedEffect(service, backup.busy) {
        if (!backup.busy) refresh()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
        title = { Text("${service.shortName} bookmarks") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Only Aistee saves these links. Provider chat history is unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("web_bookmarks_error"))
                }
                Text("Export includes bookmarks from ALL providers, not just ${service.shortName}. The JSON file is not encrypted and contains conversation links.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            error = null
                            backup.export()
                        },
                        enabled = readable && !loading && actionsEnabled,
                        modifier = Modifier.testTag("web_bookmarks_export")
                    ) { Text("Export all JSON") }
                    TextButton(
                        onClick = {
                            error = null
                            backup.importBackup()
                        },
                        enabled = readable && !loading && actionsEnabled,
                        modifier = Modifier.testTag("web_bookmarks_import")
                    ) { Text("Import JSON") }
                }
                if (!readable) {
                    Text("Encrypted bookmarks cannot be read on this device. They will not be overwritten.",
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { confirmReset = true }, enabled = actionsEnabled) { Text("Reset unreadable bookmarks") }
                } else {
                    if (safeCurrentUrl == null) {
                        Text("This address cannot be bookmarked safely. Pages with query strings, fragments or unrecognized hosts are excluded.",
                            style = MaterialTheme.typography.bodySmall)
                    } else if (!adding) {
                        OutlinedButton(
                            onClick = {
                                adding = true
                                title = existing?.title.orEmpty()
                                folder = existing?.folder.orEmpty()
                                error = null
                            },
                            enabled = !loading && actionsEnabled,
                            modifier = Modifier.fillMaxWidth().testTag("web_bookmarks_add")
                        ) {
                            Text(if (existing != null) "Edit this bookmark" else "Bookmark this page")
                        }
                    }
                    if (adding && safeCurrentUrl != null) {
                        OutlinedTextField(
                            value = title, onValueChange = { if (it.length <= 80) title = it },
                            label = { Text("Bookmark name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("web_bookmarks_title"),
                        )
                        OutlinedTextField(
                            value = folder, onValueChange = { if (it.length <= 96) folder = it },
                            label = { Text("Folder (optional)") },
                            supportingText = { Text("Use / for up to three nested levels") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("web_bookmarks_folder"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { adding = false }, enabled = !busy) { Text("Cancel") }
                            Button(
                                onClick = {
                                    busy = true
                                    error = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            store.save(service, safeCurrentUrl, title, folder)
                                        }
                                        if (result is WebChatBookmarkSaveResult.Saved) {
                                            adding = false
                                            refresh()
                                        } else {
                                            error = (result as WebChatBookmarkSaveResult.Rejected).reason
                                        }
                                        busy = false
                                    }
                                },
                                enabled = actionsEnabled && title.isNotBlank(),
                                modifier = Modifier.testTag("web_bookmarks_save"),
                            ) { Text("Save") }
                        }
                    }
                    if (loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (bookmarks.isEmpty()) {
                        Text("No bookmarks yet. Give a conversation a name, and it gets a home here.",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        val grouped = bookmarks.sortedWith(
                            compareBy<WebChatBookmark>({ it.folder.lowercase() }, { it.title.lowercase() })
                        ).groupBy { it.folder }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                                .testTag("web_bookmarks_list"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val renderedFolders = mutableSetOf<String>()
                            grouped.forEach { (group, entries) ->
                                val headings = webBookmarkFolderHeadings(group)
                                headings.forEach { heading ->
                                    if (renderedFolders.add(heading.path)) {
                                        item(key = "folder:${heading.path}") {
                                            Row(
                                                modifier = Modifier.padding(start = (heading.depth * 16).dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(Icons.Default.Folder, contentDescription = null,
                                                    modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(heading.name,
                                                    style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                }
                                items(entries, key = { it.id }) { bookmark ->
                                    Row(
                                        modifier = Modifier.padding(start = (headings.size * 16).dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                if (safeWebChatBookmarkUrl(service, bookmark.url) == bookmark.url) {
                                                    onOpen(bookmark.url)
                                                    onDismiss()
                                                } else error = "Bookmark address no longer belongs to this provider"
                                            },
                                            enabled = !busy,
                                            modifier = Modifier.weight(1f).testTag("web_bookmark_open"),
                                        ) {
                                            Text(bookmark.title, maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.fillMaxWidth())
                                        }
                                        IconButton(
                                            onClick = { pendingDelete = bookmark }, enabled = actionsEnabled,
                                            modifier = Modifier.testTag("web_bookmark_remove"),
                                        ) { Icon(Icons.Default.DeleteOutline,
                                            contentDescription = "Remove ${bookmark.title}") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Done") }
        },
        modifier = Modifier.testTag("web_bookmarks_dialog"),
    )

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDelete = null },
            title = { Text("Remove bookmark?") },
            text = { Text("Remove ${target.title} from Aistee? The provider chat stays intact.") },
            confirmButton = {
                TextButton(
                    enabled = actionsEnabled,
                    onClick = {
                        busy = true
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) { store.remove(target.id) }
                            error = if (removed) null else "Could not remove encrypted bookmark"
                            pendingDelete = null
                            refresh()
                            busy = false
                        }
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmReset = false },
            title = { Text("Reset encrypted bookmarks?") },
            text = { Text("Only Aistee bookmark references will be erased. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    enabled = actionsEnabled,
                    onClick = {
                        busy = true
                        scope.launch {
                            val cleared = withContext(Dispatchers.IO) { store.reset() }
                            error = if (cleared) null else "Could not reset encrypted bookmarks"
                            confirmReset = false
                            refresh()
                            busy = false
                        }
                    },
                ) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

package ais.tee.ui.screens

import ais.tee.data.model.ProjectLibraryArchive
import ais.tee.data.model.ProjectLibraryAsset
import ais.tee.data.model.ProjectLibraryAssetKind
import ais.tee.data.model.WebAiService
import ais.tee.data.preferences.WebChatDraftStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.text.BreakIterator
import java.util.Locale
import kotlinx.coroutines.launch

/** Canonical Project Library remains the single prompt store. Metadata-only until selection. */
internal fun webPromptVaultItems(archive: ProjectLibraryArchive): List<ProjectLibraryAsset> =
    archive.assets.filter { it.kind == ProjectLibraryAssetKind.PROMPT && it.sizeBytes > 0 }
        .sortedWith(compareBy({ it.title.lowercase() }, { it.id }))

/** Lazy text slices with grapheme-aligned offsets. Avoid broken emoji/combining marks. */
internal fun webPromptPreviewChunks(text: String): List<String> = object : AbstractList<String>() {
    private val offsets: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        val breaker = BreakIterator.getCharacterInstance(Locale.ROOT)
        breaker.setText(text)
        val cuts = mutableListOf(0)
        var start = 0
        while (start < text.length) {
            val target = minOf(start + 2_048, text.length)
            val aligned = if (target == text.length) target else breaker.preceding(target + 1)
            // Pathological grapheme clusters can be megabytes long. Bound each Text item.
            val end = if (aligned > start) aligned else if (
                target < text.length && Character.isHighSurrogate(text[target - 1]) &&
                Character.isLowSurrogate(text[target])
            ) target - 1 else target
            check(end > start && end <= text.length)
            cuts.add(end)
            start = end
        }
        cuts.toIntArray()
    }

    override val size: Int get() = offsets.size - 1

    override fun get(index: Int): String {
        if (index !in 0 until size) throw IndexOutOfBoundsException()
        return text.substring(offsets[index], offsets[index + 1])
    }
}

/** A single oversized grapheme cannot be split into separately rendered Text nodes. */
internal fun webPromptHasOversizedGrapheme(text: String): Boolean {
    val breaker = BreakIterator.getCharacterInstance(Locale.ROOT)
    breaker.setText(text)
    var start = breaker.first()
    while (true) {
        val end = breaker.next()
        if (end == BreakIterator.DONE) return false
        if (end - start > 2_048) return true
        start = end
    }
}

internal suspend fun loadWebPromptText(id: String, loader: suspend (String) -> String?): String? =
    try { loader(id) } catch (_: IOException) { null }

internal fun webPromptCanStage(text: String): Boolean =
    text.isNotBlank() && text.length <= WebChatDraftStore.MAX_DRAFT_CHARS &&
        text.toByteArray(Charsets.UTF_8).size <= WebChatDraftStore.MAX_DRAFT_UTF8_BYTES

@Composable
internal fun WebPromptVaultDialog(
    service: WebAiService,
    archive: ProjectLibraryArchive,
    libraryReady: Boolean,
    pendingDraft: Boolean,
    draftBusy: Boolean,
    loadText: suspend (String) -> String?,
    onStage: (String) -> Boolean,
    onCopy: (String) -> Boolean,
    onImport: () -> Unit,
    onExport: (ProjectLibraryAsset) -> Unit,
    transferBusy: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ProjectLibraryAsset?>(null) }
    val listState = remember(selected?.id, search) { LazyListState() }
    var preview by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val files = remember(archive) { webPromptVaultItems(archive) }
    val previewChunks = remember(preview) { webPromptPreviewChunks(preview.orEmpty()) }
    val previewHasOversizedGrapheme = remember(preview) {
        webPromptHasOversizedGrapheme(preview.orEmpty())
    }
    val filtered = remember(files, search, archive.projects) {
        files.filter { asset ->
            asset.title.contains(search, ignoreCase = true) ||
                archive.projects.firstOrNull { it.id == asset.projectId }
                    ?.name?.contains(search, ignoreCase = true) == true
        }
    }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        icon = { Icon(Icons.Default.LibraryBooks, contentDescription = null) },
        title = { Text("Prompt Vault · ${service.shortName}") },
        text = {
            // One bounded lazy scroller: no nested verticalScroll/LazyColumn measure loop.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    .testTag("web_prompt_vault_content"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "intro") {
                    Text(
                        "Choose a local Project Library prompt. Preview it here, then stage a draft for manual insertion. Nothing is sent automatically.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (error != null) {
                    item(key = "error") {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("web_prompt_vault_error"))
                    }
                }
                if (selected == null) {
                    item(key = "search") {
                        if (searchExpanded) {
                            OutlinedTextField(
                                value = search, onValueChange = { if (it.length <= 100) search = it },
                                singleLine = true, label = { Text("Search saved prompts") },
                                modifier = Modifier.fillMaxWidth().testTag("web_prompt_vault_search"),
                            )
                        } else {
                            OutlinedButton(
                                onClick = { searchExpanded = true },
                                modifier = Modifier.fillMaxWidth().testTag("web_prompt_vault_search_open"),
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Search saved prompts")
                            }
                        }
                    }
                    if (!libraryReady) {
                        item(key = "loading") {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    } else if (filtered.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                if (files.isEmpty()) "No saved prompts yet. Import one or save a prompt to Project Library."
                                else "No matching prompts.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        items(filtered, key = { it.id }) { asset ->
                            OutlinedButton(
                                onClick = {
                                    selected = asset
                                    preview = null
                                    error = null
                                    loading = true
                                    scope.launch {
                                        try {
                                            val text = loadWebPromptText(asset.id, loadText)
                                            if (text.isNullOrBlank()) {
                                                error = "Could not read this local prompt"
                                                selected = null
                                            } else {
                                                preview = text
                                            }
                                        } finally {
                                            loading = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("web_prompt_vault_select"),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(asset.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val projectName = archive.projects.firstOrNull { it.id == asset.projectId }?.name
                                        ?: "Project"
                                    Text("$projectName · ${asset.sizeBytes} bytes",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                } else {
                    item(key = "title") {
                        Text(selected?.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    }
                    if (loading) {
                        item(key = "loading") {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    } else {
                        val fullText = preview.orEmpty()
                        item(key = "readiness") {
                            Text(
                                if (webPromptCanStage(fullText)) "Ready to stage"
                                else "Too large for a Web draft; copy it instead.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (previewHasOversizedGrapheme) {
                            item(key = "unsafe-preview") {
                                Text(
                                    "Preview unavailable: a Unicode character sequence is too long to render safely. Copy or stage still uses the complete original prompt.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("web_prompt_vault_preview_warning"),
                                )
                            }
                        } else {
                            items(previewChunks.size, key = { it }) { index ->
                                Text(previewChunks[index], style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("web_prompt_vault_preview"))
                            }
                        }
                        if (pendingDraft) {
                            item(key = "warning") {
                                Text(
                                    if (draftBusy) "Finish the active Web draft first."
                                    else "Staging will replace the existing draft for this provider.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        item(key = "stage") {
                            Button(
                                onClick = {
                                    if (onStage(fullText)) onDismiss()
                                    else error = "Could not stage this prompt"
                                },
                                enabled = webPromptCanStage(fullText) && !draftBusy,
                                modifier = Modifier.fillMaxWidth().testTag("web_prompt_vault_stage"),
                            ) {
                                Text(if (pendingDraft) "Replace Web draft" else "Stage for ${service.shortName}")
                            }
                        }
                        item(key = "copy") {
                            OutlinedButton(
                                onClick = {
                                    if (onCopy(fullText)) onDismiss()
                                    else error = "Could not copy this prompt"
                                },
                                modifier = Modifier.fillMaxWidth().testTag("web_prompt_vault_copy"),
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Copy full prompt")
                            }
                        }
                        item(key = "export") {
                            OutlinedButton(
                                onClick = { selected?.let(onExport) },
                                enabled = !transferBusy,
                                modifier = Modifier.fillMaxWidth().testTag("web_prompt_vault_export"),
                            ) { Text("Export prompt as unencrypted JSON") }
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (selected != null && !loading) {
                TextButton(onClick = {
                    selected = null
                    preview = null
                    error = null
                }) { Text("Back") }
            } else if (selected == null) {
                TextButton(onClick = onImport, enabled = libraryReady && !transferBusy) {
                    Text("Import prompt JSON")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text("Close") }
        },
        modifier = Modifier.testTag("web_prompt_vault_dialog"),
    )
}

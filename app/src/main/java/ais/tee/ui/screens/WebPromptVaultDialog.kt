package ais.tee.ui.screens

import ais.tee.data.model.ProjectLibraryArchive
import ais.tee.data.model.ProjectLibraryAsset
import ais.tee.data.model.ProjectLibraryAssetKind
import ais.tee.data.model.WebAiService
import ais.tee.data.preferences.WebChatDraftStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Canonical Project Library remains the single prompt store. Metadata-only until user selects an asset. */
internal fun webPromptVaultItems(archive: ProjectLibraryArchive): List<ProjectLibraryAsset> =
    archive.assets.filter { it.kind == ProjectLibraryAssetKind.PROMPT && it.sizeBytes > 0 }
        .sortedWith(compareBy({ it.title.lowercase() }, { it.id }))

internal fun webPromptPreviewChunks(text: String): List<String> = text.chunked(2_048)

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
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ProjectLibraryAsset?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val files = remember(archive) { webPromptVaultItems(archive) }
    val filtered = remember(files, search) {
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
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Choose a local Project Library prompt. Preview it here, then stage a draft for manual insertion. Nothing is sent automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("web_prompt_vault_error"))
                }
                if (selected == null) {
                    OutlinedTextField(
                        value = search, onValueChange = { if (it.length <= 100) search = it },
                        singleLine = true, label = { Text("Search saved prompts") },
                        modifier = Modifier.fillMaxWidth().testTag("web_prompt_vault_search"),
                    )
                    if (!libraryReady) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else if (filtered.isEmpty()) {
                        Text(if (files.isEmpty()) "No saved prompts yet. Create one in Project Library."
                             else "No matching prompts.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 290.dp)
                                .testTag("web_prompt_vault_list"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(filtered, key = { it.id }) { asset ->
                                OutlinedButton(
                                    onClick = {
                                        selected = asset
                                        preview = null
                                        error = null
                                        loading = true
                                        scope.launch {
                                            try {
                                                val text = loadText(asset.id)
                                                if (text.isNullOrBlank()) {
                                                    error = "Could not read this local prompt"
                                                    selected = null
                                                } else {
                                                    preview = text
                                                }
                                            } catch (failure: Exception) {
                                                if (failure is CancellationException) throw failure
                                                error = "Could not read this local prompt"
                                                selected = null
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
                    }
                } else {
                    Text(selected?.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    if (loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        val fullText = preview.orEmpty()
                        Text(
                            if (webPromptCanStage(fullText)) "Ready to stage" else
                                "Too large for a Web draft; copy it instead.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val chunks = remember(fullText) { webPromptPreviewChunks(fullText) }
                        LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = 180.dp)
                                .testTag("web_prompt_vault_preview"),
                        ) {
                            items(chunks.size) { index ->
                                Text(chunks[index], style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (pendingDraft) {
                            Text(
                                if (draftBusy) "Finish the active Web draft first."
                                else "Staging will replace the existing draft for this provider.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text("Close") }
        },
        modifier = Modifier.testTag("web_prompt_vault_dialog"),
    )
}

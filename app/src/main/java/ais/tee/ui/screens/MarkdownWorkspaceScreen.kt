package ais.tee.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ais.tee.data.document.DocumentDiagnostic
import ais.tee.data.document.DocumentDiagnosticKind
import ais.tee.data.document.DocumentDiagnostics
import ais.tee.data.document.DocbenchWorkspaceTransformAction
import ais.tee.data.document.DocbenchWorkspaceTransformKind
import ais.tee.data.document.DocbenchWorkspaceTransformResult
import ais.tee.data.document.LineEnding
import ais.tee.data.document.LineEndingCounts
import ais.tee.data.document.MarkdownDocumentFileAccess
import ais.tee.data.document.MarkdownRecentDocumentsStore
import ais.tee.data.document.MarkdownStructureValidationResult
import ais.tee.data.document.MarkdownStructureDiagnostics
import ais.tee.data.document.RecentMarkdownDocument
import ais.tee.data.document.TextDocument
import ais.tee.data.document.TextDocumentCodec
import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import ais.tee.data.model.BuiltInBenchTool
import ais.tee.data.preferences.BuiltInBenchPreferencesStore
import ais.tee.data.security.TextInspectionResult
import ais.tee.data.security.TextInspector
import ais.tee.data.tokenizer.LocalTokenCounter
import ais.tee.ui.viewmodel.MAX_EDITABLE_MARKDOWN_CHARS
import ais.tee.ui.viewmodel.MarkdownExportSnapshot
import ais.tee.ui.viewmodel.MarkdownWorkspaceUiState
import ais.tee.ui.viewmodel.MarkdownWorkspaceOrigin
import kotlinx.coroutines.NonCancellable
import ais.tee.ui.viewmodel.MarkdownWorkspaceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val WORKSPACE_ANALYSIS_DEBOUNCE_MS = 360L
private const val MAX_TOKENIZED_CHARS = 1_000_000
private const val LARGE_PREVIEW_CHUNK_CHARS = 16 * 1024
private const val MAX_DIRECT_SHARE_BYTES = 128 * 1024
// SAF providers often advertise JSON, YAML and extensionless text with non-text MIME types.
// The shared bounded UTF-8 reader rejects invalid encodings; never auto-execute imported content.
private val WORKSPACE_IMPORT_MIME_TYPES = arrayOf("*/*")

private sealed class PendingDestructiveWorkspaceAction {
    data object New : PendingDestructiveWorkspaceAction()
    data object Import : PendingDestructiveWorkspaceAction()
    data class Recent(val document: RecentMarkdownDocument) : PendingDestructiveWorkspaceAction()
}

private data class WorkspaceTransformPreview(
    val revision: Long,
    val result: DocbenchWorkspaceTransformResult.Completed,
)

/** Keep the actual change in view, even when a large document shares a long prefix. */
internal fun workspaceTransformExcerpts(
    before: String,
    after: String,
    repairing: Boolean,
): Pair<String, String> {
    if (repairing) return before.takeLast(600) to after.takeLast(600)
    val shared = minOf(before.length, after.length)
    var firstDifference = 0
    while (firstDifference < shared && before[firstDifference] == after[firstDifference]) {
        firstDifference++
    }
    val start = (firstDifference - 60).coerceAtLeast(0)
    return before.substring(start, (start + 600).coerceAtMost(before.length)) to
        after.substring(start, (start + 600).coerceAtMost(after.length))
}

private data class MarkdownWorkspaceAnalysis(
    val lineEndings: LineEndingCounts,
    val diagnostics: List<DocumentDiagnostic>,
    val markdownStructure: MarkdownStructureValidationResult?,
    val safety: TextInspectionResult,
    val tokenCount: Int?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownWorkspaceScreen(
    workspaceViewModel: MarkdownWorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by workspaceViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val recentStore = remember(context) { MarkdownRecentDocumentsStore(context.applicationContext) }
    val benchPreferences = remember(context) { BuiltInBenchPreferencesStore(context.applicationContext) }
    var recentDocuments by remember { mutableStateOf<List<RecentMarkdownDocument>>(emptyList()) }
    var showRecents by remember { mutableStateOf(false) }
    var pendingDestructiveAction by remember { mutableStateOf<PendingDestructiveWorkspaceAction?>(null) }
    var transformPreview by remember { mutableStateOf<WorkspaceTransformPreview?>(null) }
    var transformGeneration by remember { mutableIntStateOf(0) }
    val analysis by rememberMarkdownWorkspaceAnalysis(
        text = uiState.text,
        hadUtf8Bom = uiState.hadUtf8Bom,
        revision = uiState.revision
    )

    LaunchedEffect(recentStore) {
        recentDocuments = runCatching { recentStore.load() }.getOrDefault(emptyList())
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            workspaceViewModel.cancelImport()
        } else {
            scope.launch {
                val importedName = importWorkspaceDocument(
                    context, uri, workspaceViewModel, snackbarHostState, bindSource = true
                )
                if (importedName != null) {
                    val wasRetained = recentStore.hasReadPermission(uri)
                    val retained = recentStore.retainReadPermission(uri)
                    val recordAttempt = if (retained) runCatching { recentStore.record(uri) } else null
                    val recorded = recordAttempt?.getOrNull()
                    when {
                        recorded != null && recorded.any { it.uriString == uri.toString() } -> {
                            recentDocuments = recorded
                            snackbarHostState.showSnackbar(
                                "Opened $importedName. Original changes only when you select Save source."
                            )
                        }
                        recorded != null -> {
                            recentDocuments = recorded
                            if (!wasRetained) recentStore.releaseReadPermission(uri)
                            snackbarHostState.showSnackbar(
                                "Imported $importedName; it was not added to Recents because every shortcut slot is pinned."
                            )
                        }
                        retained -> {
                            if (!wasRetained) recentStore.releaseReadPermission(uri)
                            snackbarHostState.showSnackbar(
                                "Imported $importedName; the Recents shortcut could not be saved."
                            )
                        }
                        else -> snackbarHostState.showSnackbar(
                            "Imported $importedName; this provider did not grant persistent access, so it will not stay in Recents."
                        )
                    }
                }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        CreateWorkspaceTextDocument()
    ) { uri ->
        if (uri != null) {
            val snapshot = workspaceViewModel.beginExport()
            if (snapshot != null) {
                scope.launch {
                    exportWorkspaceDocument(
                        context = context,
                        uri = uri,
                        fallbackName = workspaceExportFileName(snapshot.displayName),
                        snapshot = snapshot,
                        viewModel = workspaceViewModel,
                        snackbarHostState = snackbarHostState
                    )
                }
            }
        }
    }

    fun launchImportPicker() {
        if (!workspaceViewModel.beginImport()) return
        try {
            importLauncher.launch(WORKSPACE_IMPORT_MIME_TYPES)
        } catch (_: ActivityNotFoundException) {
            workspaceViewModel.cancelImport()
            scope.launch { snackbarHostState.showSnackbar("No document picker is available.") }
        }
    }

    fun openRecentDocument(document: RecentMarkdownDocument) {
        if (!workspaceViewModel.beginImport()) return
        scope.launch {
            var openFailure: Throwable? = null
            val importedName = importWorkspaceDocument(
                context = context,
                uri = document.uri,
                viewModel = workspaceViewModel,
                snackbarHostState = snackbarHostState,
                onFailure = { openFailure = it }
            )
            if (importedName != null) {
                recentDocuments = runCatching { recentStore.record(document.uri) }.getOrElse { recentDocuments }
                snackbarHostState.showSnackbar("Opened $importedName from Recents; original file stays untouched.")
            } else {
                val pruned = openFailure?.let { error ->
                    runCatching { recentStore.forgetIfUnavailable(document, error) }.getOrNull()
                }
                recentDocuments = pruned
                    ?: runCatching { recentStore.load() }.getOrElse { recentDocuments }
            }
        }
    }

    fun requestAction(action: PendingDestructiveWorkspaceAction) {
        if (uiState.isBusy) return
        if (uiState.isDirty) pendingDestructiveAction = action
        else performDestructiveAction(action, workspaceViewModel, ::launchImportPicker, ::openRecentDocument)
    }

    fun shareCurrentMarkdown() {
        if (uiState.text.isEmpty()) return
        val exceedsDirectShareLimit =
            uiState.text.length > MAX_DIRECT_SHARE_BYTES ||
                uiState.text.encodeToByteArray().size > MAX_DIRECT_SHARE_BYTES
        if (exceedsDirectShareLimit) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "This draft is too large for direct text sharing. Export it as a file, then share the document."
                )
            }
            return
        }
        try {
            shareMarkdownText(context, uiState.text, workspaceExportFileName(uiState.displayName))
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar("No app is available to share Markdown text.") }
        }
    }



    fun saveOpenedSource() {
        val snapshot = workspaceViewModel.beginExport() ?: return
        val origin = snapshot.origin as? MarkdownWorkspaceOrigin.AndroidDocument
        if (origin == null) {
            workspaceViewModel.failExport(snapshot)
            return
        }
        scope.launch {
            val result = runCatching {
                withContext(NonCancellable + Dispatchers.IO) {
                    val uri = Uri.parse(origin.uriString)
                    val source = MarkdownDocumentFileAccess.import(context, uri)
                    if (MarkdownDocumentFileAccess.sourceDigest(source.document) != origin.sourceDigest) {
                        throw IOException("The source changed outside Aistee. Reopen it or export a copy.")
                    }
                    MarkdownDocumentFileAccess.export(context, uri, snapshot.document)
                    MarkdownDocumentFileAccess.sourceDigest(snapshot.document)
                }
            }
            result.fold(
                onSuccess = { savedDigest ->
                    val current = workspaceViewModel.completeSourceSave(
                        snapshot,
                        origin.copy(sourceDigest = savedDigest),
                    )
                    snackbarHostState.showSnackbar(
                        if (current) "Saved changes to the opened file."
                        else "Saved an earlier draft; newer edits are still unsaved."
                    )
                },
                onFailure = { error ->
                    workspaceViewModel.failExport(snapshot)
                    if (error is CancellationException) throw error
                    snackbarHostState.showSnackbar(
                        when {
                            error is SecurityException ->
                                "No write access to this file. Reopen it with permission or export a copy."
                            error is IOException && error.message?.startsWith("The source changed outside Aistee") == true ->
                                "The file changed outside Aistee. Reopen it or export a copy."
                            else -> "Could not save the opened file. The draft is still available; export a copy."
                        }
                    )
                },
            )
        }
    }

    fun requestWorkspaceTransform(kind: DocbenchWorkspaceTransformKind) {
        val draft = workspaceViewModel.uiState.value
        if (draft.isBusy || draft.isEditorLocked || draft.text.isEmpty()) return
        val generation = transformGeneration + 1
        transformGeneration = generation
        transformPreview = null
        if (BuiltInBenchTool.DOCBENCH_DOCUMENT !in benchPreferences.loadEnabledTools()) {
            scope.launch { snackbarHostState.showSnackbar("Enable Docbench Document in Benches first.") }
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                DocbenchWorkspaceTransformAction.execute(
                    text = draft.text,
                    kind = kind,
                    surface = BenchToolSurface.COMPANION_UI,
                    isEnabled = BuiltInBenchTool.DOCBENCH_DOCUMENT in benchPreferences.loadEnabledTools(),
                    grantedPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT),
                    sourceIds = when (val origin = draft.origin) {
                        is MarkdownWorkspaceOrigin.ProjectLibrary -> listOf(origin.assetId)
                        else -> emptyList()
                    },
                )
            }
            if (generation != transformGeneration) return@launch
            if (workspaceViewModel.uiState.value.revision != draft.revision) {
                snackbarHostState.showSnackbar("The draft changed. Preview the transform again.")
                return@launch
            }
            when (result) {
                is DocbenchWorkspaceTransformResult.Completed -> when {
                    !result.changed -> snackbarHostState.showSnackbar("${kind.label}: no changes needed.")
                    result.content.length > MAX_EDITABLE_MARKDOWN_CHARS ->
                        snackbarHostState.showSnackbar("The result exceeds the editor size limit.")
                    else -> transformPreview = WorkspaceTransformPreview(draft.revision, result)
                }
                is DocbenchWorkspaceTransformResult.Rejected ->
                    snackbarHostState.showSnackbar(result.message)
                is DocbenchWorkspaceTransformResult.Blocked ->
                    snackbarHostState.showSnackbar("Docbench transform is blocked by tool policy.")
            }
        }
    }

    transformPreview?.let { preview ->
        val repairing = preview.result.kind == DocbenchWorkspaceTransformKind.REPAIR_MARKDOWN_FENCES
        val excerpts = workspaceTransformExcerpts(uiState.text, preview.result.content, repairing)
        AlertDialog(
            onDismissRequest = { transformPreview = null },
            title = { Text("Preview ${preview.result.kind.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Preview only. Apply to the local draft; source files are never saved automatically.")
                    Text("Current ${if (repairing) "ending" else "near first change"}:", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(
                            excerpts.first,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                    Text("Proposed ${if (repairing) "ending" else "near first change"}:", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(
                            excerpts.second,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                    if (uiState.text.length > 600 || preview.result.content.length > 600) {
                        Text("Preview truncated; the complete draft is applied only after confirmation.", style = MaterialTheme.typography.bodySmall)
                    }
                    preview.result.warnings.forEach { warning ->
                        Text(warning, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = uiState.revision == preview.revision && !uiState.isBusy && !uiState.isEditorLocked,
                    onClick = {
                        val enabled = BuiltInBenchTool.DOCBENCH_DOCUMENT in benchPreferences.loadEnabledTools()
                        val applied = enabled && workspaceViewModel.applyTransformedText(
                            expectedRevision = preview.revision,
                            text = preview.result.content,
                        )
                        transformPreview = null
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                when {
                                    !enabled -> "Docbench was disabled. No changes applied."
                                    applied -> when (uiState.origin) {
                                        is MarkdownWorkspaceOrigin.ProjectLibrary ->
                                            "Applied to draft. Use Save source to update the Project Library asset."
                                        is MarkdownWorkspaceOrigin.LocalSkill ->
                                            "Applied to draft. Use Save source to update the local skill."
                                        is MarkdownWorkspaceOrigin.AndroidDocument ->
                                            "Applied to draft. Use Save source to update the opened file."
                                        null -> "Applied to draft. Use Export text file to save a copy."
                                    }
                                    else -> "The draft changed. No changes applied."
                                }
                            )
                        }
                    },
                    modifier = Modifier.testTag(
                        if (repairing) "markdown_repair_apply" else "markdown_format_apply"
                    ),
                ) { Text("Apply to draft") }
            },
            dismissButton = {
                TextButton(onClick = { transformPreview = null }) { Text("Cancel") }
            },
        )
    }

    pendingDestructiveAction?.let { action ->
        DiscardWorkspaceChangesDialog(
            onDismiss = { pendingDestructiveAction = null },
            onDiscard = {
                pendingDestructiveAction = null
                performDestructiveAction(action, workspaceViewModel, ::launchImportPicker, ::openRecentDocument)
            }
        )
    }

    if (showRecents) {
        RecentMarkdownDocumentsSheet(
            documents = recentDocuments,
            onOpen = { document ->
                showRecents = false
                requestAction(PendingDestructiveWorkspaceAction.Recent(document))
            },
            onTogglePin = { document ->
                scope.launch {
                    runCatching { recentStore.setPinned(document, !document.isPinned) }
                        .onSuccess { recentDocuments = it }
                        .onFailure { snackbarHostState.showSnackbar("Could not update the Markdown shortcut pin.") }
                }
            },
            onForget = { document ->
                scope.launch {
                    recentDocuments = runCatching { recentStore.forget(document) }.getOrElse { recentDocuments }
                }
            },
            onDismiss = { showRecents = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MarkdownWorkspaceTopBar(
                uiState = uiState,
                onNew = { requestAction(PendingDestructiveWorkspaceAction.New) },
                onImport = { requestAction(PendingDestructiveWorkspaceAction.Import) },
                onRecent = {
                    scope.launch {
                        recentDocuments = runCatching { recentStore.load() }.getOrElse { recentDocuments }
                        showRecents = true
                    }
                },
                onExport = { exportLauncher.launch(workspaceExportFileName(uiState.displayName)) },
                onSaveSource = ::saveOpenedSource,
                onShare = ::shareCurrentMarkdown,
                onFormat = ::requestWorkspaceTransform
            )
        },
        modifier = modifier
    ) { innerPadding ->
        MarkdownWorkspaceBody(
            uiState = uiState,
            analysis = analysis,
            onTextChange = { text ->
                val changed = workspaceViewModel.updateText(text)
                if (!changed && text != uiState.text && !uiState.isEditorLocked) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Interactive Markdown editing is limited to $MAX_EDITABLE_MARKDOWN_CHARS characters; larger imports remain read-only and exportable."
                        )
                    }
                }
            },
            onNormalize = { workspaceViewModel.normalizeLineEndings(it) },
            onRepair = { requestWorkspaceTransform(DocbenchWorkspaceTransformKind.REPAIR_MARKDOWN_FENCES) },
            onUtf8BomChange = { workspaceViewModel.setUtf8Bom(it) },
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownWorkspaceTopBar(
    uiState: MarkdownWorkspaceUiState,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onRecent: () -> Unit,
    onExport: () -> Unit,
    onSaveSource: () -> Unit,
    onShare: () -> Unit,
    onFormat: (DocbenchWorkspaceTransformKind) -> Unit,
) {
    var showMoreActions by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Text(
                    text = uiState.displayName + if (uiState.isDirty) " *" else "",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        uiState.isRecoveryLoading -> "Restoring local draft…"
                        uiState.isImporting -> "Importing into local workspace…"
                        uiState.isExporting -> "Exporting local draft…"
                        uiState.isLargeDocumentReadOnly -> "Large local draft · read-only preview · export remains available"
                        else -> "Local autosaved draft · external files stay untouched"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            IconButton(onClick = onNew, enabled = !uiState.isBusy, modifier = Modifier.testTag("markdown_new")) {
                Icon(Icons.Default.NoteAdd, contentDescription = "New Markdown draft")
            }
            IconButton(onClick = onImport, enabled = !uiState.isBusy, modifier = Modifier.testTag("markdown_import")) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Import text or configuration file")
            }
            Box {
                IconButton(
                    onClick = { showMoreActions = true },
                    modifier = Modifier.testTag("markdown_more")
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More document actions")
                }
                DropdownMenu(
                    expanded = showMoreActions,
                    onDismissRequest = { showMoreActions = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Recent documents") },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        enabled = !uiState.isBusy,
                        onClick = {
                            showMoreActions = false
                            onRecent()
                        },
                        modifier = Modifier.testTag("markdown_recents")
                    )
                    listOf(
                        DocbenchWorkspaceTransformKind.FORMAT_JSON,
                        DocbenchWorkspaceTransformKind.FORMAT_JSON5,
                        DocbenchWorkspaceTransformKind.FORMAT_YAML,
                    ).forEach { kind ->
                        DropdownMenuItem(
                            text = { Text("Format ${kind.formatName}") },
                            leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                            enabled = !uiState.isBusy && !uiState.isEditorLocked && uiState.text.isNotBlank(),
                            onClick = {
                                showMoreActions = false
                                onFormat(kind)
                            },
                            modifier = Modifier.testTag("markdown_format_${kind.formatName.lowercase()}"),
                        )
                    }
                    if (uiState.origin is MarkdownWorkspaceOrigin.AndroidDocument) {
                        DropdownMenuItem(
                            text = { Text("Save source") },
                            leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                            enabled = uiState.isDirty && !uiState.isBusy && !uiState.isLargeDocumentReadOnly,
                            onClick = {
                                showMoreActions = false
                                onSaveSource()
                            },
                            modifier = Modifier.testTag("workspace_save_android_source"),
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Export text file") },
                        leadingIcon = { Icon(Icons.Default.SaveAs, contentDescription = null) },
                        enabled = !uiState.isBusy,
                        onClick = {
                            showMoreActions = false
                            onExport()
                        },
                        modifier = Modifier.testTag("markdown_export")
                    )
                    DropdownMenuItem(
                        text = { Text("Share as text") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        enabled = !uiState.isBusy && uiState.text.isNotEmpty(),
                        onClick = {
                            showMoreActions = false
                            onShare()
                        },
                        modifier = Modifier.testTag("markdown_share")
                    )
                }
            }
        }
    )
}

@Composable
private fun MarkdownWorkspaceBody(
    uiState: MarkdownWorkspaceUiState,
    analysis: MarkdownWorkspaceAnalysis?,
    onTextChange: (String) -> Unit,
    onNormalize: (LineEnding) -> Unit,
    onRepair: () -> Unit,
    onUtf8BomChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        WorkspaceMetadataRow(
            hadUtf8Bom = uiState.hadUtf8Bom,
            canChangeUtf8Bom = uiState.canChangeUtf8Bom,
            onUtf8BomChange = onUtf8BomChange,
            isDirty = uiState.isDirty,
            isLargeReadOnly = uiState.isLargeDocumentReadOnly,
            analysis = analysis
        )
        WorkspaceDiagnostics(
            analysis = analysis,
            canNormalize = !uiState.isEditorLocked,
            onNormalize = onNormalize,
            onRepair = onRepair,
        )
        if (uiState.isLargeDocumentReadOnly) {
            LargeMarkdownPreview(uiState.text, Modifier.fillMaxWidth().weight(1f))
        } else {
            OutlinedTextField(
                value = uiState.text,
                onValueChange = onTextChange,
                enabled = !uiState.isEditorLocked,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("markdown_editor"),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                label = { Text("Text / code") },
                placeholder = { Text("Type or paste text…") }
            )
        }
    }
}

@Composable
private fun LargeMarkdownPreview(text: String, modifier: Modifier = Modifier) {
    val chunks = remember(text) { previewChunkRanges(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Large document preview is chunked for performance. The exact text stays unchanged for recovery and export.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).testTag("markdown_large_preview")) {
            items(chunks, key = { it.first }) { range ->
                SelectionContainer {
                    Text(
                        text = text.substring(range.first, range.last + 1),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun previewChunkRanges(text: String): List<IntRange> = buildList {
    var start = 0
    while (start < text.length) {
        var endExclusive = minOf(start + LARGE_PREVIEW_CHUNK_CHARS, text.length)
        if (
            endExclusive < text.length &&
            endExclusive > start &&
            text[endExclusive - 1].isHighSurrogate() &&
            text[endExclusive].isLowSurrogate()
        ) {
            endExclusive--
        }
        add(start until endExclusive)
        start = endExclusive
    }
}

@Composable
private fun rememberMarkdownWorkspaceAnalysis(
    text: String,
    hadUtf8Bom: Boolean,
    revision: Long
): State<MarkdownWorkspaceAnalysis?> = produceState(
    initialValue = null,
    key1 = revision,
    key2 = hadUtf8Bom
) {
    delay(WORKSPACE_ANALYSIS_DEBOUNCE_MS)
    value = withContext(Dispatchers.Default) {
        val lineEndings = TextDocumentCodec.detectLineEndings(text)
        val document = TextDocument(text, hadUtf8Bom, lineEndings)
        MarkdownWorkspaceAnalysis(
            lineEndings = lineEndings,
            diagnostics = DocumentDiagnostics.inspect(document),
            markdownStructure = text.takeIf { it.length <= MAX_EDITABLE_MARKDOWN_CHARS }
                ?.let(MarkdownStructureDiagnostics::validate),
            safety = TextInspector.inspect(text),
            tokenCount = text.takeIf { it.length <= MAX_TOKENIZED_CHARS }?.let(LocalTokenCounter::count)
        )
    }
}

private suspend fun importWorkspaceDocument(
    context: Context,
    uri: Uri,
    viewModel: MarkdownWorkspaceViewModel,
    snackbarHostState: SnackbarHostState,
    onFailure: (Throwable) -> Unit = {},
    bindSource: Boolean = false,
): String? {
    val result = runCatching { MarkdownDocumentFileAccess.import(context, uri) }
    return result.fold(
        onSuccess = { opened ->
            opened.displayName.takeIf {
                viewModel.completeImport(
                    opened.displayName,
                    opened.document,
                    origin = if (bindSource) MarkdownWorkspaceOrigin.AndroidDocument(
                        uriString = uri.toString(),
                        sourceDigest = MarkdownDocumentFileAccess.sourceDigest(opened.document),
                    ) else null,
                )
            }
        },
        onFailure = { error ->
            viewModel.cancelImport()
            if (error is CancellationException) throw error
            onFailure(error)
            snackbarHostState.showSnackbar(importFailureMessage(error))
            null
        }
    )
}

private suspend fun exportWorkspaceDocument(
    context: Context,
    uri: Uri,
    fallbackName: String,
    snapshot: MarkdownExportSnapshot,
    viewModel: MarkdownWorkspaceViewModel,
    snackbarHostState: SnackbarHostState
) {
    val result = runCatching {
        MarkdownDocumentFileAccess.export(context, uri, snapshot.document)
        MarkdownDocumentFileAccess.displayName(context, uri, fallbackName)
    }
    result.fold(
        onSuccess = { actualName ->
            val stillCurrent = viewModel.completeExport(snapshot, actualName)
            if (stillCurrent) {
                snackbarHostState.showSnackbar("Exported $actualName")
            } else {
                snackbarHostState.showSnackbar("Exported an earlier snapshot to $actualName; newer edits remain local.")
            }
        },
        onFailure = { error ->
            viewModel.failExport(snapshot)
            if (error is CancellationException) throw error
            snackbarHostState.showSnackbar(exportFailureMessage(error))
        }
    )
}

private fun shareMarkdownText(context: Context, text: String, displayName: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_TITLE, displayName)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share text"))
}

private fun importFailureMessage(error: Throwable): String = when (error) {
    is IOException -> error.message ?: "Could not import document."
    is SecurityException -> error.message ?: "Document access was denied."
    else -> "Could not import document."
}

private fun exportFailureMessage(error: Throwable): String = when (error) {
    is IOException -> error.message ?: "Export failed. Choose another destination."
    is SecurityException -> error.message ?: "Write access was denied. Choose another destination."
    else -> "Export failed. Choose another destination."
}

private fun performDestructiveAction(
    action: PendingDestructiveWorkspaceAction,
    viewModel: MarkdownWorkspaceViewModel,
    importPicker: () -> Unit,
    openRecent: (RecentMarkdownDocument) -> Unit
) {
    when (action) {
        PendingDestructiveWorkspaceAction.New -> viewModel.newDocument()
        PendingDestructiveWorkspaceAction.Import -> importPicker()
        is PendingDestructiveWorkspaceAction.Recent -> openRecent(action.document)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentMarkdownDocumentsSheet(
    documents: List<RecentMarkdownDocument>,
    onOpen: (RecentMarkdownDocument) -> Unit,
    onTogglePin: (RecentMarkdownDocument) -> Unit,
    onForget: (RecentMarkdownDocument) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Recent documents", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Shortcuts to the original documents. Pinned shortcuts stay at the top and are protected from normal recent-file eviction; file contents are not copied into this list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (documents.isEmpty()) {
                Text("No persistent document shortcuts yet.", modifier = Modifier.padding(vertical = 20.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(documents, key = { it.uriString }) { document ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { onOpen(document) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Description, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(document.displayName, modifier = Modifier.fillMaxWidth())
                            }
                            IconButton(onClick = { onTogglePin(document) }) {
                                Icon(
                                    imageVector = if (document.isPinned) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = if (document.isPinned) {
                                        "Unpin ${document.displayName}"
                                    } else {
                                        "Pin ${document.displayName}"
                                    }
                                )
                            }
                            IconButton(onClick = { onForget(document) }) {
                                Icon(Icons.Default.Close, contentDescription = "Forget ${document.displayName}")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DiscardWorkspaceChangesDialog(
    onDismiss: () -> Unit,
    onDiscard: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discard unsaved changes?") },
        text = { Text("The current local draft has edits that have not been exported.") },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun WorkspaceMetadataRow(
    hadUtf8Bom: Boolean,
    canChangeUtf8Bom: Boolean,
    onUtf8BomChange: (Boolean) -> Unit,
    isDirty: Boolean,
    isLargeReadOnly: Boolean,
    analysis: MarkdownWorkspaceAnalysis?
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = hadUtf8Bom,
            onClick = { onUtf8BomChange(!hadUtf8Bom) },
            enabled = canChangeUtf8Bom,
            label = { Text(if (hadUtf8Bom) "UTF-8 BOM" else "UTF-8 no BOM") },
            modifier = Modifier.testTag("markdown_utf8_bom")
        )
        MetadataChip(analysis?.lineEndings?.style?.name ?: "EOL …")
        MetadataChip(
            when {
                analysis == null -> "tokens …"
                analysis.tokenCount != null -> "${analysis.tokenCount} tokens · ${LocalTokenCounter.ENCODING_LABEL}"
                else -> "token count skipped · large file"
            }
        )
        MetadataChip(if (isDirty) "Modified since export" else "Exported/imported state")
        if (isLargeReadOnly) MetadataChip("Read-only large preview")
        MetadataChip("Local recovery")
    }
}

@Composable
private fun MetadataChip(label: String) {
    AssistChip(onClick = {}, enabled = false, label = { Text(label) })
}

@Composable
private fun WorkspaceDiagnostics(
    analysis: MarkdownWorkspaceAnalysis?,
    canNormalize: Boolean,
    onNormalize: (LineEnding) -> Unit,
    onRepair: () -> Unit,
) {
    if (analysis == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        return
    }
    val mixed = analysis.diagnostics.firstOrNull { it.kind == DocumentDiagnosticKind.MIXED_LINE_ENDINGS }
    val nul = analysis.diagnostics.firstOrNull { it.kind == DocumentDiagnosticKind.NUL_CHARACTER }
    val markdownStructure = analysis.markdownStructure
    val safety = analysis.safety
    if (mixed == null && nul == null && (markdownStructure == null || markdownStructure.isValid) && !safety.hasFindings) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Document checks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            mixed?.let {
                Text(it.message, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onNormalize(LineEnding.LF) }, enabled = canNormalize) { Text("Normalize LF") }
                    TextButton(onClick = { onNormalize(LineEnding.CRLF) }, enabled = canNormalize) { Text("CRLF") }
                    TextButton(onClick = { onNormalize(LineEnding.CR) }, enabled = canNormalize) { Text("CR") }
                }
            }
            nul?.let { NulDiagnostic(it) }
            markdownStructure?.takeUnless { it.isValid }?.let { issues ->
                MarkdownStructureSummary(issues)
                if (issues.issues.any { it.repairable }) {
                    TextButton(
                        onClick = onRepair,
                        enabled = canNormalize,
                        modifier = Modifier.testTag("markdown_repair_preview"),
                    ) { Text("Preview fence repair") }
                }
            }
            if (safety.hasFindings) TextInspectorSummary(safety)
        }
    }
}

@Composable
private fun NulDiagnostic(diagnostic: DocumentDiagnostic) {
    Text(
        "${diagnostic.message} (${diagnostic.line}:${diagnostic.column})",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
}

@Composable
private fun MarkdownStructureSummary(validation: MarkdownStructureValidationResult) {
    validation.errorMessage?.let { error ->
        Text(
            "Markdown structure: $error",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    validation.issues.take(3).forEach { issue ->
        Text(
            "Markdown · ${issue.line}:${issue.column} · ${issue.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (validation.issues.size > 3) {
        Text(
            "${validation.issues.size - 3} more Markdown structure issue(s).",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun TextInspectorSummary(safety: TextInspectionResult) {
    Text(
        "Text Inspector: ${safety.detectedCount} finding(s), ${safety.highCount} high / " +
            "${safety.mediumCount} medium / ${safety.lowCount} low.",
        style = MaterialTheme.typography.bodySmall
    )
    safety.findings.take(4).forEach { finding ->
        Text(
            "${finding.severity} · ${finding.line}:${finding.column} · ${finding.label}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
    if (safety.findings.size > 4 || safety.truncated) {
        Text("More findings are available in the inspector result.", style = MaterialTheme.typography.labelSmall)
    }
}


package ais.tee.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import ais.tee.data.codebench.CodebenchBarcodeFormat
import ais.tee.data.codebench.CodebenchBarcodeGenerateAction
import ais.tee.data.codebench.CodebenchBarcodeGenerateActionResult
import ais.tee.data.codebench.CodebenchBarcodeMatrix
import ais.tee.data.codebench.CodebenchBarcodeCodec
import ais.tee.data.codebench.CodebenchImageSampling
import ais.tee.data.codebench.CodebenchImportedBarcodeDecodeAction
import ais.tee.data.codebench.CodebenchImportedBarcodeDecodeActionResult
import ais.tee.data.document.DocbenchDocumentPreflightActionResult
import ais.tee.data.document.DocbenchPendingExportStore
import ais.tee.data.document.DocbenchTextMergeAction
import ais.tee.data.document.DocbenchTextMergeActionResult
import ais.tee.data.document.DocbenchTextMergePart
import ais.tee.data.document.DocbenchTextPrintAction
import ais.tee.data.document.DocbenchTextPrintActionResult
import ais.tee.data.document.MAX_DOCBENCH_MERGED_TEXT_CHARS
import ais.tee.data.document.MAX_DOCBENCH_TEXT_MERGE_PARTS
import ais.tee.data.document.DocbenchTextExportAction
import ais.tee.data.document.DocbenchTextExportActionResult
import ais.tee.data.document.DocumentPreflightReport
import ais.tee.data.document.TextDocument
import ais.tee.data.document.TextDocumentCodec
import ais.tee.data.document.TextDocumentFileAccess
import ais.tee.data.document.executeDocbenchPreflightAction
import ais.tee.data.model.BenchToolNetworkBehavior
import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import ais.tee.data.model.BuiltInBenchTool
import ais.tee.data.model.capabilities
import ais.tee.data.preferences.BuiltInBenchPreferencesStore
import ais.tee.data.preferences.StreambenchStationPreferencesStore
import ais.tee.data.security.TextInspectionResult
import ais.tee.data.security.TextSafetyFinding
import ais.tee.data.streambench.StreambenchImportedPlaylistActionResult
import ais.tee.data.streambench.StreambenchPlaybackService
import ais.tee.data.streambench.StreambenchPlaylistEntry
import ais.tee.data.streambench.executeStreambenchPlaylistImportAction
import ais.tee.data.streambench.launchStreambenchPlayback
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val STREAMBENCH_PLAYLIST_MIME_TYPES = arrayOf(
    "application/vnd.apple.mpegurl",
    "audio/mpegurl",
    "audio/x-mpegurl",
    "text/plain",
    "application/octet-stream"
)

private data class StreambenchImportUiResult(
    val entries: List<StreambenchPlaylistEntry>,
    val message: String
)

private data class CodebenchImportUiResult(
    val message: String,
    val decodedText: String? = null,
    val decodedFormat: CodebenchBarcodeFormat? = null
)

internal data class DocbenchFindingSummary(
    val visibleFindings: List<TextSafetyFinding>,
    val omittedCount: Int,
    val detectedCount: Int
)

internal fun docbenchFindingSummary(
    inspection: TextInspectionResult,
    limit: Int = DOCBENCH_VISIBLE_FINDINGS
): DocbenchFindingSummary {
    val visibleFindings = inspection.findings.take(limit.coerceAtLeast(0))
    return DocbenchFindingSummary(
        visibleFindings = visibleFindings,
        omittedCount = (inspection.detectedCount - visibleFindings.size).coerceAtLeast(0),
        detectedCount = inspection.detectedCount
    )
}

internal fun docbenchValidationErrorPreview(
    message: String,
    limit: Int = DOCBENCH_VALIDATION_ERROR_PREVIEW_CHARS
): String = message
    .replace('\r', ' ')
    .replace('\n', ' ')
    .trim()
    .take(limit.coerceAtLeast(0))

private data class DocbenchImportUiResult(
    val displayName: String? = null,
    val report: DocumentPreflightReport? = null,
    val message: String? = null
)

private val DOCBENCH_TEXT_MERGE_MIME_TYPES = arrayOf(
    "text/markdown",
    "text/plain",
    "application/octet-stream"
)

private val DOCBENCH_DOCUMENT_MIME_TYPES = arrayOf(
    "text/markdown",
    "text/plain",
    "application/json",
    "application/yaml",
    "text/yaml",
    "application/x-yaml",
    "text/x-yaml",
    "application/xml",
    "text/xml",
    "application/octet-stream"
)

private data class DocbenchMergeUiResult(
    val mergedText: String? = null,
    val mergedCount: Int = 0,
    val message: String
)

internal fun isDocbenchTextMergeCandidate(
    displayName: String,
    mimeType: String?,
    hasProviderDisplayName: Boolean = true
): Boolean {
    val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (normalizedMime == "text/markdown" || normalizedMime == "text/plain") return true
    if (normalizedMime != null && normalizedMime != "application/octet-stream") return false
    if (!hasProviderDisplayName) return false
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("md", "markdown", "txt")
}

private suspend fun mergeDocbenchTextDocuments(
    context: Context,
    uris: List<Uri>,
    existingText: String,
    isEnabled: () -> Boolean
): DocbenchMergeUiResult = runCatching {
    if (!isEnabled()) {
        return@runCatching DocbenchMergeUiResult(message = DOCBENCH_POLICY_BLOCKED_MESSAGE)
    }
    if (uris.isEmpty()) {
        return@runCatching DocbenchMergeUiResult(message = "Select at least one text document.")
    }
    if (uris.size > MAX_DOCBENCH_TEXT_MERGE_PARTS) {
        return@runCatching DocbenchMergeUiResult(
            message = "Merge at most $MAX_DOCBENCH_TEXT_MERGE_PARTS documents at a time."
        )
    }

    val parts = mutableListOf<DocbenchTextMergePart>()
    var projectedLength = existingText.length.toLong()
    for (uri in uris) {
        if (!isEnabled()) {
            return@runCatching DocbenchMergeUiResult(message = DOCBENCH_POLICY_BLOCKED_MESSAGE)
        }
        val opened = TextDocumentFileAccess.import(
            context = context,
            uri = uri,
            fallbackName = "document.txt"
        )
        if (!isDocbenchTextMergeCandidate(opened.displayName, opened.mimeType, opened.hasProviderDisplayName)) {
            return@runCatching DocbenchMergeUiResult(
                message = "Merge accepts Markdown and plain-text documents only."
            )
        }
        projectedLength += opened.document.text.length
        if (existingText.isNotEmpty() || parts.isNotEmpty()) projectedLength += 2
        if (projectedLength > MAX_DOCBENCH_MERGED_TEXT_CHARS) {
            return@runCatching DocbenchMergeUiResult(
                message = "Merged text would exceed the $MAX_DOCBENCH_MERGED_TEXT_CHARS-character interactive limit."
            )
        }
        parts += DocbenchTextMergePart(opened.document.text)
    }
    if (!isEnabled()) {
        return@runCatching DocbenchMergeUiResult(message = DOCBENCH_POLICY_BLOCKED_MESSAGE)
    }
    when (
        val action = DocbenchTextMergeAction.execute(
            existingText = existingText,
            parts = parts,
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = isEnabled(),
            grantedPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)
        )
    ) {
        is DocbenchTextMergeActionResult.Completed -> DocbenchMergeUiResult(
            mergedText = action.text,
            mergedCount = action.appendedParts,
            message = if (action.changed) {
                "Merged ${action.appendedParts} text file(s) into the editor."
            } else {
                "Selected files did not change the editor."
            }
        )
        is DocbenchTextMergeActionResult.Rejected -> DocbenchMergeUiResult(message = action.message)
        is DocbenchTextMergeActionResult.Blocked ->
            DocbenchMergeUiResult(message = DOCBENCH_POLICY_BLOCKED_MESSAGE)
    }
}.getOrElse { error ->
    if (error is CancellationException) throw error
    if (error !is Exception) throw error
    DocbenchMergeUiResult(
        message = when (error) {
            is SecurityException -> "Aistee could not access one of the selected documents."
            is IOException -> error.message ?: "Could not read one of the selected documents."
            else -> "Could not merge the selected documents."
        }
    )
}

private suspend fun importDocbenchDocument(
    context: Context,
    uri: Uri,
    isEnabled: () -> Boolean
): DocbenchImportUiResult = runCatching {
    if (!isEnabled()) {
        return@runCatching DocbenchImportUiResult(message = DOCBENCH_POLICY_BLOCKED_MESSAGE)
    }
    val result = withContext(Dispatchers.Default) {
        if (!isEnabled()) return@withContext null
        val opened = TextDocumentFileAccess.import(
            context = context,
            uri = uri,
            fallbackName = "document.txt"
        )
        if (!isEnabled()) return@withContext null
        when (
            val action = opened.executeDocbenchPreflightAction(
                surface = BenchToolSurface.COMPANION_UI,
                isEnabled = isEnabled(),
                grantedPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)
            )
        ) {
            is DocbenchDocumentPreflightActionResult.Completed -> DocbenchImportUiResult(
                displayName = opened.displayName,
                report = action.report
            )
            is DocbenchDocumentPreflightActionResult.Blocked -> null
        }
    }
    result ?: DocbenchImportUiResult(message = DOCBENCH_POLICY_BLOCKED_MESSAGE)
}.getOrElse { error ->
    if (error is CancellationException) throw error
    if (error !is Exception) throw error
    DocbenchImportUiResult(
        message = when (error) {
            is SecurityException -> "Aistee could not access the selected document."
            is IOException -> error.message ?: "Could not read the selected document."
            else -> "Could not inspect the selected document."
        }
    )
}

private suspend fun importCodebenchBarcode(
    context: Context,
    uri: Uri,
    isEnabled: () -> Boolean
): CodebenchImportUiResult = runCatching {
    if (!isEnabled()) {
        return@runCatching CodebenchImportUiResult(CODEBENCH_POLICY_BLOCKED_MESSAGE)
    }
    val result = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext null
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the selected image." }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "The selected file is not a readable image."
            }

            val sampledOptions = BitmapFactory.Options().apply {
                inSampleSize = CodebenchImageSampling.sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri).use { sampledInput ->
                requireNotNull(sampledInput) { "Could not open the selected image." }
                val sampled = requireNotNull(
                    BitmapFactory.decodeStream(sampledInput, null, sampledOptions)
                ) { "The selected file is not a readable image." }
                try {
                    if (!isEnabled()) return@withContext null
                    val bounded = if (
                        sampled.width > CodebenchBarcodeCodec.MAX_RENDER_DIMENSION ||
                        sampled.height > CodebenchBarcodeCodec.MAX_RENDER_DIMENSION
                    ) {
                        val (targetWidth, targetHeight) = CodebenchImageSampling.boundedDimensions(
                            sampled.width,
                            sampled.height
                        )
                        Bitmap.createScaledBitmap(
                            sampled,
                            targetWidth,
                            targetHeight,
                            true
                        )
                    } else {
                        sampled
                    }
                    try {
                        if (!isEnabled()) return@withContext null
                        val pixels = IntArray(bounded.width * bounded.height)
                        bounded.getPixels(
                            pixels,
                            0,
                            bounded.width,
                            0,
                            0,
                            bounded.width,
                            bounded.height
                        )
                        CodebenchImportedBarcodeDecodeAction.execute(
                            width = bounded.width,
                            height = bounded.height,
                            pixels = pixels,
                            surface = BenchToolSurface.COMPANION_UI,
                            isEnabled = isEnabled(),
                            grantedPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)
                        )
                    } finally {
                        if (bounded !== sampled) bounded.recycle()
                    }
                } finally {
                    sampled.recycle()
                }
            }
        }
    }
    when (result) {
        null -> CodebenchImportUiResult(CODEBENCH_POLICY_BLOCKED_MESSAGE)
        is CodebenchImportedBarcodeDecodeActionResult.Completed -> CodebenchImportUiResult(
            message = "Decoded ${result.barcode.format.displayLabel()}.",
            decodedText = result.barcode.text,
            decodedFormat = result.barcode.format
        )
        CodebenchImportedBarcodeDecodeActionResult.NotFound -> CodebenchImportUiResult(
            "No supported QR code or barcode was found."
        )
        is CodebenchImportedBarcodeDecodeActionResult.Blocked -> CodebenchImportUiResult(
            CODEBENCH_POLICY_BLOCKED_MESSAGE
        )
        is CodebenchImportedBarcodeDecodeActionResult.Rejected -> CodebenchImportUiResult(
            "Could not decode this image."
        )
    }
}.getOrElse { error ->
    if (error is CancellationException) throw error
    if (error !is Exception) throw error
    CodebenchImportUiResult(
        when (error) {
            is SecurityException -> "Aistee could not access the selected image."
            is IOException -> error.message ?: "Could not read the selected image."
            else -> "Could not decode this image."
        }
    )
}

internal fun updatedBenchSelection(
    current: Set<BuiltInBenchTool>,
    tool: BuiltInBenchTool,
    enabled: Boolean
): Set<BuiltInBenchTool> = current.toMutableSet().apply {
    if (enabled) add(tool) else remove(tool)
}.toSet()

private suspend fun importStreambenchPlaylist(
    context: Context,
    uri: Uri,
    isEnabled: Boolean
): StreambenchImportUiResult = runCatching {
    val opened = TextDocumentFileAccess.import(
        context = context,
        uri = uri,
        fallbackName = "playlist.m3u"
    )
    when (
        val result = opened.executeStreambenchPlaylistImportAction(
            surface = BenchToolSurface.COMPANION_UI,
            isEnabled = isEnabled,
            grantedPermissions = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)
        )
    ) {
        is StreambenchImportedPlaylistActionResult.Completed -> {
            val suffix = if (result.entries.size == 1) "station" else "stations"
            StreambenchImportUiResult(result.entries, "Loaded ${result.entries.size} $suffix locally.")
        }
        is StreambenchImportedPlaylistActionResult.Blocked -> StreambenchImportUiResult(
            emptyList(),
            "Playlist import is blocked by the current Bench policy."
        )
        is StreambenchImportedPlaylistActionResult.Rejected -> StreambenchImportUiResult(
            emptyList(),
            "Could not import this playlist. Check its format and size."
        )
    }
}.getOrElse { error ->
    if (error is CancellationException) throw error
    StreambenchImportUiResult(
        entries = emptyList(),
        message = when (error) {
            is SecurityException -> "Aistee could not access the selected playlist."
            is IOException -> error.message ?: "Could not read the selected playlist."
            else -> "Could not import the selected playlist."
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchToolsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { BuiltInBenchPreferencesStore(context.applicationContext) }
    val stationStore = remember(context) {
        StreambenchStationPreferencesStore(context.applicationContext)
    }
    var enabledTools by remember(store) { mutableStateOf(store.loadEnabledTools()) }
    var favoriteStationKeys by remember(stationStore) { mutableStateOf(stationStore.loadFavoriteKeys()) }
    var recentStationKeys by remember(stationStore) { mutableStateOf(stationStore.loadRecentKeys()) }
    var streambenchEntries by remember { mutableStateOf<List<StreambenchPlaylistEntry>>(emptyList()) }
    var streambenchImportMessage by remember { mutableStateOf<String?>(null) }
    var streambenchPlaybackMessage by remember { mutableStateOf<String?>(null) }
    var streambenchImporting by remember { mutableStateOf(false) }
    var codebenchText by remember { mutableStateOf("") }
    var codebenchFormat by remember { mutableStateOf(CodebenchBarcodeFormat.QR_CODE) }
    var codebenchFormatMenuExpanded by remember { mutableStateOf(false) }
    var codebenchMatrix by remember { mutableStateOf<CodebenchBarcodeMatrix?>(null) }
    var codebenchGenerationMessage by remember { mutableStateOf<String?>(null) }
    var codebenchImportMessage by remember { mutableStateOf<CodebenchImportUiResult?>(null) }
    var codebenchImporting by remember { mutableStateOf(false) }
    var docbenchImportResult by remember { mutableStateOf<DocbenchImportUiResult?>(null) }
    var docbenchImporting by remember { mutableStateOf(false) }
    var docbenchImportGeneration by remember { mutableIntStateOf(0) }
    var docbenchMergeGeneration by rememberSaveable { mutableIntStateOf(0) }
    var pendingDocbenchMergeId by rememberSaveable { mutableStateOf<String?>(null) }
    // Null means the picker is open; an empty list means the user cancelled.
    var pendingDocbenchMergeUris by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val docbenchPendingMergeStore = remember(context.applicationContext) {
        DocbenchPendingExportStore(
            File(context.applicationContext.cacheDir, "docbench-pending-merges")
        )
    }
    var docbenchExportGeneration by rememberSaveable { mutableIntStateOf(0) }
    var pendingDocbenchExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDocbenchExportGeneration by rememberSaveable { mutableIntStateOf(-1) }
    var activeDocbenchExportLaunchGeneration by rememberSaveable { mutableIntStateOf(-1) }
    val docbenchPendingExportStore = remember(context.applicationContext) {
        DocbenchPendingExportStore(
            File(context.applicationContext.cacheDir, "docbench-pending-exports")
        )
    }
    val docbenchTextTransformState = remember(
        BuiltInBenchTool.DOCBENCH_DOCUMENT in enabledTools
    ) {
        DocbenchTextTransformUiState().apply {
            merging = pendingDocbenchMergeId != null
            exporting = activeDocbenchExportLaunchGeneration >= 0
        }
    }
    val docbenchTextInspectorState = remember(
        BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR in enabledTools
    ) { DocbenchTextInspectorUiState() }

    LaunchedEffect(docbenchPendingExportStore) {
        val pendingId = pendingDocbenchExportId
        val restored = withContext(Dispatchers.IO) {
            docbenchPendingExportStore.pruneOrphans(pendingId)
            pendingId?.let(docbenchPendingExportStore::load)
        }
        if (restored != null && pendingDocbenchExportId == pendingId) {
            docbenchTextTransformState.restoreExportDocument(restored)
        }
    }

    val streambenchImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            scope.launch {
                streambenchImporting = true
                val imported = importStreambenchPlaylist(
                    context = context,
                    uri = selectedUri,
                    isEnabled = BuiltInBenchTool.STREAMBENCH_PLAYER in enabledTools
                )
                if (BuiltInBenchTool.STREAMBENCH_PLAYER in store.loadEnabledTools()) {
                    streambenchEntries = imported.entries
                    streambenchImportMessage = imported.message
                    streambenchPlaybackMessage = null
                } else {
                    streambenchEntries = emptyList()
                    streambenchImportMessage = null
                    streambenchPlaybackMessage = null
                }
                streambenchImporting = false
            }
        }
    }

    val codebenchImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            scope.launch {
                codebenchImporting = true
                val imported = importCodebenchBarcode(
                    context = context,
                    uri = selectedUri,
                    isEnabled = {
                        BuiltInBenchTool.CODEBENCH_QR_BARCODE in store.loadEnabledTools()
                    }
                )
                codebenchImportMessage = if (
                    BuiltInBenchTool.CODEBENCH_QR_BARCODE in store.loadEnabledTools()
                ) imported else null
                codebenchImporting = false
            }
        }
    }

    val docbenchImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            val importGeneration = docbenchImportGeneration + 1
            docbenchImportGeneration = importGeneration
            docbenchImportResult = null
            scope.launch {
                docbenchImporting = true
                val imported = importDocbenchDocument(
                    context = context,
                    uri = selectedUri,
                    isEnabled = {
                        BuiltInBenchTool.DOCBENCH_DOCUMENT in store.loadEnabledTools()
                    }
                )
                if (importGeneration == docbenchImportGeneration) {
                    docbenchImportResult = if (
                        BuiltInBenchTool.DOCBENCH_DOCUMENT in store.loadEnabledTools()
                    ) {
                        imported
                    } else {
                        null
                    }
                    docbenchImporting = false
                }
            }
        }
    }

    LaunchedEffect(docbenchPendingMergeStore) {
        val pendingId = pendingDocbenchMergeId
        withContext(Dispatchers.IO) {
            docbenchPendingMergeStore.pruneOrphans(pendingId)
        }
    }

    val docbenchMergeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (pendingDocbenchMergeId != null) {
            // Retain a bounded result across recreation while the merge runs.
            pendingDocbenchMergeUris = ArrayList(
                uris.take(MAX_DOCBENCH_TEXT_MERGE_PARTS + 1).map(Uri::toString)
            )
        }
    }

    LaunchedEffect(pendingDocbenchMergeId, pendingDocbenchMergeUris) {
        val pendingId = pendingDocbenchMergeId ?: return@LaunchedEffect
        val selectedUris = pendingDocbenchMergeUris ?: return@LaunchedEffect
        val generation = docbenchMergeGeneration
        val state = docbenchTextTransformState
        fun isCurrent(): Boolean = generation == docbenchMergeGeneration &&
            pendingDocbenchMergeId == pendingId &&
            BuiltInBenchTool.DOCBENCH_DOCUMENT in store.loadEnabledTools()

        val snapshot = try {
            withContext(Dispatchers.IO) { docbenchPendingMergeStore.load(pendingId) }
        } catch (_: IOException) {
            null
        }
        if (isCurrent()) {
            if (snapshot == null) {
                state.message = "Could not restore the editor. Selected files were not merged."
            } else {
                state.restoreExportDocument(snapshot)
                val merged = if (selectedUris.isEmpty()) {
                    DocbenchMergeUiResult(message = "Merge cancelled.")
                } else {
                    mergeDocbenchTextDocuments(
                        context = context,
                        uris = selectedUris.map(Uri::parse),
                        existingText = snapshot.text,
                        isEnabled = ::isCurrent
                    )
                }
                if (isCurrent()) {
                    merged.mergedText?.let(state::updateSource)
                    state.message = merged.message
                }
            }
        }
        if (pendingDocbenchMergeId == pendingId) {
            pendingDocbenchMergeId = null
            pendingDocbenchMergeUris = null
            state.merging = false
        }
        withContext(NonCancellable + Dispatchers.IO) {
            docbenchPendingMergeStore.delete(pendingId)
        }
    }

    val docbenchTextExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val launchGeneration = activeDocbenchExportLaunchGeneration
        val pendingId = pendingDocbenchExportId
        val pendingGeneration = pendingDocbenchExportGeneration
        if (launchGeneration < 0) return@rememberLauncherForActivityResult

        if (uri == null) {
            activeDocbenchExportLaunchGeneration = -1
            if (pendingId == null || pendingGeneration != launchGeneration) {
                docbenchTextTransformState.exporting = false
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                val restored = withContext(Dispatchers.IO) {
                    docbenchPendingExportStore.load(pendingId)
                }
                if (
                    restored != null &&
                    pendingGeneration == docbenchExportGeneration &&
                    BuiltInBenchTool.DOCBENCH_DOCUMENT in store.loadEnabledTools()
                ) {
                    docbenchTextTransformState.restoreExportDocument(restored)
                }
                withContext(Dispatchers.IO) { docbenchPendingExportStore.delete(pendingId) }
                if (
                    pendingDocbenchExportId == pendingId &&
                    pendingDocbenchExportGeneration == pendingGeneration
                ) {
                    pendingDocbenchExportId = null
                    pendingDocbenchExportGeneration = -1
                }
                docbenchTextTransformState.exporting = false
                if (
                    pendingGeneration == docbenchExportGeneration &&
                    BuiltInBenchTool.DOCBENCH_DOCUMENT in store.loadEnabledTools()
                ) {
                    docbenchTextTransformState.message = "Export cancelled."
                }
            }
            return@rememberLauncherForActivityResult
        }

        if (
            pendingId == null ||
            pendingGeneration != launchGeneration ||
            pendingGeneration != docbenchExportGeneration ||
            BuiltInBenchTool.DOCBENCH_DOCUMENT !in store.loadEnabledTools()
        ) {
            activeDocbenchExportLaunchGeneration = -1
            if (pendingId != null) {
                pendingDocbenchExportId = null
                pendingDocbenchExportGeneration = -1
                scope.launch {
                    withContext(Dispatchers.IO) { docbenchPendingExportStore.delete(pendingId) }
                }
            }
            docbenchTextTransformState.exporting = false
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            val loadResult = runCatching {
                withContext(Dispatchers.IO) {
                    docbenchPendingExportStore.load(pendingId)
                        ?: throw IOException("Pending export data is no longer available.")
                }
            }
            var failure = loadResult.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (failure != null && failure !is Exception) throw failure

            val document = loadResult.getOrNull()
            if (
                document != null &&
                pendingGeneration == docbenchExportGeneration &&
                BuiltInBenchTool.DOCBENCH_DOCUMENT in store.loadEnabledTools()
            ) {
                docbenchTextTransformState.restoreExportDocument(document)
                val writeResult = runCatching {
                    TextDocumentFileAccess.export(context, uri, document)
                }
                failure = writeResult.exceptionOrNull()
                if (failure is CancellationException) throw failure
                if (failure != null && failure !is Exception) throw failure
            }

            withContext(Dispatchers.IO) { docbenchPendingExportStore.delete(pendingId) }
            if (activeDocbenchExportLaunchGeneration == launchGeneration) {
                activeDocbenchExportLaunchGeneration = -1
            }
            if (
                pendingDocbenchExportId == pendingId &&
                pendingDocbenchExportGeneration == pendingGeneration
            ) {
                pendingDocbenchExportId = null
                pendingDocbenchExportGeneration = -1
            }
            docbenchTextTransformState.exporting = false

            if (
                pendingGeneration != docbenchExportGeneration ||
                BuiltInBenchTool.DOCBENCH_DOCUMENT !in store.loadEnabledTools()
            ) {
                return@launch
            }
            docbenchTextTransformState.message = when (failure) {
                null -> "Exported text locally."
                is SecurityException -> "Aistee could not write to the selected document."
                is IOException -> failure.message ?: "Could not export the text."
                else -> "Could not export the text."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Built-in Benches", fontWeight = FontWeight.Bold)
                        Text(
                            "Enable only the first-party tools you want available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp)
                    )
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Text(
                    "Enabling a Bench does not grant file, camera, export or network access. Each action still checks its own policy before running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(BuiltInBenchTool.entries, key = { it.id }) { tool ->
                val capabilities = tool.capabilities()
                val enabled = tool in enabledTools
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bench_tool_${tool.id}")
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    capabilities.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    capabilities.networkBehavior.displayLabel(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { shouldEnable ->
                                    val updated = updatedBenchSelection(enabledTools, tool, shouldEnable)
                                    enabledTools = updated
                                    store.saveEnabledTools(updated)
                                    if (tool == BuiltInBenchTool.STREAMBENCH_PLAYER && !shouldEnable) {
                                        StreambenchPlaybackService.stop(context)
                                        streambenchEntries = emptyList()
                                        streambenchImportMessage = null
                                        streambenchPlaybackMessage = null
                                    }
                                    if (tool == BuiltInBenchTool.CODEBENCH_QR_BARCODE && !shouldEnable) {
                                        codebenchText = ""
                                        codebenchFormat = CodebenchBarcodeFormat.QR_CODE
                                        codebenchFormatMenuExpanded = false
                                        codebenchMatrix = null
                                        codebenchGenerationMessage = null
                                        codebenchImportMessage = null
                                    }
                                    if (tool == BuiltInBenchTool.DOCBENCH_DOCUMENT && !shouldEnable) {
                                        val pendingExportId = pendingDocbenchExportId
                                        val pendingMergeId = pendingDocbenchMergeId
                                        pendingDocbenchMergeId = null
                                        pendingDocbenchMergeUris = null
                                        if (pendingMergeId != null) {
                                            scope.launch(Dispatchers.IO) {
                                                docbenchPendingMergeStore.delete(pendingMergeId)
                                            }
                                        }
                                        docbenchImportGeneration += 1
                                        docbenchMergeGeneration += 1
                                        docbenchExportGeneration += 1
                                        pendingDocbenchExportId = null
                                        pendingDocbenchExportGeneration = -1
                                        docbenchImportResult = null
                                        docbenchImporting = false
                                        if (pendingExportId != null) {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    docbenchPendingExportStore.delete(pendingExportId)
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("bench_toggle_${tool.id}")
                            )
                        }

                        if (tool == BuiltInBenchTool.STREAMBENCH_PLAYER && enabled) {
                            HorizontalDivider()
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    "Import an M3U/M3U8 playlist locally, then search, favorite or explicitly start an allowed HTTPS stream. Playlist contents stay in memory; only local station hashes are kept for favorites and recents.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        try {
                                            streambenchImportLauncher.launch(STREAMBENCH_PLAYLIST_MIME_TYPES)
                                        } catch (_: ActivityNotFoundException) {
                                            streambenchImportMessage = "No document picker is available."
                                        }
                                    },
                                    enabled = !streambenchImporting,
                                    modifier = Modifier.testTag("streambench_import_playlist")
                                ) {
                                    Text(if (streambenchImporting) "Importing…" else "Import playlist")
                                }
                                streambenchImportMessage?.let { message ->
                                    Text(message, style = MaterialTheme.typography.bodySmall)
                                }
                                streambenchPlaybackMessage?.let { message ->
                                    Text(message, style = MaterialTheme.typography.bodySmall)
                                }
                                if (streambenchEntries.isNotEmpty()) {
                                    StreambenchStationBrowser(
                                        entries = streambenchEntries,
                                        favoriteKeys = favoriteStationKeys,
                                        recentKeys = recentStationKeys,
                                        onToggleFavorite = { key ->
                                            favoriteStationKeys = stationStore.toggleFavorite(key)
                                        },
                                        onPlay = { entry, key ->
                                            recentStationKeys = stationStore.recordRecent(key)
                                            streambenchPlaybackMessage = launchStreambenchPlayback(
                                                context = context,
                                                entry = entry,
                                                isEnabled = BuiltInBenchTool.STREAMBENCH_PLAYER in store.loadEnabledTools()
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        if (tool == BuiltInBenchTool.CODEBENCH_QR_BARCODE && enabled) {
                                HorizontalDivider()
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text(
                                        "Generate or decode QR codes and barcodes locally. Images are downsampled before decoding and never uploaded.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedTextField(
                                        value = codebenchText,
                                        onValueChange = {
                                            codebenchText = it
                                            codebenchMatrix = null
                                            codebenchGenerationMessage = null
                                        },
                                        label = { Text("Text to encode") },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("codebench_text")
                                    )
                                    Box {
                                        OutlinedButton(
                                            onClick = { codebenchFormatMenuExpanded = true },
                                            modifier = Modifier.testTag("codebench_format")
                                        ) {
                                            Text("Format: ${codebenchFormat.displayLabel()}")
                                        }
                                        DropdownMenu(
                                            expanded = codebenchFormatMenuExpanded,
                                            onDismissRequest = { codebenchFormatMenuExpanded = false }
                                        ) {
                                            CodebenchBarcodeFormat.entries.forEach { format ->
                                                DropdownMenuItem(
                                                    text = { Text(format.displayLabel()) },
                                                    onClick = {
                                                        codebenchFormat = format
                                                        codebenchFormatMenuExpanded = false
                                                        codebenchMatrix = null
                                                        codebenchGenerationMessage = null
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            val (previewWidth, previewHeight) =
                                                codebenchPreviewDimensions(codebenchFormat)
                                            when (
                                                val result = CodebenchBarcodeGenerateAction.execute(
                                                    text = codebenchText,
                                                    format = codebenchFormat,
                                                    width = previewWidth,
                                                    height = previewHeight,
                                                    surface = BenchToolSurface.COMPANION_UI,
                                                    isEnabled = BuiltInBenchTool.CODEBENCH_QR_BARCODE in
                                                        store.loadEnabledTools()
                                                )
                                            ) {
                                                is CodebenchBarcodeGenerateActionResult.Completed -> {
                                                    codebenchMatrix = result.matrix
                                                    codebenchGenerationMessage = "Generated locally."
                                                }
                                                is CodebenchBarcodeGenerateActionResult.Blocked -> {
                                                    codebenchGenerationMessage =
                                                        "Generation is blocked by the current Bench policy."
                                                }
                                                is CodebenchBarcodeGenerateActionResult.Rejected -> {
                                                    codebenchMatrix = null
                                                    codebenchGenerationMessage =
                                                        "Enter valid, non-empty text for this format."
                                                }
                                            }
                                        },
                                        modifier = Modifier.testTag("codebench_generate")
                                    ) {
                                        Text("Generate")
                                    }
                                    codebenchGenerationMessage?.let { message ->
                                        Text(message, style = MaterialTheme.typography.bodySmall)
                                    }
                                    codebenchMatrix?.let { matrix ->
                                        CodebenchBarcodePreview(matrix)
                                    }
                                    Button(
                                        onClick = {
                                            try {
                                                codebenchImportLauncher.launch(arrayOf("image/*"))
                                            } catch (_: ActivityNotFoundException) {
                                                codebenchImportMessage =
                                                    CodebenchImportUiResult("No document picker is available.")
                                            }
                                        },
                                        enabled = !codebenchImporting,
                                        modifier = Modifier.testTag("codebench_import_image")
                                    ) {
                                        Text(if (codebenchImporting) "Decoding…" else "Decode image")
                                    }
                                    codebenchImportMessage?.let { result ->
                                        Text(result.message, style = MaterialTheme.typography.bodySmall)
                                        result.decodedFormat?.let { format ->
                                            Text(
                                                "Format: ${format.displayLabel()}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        result.decodedText?.let { text ->
                                            Text(
                                                text,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.testTag("codebench_decoded_text")
                                            )
                                        }
                                    }

                                }
                            }
                        }

                        if (tool == BuiltInBenchTool.DOCBENCH_DOCUMENT && enabled) {
                            HorizontalDivider()
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    "Inspect a bounded local UTF-8 text document without changing it or uploading its contents.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        try {
                                            docbenchImportLauncher.launch(DOCBENCH_DOCUMENT_MIME_TYPES)
                                        } catch (_: ActivityNotFoundException) {
                                            docbenchImportResult = DocbenchImportUiResult(
                                                message = "No document picker is available."
                                            )
                                        }
                                    },
                                    enabled = !docbenchImporting,
                                    modifier = Modifier.testTag("docbench_import_document")
                                ) {
                                    Text(if (docbenchImporting) "Inspecting…" else "Inspect document")
                                }
                                docbenchImportResult?.let { result ->
                                    result.message?.let { message ->
                                        Text(message, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (result.displayName != null && result.report != null) {
                                        DocbenchDocumentReport(
                                            displayName = result.displayName,
                                            report = result.report
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                            DocbenchTextTransformPanel(
                                state = docbenchTextTransformState,
                                isEnabled = {
                                    BuiltInBenchTool.DOCBENCH_DOCUMENT in store.loadEnabledTools()
                                },
                                onMergeFiles = {
                                    val state = docbenchTextTransformState
                                    val generation = docbenchMergeGeneration + 1
                                    docbenchMergeGeneration = generation
                                    val source = state.source
                                    val includeUtf8Bom = state.includeUtf8Bom
                                    state.merging = true
                                    state.message = null
                                    scope.launch {
                                        val pendingId = try {
                                            withContext(Dispatchers.IO) {
                                                docbenchPendingMergeStore.save(
                                                    TextDocument(
                                                        text = source,
                                                        hadUtf8Bom = includeUtf8Bom,
                                                        lineEndings = TextDocumentCodec.detectLineEndings(source)
                                                    )
                                                )
                                            }
                                        } catch (_: IOException) {
                                            state.merging = false
                                            state.message = "Could not preserve the editor before merging."
                                            return@launch
                                        } catch (_: IllegalArgumentException) {
                                            state.merging = false
                                            state.message = "The editor contains text that cannot be saved as UTF-8."
                                            return@launch
                                        }
                                        if (
                                            generation != docbenchMergeGeneration ||
                                            BuiltInBenchTool.DOCBENCH_DOCUMENT !in store.loadEnabledTools()
                                        ) {
                                            withContext(Dispatchers.IO) {
                                                docbenchPendingMergeStore.delete(pendingId)
                                            }
                                            state.merging = false
                                            return@launch
                                        }
                                        pendingDocbenchMergeId = pendingId
                                        pendingDocbenchMergeUris = null
                                        try {
                                            docbenchMergeLauncher.launch(DOCBENCH_TEXT_MERGE_MIME_TYPES)
                                        } catch (_: ActivityNotFoundException) {
                                            pendingDocbenchMergeId = null
                                            state.merging = false
                                            state.message = "No document picker is available."
                                            withContext(Dispatchers.IO) {
                                                docbenchPendingMergeStore.delete(pendingId)
                                            }
                                        }
                                    }
                                },
                                onPrint = {
                                    val state = docbenchTextTransformState
                                    when (
                                        val action = DocbenchTextPrintAction.execute(
                                            text = state.source,
                                            surface = BenchToolSurface.COMPANION_UI,
                                            isEnabled = BuiltInBenchTool.DOCBENCH_DOCUMENT in
                                                store.loadEnabledTools(),
                                            grantedPermissions = setOf(
                                                BenchToolPermission.READ_USER_SELECTED_CONTENT,
                                                BenchToolPermission.WRITE_USER_EXPORT
                                            )
                                        )
                                    ) {
                                        is DocbenchTextPrintActionResult.Completed -> {
                                            state.message = launchDocbenchTextPrint(
                                                context,
                                                action.text
                                            )
                                        }
                                        is DocbenchTextPrintActionResult.Rejected -> {
                                            state.message = action.message
                                        }
                                        is DocbenchTextPrintActionResult.Blocked -> {
                                            state.message =
                                                "Printing is blocked by the current Bench policy."
                                        }
                                    }
                                },
                                onExport = {
                                    val generation = docbenchExportGeneration + 1
                                    docbenchExportGeneration = generation
                                    val source = docbenchTextTransformState.source
                                    val includeUtf8Bom = docbenchTextTransformState.includeUtf8Bom
                                    docbenchTextTransformState.exporting = true
                                    scope.launch {
                                        val action = withContext(Dispatchers.Default) {
                                            DocbenchTextExportAction.execute(
                                                text = source,
                                                includeUtf8Bom = includeUtf8Bom,
                                                surface = BenchToolSurface.COMPANION_UI,
                                                isEnabled = BuiltInBenchTool.DOCBENCH_DOCUMENT in
                                                    store.loadEnabledTools(),
                                                grantedPermissions = setOf(
                                                    BenchToolPermission.READ_USER_SELECTED_CONTENT,
                                                    BenchToolPermission.WRITE_USER_EXPORT
                                                )
                                            )
                                        }
                                        if (
                                            generation != docbenchExportGeneration ||
                                            BuiltInBenchTool.DOCBENCH_DOCUMENT !in store.loadEnabledTools()
                                        ) {
                                            docbenchTextTransformState.exporting = false
                                            return@launch
                                        }
                                        when (action) {
                                            is DocbenchTextExportActionResult.Completed -> {
                                                val saveResult = runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        docbenchPendingExportStore.save(action.document)
                                                    }
                                                }
                                                val failure = saveResult.exceptionOrNull()
                                                if (failure is CancellationException) throw failure
                                                if (failure != null && failure !is Exception) throw failure
                                                val exportId = saveResult.getOrNull()
                                                if (
                                                    generation != docbenchExportGeneration ||
                                                    BuiltInBenchTool.DOCBENCH_DOCUMENT !in
                                                        store.loadEnabledTools()
                                                ) {
                                                    if (exportId != null) {
                                                        withContext(Dispatchers.IO) {
                                                            docbenchPendingExportStore.delete(exportId)
                                                        }
                                                    }
                                                    docbenchTextTransformState.exporting = false
                                                    return@launch
                                                }
                                                if (exportId == null) {
                                                    docbenchTextTransformState.exporting = false
                                                    docbenchTextTransformState.message =
                                                        failure?.message ?: "Could not prepare the export."
                                                    return@launch
                                                }

                                                pendingDocbenchExportId = exportId
                                                pendingDocbenchExportGeneration = generation
                                                activeDocbenchExportLaunchGeneration = generation
                                                try {
                                                    docbenchTextExportLauncher.launch("docbench.txt")
                                                } catch (_: ActivityNotFoundException) {
                                                    activeDocbenchExportLaunchGeneration = -1
                                                    pendingDocbenchExportId = null
                                                    pendingDocbenchExportGeneration = -1
                                                    withContext(Dispatchers.IO) {
                                                        docbenchPendingExportStore.delete(exportId)
                                                    }
                                                    docbenchTextTransformState.exporting = false
                                                    docbenchTextTransformState.message =
                                                        "No document picker is available for export."
                                                }
                                            }
                                            is DocbenchTextExportActionResult.Blocked -> {
                                                docbenchTextTransformState.exporting = false
                                                docbenchTextTransformState.message =
                                                    "Export is blocked by the current Bench policy."
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        if (tool == BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR && enabled) {
                            HorizontalDivider()
                            DocbenchTextInspectorPanel(
                                state = docbenchTextInspectorState,
                                isEnabled = {
                                    BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR in store.loadEnabledTools()
                                }
                            )
                        }
                    }
                }
            }
        }
}

@Composable
private fun DocbenchDocumentReport(
    displayName: String,
    report: DocumentPreflightReport
) {
    val findingSummary = remember(report.textInspection) {
        docbenchFindingSummary(report.textInspection)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.testTag("docbench_report")
    ) {
        Text(displayName, fontWeight = FontWeight.SemiBold)
        Text(
            "BOM: ${if (report.hadUtf8Bom) "UTF-8 present" else "none"} • " +
                "line endings: ${report.lineEndings.style.name}",
            style = MaterialTheme.typography.bodySmall
        )
        report.tokenSummary?.let { tokens ->
            Text(
                "Tokens: ${tokens.count} (${tokens.encodingLabel})",
                style = MaterialTheme.typography.bodySmall
            )
        }
        report.structuredValidation?.let { validation ->
            Text(
                "${validation.format.name}: ${if (validation.isValid) "valid" else "invalid"}",
                style = MaterialTheme.typography.bodySmall
            )
            if (!validation.isValid) {
                validation.errorMessage?.let { error ->
                    Text(
                        docbenchValidationErrorPreview(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        report.diagnostics.forEach { diagnostic ->
            Text(
                "${diagnostic.severity.name}: ${diagnostic.message} " +
                    "(${diagnostic.line}:${diagnostic.column})",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            "Text Inspector: ${findingSummary.detectedCount} finding(s)",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        findingSummary.visibleFindings.forEach { finding ->
            Text(
                "${finding.severity.name}: ${finding.label} (${finding.line}:${finding.column})",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Text(finding.detail, style = MaterialTheme.typography.bodySmall)
        }
        if (findingSummary.omittedCount > 0) {
            Text(
                "+${findingSummary.omittedCount} more finding(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CodebenchBarcodePreview(matrix: CodebenchBarcodeMatrix) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(matrix.width.toFloat() / matrix.height)
            .clip(MaterialTheme.shapes.small)
            .background(Color.White)
            .testTag("codebench_preview")
    ) {
        val cellWidth = size.width / matrix.width
        val cellHeight = size.height / matrix.height
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            x * cellWidth,
                            y * cellHeight
                        ),
                        size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight)
                    )
                }
            }
        }
    }
}

internal fun codebenchPreviewDimensions(format: CodebenchBarcodeFormat): Pair<Int, Int> = when (format) {
    CodebenchBarcodeFormat.QR_CODE,
    CodebenchBarcodeFormat.DATA_MATRIX,
    CodebenchBarcodeFormat.AZTEC -> CODEBENCH_SQUARE_PREVIEW to CODEBENCH_SQUARE_PREVIEW
    CodebenchBarcodeFormat.PDF_417 -> CODEBENCH_WIDE_PREVIEW_WIDTH to CODEBENCH_PDF417_PREVIEW_HEIGHT
    else -> CODEBENCH_WIDE_PREVIEW_WIDTH to CODEBENCH_LINEAR_PREVIEW_HEIGHT
}

private fun CodebenchBarcodeFormat.displayLabel(): String = name.replace('_', ' ')

private fun BenchToolNetworkBehavior.displayLabel(): String = when (this) {
    BenchToolNetworkBehavior.LOCAL_ONLY -> "Local only"
    BenchToolNetworkBehavior.NETWORK_OPTIONAL -> "Network only when a concrete action requires it"
    BenchToolNetworkBehavior.NETWORK_REQUIRED -> "Network required"
}

private const val CODEBENCH_SQUARE_PREVIEW = 256
private const val CODEBENCH_WIDE_PREVIEW_WIDTH = 512
private const val CODEBENCH_PDF417_PREVIEW_HEIGHT = 256
private const val CODEBENCH_LINEAR_PREVIEW_HEIGHT = 192
private const val CODEBENCH_POLICY_BLOCKED_MESSAGE =
    "Image decoding is blocked by the current Bench policy."
private const val DOCBENCH_VISIBLE_FINDINGS = 8
private const val DOCBENCH_VALIDATION_ERROR_PREVIEW_CHARS = 240
private const val DOCBENCH_POLICY_BLOCKED_MESSAGE =
    "Document inspection is blocked by the current Bench policy."

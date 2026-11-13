package ais.tee.ui.screens

import ais.tee.data.document.PortablePrompt
import ais.tee.data.document.TextDocument
import ais.tee.data.document.TextDocumentFileAccess
import ais.tee.data.model.ProjectLibraryAsset
import ais.tee.data.model.ProjectLibraryAssetKind
import ais.tee.ui.viewmodel.StudioViewModel
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class WebPromptVaultTransfer(
    val busy: Boolean,
    val importPrompt: () -> Unit,
    val exportPrompt: (ProjectLibraryAsset) -> Unit,
)

@Composable
internal fun rememberWebPromptVaultTransfer(viewModel: StudioViewModel): WebPromptVaultTransfer {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var exportAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportRevision by rememberSaveable { mutableStateOf<Long?>(null) }

    fun transfer(fallback: String, operation: suspend () -> String?) {
        busy = true
        scope.launch {
            val message = try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                fallback
            } finally {
                busy = false
            }
            if (message != null) viewModel.showSnackbar(message)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            transfer("Could not import prompt JSON.") {
                val raw = TextDocumentFileAccess.import(context, uri).document.text
                val prompt = withContext(Dispatchers.Default) { PortablePrompt.decode(raw) }
                when {
                    prompt == null -> "Invalid or unsupported prompt JSON."
                    viewModel.importPortablePrompt(prompt) -> "Prompt imported into Project Library Inbox."
                    else -> "Could not save the imported prompt."
                }
            }
        }
    }

    suspend fun prepareExport(assetId: String, revision: Long?): TextDocument? {
        val asset = viewModel.loadProjectLibraryAssetWithMetadata(assetId) ?: return null
        if (asset.metadata.kind != ProjectLibraryAssetKind.PROMPT || asset.metadata.revision != revision) {
            return null
        }
        return withContext(Dispatchers.Default) {
            PortablePrompt.fromLibrary(asset.metadata.title, asset.text).toDocument()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val id = exportAssetId
        val revision = exportRevision
        exportAssetId = null
        exportRevision = null
        if (uri != null && id != null) {
            transfer("Could not export prompt JSON.") {
                val document = prepareExport(id, revision)
                if (document == null) {
                    "Prompt changed before export. Reopen it and try again."
                } else {
                    TextDocumentFileAccess.export(context, uri, document)
                    "Exported prompt JSON."
                }
            }
        }
    }

    return WebPromptVaultTransfer(
        busy = busy || exportAssetId != null,
        importPrompt = {
            try {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            } catch (_: ActivityNotFoundException) {
                viewModel.showSnackbar("No document picker is available.")
            }
        },
        exportPrompt = { asset ->
            if (!busy && exportAssetId == null) {
                transfer("Could not prepare prompt JSON.") {
                    if (prepareExport(asset.id, asset.revision) == null) {
                        "Prompt changed, is unreadable, or exceeds the JSON size limit."
                    } else {
                        exportAssetId = asset.id
                        exportRevision = asset.revision
                        var pickerLaunched = false
                        try {
                            exportLauncher.launch("aistee-prompt.json")
                            pickerLaunched = true
                            null
                        } catch (_: ActivityNotFoundException) {
                            "No document picker is available."
                        } finally {
                            if (!pickerLaunched) {
                                exportAssetId = null
                                exportRevision = null
                            }
                        }
                    }
                }
            }
        },
    )
}

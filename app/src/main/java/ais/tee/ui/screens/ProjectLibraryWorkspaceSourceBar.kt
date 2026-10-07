package ais.tee.ui.screens

import ais.tee.data.model.ProjectLibraryAsset
import ais.tee.ui.viewmodel.MarkdownWorkspaceOrigin
import ais.tee.ui.viewmodel.MarkdownWorkspaceViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProjectLibraryWorkspaceSourceBar(
    workspaceViewModel: MarkdownWorkspaceViewModel,
    onSave: suspend (assetId: String, expectedRevision: Long, text: String) -> ProjectLibraryAsset?,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uiState by workspaceViewModel.uiState.collectAsStateWithLifecycle()
    val origin = uiState.origin as? MarkdownWorkspaceOrigin.ProjectLibrary ?: return

    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Project Library · revision ${origin.revision}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                enabled = uiState.isDirty && !uiState.isBusy && !uiState.isLargeDocumentReadOnly,
                onClick = {
                    scope.launch {
                        val snapshot = workspaceViewModel.beginExport() ?: return@launch
                        val snapshotOrigin =
                            snapshot.origin as? MarkdownWorkspaceOrigin.ProjectLibrary
                        if (snapshotOrigin == null) {
                            workspaceViewModel.failExport(snapshot)
                            onMessage("This draft is no longer bound to a Library asset.")
                            return@launch
                        }
                        val updated = try {
                            onSave(
                                snapshotOrigin.assetId,
                                snapshotOrigin.revision,
                                snapshot.document.text,
                            )
                        } catch (error: CancellationException) {
                            workspaceViewModel.failExport(snapshot)
                            throw error
                        }
                        if (updated == null) {
                            workspaceViewModel.failExport(snapshot)
                            onMessage(
                                "Could not save Library source. The asset changed, was removed, or is unavailable.",
                            )
                            return@launch
                        }
                        val current = workspaceViewModel.completeSourceSave(
                            snapshot = snapshot,
                            persistedOrigin = MarkdownWorkspaceOrigin.ProjectLibrary(
                                assetId = updated.id,
                                revision = updated.revision,
                            ),
                        )
                        onMessage(
                            if (current) {
                                "Saved Project Library revision ${updated.revision}."
                            } else {
                                "Saved Project Library revision ${updated.revision}; newer draft edits remain unsaved."
                            },
                        )
                    }
                },
                modifier = Modifier.testTag("save_project_library_source"),
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Save source")
            }
        }
    }
}

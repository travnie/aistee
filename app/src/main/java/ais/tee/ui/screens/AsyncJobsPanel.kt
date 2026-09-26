package ais.tee.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ais.tee.data.model.AiProvider
import ais.tee.data.model.AsyncProviderJob
import ais.tee.data.model.AsyncProviderJobState
import ais.tee.data.model.DEFAULT_PROJECT_ID
import ais.tee.ui.viewmodel.StudioViewModel

@Composable
internal fun AsyncJobsPanel(
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var prompt by rememberSaveable { mutableStateOf("") }
    var selectedModel by rememberSaveable { mutableStateOf(AiProvider.CHATGPT.defaultModel) }
    var selectedProjectId by rememberSaveable { mutableStateOf(DEFAULT_PROJECT_ID) }

    LaunchedEffect(Unit) {
        viewModel.refreshAsyncProviderJobsFromDisk()
    }
    LaunchedEffect(uiState.projectLibrary.projects) {
        if (uiState.projectLibrary.projects.none { it.id == selectedProjectId }) {
            selectedProjectId = uiState.projectLibrary.projects.firstOrNull()?.id ?: DEFAULT_PROJECT_ID
        }
    }

    val jobs = uiState.asyncProviderJobs.jobs.sortedByDescending { it.createdAtEpochMs }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Background jobs",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Long OpenAI Responses can run asynchronously while Aistee polls durable status in the background. " +
                "Aistee keeps store=false; OpenAI still temporarily stores background response data so polling can work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (uiState.apiKeyConfig.openAiKey.isBlank()) {
            Text(
                text = "Add an OpenAI API key from native chat settings before starting a job.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text("Model", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AiProvider.CHATGPT.availableModels) { model ->
                FilterChip(
                    selected = model == selectedModel,
                    onClick = { selectedModel = model },
                    label = { Text(model) },
                )
            }
        }

        Text("Save result to", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.projectLibrary.projects, key = { it.id }) { project ->
                FilterChip(
                    selected = project.id == selectedProjectId,
                    onClick = { selectedProjectId = project.id },
                    label = {
                        Text(
                            text = project.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Standalone background prompt") },
            minLines = 5,
            maxLines = 12,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("background_job_prompt"),
        )
        Button(
            onClick = {
                viewModel.startOpenAiBackgroundJob(
                    prompt = prompt,
                    model = selectedModel,
                    projectId = selectedProjectId,
                )
            },
            enabled = prompt.isNotBlank() &&
                uiState.apiKeyConfig.openAiKey.isNotBlank() &&
                uiState.projectLibrary.projects.any { it.id == selectedProjectId },
            modifier = Modifier.testTag("btn_start_openai_background_job"),
        ) {
            Text("Start background job")
        }

        HorizontalDivider()

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Tracked jobs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(onClick = viewModel::refreshAsyncProviderJobsFromDisk) {
                Text("Reload")
            }
        }

        if (!uiState.isAsyncProviderJobStoreReady) {
            Text("Loading local job state…")
        } else if (jobs.isEmpty()) {
            Text(
                text = "No background jobs yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            jobs.forEach { job ->
                AsyncJobCard(
                    job = job,
                    projectName = uiState.projectLibrary.projects
                        .firstOrNull { it.id == job.projectId }
                        ?.name
                        ?: "Unknown project",
                    onRefresh = { viewModel.refreshAsyncProviderJob(job.id) },
                    onCancel = { viewModel.cancelAsyncProviderJob(job.id) },
                    onRemove = { viewModel.deleteAsyncProviderJob(job.id) },
                )
            }
        }
    }
}

@Composable
private fun AsyncJobCard(
    job: AsyncProviderJob,
    projectName: String,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("async_job_${job.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = job.model,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${job.state.displayLabel()} · $projectName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (job.resultAssetId != null) {
                Text(
                    text = "Result saved to Project Library.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            job.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.widthIn(max = 520.dp),
            ) {
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
                if (!job.state.isTerminal) {
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                } else {
                    OutlinedButton(onClick = onRemove) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

private fun AsyncProviderJobState.displayLabel(): String = when (this) {
    AsyncProviderJobState.QUEUED -> "Queued"
    AsyncProviderJobState.RUNNING -> "Running"
    AsyncProviderJobState.SUCCEEDED -> "Completed"
    AsyncProviderJobState.FAILED -> "Failed"
    AsyncProviderJobState.CANCELLED -> "Cancelled"
    AsyncProviderJobState.EXPIRED -> "Expired"
    AsyncProviderJobState.INCOMPLETE -> "Incomplete"
}

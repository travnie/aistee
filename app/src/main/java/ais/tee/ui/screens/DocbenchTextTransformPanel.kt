package ais.tee.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ais.tee.data.document.DocbenchJsonFormatAction
import ais.tee.data.document.DocbenchJsonFormatActionResult
import ais.tee.data.document.DocbenchLineEndingNormalizeAction
import ais.tee.data.document.DocbenchLineEndingNormalizeActionResult
import ais.tee.data.document.LineEnding
import ais.tee.data.document.StructuredTextFormat
import ais.tee.data.document.TextDocument
import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BenchToolSurface
import ais.tee.data.tokenizer.MAX_INTERACTIVE_TOKENIZED_CHARS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DocbenchTextTransformUiState {
    var source by mutableStateOf("")
    var sourceGeneration by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)
    var inputError by mutableStateOf<String?>(null)
    var working by mutableStateOf(false)
    var merging by mutableStateOf(false)
    var exporting by mutableStateOf(false)
    var includeUtf8Bom by mutableStateOf(false)
    var structuredFormat by mutableStateOf(StructuredTextFormat.JSON)

    val canTransform: Boolean
        get() = source.isNotEmpty() && inputError == null && !working && !merging && !exporting

    val canMerge: Boolean
        get() = inputError == null && !working && !merging && !exporting

    val canExport: Boolean
        get() = canTransform

    fun updateSource(updated: String) {
        sourceGeneration += 1
        if (updated.length > MAX_INTERACTIVE_TOKENIZED_CHARS) {
            inputError =
                "Interactive transforms are limited to $MAX_INTERACTIVE_TOKENIZED_CHARS characters."
            message = null
            return
        }
        source = updated
        inputError = null
        message = null
    }

    fun restoreExportDocument(document: TextDocument) {
        sourceGeneration += 1
        source = document.text
        includeUtf8Bom = document.hadUtf8Bom
        inputError = null
    }
}

@Composable
internal fun DocbenchTextTransformPanel(
    state: DocbenchTextTransformUiState,
    isEnabled: () -> Boolean,
    onMergeFiles: () -> Unit,
    onPrint: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(16.dp)
    ) {
        Text(
            "Merge local Markdown/text files into this editor, format JSON, JSON5 or YAML, normalize line endings, then print or export locally.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = state.source,
            onValueChange = state::updateSource,
            label = { Text("Text to transform") },
            minLines = 5,
            enabled = !state.merging && !state.exporting,
            isError = state.inputError != null,
            supportingText = {
                state.inputError?.let { error -> Text(error) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("docbench_json_formatter_input")
        )
        OutlinedButton(
            onClick = onMergeFiles,
            enabled = state.canMerge,
            modifier = Modifier.testTag("docbench_merge_text_files")
        ) {
            Text(if (state.merging) "Merging…" else "Merge text files")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                StructuredTextFormat.JSON,
                StructuredTextFormat.JSON5,
                StructuredTextFormat.YAML
            ).forEach { format ->
                FilterChip(
                    selected = state.structuredFormat == format,
                    onClick = { state.structuredFormat = format },
                    enabled = !state.working && !state.merging && !state.exporting,
                    label = { Text(format.name) },
                    modifier = Modifier.testTag(
                        "docbench_json_mode_${format.name.lowercase()}"
                    )
                )
            }
        }
        Button(
            onClick = { launchStructuredFormat(scope, state, isEnabled) },
            enabled = state.canTransform,
            modifier = Modifier.testTag("docbench_json_formatter_run")
        ) {
            Text(if (state.working) "Working…" else "Format ${state.structuredFormat.name}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineEnding.entries.forEach { target ->
                OutlinedButton(
                    onClick = {
                        launchLineEndingNormalization(scope, state, target, isEnabled)
                    },
                    enabled = state.canTransform,
                    modifier = Modifier.testTag(
                        "docbench_normalize_${target.name.lowercase()}"
                    )
                ) {
                    Text(target.name)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.includeUtf8Bom,
                onClick = { state.includeUtf8Bom = !state.includeUtf8Bom },
                enabled = !state.working && !state.merging && !state.exporting,
                label = {
                    Text(if (state.includeUtf8Bom) "UTF-8 BOM" else "UTF-8 no BOM")
                },
                modifier = Modifier.testTag("docbench_export_bom")
            )
            OutlinedButton(
                onClick = onPrint,
                enabled = state.canExport,
                modifier = Modifier.testTag("docbench_print_text")
            ) {
                Text("Print")
            }
            OutlinedButton(
                onClick = onExport,
                enabled = state.canExport,
                modifier = Modifier.testTag("docbench_export_text")
            ) {
                Text(if (state.exporting) "Exporting..." else "Export text")
            }
        }
        state.message?.let { status ->
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun launchStructuredFormat(
    scope: CoroutineScope,
    state: DocbenchTextTransformUiState,
    isEnabled: () -> Boolean
) {
    val sourceToFormat = state.source
    val formatToUse = state.structuredFormat
    val generation = state.sourceGeneration
    scope.launch {
        state.working = true
        try {
            val action = withContext(Dispatchers.Default) {
                DocbenchJsonFormatAction.execute(
                    text = sourceToFormat,
                    format = formatToUse,
                    surface = BenchToolSurface.COMPANION_UI,
                    isEnabled = isEnabled(),
                    grantedPermissions = DOCUMENT_READ_GRANT
                )
            }
            if (!isEnabled() || generation != state.sourceGeneration) return@launch
            applyStructuredFormatResult(state, formatToUse, action)
        } finally {
            state.working = false
        }
    }
}

private fun applyStructuredFormatResult(
    state: DocbenchTextTransformUiState,
    format: StructuredTextFormat,
    action: DocbenchJsonFormatActionResult
) {
    when (action) {
        is DocbenchJsonFormatActionResult.Completed -> {
            if (action.text.length > MAX_INTERACTIVE_TOKENIZED_CHARS) {
                state.message =
                    "Transformed result was not applied because it exceeds the " +
                        "1,000,000-character interactive display limit. Original text is unchanged."
                return
            }
            state.source = action.text
            state.message = if (action.changed) "Formatted ${format.name} locally." else "${format.name} is already formatted."
        }
        is DocbenchJsonFormatActionResult.Rejected -> {
            state.message = docbenchValidationErrorPreview(action.message)
        }
        is DocbenchJsonFormatActionResult.Blocked -> {
            state.message = "Formatting is blocked by the current Bench policy."
        }
    }
}

private fun launchLineEndingNormalization(
    scope: CoroutineScope,
    state: DocbenchTextTransformUiState,
    target: LineEnding,
    isEnabled: () -> Boolean
) {
    val sourceToNormalize = state.source
    val generation = state.sourceGeneration
    scope.launch {
        state.working = true
        try {
            val action = withContext(Dispatchers.Default) {
                DocbenchLineEndingNormalizeAction.execute(
                    text = sourceToNormalize,
                    target = target,
                    surface = BenchToolSurface.COMPANION_UI,
                    isEnabled = isEnabled(),
                    grantedPermissions = DOCUMENT_READ_GRANT
                )
            }
            if (!isEnabled() || generation != state.sourceGeneration) return@launch
            applyLineEndingResult(state, target, action)
        } finally {
            state.working = false
        }
    }
}

private fun applyLineEndingResult(
    state: DocbenchTextTransformUiState,
    target: LineEnding,
    action: DocbenchLineEndingNormalizeActionResult
) {
    when (action) {
        is DocbenchLineEndingNormalizeActionResult.Completed -> {
            if (action.text.length > MAX_INTERACTIVE_TOKENIZED_CHARS) {
                state.message =
                    "Normalized result was not applied because it exceeds the " +
                        "1,000,000-character interactive display limit. Original text is unchanged."
                return
            }
            state.source = action.text
            state.message = if (action.changed) {
                "Normalized line endings to ${target.name}."
            } else {
                "Line endings are already ${target.name}."
            }
        }
        is DocbenchLineEndingNormalizeActionResult.Blocked -> {
            state.message = "Normalization is blocked by the current Bench policy."
        }
    }
}

private val DOCUMENT_READ_GRANT = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)

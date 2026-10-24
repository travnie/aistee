package ais.tee.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ais.tee.data.skills.AgentSkillDraftFactory
import ais.tee.data.skills.AgentSkillManifestParser
import ais.tee.data.skills.SkillAssistField
import ais.tee.data.skills.SkillAssistRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

/** Deterministic skills-only entry point; preview and persistence reuse the existing import flow. */
@Composable
internal fun SkillCreationDialog(
    onDismiss: () -> Unit,
    onPreview: (String) -> Unit,
    assistantRoute: SkillAssistRoute?,
    onAssist: suspend (SkillAssistRoute, SkillAssistField, String, String, String) -> String
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var instructions by rememberSaveable { mutableStateOf("") }
    val source = remember(name, description, instructions) {
        AgentSkillDraftFactory.create(name, description, instructions)
    }
    val validation = remember(source) { AgentSkillManifestParser.parse(source) }
    val missingInstructions = instructions.isBlank()
    val ready = validation.isValid && !missingInstructions
    val scope = rememberCoroutineScope()
    var assisting by remember { mutableStateOf(false) }
    var assistanceError by remember { mutableStateOf<String?>(null) }
    var proposedField by remember { mutableStateOf<SkillAssistField?>(null) }
    var proposal by remember { mutableStateOf("") }

    fun requestHelp(field: SkillAssistField) {
        val route = assistantRoute ?: return
        if (assisting) return
        scope.launch {
            assisting = true
            assistanceError = null
            proposedField = null
            try {
                proposal = onAssist(route, field, name, description, instructions)
                proposedField = field
            } catch (_: TimeoutCancellationException) {
                assistanceError = "Suggestion timed out. Try again."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                assistanceError = "Draft too long for Lite assistance. Shorten it and try again."
            } catch (_: Exception) {
                assistanceError = "AI suggestion failed. Check the selected provider or try again."
            } finally {
                assisting = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("skill_creation_dialog"),
        title = { Text("Create portable skill") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Create an instructions-only SKILL.md. Review the generated source before saving. " +
                        "It stays disabled until you enable it in the local library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Skill name") },
                    placeholder = { Text("markdown-helper") },
                    supportingText = { Text("Lowercase letters, digits and hyphens, up to 64 characters.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("skill_creation_name")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description and when to use") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("skill_creation_description")
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Markdown instructions") },
                    placeholder = { Text("# Instructions\n\nExplain what to do and what to avoid.") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("skill_creation_instructions")
                )
                Text(
                    text = if (assistantRoute == null) {
                        "Optional AI: select a single configured provider in Native Chat. " +
                            "Creating and validating skills works without AI."
                    } else {
                        "Optional: ${assistantRoute.provider.displayName} · ${assistantRoute.model} " +
                            "(${assistantRoute.funding}). Only this draft is sent after tapping a button."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { requestHelp(SkillAssistField.DESCRIPTION) },
                    enabled = assistantRoute != null && !assisting && name.isNotBlank(),
                    modifier = Modifier.testTag("skill_assist_description")
                ) { Text("Suggest description") }
                OutlinedButton(
                    onClick = { requestHelp(SkillAssistField.INSTRUCTIONS) },
                    enabled = assistantRoute != null && !assisting &&
                        name.isNotBlank() && description.isNotBlank(),
                    modifier = Modifier.testTag("skill_assist_instructions")
                ) { Text("Draft instructions") }
                if (assisting) CircularProgressIndicator()
                assistanceError?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                proposedField?.let { field ->
                    Text("Review suggestion before applying it.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = proposal,
                        onValueChange = { proposal = it },
                        label = { Text(if (field == SkillAssistField.DESCRIPTION)
                            "Suggested description" else "Suggested instructions") },
                        minLines = 3,
                        maxLines = 7,
                        modifier = Modifier.fillMaxWidth().testTag("skill_assist_proposal")
                    )
                    OutlinedButton(
                        onClick = {
                            if (field == SkillAssistField.DESCRIPTION) description = proposal
                            else instructions = proposal
                            proposedField = null
                            proposal = ""
                        },
                        enabled = proposal.isNotBlank() && !assisting,
                        modifier = Modifier.testTag("skill_assist_apply")
                    ) { Text("Apply suggestion") }
                    TextButton(onClick = {
                        proposedField = null
                        proposal = ""
                    }) { Text("Discard suggestion") }
                }
                if (!ready) {
                    val problem = validation.issues.firstOrNull()?.message
                        ?: if (missingInstructions) "Instructions are required." else null
                    problem?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPreview(source) },
                enabled = ready,
                modifier = Modifier.testTag("skill_creation_preview")
            ) { Text("Preview SKILL.md") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

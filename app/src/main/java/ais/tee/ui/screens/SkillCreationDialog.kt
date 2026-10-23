package ais.tee.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/** Deterministic skills-only entry point; preview and persistence reuse the existing import flow. */
@Composable
internal fun SkillCreationDialog(
    onDismiss: () -> Unit,
    onPreview: (String) -> Unit
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

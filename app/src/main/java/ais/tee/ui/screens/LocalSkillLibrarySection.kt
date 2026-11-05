package ais.tee.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ais.tee.data.document.MarkdownDocumentFileAccess
import ais.tee.data.document.TextDocument
import ais.tee.data.document.TextDocumentCodec
import ais.tee.data.skills.LocalSkillLibraryStore
import ais.tee.data.skills.LocalSkillSummary
import ais.tee.data.skills.LocalSkillDocument
import ais.tee.data.model.appendToNativeChatDraft
import ais.tee.ui.viewmodel.NavigationTab
import ais.tee.ui.viewmodel.StudioViewModel
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val LOCAL_SKILL_MISSING_MESSAGE = "Local skill is no longer available."
private const val MAX_ONE_SHOT_SKILL_DRAFT_CHARS = 128 * 1024

/** User-selected instructions, never runtime activation. */
internal fun oneShotSkillPrompt(source: String): String? =
    source.takeIf { it.isNotBlank() }?.let {
        "Apply this SKILL.md only for this request. Do not run skill scripts or tools.\n\n$it"
    }

private sealed interface LocalSkillExportResult {
    data object Missing : LocalSkillExportResult
    data object Exported : LocalSkillExportResult
    data class Failed(val message: String) : LocalSkillExportResult
}

private sealed interface LocalSkillActivationResult {
    data object Missing : LocalSkillActivationResult
    data class Updated(val skill: LocalSkillSummary) : LocalSkillActivationResult
    data class Failed(val message: String) : LocalSkillActivationResult
}

private suspend fun exportLocalSkill(
    context: Context,
    uri: Uri,
    skillName: String,
    store: LocalSkillLibraryStore
): LocalSkillExportResult {
    val stored = store.read(skillName) ?: return LocalSkillExportResult.Missing
    val source = stored.source
    val failure = runCatching {
        MarkdownDocumentFileAccess.export(
            context = context,
            uri = uri,
            document = TextDocument(
                text = source,
                hadUtf8Bom = false,
                lineEndings = TextDocumentCodec.detectLineEndings(source)
            )
        )
    }.exceptionOrNull()
    if (failure == null) return LocalSkillExportResult.Exported

    currentCoroutineContext().ensureActive()
    return when (failure) {
        is IOException, is SecurityException -> LocalSkillExportResult.Failed(
            failure.message ?: "Could not export local skill."
        )
        else -> throw failure
    }
}

private suspend fun updateLocalSkillActivation(
    store: LocalSkillLibraryStore,
    skillName: String,
    enabled: Boolean
): LocalSkillActivationResult = try {
    val updated = store.setEnabled(skillName, enabled)
        ?: return LocalSkillActivationResult.Missing
    LocalSkillActivationResult.Updated(updated)
} catch (error: IOException) {
    LocalSkillActivationResult.Failed(error.message ?: "Could not change local skill activation.")
}

private fun CoroutineScope.launchLocalSkillActivation(
    store: LocalSkillLibraryStore,
    currentSkills: List<LocalSkillSummary>,
    skillName: String,
    enabled: Boolean,
    onBusySkillChanged: (String?) -> Unit,
    onSkillsChanged: (List<LocalSkillSummary>) -> Unit,
    onMessage: (String) -> Unit
) = launch {
    onBusySkillChanged(skillName)
    try {
        when (val result = updateLocalSkillActivation(store, skillName, enabled)) {
            LocalSkillActivationResult.Missing -> {
                onSkillsChanged(store.load())
                onMessage(LOCAL_SKILL_MISSING_MESSAGE)
            }
            is LocalSkillActivationResult.Updated -> {
                onSkillsChanged(
                    currentSkills.map { current ->
                        if (current.name == result.skill.name) result.skill else current
                    }
                )
                onMessage(
                    if (enabled) {
                        "Enabled '$skillName' for native/API chats."
                    } else {
                        "Disabled '$skillName'."
                    }
                )
            }
            is LocalSkillActivationResult.Failed -> onMessage(result.message)
        }
    } finally {
        onBusySkillChanged(null)
    }
}

private fun CoroutineScope.launchLocalSkillView(
    store: LocalSkillLibraryStore,
    skillName: String,
    onBusySkillChanged: (String?) -> Unit,
    onSkillsChanged: (List<LocalSkillSummary>) -> Unit,
    onViewSource: (String, String) -> Unit,
    onMessage: (String) -> Unit
) = launch {
    onBusySkillChanged(skillName)
    try {
        val document = store.read(skillName)
        if (document == null) {
            onSkillsChanged(store.load())
            onMessage(LOCAL_SKILL_MISSING_MESSAGE)
        } else {
            onViewSource(skillName, boundedSkillSourceForDisplay(document.source))
        }
    } finally {
        onBusySkillChanged(null)
    }
}

private fun CoroutineScope.launchLocalSkillEdit(
    store: LocalSkillLibraryStore,
    skillName: String,
    onBusySkillChanged: (String?) -> Unit,
    onSkillsChanged: (List<LocalSkillSummary>) -> Unit,
    onEditSource: (String, String) -> Unit,
    onMessage: (String) -> Unit
) = launch {
    onBusySkillChanged(skillName)
    try {
        val document = store.read(skillName)
        if (document == null) {
            onSkillsChanged(store.load())
            onMessage(LOCAL_SKILL_MISSING_MESSAGE)
        } else {
            onEditSource(skillName, document.source)
        }
    } finally {
        onBusySkillChanged(null)
    }
}

private fun CoroutineScope.launchLocalSkillExport(
    context: Context,
    uri: Uri,
    store: LocalSkillLibraryStore,
    skillName: String,
    onBusySkillChanged: (String?) -> Unit,
    onSkillsChanged: (List<LocalSkillSummary>) -> Unit,
    onMessage: (String) -> Unit
) = launch {
    onBusySkillChanged(skillName)
    try {
        when (val result = exportLocalSkill(context, uri, skillName, store)) {
            LocalSkillExportResult.Missing -> {
                onSkillsChanged(store.load())
                onMessage(LOCAL_SKILL_MISSING_MESSAGE)
            }
            LocalSkillExportResult.Exported -> onMessage("Exported '$skillName' as SKILL.md.")
            is LocalSkillExportResult.Failed -> onMessage(result.message)
        }
    } finally {
        onBusySkillChanged(null)
    }
}

private fun CoroutineScope.launchLocalSkillRemoval(
    store: LocalSkillLibraryStore,
    skillName: String,
    onBusySkillChanged: (String?) -> Unit,
    onSkillsChanged: (List<LocalSkillSummary>) -> Unit,
    onMessage: (String) -> Unit
) = launch {
    onBusySkillChanged(skillName)
    try {
        store.remove(skillName)
        onSkillsChanged(store.load())
        onMessage("Removed '$skillName' from local skills.")
    } catch (error: IOException) {
        onMessage(error.message ?: "Could not remove local skill.")
    } finally {
        onBusySkillChanged(null)
    }
}

@Composable
internal fun LocalSkillLibrarySection(
    store: LocalSkillLibraryStore,
    searchQuery: String,
    refreshToken: Int,
    onCountChanged: (Int) -> Unit,
    onViewSource: (String, String) -> Unit,
    onEditSource: (String, String) -> Unit,
    onMessage: (String) -> Unit,
    activeSkillsEnabled: Boolean = false,
    viewModel: StudioViewModel? = null
) {
    val context = LocalContext.current
    var runningSkill by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<LocalSkillSummary>>(emptyList()) }
    var busySkill by remember { mutableStateOf<String?>(null) }
    var pendingEnableSkillName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportSkill by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRemoveSkill by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUseSkill by remember { mutableStateOf<LocalSkillDocument?>(null) }

    fun applySkills(updated: List<LocalSkillSummary>) {
        skills = updated
        onCountChanged(updated.size)
    }

    fun requestActivation(skillName: String, enabled: Boolean) {
        scope.launchLocalSkillActivation(
            store = store,
            currentSkills = skills,
            skillName = skillName,
            enabled = enabled,
            onBusySkillChanged = { busySkill = it },
            onSkillsChanged = ::applySkills,
            onMessage = onMessage
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val skillName = pendingExportSkill
        pendingExportSkill = null
        if (uri != null && skillName != null) {
            scope.launchLocalSkillExport(
                context = context,
                uri = uri,
                store = store,
                skillName = skillName,
                onBusySkillChanged = { busySkill = it },
                onSkillsChanged = ::applySkills,
                onMessage = onMessage
            )
        }
    }

    LaunchedEffect(store, refreshToken) {
        applySkills(store.load())
    }

    LocalSkillLibraryContent(
        skills = skills,
        searchQuery = searchQuery,
        actionsEnabled = busySkill == null,
        onActivationChanged = { skill, enabled ->
            if (enabled) pendingEnableSkillName = skill.name
            else requestActivation(skill.name, enabled = false)
        },
        onView = { skill ->
            scope.launchLocalSkillView(
                store = store,
                skillName = skill.name,
                onBusySkillChanged = { busySkill = it },
                onSkillsChanged = ::applySkills,
                onViewSource = onViewSource,
                onMessage = onMessage
            )
        },
        onEdit = { skill ->
            scope.launchLocalSkillEdit(
                store = store,
                skillName = skill.name,
                onBusySkillChanged = { busySkill = it },
                onSkillsChanged = ::applySkills,
                onEditSource = onEditSource,
                onMessage = onMessage
            )
        },
        onExport = { skill ->
            pendingExportSkill = skill.name
            try {
                exportLauncher.launch("SKILL.md")
            } catch (_: ActivityNotFoundException) {
                pendingExportSkill = null
                onMessage("No document picker is available for export.")
            }
        },
        onRemove = { skill -> pendingRemoveSkill = skill.name },
        onUseOnce = if (viewModel != null) { skill: LocalSkillSummary ->
            scope.launch {
                busySkill = skill.name
                try {
                    pendingUseSkill = store.read(skill.name)
                    if (pendingUseSkill == null) onMessage(LOCAL_SKILL_MISSING_MESSAGE)
                } catch (_: IOException) {
                    onMessage("Could not read this SKILL.md.")
                } finally {
                    busySkill = null
                }
            }
        } else null,
        onRun = if (activeSkillsEnabled) { skill: LocalSkillSummary -> runningSkill = skill.name } else null
    )

    pendingUseSkill?.let { document ->
        val model = viewModel
        if (model != null) {
            val name = document.manifest.name
            val prompt = oneShotSkillPrompt(document.source)
            AlertDialog(
                onDismissRequest = { pendingUseSkill = null },
                title = { Text("Use '$name' once?") },
                text = {
                    Text(
                        "Stages SKILL.md instructions for one message only; never executes scripts or tools. " +
                            "Review before sending. Web staging replaces the provider's pending draft."
                    )
                },
                confirmButton = {
                    Column {
                        Row {
                            TextButton(
                                onClick = {
                                    val state = model.uiState.value
                                    val conversation = state.nativeChat.activeConversation
                                    val updated = if (
                                        state.isNativeConversationStoreReady &&
                                        conversation != null && prompt != null
                                    ) appendToNativeChatDraft(
                                        prompt, conversation.draft, MAX_ONE_SHOT_SKILL_DRAFT_CHARS
                                    ) else null
                                    if (updated == null) {
                                        onMessage("No native conversation or prompt too large.")
                                    } else {
                                        model.updateNativeConversationDraft(updated)
                                        model.selectTab(NavigationTab.COMPARE_HUB)
                                        pendingUseSkill = null
                                        onMessage("Staged SKILL.md once. Nothing was sent.")
                                    }
                                },
                                modifier = Modifier.testTag("skill_use_once_native"),
                            ) { Text("Native draft") }
                            TextButton(
                                onClick = {
                                    val provider = model.uiState.value.selectedWebService
                                    if (prompt != null && model.stageWebDraftReply(provider, prompt)) {
                                        pendingUseSkill = null
                                        onMessage("Web draft staged; review before sending.")
                                    } else {
                                        onMessage("Too large for Web draft. Export SKILL.md for upload.")
                                    }
                                },
                                modifier = Modifier.testTag("skill_use_once_web"),
                            ) { Text("Web draft") }
                        }
                        TextButton(
                            onClick = {
                                pendingUseSkill = null
                                pendingExportSkill = name
                                try {
                                    exportLauncher.launch("SKILL.md")
                                } catch (_: ActivityNotFoundException) {
                                    pendingExportSkill = null
                                    onMessage("No document picker is available.")
                                }
                            },
                            modifier = Modifier.testTag("skill_use_once_export"),
                        ) { Text("Export SKILL.md for upload") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingUseSkill = null }) { Text("Cancel") }
                },
            )
        }
    }

    runningSkill?.let { name ->
        ActiveSkillRunFlow(skillName = name, store = store, onClose = { runningSkill = null })
    }

    pendingEnableSkillName
        ?.let { name -> skills.firstOrNull { it.name == name } }
        ?.let { skill ->
            ConfirmEnableLocalSkillDialog(
                skillName = skill.name,
                onDismiss = { pendingEnableSkillName = null },
                onConfirm = {
                    pendingEnableSkillName = null
                    requestActivation(skill.name, enabled = true)
                }
            )
        }

    pendingRemoveSkill?.let { skillName ->
        ConfirmRemoveLocalSkillDialog(
            skillName = skillName,
            onDismiss = { pendingRemoveSkill = null },
            onConfirm = {
                pendingRemoveSkill = null
                scope.launchLocalSkillRemoval(
                    store = store,
                    skillName = skillName,
                    onBusySkillChanged = { busySkill = it },
                    onSkillsChanged = ::applySkills,
                    onMessage = onMessage
                )
            }
        )
    }
}

@Composable
private fun LocalSkillLibraryContent(
    skills: List<LocalSkillSummary>,
    searchQuery: String,
    actionsEnabled: Boolean,
    onActivationChanged: (LocalSkillSummary, Boolean) -> Unit,
    onView: (LocalSkillSummary) -> Unit,
    onEdit: (LocalSkillSummary) -> Unit,
    onExport: (LocalSkillSummary) -> Unit,
    onRemove: (LocalSkillSummary) -> Unit,
    onRun: ((LocalSkillSummary) -> Unit)? = null,
    onUseOnce: ((LocalSkillSummary) -> Unit)? = null
) {
    val filtered = skills.filter { skill ->
        skill.name.contains(searchQuery, ignoreCase = true) ||
            skill.description.contains(searchQuery, ignoreCase = true)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        LocalSkillLibraryHeader(skills.size)
        Text(
            text = "Enabling a skill adds only its Markdown instructions to native/API system prompts. Declared scripts and tools remain inert.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LocalSkillLibraryEmptyMessage(
            allSkillsEmpty = skills.isEmpty(),
            filteredSkillsEmpty = filtered.isEmpty()
        )
        filtered.forEach { skill ->
            LocalSkillCard(
                skill = skill,
                actionsEnabled = actionsEnabled,
                onActivationChanged = { enabled -> onActivationChanged(skill, enabled) },
                onView = { onView(skill) },
                onEdit = { onEdit(skill) },
                onExport = { onExport(skill) },
                onRemove = { onRemove(skill) },
                onRun = onRun?.takeIf { skill.hasActiveRuntime }?.let { run -> { run(skill) } },
                onUseOnce = onUseOnce?.let { use -> { use(skill) } }
            )
        }
    }
}

@Composable
private fun LocalSkillLibraryHeader(skillCount: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.FolderCopy, contentDescription = null)
        Text(
            text = "Local skills ($skillCount)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LocalSkillLibraryEmptyMessage(
    allSkillsEmpty: Boolean,
    filteredSkillsEmpty: Boolean
) {
    val message = when {
        allSkillsEmpty -> "No local skills saved yet. Preview a valid SKILL.md above to add one."
        filteredSkillsEmpty -> "No local skills match this search."
        else -> return
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LocalSkillCard(
    skill: LocalSkillSummary,
    actionsEnabled: Boolean,
    onActivationChanged: (Boolean) -> Unit,
    onView: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onRemove: () -> Unit,
    onRun: (() -> Unit)? = null,
    onUseOnce: (() -> Unit)? = null
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = skill.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use in native/API chats",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (skill.enabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = skill.enabled,
                    enabled = actionsEnabled,
                    onCheckedChange = onActivationChanged
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(enabled = actionsEnabled, onClick = onView) {
                    Icon(Icons.Default.Visibility, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("View")
                }
                OutlinedButton(enabled = actionsEnabled, onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                onUseOnce?.let { use ->
                    OutlinedButton(
                        enabled = actionsEnabled,
                        onClick = use,
                        modifier = Modifier.testTag("skill_use_once_${skill.name}"),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Use once")
                    }
                }
                onRun?.let { run ->
                    Button(
                        enabled = actionsEnabled,
                        onClick = run,
                        modifier = Modifier.testTag("btn_run_skill_${skill.name}")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Run")
                    }
                }
                OutlinedButton(enabled = actionsEnabled, onClick = onExport) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Export")
                }
                TextButton(enabled = actionsEnabled, onClick = onRemove) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Remove")
                }
            }
        }
    }
}

@Composable
private fun ConfirmEnableLocalSkillDialog(
    skillName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable '$skillName'?") },
        text = {
            Text(
                "Its Markdown instructions can influence model responses in native/API chats. " +
                    "Aistee will not execute scripts or declared tools. Enable only skills you trust, " +
                    "and keep secrets outside SKILL.md."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ConfirmRemoveLocalSkillDialog(
    skillName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove '$skillName'?") },
        text = {
            Text(
                "This permanently deletes the local stored copy of SKILL.md. " +
                    "Export it first if this is your only copy."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

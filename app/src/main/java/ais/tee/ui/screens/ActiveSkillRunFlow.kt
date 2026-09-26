package ais.tee.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ais.tee.data.model.CapabilityDecision
import ais.tee.data.preferences.ActiveSkillsPreferencesStore
import ais.tee.data.skills.ActiveSkillBundle
import ais.tee.data.skills.ActiveSkillCard
import ais.tee.data.skills.ActiveSkillCardActivity
import ais.tee.data.skills.ActiveSkillDeclaration
import ais.tee.data.skills.ActiveSkillInvoker
import ais.tee.data.skills.ActiveSkillOutcome
import ais.tee.data.skills.ActiveSkillRunner
import ais.tee.data.skills.ActiveSkillToolAction
import ais.tee.data.skills.ActiveSkillToolPerformer
import ais.tee.data.skills.ActiveSkillToolValidation
import ais.tee.data.skills.ActiveSkillTrust
import ais.tee.data.skills.AgentSkillManifest
import ais.tee.data.skills.LocalSkillLibraryStore
import ais.tee.data.skills.activeSkillBundleDigest
import ais.tee.data.skills.activeSkillDeclaration
import ais.tee.data.skills.activeSkillUserInput
import ais.tee.data.skills.isActiveSkillTrusted
import ais.tee.data.skills.validateActiveSkillToolRequest
import java.security.MessageDigest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val prettyJson = Json { prettyPrint = true }

private sealed interface RunStep {
    data object Loading : RunStep
    data class Trust(val skill: LoadedSkill) : RunStep
    data class Input(val skill: LoadedSkill, val running: Boolean = false) : RunStep
    data class Consent(val skill: LoadedSkill, val pending: List<ActiveSkillToolValidation>, val done: List<String>) : RunStep
    data class Finished(val title: String, val body: String, val card: ActiveSkillCard? = null) : RunStep
}

private class LoadedSkill(
    val manifest: AgentSkillManifest,
    val declaration: ActiveSkillDeclaration,
    val bundle: ActiveSkillBundle,
)

/**
 * User-initiated "Run skill" (`docs/skills-runtime.md`): trust this exact bundle once, type an
 * input, run it in the sandbox, then confirm each requested native action one by one.
 */
@Composable
internal fun ActiveSkillRunFlow(
    skillName: String,
    store: LocalSkillLibraryStore,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { ActiveSkillsPreferencesStore(context) }
    val runner = remember(context) { ActiveSkillRunner(context) }
    var step by remember { mutableStateOf<RunStep>(RunStep.Loading) }

    LaunchedEffect(skillName) {
        val manifest = store.read(skillName)?.manifest
        val files = store.readBundleFiles(skillName)
        val declaration = manifest?.let { activeSkillDeclaration(it).declaration }
        step = if (manifest == null || files == null || declaration == null) {
            RunStep.Finished("Cannot run", "This skill has no valid runtime declaration.")
        } else {
            val skill = LoadedSkill(manifest, declaration, ActiveSkillBundle(skillName, activeSkillBundleDigest(files), files))
            val trusted = isActiveSkillTrusted(preferences.trustFor(skillName), skillName, skill.bundle.digest, declaration)
            if (trusted) RunStep.Input(skill) else RunStep.Trust(skill)
        }
    }

    fun finishWithOutcome(skill: LoadedSkill, outcome: ActiveSkillOutcome) {
        step = when (outcome) {
            is ActiveSkillOutcome.Result -> RunStep.Finished(
                title = "Result",
                body = prettyJson.encodeToString(JsonElement.serializer(), outcome.result),
                card = outcome.card?.copy(skillName = skill.manifest.name, bundleDigest = skill.bundle.digest),
            )
            is ActiveSkillOutcome.Error -> RunStep.Finished("Skill failed", outcome.message)
            is ActiveSkillOutcome.ToolRequests -> {
                // Only side-effect-free tools (ALLOW) run without a prompt; the rest wait for the user.
                val (automatic, confirm) = outcome.requests.partition { it.decision == CapabilityDecision.ALLOW }
                val done = automatic.map { request ->
                    when (val validation = validateActiveSkillToolRequest(request)) {
                        is ActiveSkillToolValidation.Valid -> ActiveSkillToolPerformer.perform(context, validation.action)
                        is ActiveSkillToolValidation.Invalid -> validation.reason
                    }
                }
                RunStep.Consent(skill, confirm.map(::validateActiveSkillToolRequest), done)
            }
        }
    }

    when (val current = step) {
        RunStep.Loading -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Loading skill") },
            text = { CircularProgressIndicator() },
            confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        )
        is RunStep.Trust -> ActiveSkillTrustDialog(
            skill = current.skill,
            onTrust = {
                preferences.grantTrust(
                    ActiveSkillTrust(skillName, current.skill.bundle.digest, current.skill.declaration.networkOrigins)
                )
                step = RunStep.Input(current.skill)
            },
            onDismiss = onClose,
        )
        is RunStep.Input -> ActiveSkillInputDialog(
            skillName = skillName,
            running = current.running,
            onRun = { text ->
                step = current.copy(running = true)
                scope.launch {
                    val outcome = runner.run(
                        invoker = ActiveSkillInvoker.USER,
                        manifest = current.skill.manifest,
                        bundle = current.skill.bundle,
                        input = activeSkillUserInput(text),
                        isIncognitoChat = false,
                        isQuickPrivacyOn = false,
                    )
                    finishWithOutcome(current.skill, outcome)
                }
            },
            onRevokeTrust = {
                preferences.revokeTrust(skillName)
                onClose()
            },
            onDismiss = { if (!current.running) onClose() },
        )
        is RunStep.Consent -> {
            val next = current.pending.firstOrNull()
            if (next == null) {
                LaunchedEffect(current) {
                    step = RunStep.Finished("Done", current.done.joinToString("\n").ifEmpty { "No actions were taken." })
                }
            } else {
                ActiveSkillConsentDialog(
                    skillName = skillName,
                    validation = next,
                    onApprove = { action ->
                        val message = ActiveSkillToolPerformer.perform(context, action)
                        step = current.copy(pending = current.pending.drop(1), done = current.done + message)
                    },
                    onDeny = {
                        val reason = (next as? ActiveSkillToolValidation.Invalid)?.reason ?: "Declined."
                        step = current.copy(pending = current.pending.drop(1), done = current.done + reason)
                    },
                )
            }
        }
        is RunStep.Finished -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(current.title) },
            text = {
                Text(
                    current.body,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("active_skill_result"),
                )
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
            dismissButton = current.card?.let { card ->
                {
                    TextButton(
                        onClick = { ActiveSkillCardActivity.open(context, card) },
                        modifier = Modifier.testTag("btn_active_skill_open_card"),
                    ) { Text("Open ${card.title.take(40)}") }
                }
            },
        )
    }
}

@Composable
private fun ActiveSkillTrustDialog(skill: LoadedSkill, onTrust: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trust ${skill.manifest.name}?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("active_skill_trust_dialog"),
            ) {
                Text(
                    "This lets the skill's scripts run in a sandbox without network, storage or cookies. " +
                        "Trust covers only this exact version; any edit asks again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                skill.bundle.files.toSortedMap().forEach { (path, bytes) ->
                    Text(
                        "$path · ${bytes.size} B · ${sha256Short(bytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                val tools = skill.declaration.tools.map { it.id }
                Text(
                    if (tools.isEmpty()) "Requests no native tools." else "May ask, each time, to: ${tools.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Bundle ${skill.bundle.digest.take(16)}…", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust, modifier = Modifier.testTag("btn_active_skill_trust")) { Text("Trust this version") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActiveSkillInputDialog(
    skillName: String,
    running: Boolean,
    onRun: (String) -> Unit,
    onRevokeTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run $skillName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The skill receives only this input, your locale and the current time.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Input (JSON or text)") },
                    enabled = !running,
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("active_skill_input"),
                )
                if (running) CircularProgressIndicator()
                TextButton(onClick = onRevokeTrust, enabled = !running) { Text("Revoke trust") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRun(input) }, enabled = !running, modifier = Modifier.testTag("btn_active_skill_run")) {
                Text("Run")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("Cancel") } },
    )
}

@Composable
private fun ActiveSkillConsentDialog(
    skillName: String,
    validation: ActiveSkillToolValidation,
    onApprove: (ActiveSkillToolAction) -> Unit,
    onDeny: () -> Unit,
) {
    val action = (validation as? ActiveSkillToolValidation.Valid)?.action
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text(if (action == null) "Request rejected" else "$skillName wants to…") },
        text = {
            Text(
                text = when (validation) {
                    is ActiveSkillToolValidation.Invalid -> validation.reason
                    is ActiveSkillToolValidation.Valid -> describeAction(validation.action)
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("active_skill_consent"),
            )
        },
        confirmButton = {
            if (action != null) {
                TextButton(onClick = { onApprove(action) }, modifier = Modifier.testTag("btn_active_skill_allow")) {
                    Text("Allow once")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDeny) { Text(if (action == null) "OK" else "Deny") } },
    )
}

internal fun describeAction(action: ActiveSkillToolAction): String = when (action) {
    ActiveSkillToolAction.CurrentDateTime -> "Read the current date and time."
    is ActiveSkillToolAction.CalendarEvent -> buildString {
        append("Open a new calendar event for review:\n")
        append("Title: ${action.title}\nStart: ${java.time.Instant.ofEpochMilli(action.startEpochMs)}")
        action.endEpochMs?.let { append("\nEnd: ${java.time.Instant.ofEpochMilli(it)}") }
        action.location?.let { append("\nLocation: $it") }
        action.description?.let { append("\nDescription: $it") }
    }
    is ActiveSkillToolAction.Email ->
        "Open an email draft (not sent):\nTo: ${action.to.joinToString().ifEmpty { "(none)" }}\nSubject: ${action.subject}\n\n${action.body}"
    is ActiveSkillToolAction.Notification ->
        "Schedule a reminder at ${java.time.Instant.ofEpochMilli(action.atEpochMs)}:\n${action.title}\n${action.text}"
    is ActiveSkillToolAction.Clipboard -> "Copy to the clipboard:\n${action.text}"
}

private fun sha256Short(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }

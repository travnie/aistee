package ais.tee.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.net.URLEncoder

/** Only official Claude Code links. Aistee does not access Claude authentication or run a proxy. */
internal object ClaudeCodeHandoff {
    const val SESSIONS_URL = "claude://code"
    const val WEB_URL = "https://claude.ai/code"
    private const val NEW_SESSION_URL = "https://claude.ai/code"
    const val MAX_TASK_CHARS = 2_000
    private val ownerPattern = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")
    private val repoPattern = Regex("^[A-Za-z0-9._-]{1,100}$")

    private fun validRepository(value: String): Boolean {
        val parts = value.split("/")
        return parts.size == 2 && ownerPattern.matches(parts[0]) &&
            repoPattern.matches(parts[1]) && parts[1] !in setOf(".", "..")
    }

    private fun validBranch(value: String): Boolean {
        if (value.isEmpty() || value.length > 150 || value == "@" ||
            value.startsWith("-") || value.startsWith("/") || value.endsWith("/")
        ) return false
        if (value.contains("..") || value.contains("@{") || value.contains("//")) return false
        if (value.any { it.isWhitespace() || it.code < 32 || it.code == 127 ||
                it.code == 92 || it in "~^:?*["
            }
        ) return false
        return value.split("/").all { part ->
            part.isNotEmpty() && !part.startsWith(".") && !part.endsWith(".") &&
                !part.endsWith(".lock")
        }
    }

    enum class Mode(val value: String) { PLAN("plan"), CODE("code") }

    fun canOpen(task: String, repository: String, branch: String): Boolean {
        val repo = repository.trim()
        val ref = branch.trim()
        return task.isNotBlank() && task.length <= MAX_TASK_CHARS &&
            (repo.isEmpty() || validRepository(repo)) &&
            (ref.isEmpty() || (repo.isNotEmpty() && validBranch(ref)))
    }

    fun newSessionUrl(task: String, repository: String = "", branch: String = "", mode: Mode = Mode.PLAN): String {
        require(canOpen(task, repository, branch)) { "Invalid Claude Code handoff fields" }
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        return buildString {
            append(NEW_SESSION_URL)
            append("?prompt=").append(encode(buildString {
                if (mode == Mode.PLAN) append("Plan the work before making changes.\n\n")
                if (branch.isNotBlank()) append("Preferred branch: ").append(branch.trim()).append("\n\n")
                append(task.trim())
            }))
            if (repository.isNotBlank()) {
                append("&repositories=").append(encode(repository.trim()))
            }
        }
    }
}

/** Optional subscription-account handoff inside the maintained Studio Tools hub. */
@Composable
internal fun ClaudeCodeHandoffPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var task by rememberSaveable { mutableStateOf("") }
    var repository by rememberSaveable { mutableStateOf("") }
    var branch by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(ClaudeCodeHandoff.Mode.PLAN) }
    var confirmation by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag("claude_code_handoff"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Claude Code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Open a task in the official Claude app or website, using your own Claude account. " +
                "No Aistee API key, token transfer or paired computer is needed.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = task,
            onValueChange = { if (it.length <= ClaudeCodeHandoff.MAX_TASK_CHARS) task = it },
            label = { Text("Task") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("claude_code_task"),
        )
        OutlinedTextField(
            value = repository,
            onValueChange = { repository = it; if (it.isBlank()) branch = "" },
            label = { Text("GitHub owner/repository (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("claude_code_repo"),
        )
        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it },
            label = { Text("Preferred branch (prompt hint)") },
            enabled = repository.isNotBlank(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("claude_code_branch"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClaudeCodeHandoff.Mode.entries.forEach { choice ->
                FilterChip(
                    selected = choice == mode,
                    onClick = { mode = choice },
                    label = { Text(if (choice == ClaudeCodeHandoff.Mode.PLAN) "Plan" else "Code") },
                )
            }
        }
        Text(
            "Claude controls access, usage limits and optional extra credits. " +
                "No local Aistee files or chats are attached.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Plan and branch are prompt hints. Confirm the actual mode and branch in Claude Code.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { confirmation = true },
            enabled = ClaudeCodeHandoff.canOpen(task, repository, branch),
            modifier = Modifier.testTag("claude_code_open"),
        ) { Text("Open in Claude Code") }
        TextButton(onClick = {
            notice = if (openExternalUri(context, Uri.parse(ClaudeCodeHandoff.SESSIONS_URL))) {
                null
            } else if (openExternalUri(context, Uri.parse(ClaudeCodeHandoff.WEB_URL))) {
                "Claude app unavailable. Opened the Claude Code website instead."
            } else {
                "No app or browser could open Claude Code."
            }
        }) { Text("Open Claude Code sessions") }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (confirmation) {
        AlertDialog(
            onDismissRequest = { confirmation = false },
            title = { Text("Send task to Claude?") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Review everything sent to the official Claude Code composer:")
                    Text("Mode: " + mode.value)
                    if (repository.isNotBlank()) Text("Repository: " + repository.trim())
                    if (branch.isNotBlank()) Text("Branch: " + branch.trim())
                    Text("Task:", fontWeight = FontWeight.Bold)
                    Text(task, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = false
                    val url = ClaudeCodeHandoff.newSessionUrl(task, repository, branch, mode)
                    notice = if (openExternalUri(context, Uri.parse(url))) null
                        else "No app or browser could open Claude Code."
                }) { Text("Open Claude") }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = false }) { Text("Cancel") }
            },
        )
    }
}

package ais.tee.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Requests Android's app-data reset, equivalent to Settings > Clear storage.
 *
 * This clears Aistee-owned app data and embedded session state. Provider-side/cloud data,
 * browser-owned sessions and files the user exported outside Aistee are intentionally outside
 * this boundary.
 */
@Composable
internal fun DeleteLocalDataSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var confirming by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        Text("Delete all local data", style = MaterialTheme.typography.titleMedium)
        Text(
            "Removes Aistee-owned data from this device: native chats, projects and library, " +
                "skills, API keys, drafts, embedded provider sessions and settings. " +
                "Provider accounts, browser-owned sessions and provider-side chats are not affected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { confirming = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag("btn_delete_local_data"),
        ) {
            Text("Delete all local data")
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete all local data?") },
            text = {
                Text(
                    "This cannot be undone for data Aistee owns. Export anything you want to keep first. " +
                        "Files already exported outside Aistee stay where you saved them."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        if (!requestClearLocalData(context)) {
                            Toast.makeText(context, "Could not delete local data.", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("btn_confirm_delete_local_data"),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/** Returns whether Android accepted the clear-data request. */
private fun requestClearLocalData(context: Context): Boolean =
    try {
        context.getSystemService(ActivityManager::class.java)?.clearApplicationUserData() == true
    } catch (_: SecurityException) {
        false
    }

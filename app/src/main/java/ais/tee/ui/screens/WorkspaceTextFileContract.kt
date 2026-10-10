package ais.tee.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/** Preserve the source filename, including JSON/YAML extensions and extensionless dotfiles. */
internal fun workspaceExportFileName(displayName: String): String =
    displayName.trim().ifEmpty { "untitled.md" }

/** Choose the actual output MIME per document instead of forcing every file to text/markdown. */
internal fun workspaceExportMimeType(displayName: String): String = when (
    workspaceExportFileName(displayName).substringAfterLast('.', "").lowercase()
) {
    "md", "markdown" -> "text/markdown"
    "json" -> "application/json"
    "json5" -> "application/json5"
    "yaml", "yml" -> "application/yaml"
    "xml" -> "application/xml"
    else -> "text/plain"
}

/** SAF create-document contract with MIME type selected for each individual file. */
internal class CreateWorkspaceTextDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(workspaceExportMimeType(input))
            .putExtra(Intent.EXTRA_TITLE, workspaceExportFileName(input))

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

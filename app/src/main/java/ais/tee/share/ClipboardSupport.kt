package ais.tee.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

internal fun plainTextClip(
    label: CharSequence,
    text: CharSequence,
    sensitive: Boolean,
): ClipData = ClipData.newPlainText(label, text).apply {
    if (sensitive) {
        description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_IS_SENSITIVE, true)
        }
    }
}

internal fun copyPlainTextToClipboard(
    context: Context,
    label: CharSequence,
    text: CharSequence,
    sensitive: Boolean,
): Boolean = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(plainTextClip(label, text, sensitive))
}.isSuccess

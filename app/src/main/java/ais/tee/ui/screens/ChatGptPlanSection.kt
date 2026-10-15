package ais.tee.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ais.tee.ui.viewmodel.ChatGptPlanViewModel
import ais.tee.ui.viewmodel.ChatGptPlanViewModel.Notice

// Undocumented settings deep link; unknown hash falls back to chatgpt.com home, notice text names the path.
private const val CHATGPT_SETTINGS_URL = "https://chatgpt.com/#settings/Security"

/** Sign in with ChatGPT accounts. ViewModel created on first show, never on chat startup. */
@Composable
internal fun ChatGptPlanSection(viewModel: ChatGptPlanViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launch: (String) -> Boolean = { url -> launchAuthorization(context, url) }
    val idle = !state.signingIn && state.signingOut == null
    // ViewModel outlives the dialog: a dismissed section must not leave a listener running.
    DisposableEffect(viewModel) {
        onDispose {
            if (context.findActivity()?.isChangingConfigurations != true) viewModel.cancelSignIn()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("section_chatgpt_plan"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("ChatGPT plan (preview)", fontWeight = FontWeight.Bold)
        Text(
            text = "Sign in with ChatGPT in your browser. The account is stored encrypted on this " +
                "device. Pick an account per chat from \"ChatGPT via\" in the chat header. Aistee " +
                "never switches between the plan and API-key billing for you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.accounts.forEach { account ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(account.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = when {
                            !account.connected -> "Signed out"
                            account.planUsageGranted -> "Connected"
                            else -> "Connected · plan usage not granted"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (account.connected) {
                    TextButton(
                        onClick = { viewModel.signOut(account.clientId) },
                        enabled = idle,
                        modifier = Modifier.testTag("btn_chatgpt_sign_out"),
                    ) { Text(if (state.signingOut == account.clientId) "Signing out…" else "Sign out") }
                } else {
                    TextButton(
                        onClick = { viewModel.forget(account.clientId) },
                        enabled = idle,
                        modifier = Modifier.testTag("btn_chatgpt_forget"),
                    ) { Text("Remove") }
                    TextButton(
                        onClick = { viewModel.signIn(account.clientId, launch) },
                        enabled = idle,
                        modifier = Modifier.testTag("btn_chatgpt_sign_in_again"),
                    ) { Text("Sign in") }
                }
            }
        }
        if (state.signingIn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Waiting for the browser…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = viewModel::cancelSignIn,
                    modifier = Modifier.testTag("btn_chatgpt_cancel_sign_in"),
                ) { Text("Cancel") }
            }
        } else {
            OutlinedButton(
                onClick = { viewModel.signIn(null, launch) },
                enabled = idle,
                modifier = Modifier.fillMaxWidth().testTag("btn_chatgpt_sign_in"),
            ) { Text(if (state.accounts.isEmpty()) "Sign in with ChatGPT" else "Add ChatGPT account") }
        }
        state.notice?.let { notice ->
            Text(
                text = noticeText(notice),
                style = MaterialTheme.typography.bodySmall,
                color = if (notice.isProblem()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag("text_chatgpt_notice"),
            )
            if (notice == Notice.SIGNED_OUT_UNCONFIRMED || notice == Notice.DISCARDED_UNCONFIRMED) {
                TextButton(
                    onClick = { openExternalUri(context, Uri.parse(CHATGPT_SETTINGS_URL)) },
                ) { Text("Open ChatGPT Settings") }
            }
        }
    }
}

private fun noticeText(notice: Notice): String = when (notice) {
    Notice.CONNECTED -> "ChatGPT account connected."
    Notice.CONNECTED_WITHOUT_PLAN ->
        "Signed in, but ChatGPT plan usage wasn't granted. Sign in again to allow it."
    Notice.CANCELLED -> "Sign-in cancelled."
    Notice.TIMED_OUT -> "Sign-in timed out. Try again."
    Notice.FAILED -> "Sign-in failed. Try again."
    Notice.ACCOUNT_LIMIT -> "Account limit reached. Sign out of an account you no longer use, then remove it."
    Notice.BROWSER_UNAVAILABLE -> "No browser available to sign in."
    Notice.SIGNED_OUT -> "Signed out."
    Notice.SIGNED_OUT_UNCONFIRMED ->
        "Signed out on this device, but OpenAI didn't confirm it. You can disconnect Aistee in ChatGPT Settings → Security and login → Sign in with ChatGPT."
    Notice.STORAGE_ERROR -> "Couldn't access secure storage on this device."
    Notice.DISCARDED_UNCONFIRMED ->
        "Sign-in wasn't kept, and OpenAI didn't confirm ending it. You can disconnect Aistee in ChatGPT Settings → Security and login → Sign in with ChatGPT."
}

private fun Notice.isProblem() = this in setOf(
    Notice.FAILED, Notice.TIMED_OUT, Notice.ACCOUNT_LIMIT, Notice.BROWSER_UNAVAILABLE,
    Notice.SIGNED_OUT_UNCONFIRMED, Notice.STORAGE_ERROR, Notice.CONNECTED_WITHOUT_PLAN,
    Notice.DISCARDED_UNCONFIRMED,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Custom Tab, falling back to any browser; never a WebView. */
private fun launchAuthorization(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    return try {
        CustomTabsIntent.Builder()
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
            .launchUrl(context, uri)
        true
    } catch (_: ActivityNotFoundException) {
        openExternalUri(context, uri)
    }
}

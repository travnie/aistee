package ais.tee.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ais.tee.data.model.WebAiService
import ais.tee.web.WebUserTurnJumpResult
import ais.tee.web.WebUserTurnTimeline
import ais.tee.web.WebUserTurnTimelineSession
import kotlinx.coroutines.delay

@Composable
internal fun WebUserTurnTimelineDialog(
    webView: WebView,
    service: WebAiService,
    isCurrentWebView: () -> Boolean,
    onDismiss: () -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    val latestIsCurrent by rememberUpdatedState(isCurrentWebView)
    val session = remember(webView, service) {
        WebUserTurnTimelineSession(webView, service) { latestIsCurrent() }
    }
    var snapshot by remember(session) { mutableStateOf<WebUserTurnTimeline?>(null) }
    var loading by remember(session) { mutableStateOf(true) }
    var jumping by remember(session) { mutableStateOf(false) }
    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(session) {
        session.read { snapshot = it; loading = false }
        delay(5_000)
        loading = false
    }
    LaunchedEffect(session) {
        while (true) {
            delay(300)
            session.checkCurrent { if (!it) onDismiss() }
        }
    }

    WebUserTurnTimelinePicker(
        loading = loading,
        snapshot = snapshot,
        jumping = jumping,
        onDismiss = onDismiss,
        onSelect = { index ->
            jumping = true
            session.jump(index) { result ->
                if (result != WebUserTurnJumpResult.MOVED) {
                    onShowSnackbar("This page changed. Reopen the timeline to refresh its targets.")
                }
                onDismiss()
            }
        }
    )
}

@Composable
internal fun WebUserTurnTimelinePicker(
    loading: Boolean,
    snapshot: WebUserTurnTimeline?,
    jumping: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conversation timeline (beta)") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("web_timeline_list")) {
                item { Text("Loaded messages only. Message text stays on the provider page.") }
                when {
                    loading -> item { Text("Checking this page…") }
                    snapshot == null -> item { Text("Timeline unavailable. Reopen it from a supported conversation.") }
                    snapshot.count == 0 -> item { Text("No user messages detected on this page.") }
                    else -> {
                        item {
                            Text(
                                if (snapshot.limited) "First ${snapshot.count} loaded user messages."
                                else "${snapshot.count} loaded user messages.",
                                modifier = Modifier.testTag("web_timeline_count")
                            )
                        }
                        items(snapshot.count, key = { it }) { index ->
                            TextButton(
                                enabled = !jumping,
                                onClick = { onSelect(index) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("web_timeline_turn_$index")
                            ) { Text("User message ${index + 1}") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        modifier = Modifier.testTag("web_timeline_dialog")
    )
}

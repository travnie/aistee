package ais.tee.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ais.tee.R

/** One foreground search; never stores queries or extracts page content. Close before destroying its WebView. */
internal class WebFindInPageSession(val webView: WebView) {
    var query by mutableStateOf("")
        private set
    var matchCount by mutableIntStateOf(0)
        private set
    var activeMatch by mutableIntStateOf(0)
        private set
    var isCounting by mutableStateOf(false)
        private set
    private var closed = false

    val canNavigate: Boolean get() = !closed && !isCounting && matchCount > 0

    init {
        webView.setFindListener { ordinal, count, done ->
            if (!closed && query.isNotBlank()) {
                isCounting = !done
                matchCount = if (done) count else 0
                activeMatch = if (done && count > 0) ordinal + 1 else 0
            }
        }
    }

    fun updateQuery(value: String) {
        if (closed || query == value) return
        query = value
        matchCount = 0
        activeMatch = 0
        isCounting = value.isNotBlank()
        // WebView cancels earlier searches when a new findAllAsync request arrives.
        webView.findAllAsync(if (isCounting) value else "")
        if (!isCounting) webView.clearMatches()
    }

    fun move(forward: Boolean) {
        if (canNavigate) webView.findNext(forward)
    }

    fun close(rendererGone: Boolean = false) {
        if (closed) return
        closed = true
        // A terminated renderer must only be detached/destroyed by its owner.
        if (!rendererGone) {
            webView.setFindListener(null)
            webView.findAllAsync("")
            webView.clearMatches()
        }
        query = ""
        matchCount = 0
        activeMatch = 0
        isCounting = false
    }
}

@Composable
internal fun WebFindInPageBar(session: WebFindInPageSession, onClose: () -> Unit) {
    val focusRequester = remember(session) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(session) { focusRequester.requestFocus() }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = session.query,
                onValueChange = session::updateQuery,
                label = { Text(stringResource(R.string.web_find_title)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    session.move(forward = true)
                }),
                trailingIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("btn_web_find_close")) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.web_find_close))
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("web_find_query"),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        session.query.isBlank() -> stringResource(R.string.web_find_loaded_text)
                        session.isCounting -> stringResource(R.string.web_find_searching)
                        session.matchCount == 0 -> stringResource(R.string.web_find_no_matches)
                        else -> stringResource(R.string.web_find_result, session.activeMatch, session.matchCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("web_find_result"),
                )
                IconButton(
                    onClick = { keyboard?.hide(); session.move(forward = false) },
                    enabled = session.canNavigate,
                    modifier = Modifier.testTag("btn_web_find_previous"),
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.web_find_previous))
                }
                IconButton(
                    onClick = { keyboard?.hide(); session.move(forward = true) },
                    enabled = session.canNavigate,
                    modifier = Modifier.testTag("btn_web_find_next"),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.web_find_next))
                }
            }
        }
    }
}

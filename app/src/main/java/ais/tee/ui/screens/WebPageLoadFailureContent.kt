package ais.tee.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ais.tee.R
import ais.tee.data.model.WebAiService

@Composable
internal fun WebPageLoadFailureContent(
    service: WebAiService,
    failure: WebPageLoadFailure,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onOpenHome: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (failure == WebPageLoadFailure.SECURE_CONNECTION) Icons.Outlined.Lock else Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.web_load_failed_title, service.shortName),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(when (failure) {
                        WebPageLoadFailure.CONNECTION -> R.string.web_load_failed_connection
                        WebPageLoadFailure.TIMEOUT -> R.string.web_load_failed_timeout
                        WebPageLoadFailure.SECURE_CONNECTION -> R.string.web_load_failed_secure
                        WebPageLoadFailure.OTHER -> R.string.web_load_failed_other
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (canRetry) {
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.web_load_retry))
                    }
                }
                OutlinedButton(onClick = onOpenHome, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.web_load_home, service.shortName))
                }
            }
        }
    }
}

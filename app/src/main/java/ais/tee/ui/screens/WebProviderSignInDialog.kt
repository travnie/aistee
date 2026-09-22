package ais.tee.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ais.tee.R
import ais.tee.data.model.ProviderIdentityMethod
import ais.tee.data.model.WebAiService
import ais.tee.data.model.onboardingCapabilities
import ais.tee.data.model.resolveOnboardingIdentityMethod

@Composable
internal fun WebProviderSignInDialog(
    service: WebAiService,
    preferredMethod: ProviderIdentityMethod?,
    canOpenInApp: Boolean,
    onChooseMethod: (ProviderIdentityMethod) -> Unit,
    onOpenInApp: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit,
) {
    val methods = service.onboardingCapabilities().preferredIdentityMethods
    val selected = service.resolveOnboardingIdentityMethod(preferredMethod)
    AlertDialog(
        modifier = Modifier.testTag("web_sign_in_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_sign_in_title, service.shortName)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.web_sign_in_preference))
                methods.forEach { method ->
                    FilterChip(
                        selected = method == selected,
                        onClick = { onChooseMethod(method) },
                        label = { Text(identityMethodLabel(method)) },
                        modifier = Modifier.testTag("web_sign_in_method_${method.name}"),
                    )
                }
                if (selected != null) {
                    Text(stringResource(R.string.web_sign_in_instruction, identityMethodLabel(selected)))
                }
                if (selected == ProviderIdentityMethod.GOOGLE) {
                    Text(stringResource(R.string.web_sign_in_google_hint))
                }
                Text(stringResource(R.string.web_sign_in_app_hint))
                Button(
                    onClick = onOpenInApp,
                    enabled = canOpenInApp,
                    modifier = Modifier.fillMaxWidth().testTag("web_sign_in_app"),
                ) { Text(stringResource(R.string.web_sign_in_app)) }
                Text(stringResource(R.string.web_sign_in_browser_hint))
                OutlinedButton(
                    onClick = onOpenInBrowser,
                    modifier = Modifier.fillMaxWidth().testTag("web_sign_in_browser"),
                ) { Text(stringResource(R.string.web_sign_in_browser)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("web_sign_in_close")) {
                Text(stringResource(R.string.web_sign_in_close))
            }
        },
    )
}

private fun identityMethodLabel(method: ProviderIdentityMethod): String = when (method) {
    ProviderIdentityMethod.GOOGLE -> "Google"
    ProviderIdentityMethod.GITHUB -> "GitHub"
    ProviderIdentityMethod.MICROSOFT -> "Microsoft"
}

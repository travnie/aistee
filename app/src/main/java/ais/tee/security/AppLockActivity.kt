package ais.tee.security

import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ais.tee.ui.theme.AisteeTheme

/** Opaque lock screen shown above the task until the device check passes. */
class AppLockActivity : ComponentActivity() {
    private val errorMessage = mutableStateOf<String?>(null)
    private var promptInFlight = false
    private var autoPromptDone = false
    private var cancellation: CancellationSignal? = null

    private val confirmCredential =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            promptInFlight = false
            if (result.resultCode == RESULT_OK) unlock() else errorMessage.value = "Not unlocked."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoPromptDone = savedInstanceState?.getBoolean(KEY_AUTO_PROMPT_DONE) == true
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    moveTaskToBack(true)
                }
            },
        )
        setContent {
            AisteeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null)
                        Text("Aistee is locked", style = MaterialTheme.typography.titleLarge)
                        errorMessage.value?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = ::authenticate,
                            modifier = Modifier.testTag("btn_app_unlock"),
                        ) {
                            Text("Unlock")
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!AppLock.isDeviceSecure(this)) {
            // Lock screen security was removed; there is nothing to check against.
            unlock()
            return
        }
        if (!AppLock.isLockRequired(this)) {
            // Another lock screen instance already passed the check.
            finish()
            return
        }
        if (!autoPromptDone) {
            autoPromptDone = true
            authenticate()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTO_PROMPT_DONE, autoPromptDone)
    }

    override fun onDestroy() {
        cancellation?.cancel()
        super.onDestroy()
    }

    private fun authenticate() {
        if (promptInFlight) return
        errorMessage.value = null
        promptInFlight = true
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> showBiometricPrompt(
                BiometricPrompt.Builder(this)
                    .setTitle(PROMPT_TITLE)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> showBiometricPrompt(
                @Suppress("DEPRECATION")
                BiometricPrompt.Builder(this)
                    .setTitle(PROMPT_TITLE)
                    .setDeviceCredentialAllowed(true)
            )
            else -> showLegacyCredentialConfirmation()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun showBiometricPrompt(builder: BiometricPrompt.Builder) {
        val signal = CancellationSignal().also { cancellation = it }
        builder.build().authenticate(
            signal,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptInFlight = false
                    unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptInFlight = false
                    errorMessage.value = errString.toString()
                }
            },
        )
    }

    private fun showLegacyCredentialConfirmation() {
        val intent = getSystemService(KeyguardManager::class.java)
            ?.createConfirmDeviceCredentialIntent(PROMPT_TITLE, null)
        if (intent == null) {
            promptInFlight = false
            unlock()
        } else {
            confirmCredential.launch(intent)
        }
    }

    private fun unlock() {
        AppLock.markUnlocked()
        finish()
    }

    private companion object {
        const val PROMPT_TITLE = "Unlock Aistee"
        const val KEY_AUTO_PROMPT_DONE = "auto_prompt_done"
    }
}

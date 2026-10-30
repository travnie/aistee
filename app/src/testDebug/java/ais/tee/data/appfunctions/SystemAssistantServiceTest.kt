package ais.tee.data.appfunctions

import android.content.Context
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import ais.tee.data.model.BuiltInBenchTool
import ais.tee.data.preferences.BuiltInBenchPreferencesStore
import ais.tee.data.tokenizer.LocalTokenCounter
import ais.tee.security.AppLockStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SystemAssistantServiceTest {
    private lateinit var context: Context
    private lateinit var consent: SystemAssistantConsentStore
    private lateinit var service: AisteeAppFunctionService

    @Before
    fun reset() {
        context = RuntimeEnvironment.getApplication()
        consent = SystemAssistantConsentStore(context)
        consent.setEnabled(false)
        AppLockStore.get(context).setEnabled(false)
        BuiltInBenchPreferencesStore(context).saveEnabledTools(emptySet())
        service = Robolectric.buildService(AisteeAppFunctionService::class.java).get()
    }

    @Test
    fun callerAssertionNeverEnablesConsentOrDocbench() {
        assertFalse(SystemAssistantConsentStore(context).isEnabled())
        assertThrows(AppFunctionDeniedException::class.java) {
            runBlocking { service.countTokens("hello", userRequested = true) }
        }
        consent.setEnabled(true)
        assertThrows(AppFunctionDeniedException::class.java) {
            runBlocking { service.countTokens("hello", userRequested = true) }
        }
    }

    @Test
    fun explicitRequestCountsLocallyAndRevocationBlocksNextCall(): Unit = runBlocking {
        allow()
        val text = "Hello from Aistee 👋"
        assertEquals(
            AssistantTokenCount(LocalTokenCounter.count(text), LocalTokenCounter.ENCODING_LABEL),
            service.countTokens(text, userRequested = true),
        )
        SystemAssistantConsentStore(context).setEnabled(false)
        assertThrows(AppFunctionDeniedException::class.java) {
            runBlocking { service.countTokens(text, userRequested = true) }
        }
    }

    @Test
    fun omittedUserRequestAndAppLockDenyEvenWithConsent() {
        allow()
        assertThrows(AppFunctionDeniedException::class.java) {
            runBlocking { service.countTokens("hello") }
        }
        AppLockStore.get(context).setEnabled(true)
        assertThrows(AppFunctionDeniedException::class.java) {
            runBlocking { service.countTokens("hello", userRequested = true) }
        }
    }

    @Test
    fun disablingDocbenchRevokesPreviouslyAllowedAccess(): Unit = runBlocking {
        allow()
        service.countTokens("hello", userRequested = true)
        BuiltInBenchPreferencesStore(context).saveEnabledTools(emptySet())
        assertThrows(AppFunctionDeniedException::class.java) {
            runBlocking { service.countTokens("hello", userRequested = true) }
        }
    }

    @Test
    fun authorizedInvalidInputUsesTypedErrorWithoutEchoingContent() {
        allow()
        for (text in listOf(" ", "x".repeat(20_001))) {
            val error = assertThrows(AppFunctionInvalidArgumentException::class.java) {
                runBlocking { service.countTokens(text, userRequested = true) }
            }
            assertEquals("Supply 1 to 20,000 nonblank characters.", error.message)
        }
    }

    private fun allow() {
        consent.setEnabled(true)
        BuiltInBenchPreferencesStore(context).saveEnabledTools(setOf(BuiltInBenchTool.DOCBENCH_DOCUMENT))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SystemAssistantUnsupportedDeviceTest {
    @Test
    fun olderAndroidCannotEnableAccess() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val consent = SystemAssistantConsentStore(context)
        assertFalse(SystemAssistantAccess.isSupported(context))
        consent.setEnabled(true)
        assertFalse(SystemAssistantAccess.setEnabled(context, true))
        assertFalse(consent.isEnabled())
    }
}

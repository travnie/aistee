package ais.tee

import android.content.Context
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionDisabledException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionName
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.ExperimentalAppFunctionsApi
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import ais.tee.data.appfunctions.SystemAssistantAccess
import ais.tee.data.appfunctions.SystemAssistantConsentStore
import ais.tee.data.engine.MAX_INLINE_TOOL_TEXT_CHARS
import ais.tee.data.model.BuiltInBenchTool
import ais.tee.data.preferences.BuiltInBenchPreferencesStore
import ais.tee.data.tokenizer.LocalTokenCounter
import ais.tee.security.AppLockStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real OS dispatch; no framework service emulation or test-only service hooks. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36)
@OptIn(ExperimentalAppFunctionsApi::class)
class SystemAssistantInvocationTest {
    private lateinit var context: Context
    private lateinit var manager: AppFunctionManager
    private lateinit var metadata: AppFunctionMetadata
    private lateinit var consent: SystemAssistantConsentStore

    @Before
    fun prepare(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext<AisteeApplication>()
        consent = SystemAssistantConsentStore
        consent.setEnabled(false)
        AppLockStore.get(context).setEnabled(false)
        BuiltInBenchPreferencesStore(context).saveEnabledTools(emptySet())
        manager = requireNotNull(AppFunctionManager.getInstance(context))
        val spec = AppFunctionSearchSpec(
            functionNames = setOf(AppFunctionName(context.packageName, SystemAssistantAccess.FUNCTION_ID)),
        )
        metadata = withTimeout(30_000) {
            var functions = manager.searchAppFunctions(spec)
            while (functions.isEmpty()) {
                delay(500)
                functions = manager.searchAppFunctions(spec)
            }
            functions.single()
        }
        assertEquals(AppFunctionMetadata.ACCESS_LEVEL_SELF, metadata.accessLevel)
        assertTrue(metadata.isCompatEnforcementEnabled)
        // Enable self dispatch only; each test still exercises Aistee's separate consent gates.
        manager.setAppFunctionEnabled(SystemAssistantAccess.FUNCTION_ID, AppFunctionManager.APP_FUNCTION_STATE_ENABLED)
    }

    @After
    fun restore(): Unit = runBlocking {
        if (!::context.isInitialized) return@runBlocking
        consent.setEnabled(false)
        AppLockStore.get(context).setEnabled(false)
        BuiltInBenchPreferencesStore(context).saveEnabledTools(emptySet())
        if (::metadata.isInitialized) {
            withTimeout(10_000) {
                manager.setAppFunctionEnabled(
                    SystemAssistantAccess.FUNCTION_ID, AppFunctionManager.APP_FUNCTION_STATE_DISABLED,
                )
            }
        }
    }

    @Test
    fun callerAssertionNeverEnablesConsentOrDocbench(): Unit = runBlocking {
        assertFalse(consent.isEnabled())
        assertTrue(rejection("hello") is AppFunctionDeniedException)
        consent.setEnabled(true)
        assertTrue(rejection("hello") is AppFunctionDeniedException)
    }

    @Test
    fun explicitRequestCountsLocallyAndRevocationBlocksNextCall(): Unit = runBlocking {
        allow()
        val text = "Hello from Aistee 👋"
        val data = success(text)
        assertEquals(LocalTokenCounter.count(text), data.getInt("tokens"))
        assertEquals(LocalTokenCounter.ENCODING_LABEL, data.getString("encoding"))
        consent.setEnabled(false)
        assertTrue(rejection(text) is AppFunctionDeniedException)
    }

    @Test
    fun omittedUserRequestAndAppLockDenyEvenWithConsent(): Unit = runBlocking {
        allow()
        assertTrue(rejection("hello", userRequested = null) is AppFunctionDeniedException)
        AppLockStore.get(context).setEnabled(true)
        assertTrue(rejection("hello") is AppFunctionDeniedException)
    }

    @Test
    fun disablingDocbenchRevokesPreviouslyAllowedAccess(): Unit = runBlocking {
        allow()
        success("hello")
        BuiltInBenchPreferencesStore(context).saveEnabledTools(emptySet())
        assertTrue(rejection("hello") is AppFunctionDeniedException)
    }

    @Test
    fun invalidInputReturnsTypedErrorsWithoutEchoingContent(): Unit = runBlocking {
        allow()
        for (text in listOf(" ", "x".repeat(MAX_INLINE_TOOL_TEXT_CHARS + 1))) {
            val error = rejection(text)
            assertTrue(error is AppFunctionInvalidArgumentException)
            assertEquals("Supply 1 to 20,000 nonblank characters.", error.errorMessage)
        }
    }

    @Test
    fun privacyControlEnablesAndDisablesOsInvocation(): Unit = runBlocking {
        BuiltInBenchPreferencesStore(context).saveEnabledTools(setOf(BuiltInBenchTool.DOCBENCH_DOCUMENT))
        assertTrue(SystemAssistantAccess.setEnabled(context, true))
        success("hello")
        assertTrue(SystemAssistantAccess.setEnabled(context, false))
        assertFalse(consent.isEnabled())
        assertTrue(rejection("hello") is AppFunctionDisabledException)
    }

    private fun allow() {
        consent.setEnabled(true)
        BuiltInBenchPreferencesStore(context).saveEnabledTools(setOf(BuiltInBenchTool.DOCBENCH_DOCUMENT))
    }

    private suspend fun invoke(text: String, userRequested: Boolean?): ExecuteAppFunctionResponse {
        val parameters = AppFunctionData.Builder(metadata.parameters, metadata.packageMetadata.components)
            .setString("text", text)
        if (userRequested != null) parameters.setBoolean("userRequested", userRequested)
        return withTimeout(10_000) {
            manager.executeAppFunction(
                ExecuteAppFunctionRequest(context.packageName, SystemAssistantAccess.FUNCTION_ID, parameters.build())
            )
        }
    }

    private suspend fun success(text: String): AppFunctionData {
        val response = invoke(text, true)
        check(response is ExecuteAppFunctionResponse.Success) { "Expected a local token count" }
        return requireNotNull(response.returnValue.getAppFunctionData(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE))
    }

    private suspend fun rejection(text: String, userRequested: Boolean? = true): AppFunctionException =
        try {
            val response = invoke(text, userRequested)
            check(response is ExecuteAppFunctionResponse.Error) { "Expected a denied or invalid request" }
            response.error
        } catch (error: AppFunctionException) {
            error
        }
}

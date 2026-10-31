package ais.tee

import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Xml
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import ais.tee.data.appfunctions.AisteeAppFunctionService
import ais.tee.data.appfunctions.SystemAssistantAccess
import ais.tee.data.appfunctions.SystemAssistantConsentStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36)
class SystemAssistantRegistrationTest {
    @Test
    fun generatedServiceIsProtectedWithDefaultDenial() {
        val context = ApplicationProvider.getApplicationContext<AisteeApplication>()
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, AisteeAppFunctionService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertTrue(info.exported)
        assertEquals("android.permission.BIND_APP_FUNCTION_SERVICE", info.permission)
        assertFalse(SystemAssistantConsentStore.isEnabled())

        var functionIdFound = false
        val generatedFunctionIds = linkedSetOf<String>()
        var defaultDenied = false
        context.assets.open("aistee_app_functions.xml").use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "id" -> {
                        val id = parser.nextText()
                        if ('#' in id) generatedFunctionIds += id
                        functionIdFound = id == SystemAssistantAccess.functionId || functionIdFound
                    }
                    "enabledByDefault" -> defaultDenied = parser.nextText().trim() == "false" || defaultDenied
                }
            }
        }
        assertTrue("Expected ${SystemAssistantAccess.functionId}; generated IDs: $generatedFunctionIds", functionIdFound)
        assertTrue("Generated metadata must disable the function by default", defaultDenied)
    }

    @Test
    @SdkSuppress(minSdkVersion = 36, maxSdkVersion = 36)
    fun android16CannotEnableTheFullSchemaPreview(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<AisteeApplication>()
        assertFalse(SystemAssistantAccess.isSupported(context))
        assertFalse(SystemAssistantAccess.setEnabled(context, true))
        assertFalse(SystemAssistantConsentStore.isEnabled())
    }

    @Test
    @SdkSuppress(minSdkVersion = 37)
    fun fullSchemaIsIndexedWithDefaultDenial(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<AisteeApplication>()
        val manager = AppFunctionManager.getInstance(context)
        assertNotNull("Android 17 smoke device must support AppFunctions", manager)
        val name = AppFunctionName(context.packageName, SystemAssistantAccess.functionId)
        // Fresh installs may need time for the OS indexer; no assumed shell syntax.
        val states = withTimeout(30_000) {
            var current = manager!!.getAppFunctionStates(listOf(name))
            while (current.isEmpty()) {
                delay(500)
                current = manager.getAppFunctionStates(listOf(name))
            }
            current
        }
        assertFalse("Fresh installation must not expose an enabled function", states.single().isEnabled)
    }
}

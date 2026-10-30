package ais.tee.data.appfunctions

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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

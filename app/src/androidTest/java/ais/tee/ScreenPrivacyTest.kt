package ais.tee

import android.content.Context
import android.view.WindowManager
import ais.tee.security.ScreenPrivacyStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenPrivacyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ScreenPrivacyStore.get(context)

    @After
    fun resetPreference() {
        store.setEnabled(false)
    }

    @Test
    fun mainActivityAppliesScreenPrivacyAtLaunchAndFollowsChanges() {
        store.setEnabled(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertTrue(activity.hasSecureFlag()) }

            scenario.onActivity { store.setEnabled(false) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity -> assertFalse(activity.hasSecureFlag()) }

            scenario.onActivity { store.setEnabled(true) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity -> assertTrue(activity.hasSecureFlag()) }
        }
    }

    @Test
    fun disabledScreenPrivacyLeavesWindowCapturableAndPreferencePersists() {
        store.setEnabled(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.hasSecureFlag()) }
        }
        store.setEnabled(true)
        val stored = context
            .getSharedPreferences(ScreenPrivacyStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(ScreenPrivacyStore.KEY_ENABLED, false)
        assertEquals(true, stored)
    }

    private fun MainActivity.hasSecureFlag(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
}

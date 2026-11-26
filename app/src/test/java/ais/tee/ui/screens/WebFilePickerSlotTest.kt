package ais.tee.ui.screens

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.os.Looper
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebFilePickerSlotTest {
    private class Registry : ActivityResultRegistry() {
        val launches = mutableListOf<Int>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            launches.add(requestCode)
        }
    }

    @Test
    fun lateResultCannotReachReplacementProvider() {
        val registry = Registry()
        val slot = WebFilePickerSlot<String>(registry)
        val delivered = mutableListOf<String>()
        slot.launch(Intent(), "ChatGPT") { owner, _ -> delivered.add(owner) }
        assertNull(slot.cancelIf { it == "Claude" })
        assertEquals("ChatGPT", slot.cancelIf { true })
        slot.launch(Intent(), "Claude") { owner, _ -> delivered.add(owner) }
        assertNotEquals(registry.launches[0], registry.launches[1])
        registry.dispatchResult(registry.launches[0], Activity.RESULT_OK, Intent().setData(Uri.parse("content://test/old")))
        assertTrue(delivered.isEmpty())
        assertEquals(1, slot.outstandingKeys().size)
        registry.dispatchResult(registry.launches[1], Activity.RESULT_OK, Intent())
        assertEquals(listOf("Claude"), delivered)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(slot.outstandingKeys().isEmpty())
        assertNoPendingResults(registry)
    }

    @Test
    fun recreatedRegistryAllowsNewLaunchEvenIfOldResultNeverArrives() {
        val firstRegistry = Registry()
        val first = WebFilePickerSlot<String>(firstRegistry)
        first.launch(Intent(), "old") { _, _ -> error("Old owner must not receive a result") }
        val oldCode = firstRegistry.launches.single()
        val state = Bundle()
        firstRegistry.onSaveInstanceState(state)
        val restoredKeys = first.outstandingKeys()
        first.close()

        val restoredRegistry = Registry()
        restoredRegistry.onRestoreInstanceState(state)
        val restored = WebFilePickerSlot<String>(restoredRegistry, restoredKeys)
        val delivered = mutableListOf<String>()
        restored.launch(Intent(), "new") { owner, _ -> delivered.add(owner) }
        val newCode = restoredRegistry.launches.single()
        assertNotEquals(oldCode, newCode)
        restoredRegistry.dispatchResult(oldCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://test/old")))
        assertTrue(delivered.isEmpty())
        assertEquals(1, restored.outstandingKeys().size)
        restoredRegistry.dispatchResult(newCode, Activity.RESULT_CANCELED, null)
        assertEquals(listOf("new"), delivered)
        shadowOf(Looper.getMainLooper()).idle()
        restored.launch(Intent(), "next") { owner, _ -> delivered.add(owner) }
        restoredRegistry.dispatchResult(restoredRegistry.launches.last(), Activity.RESULT_OK, Intent())
        assertEquals(listOf("new", "next"), delivered)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(restored.outstandingKeys().isEmpty())
        assertNoPendingResults(restoredRegistry)
    }

    private fun assertNoPendingResults(registry: Registry) {
        val state = Bundle()
        registry.onSaveInstanceState(state)
        // AndroidX stores undelivered ActivityResult parcelables in a nested Bundle.
        assertTrue(state.keySet().mapNotNull(state::getBundle).all { it.isEmpty })
    }
}

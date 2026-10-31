package ais.tee.data.appfunctions

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAssistantConsentStoreTest {
    private val consent = SystemAssistantConsentStore

    @After
    fun reset() = consent.setEnabled(false)

    @Test
    fun beginningAnUpdateRevokesBeforeAnyPlatformWork() {
        consent.setEnabled(true)
        consent.beginUpdate()
        assertFalse(consent.isEnabled())
    }

    @Test
    fun staleEnableCannotUndoANewerOptOut() {
        val enabling = consent.beginUpdate()
        consent.beginUpdate()
        assertFalse(consent.completeUpdate(enabling, true))
        assertFalse(consent.isEnabled())
    }

    @Test
    fun revokeAndReenableInvalidateAnInFlightConsentSnapshot() {
        consent.setEnabled(true)
        val initial = consent.snapshot()
        consent.setEnabled(false)
        consent.setEnabled(true)
        assertNotEquals(initial, consent.snapshot())
    }

    @Test
    fun onlyTheLatestExplicitUpdateCanGrantAccess() {
        val stale = consent.beginUpdate()
        val current = consent.beginUpdate()
        assertTrue(consent.completeUpdate(current, true))
        assertFalse(consent.completeUpdate(stale, false))
        assertTrue(consent.isEnabled())
    }
}

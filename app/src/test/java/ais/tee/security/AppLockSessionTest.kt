package ais.tee.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSessionTest {
    @Test
    fun freshSessionStartsLocked() {
        val session = AppLockSession(graceMillis = 1_000)
        session.onActivityStarted(nowMillis = 0)
        assertFalse(session.unlocked)
    }

    @Test
    fun shortTripAwayKeepsUnlock() {
        val session = unlockedInForeground()
        session.onActivityStopped(nowMillis = 100)
        session.onActivityStarted(nowMillis = 1_099)
        assertTrue(session.unlocked)
    }

    @Test
    fun graceExpiryLocksAgain() {
        val session = unlockedInForeground()
        session.onActivityStopped(nowMillis = 100)
        session.onActivityStarted(nowMillis = 1_100)
        assertFalse(session.unlocked)
    }

    @Test
    fun switchingBetweenOwnActivitiesIsNotBackgrounding() {
        val session = unlockedInForeground()
        // The lock screen or another Aistee activity starts before the previous one stops.
        session.onActivityStarted(nowMillis = 100)
        session.onActivityStopped(nowMillis = 100)
        session.onActivityStarted(nowMillis = 60_000)
        session.onActivityStopped(nowMillis = 60_000)
        assertTrue(session.unlocked)
    }

    @Test
    fun longWaitOnLockScreenDoesNotRelockAfterSuccess() {
        val session = AppLockSession(graceMillis = 1_000)
        session.onActivityStarted(nowMillis = 0) // main activity
        session.onActivityStarted(nowMillis = 1) // lock screen
        session.onActivityStopped(nowMillis = 2) // main hidden behind lock screen
        session.markUnlocked()
        session.onActivityStarted(nowMillis = 90_000) // main returns
        session.onActivityStopped(nowMillis = 90_001) // lock screen finishes
        assertTrue(session.unlocked)
    }

    private fun unlockedInForeground(): AppLockSession =
        AppLockSession(graceMillis = 1_000).apply {
            onActivityStarted(nowMillis = 0)
            markUnlocked()
        }
}

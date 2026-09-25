package ais.tee.security

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground/background bookkeeping for the app lock. Pure logic so it stays JVM-testable.
 *
 * A fresh process starts locked. Leaving the app for at least [graceMillis] locks it again; a
 * shorter trip (file picker, share sheet, sign-in tab) keeps the current unlock.
 */
internal class AppLockSession(private val graceMillis: Long = DEFAULT_GRACE_MILLIS) {
    private var startedActivities = 0
    private var backgroundedAt: Long? = null

    var unlocked: Boolean = false
        private set

    fun onActivityStarted(nowMillis: Long) {
        if (startedActivities == 0) {
            val leftAt = backgroundedAt
            if (leftAt != null && nowMillis - leftAt >= graceMillis) unlocked = false
            backgroundedAt = null
        }
        startedActivities++
    }

    fun onActivityStopped(nowMillis: Long) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) backgroundedAt = nowMillis
    }

    fun markUnlocked() {
        unlocked = true
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 60_000L
    }
}

/** Device-local, opt-in app lock preference. Not in the backup/transfer allowlist. */
internal class AppLockStore private constructor(
    private val preferences: SharedPreferences,
) {
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        mutableEnabled.value = value
    }

    companion object {
        internal const val PREFERENCES_NAME = "app_lock"
        internal const val KEY_ENABLED = "enabled"

        @Volatile
        private var instance: AppLockStore? = null

        fun get(context: Context): AppLockStore =
            instance ?: synchronized(this) {
                instance ?: AppLockStore(
                    context.applicationContext.getSharedPreferences(
                        PREFERENCES_NAME,
                        Context.MODE_PRIVATE,
                    )
                ).also { instance = it }
            }
    }
}

/**
 * Covers every Aistee activity with [AppLockActivity] until the user passes the device
 * biometric/credential check. Without a secure lock screen there is nothing to check against,
 * so the lock stays inactive rather than locking the user out.
 */
internal object AppLock : Application.ActivityLifecycleCallbacks {
    private val session = AppLockSession()
    private var lockScreenVisible = false

    fun isDeviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** The user is present (they just enabled the lock or passed the check). */
    fun markUnlocked() {
        session.markUnlocked()
    }

    internal fun onLockScreenClosed() {
        lockScreenVisible = false
    }

    private fun requiresLock(activity: Activity): Boolean =
        AppLockStore.get(activity).enabled.value && !session.unlocked && isDeviceSecure(activity)

    override fun onActivityStarted(activity: Activity) {
        session.onActivityStarted(SystemClock.elapsedRealtime())
        if (activity is AppLockActivity || !requiresLock(activity)) return
        // Hide content until unlocked so it never flashes behind the lock screen.
        activity.window.decorView.alpha = 0f
        if (!lockScreenVisible) {
            lockScreenVisible = true
            activity.startActivity(
                Intent(activity, AppLockActivity::class.java),
                ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle(),
            )
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is AppLockActivity && !requiresLock(activity)) {
            activity.window.decorView.alpha = 1f
        }
    }

    override fun onActivityStopped(activity: Activity) {
        session.onActivityStopped(SystemClock.elapsedRealtime())
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

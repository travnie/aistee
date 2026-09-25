package ais.tee.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Opt-in app-wide screen privacy. When enabled, every Aistee activity window carries
 * FLAG_SECURE, which blocks screenshots, screen recording, casting and recents thumbnails.
 *
 * The preference is device-local on purpose: it is not in the backup/transfer allowlist.
 */
internal class ScreenPrivacyStore private constructor(
    private val preferences: SharedPreferences,
) {
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        mutableEnabled.value = value
    }

    companion object {
        internal const val PREFERENCES_NAME = "screen_privacy"
        internal const val KEY_ENABLED = "block_screen_capture"

        @Volatile
        private var instance: ScreenPrivacyStore? = null

        fun get(context: Context): ScreenPrivacyStore =
            instance ?: synchronized(this) {
                instance ?: ScreenPrivacyStore(
                    context.applicationContext.getSharedPreferences(
                        PREFERENCES_NAME,
                        Context.MODE_PRIVATE,
                    )
                ).also { instance = it }
            }
    }
}

internal fun Window.applyScreenPrivacy(enabled: Boolean) {
    if (enabled) {
        addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/**
 * Applies the current setting before the first frame, then follows live changes for as long as
 * the activity exists. Registered for every activity by [ScreenPrivacyActivityCallbacks].
 */
internal fun ComponentActivity.bindScreenPrivacy() {
    val store = ScreenPrivacyStore.get(this)
    window.applyScreenPrivacy(store.enabled.value)
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.CREATED) {
            store.enabled.collect { enabled -> window.applyScreenPrivacy(enabled) }
        }
    }
}

/** Binds screen privacy to every activity the app creates, including future ones. */
internal object ScreenPrivacyActivityCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is ComponentActivity) {
            activity.bindScreenPrivacy()
        } else {
            activity.window.applyScreenPrivacy(ScreenPrivacyStore.get(activity).enabled.value)
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

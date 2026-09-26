package ais.tee.security

import android.content.Context
import android.content.SharedPreferences
import androidx.glance.appwidget.updateAll
import ais.tee.notifications.NativeChatConversationShortcuts
import ais.tee.notifications.NativeChatNotificationPublisher
import ais.tee.widget.AisteeWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PrivacySurfaceVisibility(
    val showConversationTitles: Boolean,
    val showMessagePreviews: Boolean,
)

internal fun effectivePrivacySurfaceVisibility(
    showConversationTitles: Boolean,
    showMessagePreviews: Boolean,
    quickPrivacyEnabled: Boolean,
): PrivacySurfaceVisibility =
    if (quickPrivacyEnabled) {
        PrivacySurfaceVisibility(
            showConversationTitles = false,
            showMessagePreviews = false,
        )
    } else {
        PrivacySurfaceVisibility(
            showConversationTitles = showConversationTitles,
            showMessagePreviews = showMessagePreviews,
        )
    }

/**
 * Device-local emergency redaction for launcher widgets and native-chat notifications.
 *
 * Per-surface choices stay untouched so disabling Quick privacy restores the user's normal policy
 * on the next render/publication. The preference is intentionally absent from backup/transfer.
 */
internal class QuickPrivacyModeStore private constructor(
    private val preferences: SharedPreferences,
) {
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    internal fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        mutableEnabled.value = value
    }

    companion object {
        internal const val PREFERENCES_NAME = "quick_privacy"
        internal const val KEY_ENABLED = "enabled"

        @Volatile
        private var instance: QuickPrivacyModeStore? = null

        fun get(context: Context): QuickPrivacyModeStore =
            instance ?: synchronized(this) {
                instance ?: QuickPrivacyModeStore(
                    context.applicationContext.getSharedPreferences(
                        PREFERENCES_NAME,
                        Context.MODE_PRIVATE,
                    )
                ).also { instance = it }
            }
    }
}

internal object QuickPrivacyModeController {
    suspend fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        QuickPrivacyModeStore.get(appContext).setEnabled(enabled)

        // Publication and cancellation share the notification privacy lock, so an in-flight
        // unredacted notification cannot survive after Quick privacy becomes active.
        NativeChatNotificationPublisher.cancelPostedConversations(appContext)
        if (enabled) NativeChatConversationShortcuts.removeTitled(appContext)
        AisteeWidget().updateAll(appContext)
    }
}

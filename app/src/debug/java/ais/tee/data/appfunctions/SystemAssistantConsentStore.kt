package ais.tee.data.appfunctions

import android.content.Context

/** Local consent only; absent from backup/transfer and explicit export allowlists. */
internal class SystemAssistantConsentStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "system_assistant_consent", Context.MODE_PRIVATE,
    )

    fun isEnabled(): Boolean = preferences.getBoolean("inline_token_count", false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("inline_token_count", enabled).apply()
    }
}

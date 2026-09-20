package ais.tee.data.preferences

import android.content.Context

/**
 * Per-widget display preferences. Titles are hidden by default so adding a widget never exposes
 * conversation names on the launcher unless the user explicitly opts in.
 *
 * This preference file is intentionally absent from the app's Android backup/device-transfer
 * allowlist.
 */
internal class AisteeWidgetPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun showConversationTitles(appWidgetId: Int): Boolean =
        preferences.getBoolean(showTitlesKey(appWidgetId), false)

    fun saveShowConversationTitles(appWidgetId: Int, showConversationTitles: Boolean) {
        preferences.edit()
            .putBoolean(showTitlesKey(appWidgetId), showConversationTitles)
            .apply()
    }

    fun remove(appWidgetId: Int) {
        preferences.edit().remove(showTitlesKey(appWidgetId)).apply()
    }

    private fun showTitlesKey(appWidgetId: Int): String = "show_conversation_titles_$appWidgetId"

    private companion object {
        const val PREFERENCES_NAME = "aistee_widget_preferences"
    }
}

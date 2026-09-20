package ais.tee.data.preferences

import android.content.Context

internal enum class AisteeWidgetMode(val id: String) {
    RECENT_CHATS("recent_chats"),
    PINNED_CHAT("pinned_chat"),
}

internal fun resolveAisteeWidgetMode(id: String?): AisteeWidgetMode =
    AisteeWidgetMode.entries.firstOrNull { mode -> mode.id == id }
        ?: AisteeWidgetMode.RECENT_CHATS

internal data class AisteeWidgetPreferences(
    val mode: AisteeWidgetMode = AisteeWidgetMode.RECENT_CHATS,
    val pinnedConversationId: String? = null,
    val showConversationTitles: Boolean = false,
)

/**
 * Per-widget display preferences. Titles are hidden by default so adding a widget never exposes
 * conversation names on the launcher unless the user explicitly opts in.
 *
 * This preference file is intentionally absent from the app's Android backup/device-transfer
 * allowlist.
 */
internal class AisteeWidgetPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(appWidgetId: Int): AisteeWidgetPreferences =
        AisteeWidgetPreferences(
            mode = resolveAisteeWidgetMode(preferences.getString(modeKey(appWidgetId), null)),
            pinnedConversationId = preferences.getString(pinnedConversationKey(appWidgetId), null)
                ?.takeIf { it.isNotBlank() },
            showConversationTitles = preferences.getBoolean(showTitlesKey(appWidgetId), false),
        )

    fun save(appWidgetId: Int, configuration: AisteeWidgetPreferences) {
        preferences.edit().apply {
            putString(modeKey(appWidgetId), configuration.mode.id)
            putBoolean(showTitlesKey(appWidgetId), configuration.showConversationTitles)
            val pinnedConversationId = configuration.pinnedConversationId?.trim()?.takeIf { it.isNotEmpty() }
            if (pinnedConversationId == null) {
                remove(pinnedConversationKey(appWidgetId))
            } else {
                putString(pinnedConversationKey(appWidgetId), pinnedConversationId)
            }
            apply()
        }
    }

    fun remove(appWidgetId: Int) {
        preferences.edit()
            .remove(modeKey(appWidgetId))
            .remove(pinnedConversationKey(appWidgetId))
            .remove(showTitlesKey(appWidgetId))
            .apply()
    }

    private fun modeKey(appWidgetId: Int): String = "mode_$appWidgetId"
    private fun pinnedConversationKey(appWidgetId: Int): String = "pinned_conversation_$appWidgetId"
    private fun showTitlesKey(appWidgetId: Int): String = "show_conversation_titles_$appWidgetId"

    private companion object {
        const val PREFERENCES_NAME = "aistee_widget_preferences"
    }
}

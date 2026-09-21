package ais.tee.data.preferences

import android.content.Context

internal enum class AisteeWidgetMode(val id: String) {
    RECENT_CHATS("recent_chats"),
    MESSAGES("messages"),
    PINNED_CHAT("pinned_chat"),
}

internal fun resolveAisteeWidgetMode(id: String?): AisteeWidgetMode =
    AisteeWidgetMode.entries.firstOrNull { mode -> mode.id == id }
        ?: AisteeWidgetMode.RECENT_CHATS

internal data class AisteeWidgetPreferences(
    val mode: AisteeWidgetMode = AisteeWidgetMode.RECENT_CHATS,
    val pinnedConversationId: String? = null,
    val showConversationTitles: Boolean = false,
    val showMessagePreviews: Boolean = false,
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
            showMessagePreviews = preferences.getBoolean(showMessagePreviewsKey(appWidgetId), false),
        )

    fun save(appWidgetId: Int, configuration: AisteeWidgetPreferences) {
        val editor = preferences.edit()
            .putString(modeKey(appWidgetId), configuration.mode.id)
            .putBoolean(showTitlesKey(appWidgetId), configuration.showConversationTitles)
            .putBoolean(showMessagePreviewsKey(appWidgetId), configuration.showMessagePreviews)
        val pinnedConversationId = configuration.pinnedConversationId?.trim()?.takeIf { it.isNotEmpty() }
        if (pinnedConversationId == null) {
            editor.remove(pinnedConversationKey(appWidgetId))
        } else {
            editor.putString(pinnedConversationKey(appWidgetId), pinnedConversationId)
        }
        editor.apply()
    }

    fun remove(appWidgetId: Int) {
        preferences.edit()
            .remove(modeKey(appWidgetId))
            .remove(pinnedConversationKey(appWidgetId))
            .remove(showTitlesKey(appWidgetId))
            .remove(showMessagePreviewsKey(appWidgetId))
            .apply()
    }

    private fun modeKey(appWidgetId: Int): String = "mode_$appWidgetId"
    private fun pinnedConversationKey(appWidgetId: Int): String = "pinned_conversation_$appWidgetId"
    private fun showTitlesKey(appWidgetId: Int): String = "show_conversation_titles_$appWidgetId"
    private fun showMessagePreviewsKey(appWidgetId: Int): String = "show_message_previews_$appWidgetId"

    private companion object {
        const val PREFERENCES_NAME = "aistee_widget_preferences"
    }
}

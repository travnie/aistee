package ais.tee.data.preferences

import android.content.Context
import ais.tee.data.model.ProviderIdentityMethod
import ais.tee.data.model.WebAiService

internal fun resolveWebService(id: String?): WebAiService =
    WebAiService.entries.firstOrNull { service ->
        id?.equals(service.id, ignoreCase = true) == true
    } ?: WebAiService.CLAUDE

internal fun resolveWebServices(ids: Set<String>): Set<WebAiService> =
    WebAiService.entries.filterTo(linkedSetOf()) { service ->
        ids.any { id -> id.equals(service.id, ignoreCase = true) }
    }

internal fun resolveFavoriteWebServices(ids: Set<String>): Set<WebAiService> =
    resolveWebServices(ids)

internal fun resolveDisabledTurnNavigationServices(ids: Set<String>): Set<WebAiService> =
    resolveWebServices(ids)

internal fun resolvePreferredIdentityMethod(id: String?): ProviderIdentityMethod? =
    ProviderIdentityMethod.entries.firstOrNull { method ->
        id?.equals(method.name, ignoreCase = true) == true
    }

internal class WebChatPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSelectedService(): WebAiService = resolveWebService(
        preferences.getString(SELECTED_SERVICE_KEY, null)
    )

    fun saveSelectedService(service: WebAiService) {
        preferences.edit().putString(SELECTED_SERVICE_KEY, service.id).apply()
    }

    fun loadFavorites(): Set<WebAiService> = resolveFavoriteWebServices(
        preferences.getStringSet(FAVORITE_SERVICES_KEY, emptySet()).orEmpty()
    )

    fun saveFavorites(favorites: Set<WebAiService>) {
        preferences.edit()
            .putStringSet(FAVORITE_SERVICES_KEY, favorites.mapTo(linkedSetOf()) { it.id })
            .apply()
    }

    fun loadDisabledTurnNavigationServices(): Set<WebAiService> =
        resolveDisabledTurnNavigationServices(
            preferences.getStringSet(DISABLED_TURN_NAVIGATION_KEY, emptySet()).orEmpty()
        )

    fun saveDisabledTurnNavigationServices(disabled: Set<WebAiService>) {
        preferences.edit()
            .putStringSet(DISABLED_TURN_NAVIGATION_KEY, disabled.mapTo(linkedSetOf()) { it.id })
            .apply()
    }

    fun loadPreferredIdentityMethod(): ProviderIdentityMethod? = resolvePreferredIdentityMethod(
        preferences.getString(PREFERRED_IDENTITY_METHOD_KEY, null)
    )

    /** Stores only a local UI preference. No identity token, cookie or provider session is copied. */
    fun savePreferredIdentityMethod(method: ProviderIdentityMethod?) {
        val editor = preferences.edit()
        if (method == null) {
            editor.remove(PREFERRED_IDENTITY_METHOD_KEY)
        } else {
            editor.putString(PREFERRED_IDENTITY_METHOD_KEY, method.name)
        }
        editor.apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "web_chat_preferences"
        const val SELECTED_SERVICE_KEY = "selected_service"
        const val FAVORITE_SERVICES_KEY = "favorite_services"
        const val PREFERRED_IDENTITY_METHOD_KEY = "preferred_identity_method"
        const val DISABLED_TURN_NAVIGATION_KEY = "disabled_turn_navigation_services"
    }
}

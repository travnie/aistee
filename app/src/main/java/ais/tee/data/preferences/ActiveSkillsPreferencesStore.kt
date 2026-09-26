package ais.tee.data.preferences

import android.content.Context
import ais.tee.data.skills.ActiveSkillTrust
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Active skills flag and execution trust (`docs/skills-runtime.md`). The flag is off by default,
 * and this file is outside the backup allowlist so neither the flag nor trust leaves the device.
 */
internal class ActiveSkillsPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(ENABLED_KEY, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    fun trustFor(skillName: String): ActiveSkillTrust? = loadTrust().firstOrNull { it.skillName == skillName }

    fun grantTrust(trust: ActiveSkillTrust) {
        saveTrust(loadTrust().filterNot { it.skillName == trust.skillName } + trust)
    }

    fun revokeTrust(skillName: String) {
        saveTrust(loadTrust().filterNot { it.skillName == skillName })
    }

    private fun loadTrust(): List<ActiveSkillTrust> {
        val raw = preferences.getString(TRUST_KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(trustSerializer, raw) }
            .getOrDefault(emptyList())
            .map { ActiveSkillTrust(it.skillName, it.bundleDigest, it.networkOrigins) }
    }

    private fun saveTrust(trust: List<ActiveSkillTrust>) {
        val stored = trust.map { StoredTrust(it.skillName, it.bundleDigest, it.networkOrigins) }
        preferences.edit().putString(TRUST_KEY, json.encodeToString(trustSerializer, stored)).apply()
    }

    @Serializable
    private data class StoredTrust(
        val skillName: String,
        val bundleDigest: String,
        val networkOrigins: List<String>,
    )

    private companion object {
        const val PREFERENCES_NAME = "active_skills_preferences"
        const val ENABLED_KEY = "active_skills_enabled"
        const val TRUST_KEY = "execution_trust"
        val json = Json { ignoreUnknownKeys = true }
        val trustSerializer = ListSerializer(StoredTrust.serializer())
    }
}

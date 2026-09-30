package ais.tee.data.security

import android.content.Context
import ais.tee.data.model.ApiKeyConfig
import org.json.JSONObject

internal class ApiKeyStore(context: Context) {
    private val encryptedStore = EncryptedJsonStore(
        context = context,
        preferencesName = PREFERENCES_NAME,
        keyAlias = KEY_ALIAS,
    )

    fun load(): ApiKeyConfig = runCatching {
        val plaintext = encryptedStore.read() ?: return ApiKeyConfig()
        val json = JSONObject(plaintext)
        ApiKeyConfig(
            geminiKey = json.optString("gemini"),
            openAiKey = json.optString("openai"),
            claudeKey = json.optString("claude"),
            deepseekKey = json.optString("deepseek"),
            kimiKey = json.optString("kimi"),
            openRouterKey = json.optString("openrouter"),
            aiHubMixKey = json.optString("aihubmix"),
            vercelAiGatewayKey = json.optString("vercel_ai_gateway")
        )
    }.getOrDefault(ApiKeyConfig())

    fun save(config: ApiKeyConfig): Boolean {
        val plaintext = JSONObject()
            .put("gemini", config.geminiKey)
            .put("openai", config.openAiKey)
            .put("claude", config.claudeKey)
            .put("deepseek", config.deepseekKey)
            .put("kimi", config.kimiKey)
            .put("openrouter", config.openRouterKey)
            .put("aihubmix", config.aiHubMixKey)
            .put("vercel_ai_gateway", config.vercelAiGatewayKey)
            .toString()
        return encryptedStore.write(plaintext)
    }

    private companion object {
        // Keep these values stable so the refactor reads the existing on-device record unchanged.
        const val KEY_ALIAS = "llmbench-api-keys"
        const val PREFERENCES_NAME = "encrypted_api_keys"
    }
}

package ais.tee.data.security

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands out usable SIWC credentials for one explicitly selected registration, refreshing near
 * expiry. Refreshes are serialized per client ID process-wide so two callers never race a
 * rotating refresh token. Never falls back to another account, provider or API-key billing.
 */
internal class ChatGptPlanSessions(
    private val vault: ChatGptPlanCredentialStore,
    private val exchange: ChatGptPlanTokenExchange,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /**
     * Null when the registration holds no tokens. Throws
     * [ChatGptPlanTokenExchange.ReauthorizationRequired] after clearing an unusable token set
     * (registration kept for re-sign-in); plain [IOException] on transient failure, tokens kept.
     */
    fun usable(clientId: String): ChatGptPlanCredentialStore.Credentials? =
        synchronized(locks.computeIfAbsent(clientId) { Any() }) {
            val current = vault.credentials(clientId) ?: return@synchronized null
            if (current.expiresAt - nowEpochSeconds() > REFRESH_SKEW_SECONDS) return@synchronized current
            val refreshed = try {
                exchange.refresh(clientId, current.refreshToken, nowEpochSeconds())
            } catch (error: ChatGptPlanTokenExchange.ReauthorizationRequired) {
                vault.clearRejected(clientId, current.refreshToken)
                throw error
            }
            // Server already rotated: a failed write here costs a re-sign-in, never a stale write.
            if (!vault.saveRefreshed(clientId, current.refreshToken, refreshed)) {
                throw IOException("Unable to persist refreshed ChatGPT credentials")
            }
            vault.credentials(clientId)
        }

    private companion object {
        const val REFRESH_SKEW_SECONDS = 300L
        val locks = ConcurrentHashMap<String, Any>()
    }
}

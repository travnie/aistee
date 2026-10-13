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
    private val sleep: (Long) -> Unit = Thread::sleep,
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

    enum class SignOut { NOT_CONNECTED, REVOKED, CLEARED_UNCONFIRMED }

    /**
     * End the renewable session: revoke the refresh token (bounded retries), then clear local
     * secrets either way. [SignOut.CLEARED_UNCONFIRMED] means the UI must say remote revocation
     * was not confirmed and point to ChatGPT Settings. Client ID and subject are kept.
     */
    fun signOut(clientId: String): SignOut = synchronized(locks.computeIfAbsent(clientId) { Any() }) {
        val current = vault.credentials(clientId) ?: return@synchronized SignOut.NOT_CONNECTED
        var revoked = false
        try {
            for (attempt in 0 until REVOKE_ATTEMPTS) {
                if (attempt > 0) sleep(REVOKE_BACKOFF_MILLIS shl (attempt - 1))
                revoked = try {
                    exchange.revoke(clientId, current.refreshToken)
                    true
                } catch (_: ChatGptPlanTokenExchange.RevocationRejected) {
                    break // Terminal: clear now, report unconfirmed.
                } catch (_: IOException) {
                    false
                }
                if (revoked) break
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt() // Cancelled: still clear locally below.
        }
        // Only the token set we revoked; a sign-in saved meanwhile (outside this lock) stays.
        if (!vault.clearRejected(clientId, current.refreshToken)) {
            throw IOException("Unable to clear ChatGPT credentials")
        }
        if (revoked) SignOut.REVOKED else SignOut.CLEARED_UNCONFIRMED
    }

    private companion object {
        const val REVOKE_ATTEMPTS = 3
        const val REVOKE_BACKOFF_MILLIS = 1_000L
        const val REFRESH_SKEW_SECONDS = 300L
        val locks = ConcurrentHashMap<String, Any>()
    }
}

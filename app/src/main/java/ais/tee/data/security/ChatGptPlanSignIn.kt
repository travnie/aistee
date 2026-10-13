package ais.tee.data.security

import java.io.Closeable

/**
 * One SIWC browser attempt: loopback listener, authorize URL, token exchange, verified save.
 * Blocking; run off the main thread. [Attempt.close] from any thread cancels and frees the port.
 */
internal class ChatGptPlanSignIn(
    private val vault: ChatGptPlanCredentialStore,
    private val exchange: ChatGptPlanTokenExchange,
    private val openLoopback: () -> ChatGptPlanLoopback = ChatGptPlanLoopback::open,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    /** `*_UNCONFIRMED`: an issued grant was discarded but OpenAI didn't confirm revoking it. */
    enum class Result {
        CONNECTED, CONNECTED_WITHOUT_PLAN, CANCELLED, TIMED_OUT, FAILED, ACCOUNT_LIMIT,
        CANCELLED_UNCONFIRMED, FAILED_UNCONFIRMED,
    }

    /**
     * Null [account] registers a new client (or resumes [retryClientId] from a failed attempt);
     * otherwise reauthorizes that registration and rejects a different ChatGPT identity.
     * Null return: account cap reached, nothing opened.
     */
    fun start(account: ChatGptPlanCredentialStore.Account? = null, retryClientId: String? = null): Attempt? {
        val full = vault.accounts().size >= ChatGptPlanCredentialStore.MAX_ACCOUNTS
        if (account == null && full) return null
        val hostId = vault.hostId()
        val loopback = openLoopback()
        val clientId = account?.clientId ?: retryClientId
        return try {
            Attempt(loopback, ChatGptPlanOAuth.prepare(hostId, loopback.port, clientId), account?.subject)
        } catch (error: Exception) {
            loopback.close()
            throw error
        }
    }

    inner class Attempt internal constructor(
        private val loopback: ChatGptPlanLoopback,
        private val pending: ChatGptPlanOAuth.Pending,
        private val expectedSubject: String?,
    ) : Closeable {
        /** Issued client ID to pass to the next [start] after a failed exchange. */
        @Volatile var retryClientId: String? = null
            private set

        /** Open only in a browser/Custom Tab, never a WebView. Never log: may carry hints. */
        val authorizationUrl: String get() = pending.authorizationUrl

        /** Waits for the browser callback; always closes the port. [close] meanwhile: IOException. */
        fun finish(timeoutMillis: Long = TIMEOUT_MILLIS): Result {
            val outcome = loopback.use { it.await(pending, timeoutMillis) }
            val callbackUrl = when (outcome) {
                is ChatGptPlanLoopback.Outcome.Received -> outcome.callbackUrl
                ChatGptPlanLoopback.Outcome.Denied -> return Result.CANCELLED
                ChatGptPlanLoopback.Outcome.Failed -> return Result.FAILED
                ChatGptPlanLoopback.Outcome.TimedOut -> return Result.TIMED_OUT
            }
            // Issued once the callback arrives: keep it so a retry reuses it (cleared on success).
            retryClientId = (pending.peekCallback(callbackUrl) as? ChatGptPlanOAuth.Callback.Code)?.clientId
            // Subject checked here, not in complete(), so a mismatched grant can be revoked.
            val grant = try {
                exchange.complete(pending, callbackUrl)
            } catch (unverified: ChatGptPlanTokenExchange.UnverifiedGrant) {
                return revoke(unverified.clientId, unverified.refreshToken, Result.FAILED, Result.FAILED_UNCONFIRMED)
            } catch (_: Exception) {
                return Result.FAILED
            }
            if (cancelled) return discard(grant, Result.CANCELLED, Result.CANCELLED_UNCONFIRMED)
            if (expectedSubject != null && grant.identity.subject != expectedSubject) {
                return discard(grant, Result.FAILED, Result.FAILED_UNCONFIRMED)
            }
            if (!runCatching { vault.saveVerified(grant) }.getOrDefault(false)) {
                val full = runCatching { vault.accounts().size >= ChatGptPlanCredentialStore.MAX_ACCOUNTS }
                    .getOrDefault(false)
                val failed = if (expectedSubject == null && full) Result.ACCOUNT_LIMIT else Result.FAILED
                return discard(grant, failed, Result.FAILED_UNCONFIRMED)
            }
            retryClientId = null
            return if (grant.planUsageGranted) Result.CONNECTED else Result.CONNECTED_WITHOUT_PLAN
        }

        /** Issued but not kept: end the 30-day session rather than orphan it. */
        private fun discard(grant: ChatGptPlanTokenExchange.Grant, revoked: Result, unconfirmed: Result): Result =
            revoke(grant.identity.clientId, grant.refreshToken, revoked, unconfirmed)

        private fun revoke(clientId: String, refreshToken: String, revoked: Result, unconfirmed: Result): Result =
            if (ChatGptPlanSessions.revokeWithRetry(exchange, clientId, refreshToken, sleep)) revoked else unconfirmed

        @Volatile private var cancelled = false

        /** Cancels: frees the port, or discards a grant still being exchanged. */
        override fun close() {
            cancelled = true
            loopback.close()
        }
    }

    companion object {
        const val TIMEOUT_MILLIS = 5 * 60_000L
    }
}

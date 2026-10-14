package ais.tee.data.security

import android.content.Context
import ais.tee.data.engine.ChatGptPlanAuth
import ais.tee.data.engine.ChatGptPlanTokens
import ais.tee.data.engine.ChatGptPlanUnavailable
import ais.tee.data.engine.ChatGptPlanUnavailable.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Vault-backed plan tokens, refreshed near expiry; serialization lives in [ChatGptPlanSessions]. */
internal class ChatGptPlanTokenSource(
    private val sessions: ChatGptPlanSessions,
) : ChatGptPlanTokens {
    constructor(context: Context) : this(
        ChatGptPlanSessions(ChatGptPlanCredentialStore(context.applicationContext), ChatGptPlanTokenExchange()),
    )

    override suspend fun auth(clientId: String): ChatGptPlanAuth = withContext(Dispatchers.IO) {
        val credentials = try {
            sessions.usable(clientId)
        } catch (_: ChatGptPlanTokenExchange.ReauthorizationRequired) {
            throw ChatGptPlanUnavailable(Reason.SIGNED_OUT)
        } catch (_: Exception) {
            throw ChatGptPlanUnavailable(Reason.TEMPORARY)
        } ?: throw ChatGptPlanUnavailable(Reason.SIGNED_OUT)
        if (PLAN_SCOPE !in credentials.scopes) throw ChatGptPlanUnavailable(Reason.PLAN_NOT_GRANTED)
        ChatGptPlanAuth(clientId, credentials.accessToken)
    }

    private companion object {
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    }
}

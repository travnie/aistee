package ais.tee.data.engine

import java.io.IOException

/**
 * Explicit funding route for a ChatGPT-plan Responses call. Never inferred from a missing API
 * key; a call with this auth never reads API keys, simulates, or falls back to another route.
 */
class ChatGptPlanAuth(val clientId: String, val accessToken: String) {
    init {
        require(clientId.isNotBlank() && accessToken.isNotBlank())
    }

    override fun toString(): String = "ChatGptPlanAuth(<redacted>)"
}

/** One entry of the signed-in account's model catalog; [slug] goes into `model`. */
data class ChatGptPlanModel(val slug: String, val displayName: String)

/** Provider error keeping HTTP status and machine-readable code; message stays the display text. */
class ProviderApiException(message: String, val status: Int?, val code: String?) : IOException(message)

/** Recovery text for documented ChatGPT-plan error codes, then the provider's own message. */
internal fun describeChatGptPlanError(error: Throwable): String {
    val api = error as? ProviderApiException
    val hint = when (api?.code) {
        "subscription_sharing_usage_limit_exceeded" ->
            "ChatGPT plan usage limit reached. Check usage at https://chatgpt.com/settings/usage."
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" ->
            "ChatGPT plan usage is temporarily unavailable. Try again later."
        "subscription_sharing_user_not_eligible" ->
            "ChatGPT plan usage isn't available for this account or workspace."
        "subscription_sharing_unsupported_capability" ->
            "This request uses something the ChatGPT plan route doesn't support."
        "subscription_sharing_invalid_user",
        "chatpass_v2_scope_not_authorized",
        "chatpass_v2_invalid_authorization_context" ->
            "ChatGPT didn't accept this account's plan permission. Sign in to the account again."
        else -> when (api?.status) {
            401 -> "ChatGPT didn't accept this account's sign-in. Sign in to the account again."
            403 -> "ChatGPT blocked this request (policy or region)."
            503 -> "ChatGPT plan routing is unavailable right now. Try again later."
            else -> null
        }
    }
    val detail = error.localizedMessage ?: error.message ?: "Unknown error"
    return listOfNotNull(hint, detail).joinToString("\n\n")
}

package ais.tee.data.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Portable OpenID Connect identity-claim policy shared across Aistee clients.
 * Checks claim semantics only. Input may be unsigned; callers MUST cryptographically verify
 * the JWT first. This result is NOT proof of identity or signature verification.
 * Identity alone does not authorize account-plan inference.
 */
data class ChatGptPlanClaims(
    val subject: String,
    val email: String?,
    val clientId: String,
) {
    override fun toString(): String = "ChatGptPlanClaims(<redacted>)"
}

fun validateChatGptPlanClaims(
    payload: String,
    clientId: String,
    nonce: String,
    nowEpochSeconds: Long,
): ChatGptPlanClaims {
    require(clientId.isNotBlank() && nonce.isNotBlank())
    val claims = Json.parseToJsonElement(payload) as? JsonObject ?: error("Invalid identity token")
    fun string(name: String): String? = (claims[name] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content
    require(string("iss") == "https://auth.openai.com")
    val audience = claims["aud"]
    val matchesAudience = when (audience) {
        is JsonPrimitive -> audience.isString && audience.content == clientId
        is JsonArray -> audience.any { element ->
            element is JsonPrimitive && element.isString && element.content == clientId
        }
        else -> false
    }
    require(matchesAudience)
    // OIDC does not require azp on single-audience arrays, but validate it if present.
    if ("azp" in claims) require(string("azp") == clientId)
    fun timestamp(name: String): Long = (claims[name] as? JsonPrimitive)
        ?.longOrNull ?: error("Invalid identity timestamp")
    val exp = timestamp("exp")
    val issuedAt = timestamp("iat")
    require(exp > nowEpochSeconds - 5 && issuedAt <= nowEpochSeconds + 5 && issuedAt <= exp)
    if ("nbf" in claims) require(timestamp("nbf") <= nowEpochSeconds + 5)
    val signedNonce = string("nonce") ?: error("Missing identity nonce")
    require(sameBytes(signedNonce.encodeToByteArray(), nonce.encodeToByteArray()))
    val subject = string("sub")?.takeIf { it.isNotBlank() } ?: error("Missing identity subject")
    return ChatGptPlanClaims(
        subject = subject,
        email = string("email")?.takeIf { it.isNotBlank() },
        clientId = clientId,
    )
}

/** Bound by the caller's size limits; avoids early-return secret comparison. */
private fun sameBytes(first: ByteArray, second: ByteArray): Boolean {
    var difference = first.size xor second.size
    for (i in 0 until maxOf(first.size, second.size)) {
        difference = difference or
            ((first.getOrElse(i) { 0 }.toInt() and 0xff) xor
                (second.getOrElse(i) { 0 }.toInt() and 0xff))
    }
    return difference == 0
}

package ais.tee.data.security

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First stage of Sign in with ChatGPT: construct a browser authorization request and validate
 * its loopback callback. Does not exchange tokens, validate identity or enable plan inference.
 *
 * The caller must start a loopback listener before opening [Pending.authorizationUrl], provide
 * a stable host ID, and complete verified token exchange before persisting any connection.
 */
internal object ChatGptPlanOAuth {
    private const val AUTHORIZATION_URL = "https://auth.openai.com/api/accounts/authorize"
    private const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    private const val RESOURCE = "https://api.openai.com/v1"
    private const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    private const val CALLBACK_PATH = "/auth/callback"
    private const val AGENT_NAME = "Aistee"
    private val secureRandom = SecureRandom()

    internal sealed interface Callback {
        class Code(val authorizationCode: String, val clientId: String) : Callback {
            override fun toString(): String = "Code(<redacted>)"
        }
        data object AccessDenied : Callback
        data object Invalid : Callback
    }

    internal class Pending internal constructor(
        val authorizationUrl: String,
        val redirectUri: String,
        val codeVerifier: String,
        val nonce: String,
        private val state: String,
        private val expectedClientId: String?,
    ) {
        private val finished = AtomicBoolean(false)

        /**
         * A successful callback is one-use. Invalid redirects do not cancel the legitimate
         * sign-in attempt. Never log the callback URL or the authorization code.
         */
        fun acceptCallback(callbackUrl: String): Callback {
            if (finished.get()) return Callback.Invalid
            val callback = runCatching { URI(callbackUrl) }.getOrNull() ?: return Callback.Invalid
            val registered = runCatching { URI(redirectUri) }.getOrNull() ?: return Callback.Invalid
            if (
                callback.scheme != registered.scheme ||
                callback.host != registered.host ||
                callback.port != registered.port ||
                callback.rawPath != registered.rawPath ||
                callback.rawFragment != null ||
                callback.rawUserInfo != null
            ) return Callback.Invalid
            val parameters = decodeQuery(callback.rawQuery) ?: return Callback.Invalid
            if (parameters["state"] != state) return Callback.Invalid
            val error = parameters["error"]
            if (error != null) {
                if (!finished.compareAndSet(false, true)) return Callback.Invalid
                return if (error == "access_denied") Callback.AccessDenied else Callback.Invalid
            }
            val code = parameters["code"]?.takeIf { it.isNotBlank() } ?: return Callback.Invalid
            val returnedClientId = parameters["client_id"]
            val clientId = if (expectedClientId == null) {
                returnedClientId?.takeIf { it.isNotBlank() && it != DYNAMIC_CLIENT_ID }
            } else {
                expectedClientId.takeIf { returnedClientId == null || returnedClientId == it }
            } ?: return Callback.Invalid
            if (!finished.compareAndSet(false, true)) return Callback.Invalid
            return Callback.Code(code, clientId)
        }

        override fun toString(): String = "ChatGptPlanOAuth.Pending(<redacted>)"
    }

    fun prepare(hostId: String, port: Int, issuedClientId: String? = null): Pending {
        require(
            hostId.isNotBlank() && hostId.length <= 256 &&
                hostId.none { it.isWhitespace() || it.isISOControl() } &&
                (hostId.startsWith("urn:uuid:") ||
                    hostId.startsWith("urn:ietf:params:oauth:jwk-thumbprint:") ||
                    hostId.startsWith("did:key:"))
        ) { "A stable, supported opaque host ID is required" }
        require(port in 1..65535) { "Callback listener must have a valid bound port" }
        require(issuedClientId == null || (
            issuedClientId.isNotBlank() && issuedClientId != DYNAMIC_CLIENT_ID &&
                issuedClientId.none { it.isWhitespace() || it.isISOControl() }
        )) { "Reuse the issued client ID, never the dynamic registration placeholder" }

        val state = randomUrlSafe(32)
        val nonce = randomUrlSafe(32)
        val verifier = randomUrlSafe(64)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
        )
        val redirectUri = "http://127.0.0.1:$port$CALLBACK_PATH"
        val parameters = linkedMapOf(
            "client_id" to (issuedClientId ?: DYNAMIC_CLIENT_ID),
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
        )
        if (issuedClientId == null) parameters["agent_name_hint"] = AGENT_NAME
        val url = AUTHORIZATION_URL + "?" + parameters.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return Pending(url, redirectUri, verifier, nonce, state, issuedClientId)
    }

    private fun randomUrlSafe(size: Int): String {
        val bytes = ByteArray(size).also(secureRandom::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun encode(value: String) =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun decodeQuery(query: String?): Map<String, String>? {
        if (query == null) return null
        val result = mutableMapOf<String, String>()
        for (entry in query.split("&")) {
            if (entry.isBlank()) return null
            val divider = entry.indexOf('=')
            if (divider < 1) return null
            val (key, value) = try {
                URLDecoder.decode(entry.substring(0, divider), "UTF-8") to
                    URLDecoder.decode(entry.substring(divider + 1), "UTF-8")
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (result.put(key, value) != null) return null // OAuth params must be unambiguous.
        }
        return result
    }
}

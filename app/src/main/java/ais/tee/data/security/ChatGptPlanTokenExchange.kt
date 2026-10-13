package ais.tee.data.security

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Exchanges a one-use, validated SIWC callback for a signature-verified identity and token grant.
 * No browser, WebView, API-key fallback or persisted account state is involved.
 */
internal class ChatGptPlanTokenExchange(
    private val transport: Transport = NetworkTransport(),
) {
    /** Transport seam for deterministic tests; production always uses pinned OpenAI endpoints. */
    fun interface Transport {
        fun execute(request: Request): String
    }

    /** Never log/export tokens or treat identity-only authorization as plan access. */
    class Grant internal constructor(
        val identity: ChatGptPlanIdentity.Verified,
        val idToken: String,
        val accessToken: String,
        val refreshToken: String,
        val scopes: Set<String>,
        val expiresAtEpochSeconds: Long,
    ) {
        val planUsageGranted: Boolean
            get() = "chatgpt.tokens.use.direct" in scopes

        override fun toString(): String = "ChatGptPlanTokenExchange.Grant(<redacted>)"
    }

    /**
     * Rotated token set. Refresh responses are not nonce-bound, so no ID token is taken from
     * them: the vault keeps the one verified at sign-in. Null [scopes] means grant unchanged.
     */
    class Refreshed internal constructor(
        val accessToken: String,
        val refreshToken: String,
        val scopes: Set<String>?,
        val expiresAtEpochSeconds: Long,
    ) {
        override fun toString(): String = "ChatGptPlanTokenExchange.Refreshed(<redacted>)"
    }

    /** OAuth error code from a 400/401 body. Code only; the body is never kept. */
    class OAuthError(val code: String) : IOException("ChatGPT authorization rejected")

    /** Token set is unusable: clear it and repeat OAuth with the saved issued client ID. */
    class ReauthorizationRequired : IOException("ChatGPT sign-in expired")

    fun complete(
        pending: ChatGptPlanOAuth.Pending,
        callbackUrl: String,
        expectedSubject: String? = null,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Grant {
        val callback = pending.acceptCallback(callbackUrl) as? ChatGptPlanOAuth.Callback.Code
            ?: throw IOException("ChatGPT authorization did not complete")
        require(expectedSubject == null || expectedSubject.isNotBlank())
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", callback.clientId)
            .add("code", callback.authorizationCode)
            .add("code_verifier", pending.codeVerifier)
            .add("redirect_uri", pending.redirectUri)
            .add("resource", RESOURCE)
            .build()
        val token = parseObject(request(
            Request.Builder().url(TOKEN_ENDPOINT)
                .header("Accept", "application/json").post(form).build()
        ))
        fun required(name: String): String =
            (token[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() } ?: throw IOException("Incomplete ChatGPT authorization")
        require(required("token_type").equals("Bearer", ignoreCase = true))
        val accessToken = required("access_token")
        val refreshToken = required("refresh_token")
        val idToken = required("id_token")
        val grantedScopes = required("scope").split(' ').filter { it.isNotBlank() }.toSet()
        val lifetime = (token["expires_in"] as? JsonPrimitive)?.longOrNull
            ?: throw IOException("Missing ChatGPT token expiration")
        require(lifetime in 1..86_400)
        val jwks = request(Request.Builder().url(JWKS_ENDPOINT)
            .header("Accept", "application/json").get().build())
        val identity = try {
            ChatGptPlanIdentity.verify(
                jwt = idToken,
                jwks = jwks,
                clientId = callback.clientId,
                nonce = pending.nonce,
                now = nowEpochSeconds,
            )
        } catch (_: Exception) {
            throw IOException("ChatGPT identity verification failed")
        }
        if (expectedSubject != null && expectedSubject != identity.subject) {
            throw IOException("ChatGPT account does not match selected account")
        }
        return Grant(identity, idToken, accessToken, refreshToken, grantedScopes,
            Math.addExact(nowEpochSeconds, lifetime))
    }

    /**
     * Standard refresh near expiry. Callers must serialize per registration (rotating token) and
     * persist the result atomically; see [ChatGptPlanSessions]. Transient failures throw plain
     * [IOException] and must not clear stored credentials.
     */
    fun refresh(
        clientId: String,
        refreshToken: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Refreshed {
        require(clientId.isNotBlank() && clientId != DYNAMIC_CLIENT_ID && refreshToken.isNotBlank())
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("resource", RESOURCE)
            .build()
        val raw = try {
            request(Request.Builder().url(TOKEN_ENDPOINT)
                .header("Accept", "application/json").post(form).build())
        } catch (error: OAuthError) {
            if (error.code in UNUSABLE_TOKEN_CODES) throw ReauthorizationRequired()
            throw IOException("ChatGPT token refresh rejected")
        }
        val token = parseObject(raw)
        fun required(name: String): String =
            (token[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() } ?: throw IOException("Incomplete ChatGPT token refresh")
        if (!required("token_type").equals("Bearer", ignoreCase = true)) {
            throw IOException("Unexpected ChatGPT token type")
        }
        val lifetime = (token["expires_in"] as? JsonPrimitive)?.longOrNull
            ?.takeIf { it in 1..86_400 } ?: throw IOException("Invalid ChatGPT token expiration")
        val scopes = (token["scope"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.split(' ')?.filter { it.isNotBlank() }?.toSet()
        return Refreshed(required("access_token"), required("refresh_token"), scopes,
            Math.addExact(nowEpochSeconds, lifetime))
    }

    /**
     * Revoke the refresh token (ends the renewable session; the registration survives). The
     * endpoint comes from issuer discovery and must stay on the issuer origin. An already
     * invalid token also yields 200. Network/5xx failures throw; callers may retry.
     */
    fun revoke(clientId: String, refreshToken: String) {
        require(clientId.isNotBlank() && clientId != DYNAMIC_CLIENT_ID && refreshToken.isNotBlank())
        val endpoint = (parseObject(request(Request.Builder().url(DISCOVERY_ENDPOINT)
            .header("Accept", "application/json").get().build()))["revocation_endpoint"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?.takeIf { it.startsWith(ISSUER + "/") && it.none { c -> c.isWhitespace() || c == '#' } }
            ?: throw IOException("Missing ChatGPT revocation endpoint")
        val form = FormBody.Builder()
            .add("token", refreshToken)
            .add("token_type_hint", "refresh_token")
            .add("client_id", clientId)
            .build()
        val builder = runCatching { Request.Builder().url(endpoint) }.getOrNull()
            ?: throw IOException("Invalid ChatGPT revocation endpoint")
        try {
            request(builder.post(form).build())
        } catch (_: OAuthError) {
            throw IOException("ChatGPT revocation rejected")
        }
    }

    private fun request(request: Request): String = try {
        val response = transport.execute(request)
        if (response.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) {
            throw IOException("Authorization response too large")
        }
        response
    } catch (error: OAuthError) {
        throw error
    } catch (_: Exception) {
        // Do not surface token responses or authorization URLs in exceptions.
        throw IOException("ChatGPT authorization request failed")
    }

    private fun parseObject(raw: String): JsonObject = try {
        Json.parseToJsonElement(raw) as? JsonObject
            ?: throw IOException("Invalid ChatGPT authorization response")
    } catch (_: Exception) {
        throw IOException("Invalid ChatGPT authorization response")
    }

    private class NetworkTransport : Transport {
        private val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        override fun execute(request: Request): String {
            // Pinned issuer origin only: token, JWKS, discovery and its revocation endpoint.
            require(request.url.isHttps && request.url.host == "auth.openai.com" &&
                request.url.port == 443 && request.url.username.isEmpty())
            client.newCall(request).execute().use { response ->
                if (response.request.url.toString() != request.url.toString()) {
                    throw IOException("Authorization request unsuccessful")
                }
                if (response.code == 400 || response.code == 401) {
                    throw OAuthError(errorCode(readBounded(response)) ?: "unknown")
                }
                if (!response.isSuccessful) throw IOException("Authorization request unsuccessful")
                return readBounded(response)
            }
        }

        /** Accepts `{"error":"code"}` and `{"error":{"code":"code"}}`; nothing else is read. */
        private fun errorCode(raw: String): String? {
            val error = (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject)
                ?.get("error") ?: return null
            val code = (error as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
            return code?.takeIf { it.length <= 64 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '.' } }
        }

        private fun readBounded(response: Response): String {
            val body = response.body ?: throw IOException("Empty authorization response")
            if (body.contentLength() > MAX_BYTES) throw IOException("Authorization response too large")
            val buffer = ByteArray(4096)
            val output = ByteArrayOutputStream()
            body.byteStream().use { stream ->
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) break
                    if (count + output.size() > MAX_BYTES) throw IOException("Authorization response too large")
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(StandardCharsets.UTF_8.name())
        }
    }

    private companion object {
        const val TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
        const val JWKS_ENDPOINT = "https://auth.openai.com/.well-known/jwks.json"
        const val ISSUER = "https://auth.openai.com"
        const val DISCOVERY_ENDPOINT = "https://auth.openai.com/.well-known/openid-configuration"
        const val RESOURCE = "https://api.openai.com/v1"
        const val MAX_BYTES = 65_536
        const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
        val UNUSABLE_TOKEN_CODES = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused",
        )
    }
}

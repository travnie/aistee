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

    private fun request(request: Request): String = try {
        val response = transport.execute(request)
        if (response.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) {
            throw IOException("Authorization response too large")
        }
        response
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
            require(request.url.toString() == TOKEN_ENDPOINT ||
                request.url.toString() == JWKS_ENDPOINT)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.request.url.toString() != request.url.toString()) {
                    throw IOException("Authorization request unsuccessful")
                }
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
    }

    private companion object {
        const val TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
        const val JWKS_ENDPOINT = "https://auth.openai.com/.well-known/jwks.json"
        const val RESOURCE = "https://api.openai.com/v1"
        const val MAX_BYTES = 65_536
    }
}

package ais.tee.data.engine

import ais.tee.data.model.MAX_MCP_ENDPOINT_LENGTH
import ais.tee.data.model.McpAuthorizationServerMetadata
import ais.tee.data.model.McpProtectedResourceMetadata
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_METADATA_BYTES = 65_536
private const val MAX_METADATA_ITEMS = 64
private const val METADATA_TIMEOUT_MS = 40_000L
private const val RESOURCE_WELL_KNOWN = "/.well-known/oauth-protected-resource"

/** Ephemeral 401 context. Header URLs are untrusted and never fetched automatically. */
internal data class McpAuthorizationChallenge(
    val endpoint: String,
    val resourceMetadata: String? = null,
    val scopes: List<String>? = null,
) {
    override fun toString(): String = "McpAuthorizationChallenge(redacted)"

    companion object {
        fun fromResponse(endpoint: HttpUrl, response: Response): McpAuthorizationChallenge {
            val headers = response.headers.values("WWW-Authenticate")
            if (headers.sumOf { it.length } > 8_192) return McpAuthorizationChallenge(endpoint.toString())
            val bearer = response.challenges().filter { it.scheme.equals("Bearer", ignoreCase = true) }
                .singleOrNull()
            val params = bearer?.authParams.orEmpty()
            return McpAuthorizationChallenge(endpoint.toString(), params["resource_metadata"],
                params["scope"]?.let { parseScopeString(it) ?: listOf("") })
        }
    }
}

internal class McpAuthorizationRequiredException(val challenge: McpAuthorizationChallenge) :
    IOException("MCP authorization required")

internal enum class McpAuthorizationFailure { INVALID_METADATA, NETWORK_FAILURE, TIMEOUT }

internal sealed interface McpAuthorizationInspection<out T> {
    data class Found<T>(val metadata: T) : McpAuthorizationInspection<T>
    data class Failed(val reason: McpAuthorizationFailure) : McpAuthorizationInspection<Nothing>
}

/** Read-only, explicit checks. Isolated transport; no registration, cookies, tokens or redirects. */
internal class McpAuthorizationInspector(private val calls: Call.Factory = secureMcpHttpClient()) {
    suspend fun inspectResource(challenge: McpAuthorizationChallenge): McpAuthorizationInspection<McpProtectedResourceMetadata> =
        inspect {
            val endpoint = validatedMcpEndpoint(challenge.endpoint) ?: return@inspect null
            val explicit = challenge.resourceMetadata
            val urls = if (explicit != null) {
                listOf(validatedMetadataEndpoint(explicit) ?: return@inspect null)
            } else {
                wellKnownUrls(endpoint, RESOURCE_WELL_KNOWN)
            }
            val document = firstDocument(urls) ?: return@inspect null
            val resource = document.string("resource") ?: return@inspect null
            // Conservative preview: bind the complete endpoint, never widen its audience.
            if (validatedMcpEndpoint(resource) != endpoint) return@inspect null
            val issuers = document.strings("authorization_servers", required = true)
                ?.takeIf { it.size in 1..8 && it.all { issuer -> validatedMcpEndpoint(issuer) != null } }
                ?: return@inspect null
            val advertisedScopes = document.strings("scopes_supported") ?: return@inspect null
            if (advertisedScopes.any { !validScope(it) }) return@inspect null
            val scopes = challenge.scopes ?: advertisedScopes
            if (scopes.size > MAX_METADATA_ITEMS || scopes.any { !validScope(it) }) return@inspect null
            McpProtectedResourceMetadata(resource, issuers, scopes)
        }

    /** The user selects an issuer from the resource metadata before any request reaches it. */
    suspend fun inspectServer(resource: McpProtectedResourceMetadata, issuer: String): McpAuthorizationInspection<McpAuthorizationServerMetadata> =
        inspect {
            if (issuer !in resource.authorizationServers) return@inspect null
            val url = validatedMcpEndpoint(issuer) ?: return@inspect null
            val urls = wellKnownUrls(url, "/.well-known/oauth-authorization-server").take(1) +
                wellKnownUrls(url, "/.well-known/openid-configuration").take(1) +
                url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/.well-known/openid-configuration").build()
            val document = firstDocument(urls.distinct()) ?: return@inspect null
            // RFC 8414 issuer equality is exact; do not normalize the advertised identifier.
            if (document.string("issuer") != issuer) return@inspect null
            val authorization = document.string("authorization_endpoint")
                ?.takeIf { validatedMetadataEndpoint(it) != null } ?: return@inspect null
            val token = document.string("token_endpoint")
                ?.takeIf { validatedMetadataEndpoint(it) != null } ?: return@inspect null
            val methods = document.strings("code_challenge_methods_supported") ?: return@inspect null
            val clientMetadata = document["client_id_metadata_document_supported"]?.let {
                (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.booleanOrNull
                    ?: return@inspect null
            } ?: false
            val registration = document["registration_endpoint"]?.let {
                document.string("registration_endpoint")?.takeIf { value -> validatedMetadataEndpoint(value) != null }
                    ?: return@inspect null
            }
            McpAuthorizationServerMetadata(issuer, authorization, token, "S256" in methods,
                clientMetadata, registration != null)
        }

    private suspend fun <T : Any> inspect(block: suspend () -> T?): McpAuthorizationInspection<T> =
        withTimeoutOrNull(METADATA_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                try {
                    block()?.let { McpAuthorizationInspection.Found(it) }
                        ?: McpAuthorizationInspection.Failed(McpAuthorizationFailure.INVALID_METADATA)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) { // skipcq: KT-W1064
                    // Async OkHttp failures resume the suspended metadata request.
                    McpAuthorizationInspection.Failed(McpAuthorizationFailure.NETWORK_FAILURE)
                } catch (_: Exception) { // skipcq: KT-W1064
                    // Injectable factories can fail synchronously; diagnostics stay metadata-only.
                    McpAuthorizationInspection.Failed(McpAuthorizationFailure.INVALID_METADATA)
                }
            }
        } ?: McpAuthorizationInspection.Failed(McpAuthorizationFailure.TIMEOUT)

    private suspend fun firstDocument(urls: List<HttpUrl>): JsonObject? {
        for (url in urls) {
            val document = requestDocument(url)
            // Only an absent well-known location permits a fallback. Invalid data fails closed.
            if (document != null) return document
        }
        return null
    }

    private suspend fun requestDocument(url: HttpUrl): JsonObject? = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (it.code == 404 || it.code == 410) return@use null
                        require(it.code == 200)
                        val body = requireNotNull(it.body)
                        require(body.contentType()?.let { type -> type.type == "application" && type.subtype == "json" } == true)
                        val bytes = readMetadataBytes(body.byteStream())
                        require(bytes.size <= MAX_METADATA_BYTES)
                        val raw = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString()
                        require(hasSafeMcpJsonDepth(raw, maxDepth = 16))
                        requireNotNull(Json.parseToJsonElement(raw) as? JsonObject)
                    }
                }
                if (continuation.isActive) result.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = { continuation.resumeWithException(it) },
                )
            }
        })
    }
}

private fun wellKnownUrls(url: HttpUrl, suffix: String): List<HttpUrl> = listOf(
    url.newBuilder().encodedPath(suffix + url.encodedPath.trimEnd('/')).build(),
    url.newBuilder().encodedPath(suffix).build(),
).distinct()

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 2_048 }

private fun JsonObject.strings(key: String, required: Boolean = false): List<String>? {
    val value = this[key] ?: return if (required) null else emptyList()
    val array = value as? JsonArray ?: return null
    if (array.size > MAX_METADATA_ITEMS) return null
    return array.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString && item.content.length <= 2_048 }
        ?.content ?: return null }.distinct()
}

private fun parseScopeString(raw: String): List<String>? = raw.takeIf { it.length <= 8_192 }
    ?.split(' ')?.takeIf { it.size <= MAX_METADATA_ITEMS && it.all(::validScope) }?.distinct()

private fun validScope(scope: String): Boolean = scope.length in 1..128 &&
    scope.all { it.code == 0x21 || it.code in 0x23..0x5b || it.code in 0x5d..0x7e }

private fun readMetadataBytes(input: InputStream): ByteArray {
    val buffer = ByteArray(MAX_METADATA_BYTES + 1)
    var size = 0
    while (size < buffer.size) {
        val read = input.read(buffer, size, buffer.size - size)
        if (read == -1) break
        size += read
    }
    return buffer.copyOf(size)
}

/** OAuth endpoint and metadata URLs may contain a query, unlike resource/issuer identifiers. */
private fun validatedMetadataEndpoint(raw: String): HttpUrl? {
    if (raw.length > MAX_MCP_ENDPOINT_LENGTH || raw.any { it.isISOControl() }) return null
    val url = raw.toHttpUrlOrNull() ?: return null
    val withoutQuery = url.newBuilder().query(null).build().toString()
    return url.takeIf { it.toString().length <= MAX_MCP_ENDPOINT_LENGTH && validatedMcpEndpoint(withoutQuery) != null }
}

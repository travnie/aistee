package ais.tee.data.engine

import ais.tee.data.model.McpToolCatalog
import ais.tee.data.streambench.StreambenchPublicDns
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

private const val MCP_VERSION = "2026-07-28"
private const val MAX_RESPONSE_BYTES = 256_000
private const val MAX_CATALOG_PAGES = 16
private const val TOTAL_DISCOVERY_MILLIS = 40_000L
private const val MAX_CURSOR_LENGTH = 2_048
private val MCP_SERVER_ID = Regex("^[A-Za-z0-9_-]{1,20}$")
private val JSON_CONTENT_TYPE = "application/json; charset=utf-8".toMediaType()

/** Coarse metadata-only failure reasons for a future user-reviewed connection diagnostic. */
internal enum class McpDiscoveryFailure {
    INVALID_ENDPOINT, INVALID_RESPONSE, NETWORK_FAILURE, TIMEOUT,
}

internal sealed interface McpDiscoveryOutcome {
    data class Success(val catalog: McpToolCatalog) : McpDiscoveryOutcome
    data class Failure(val reason: McpDiscoveryFailure) : McpDiscoveryOutcome
}

/**
 * On-demand, unauthenticated Streamable HTTP tools/list discovery for MCP 2026-07-28.
 *
 * Not a background connection, saved server registry, authentication flow, or tool executor.
 * The caller must be an explicit user "test/discover" action. Only metadata and schemas are
 * returned through the existing McpToolCatalog. No model or WebView may invoke this directly.
 *
 * Earlier, initialize-based MCP versions need a separate, negotiated session transport.
 */
/**
 * Production calls use a fresh isolated client. A Call.Factory may be injected for JVM
 * tests only, not reused from provider transports with authentication or logging hooks.
 */
internal fun secureMcpHttpClient(dns: Dns = Dns.SYSTEM): OkHttpClient =
    OkHttpClient.Builder()
        .dns(StreambenchPublicDns(dns))
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

internal class McpHttpDiscovery(
    private val calls: Call.Factory = secureMcpHttpClient(),
    private val random: SecureRandom = SecureRandom(),
) {

    suspend fun discover(endpoint: String, serverId: String): McpToolCatalog? =
        (discoverChecked(endpoint, serverId) as? McpDiscoveryOutcome.Success)?.catalog

    suspend fun discoverChecked(endpoint: String, serverId: String): McpDiscoveryOutcome =
        withTimeoutOrNull(TOTAL_DISCOVERY_MILLIS) {
            withContext(Dispatchers.IO) {
            if (!MCP_SERVER_ID.matches(serverId)) {
                return@withContext McpDiscoveryOutcome.Failure(McpDiscoveryFailure.INVALID_ENDPOINT)
            }
            val url = validateEndpoint(endpoint)
                ?: return@withContext McpDiscoveryOutcome.Failure(McpDiscoveryFailure.INVALID_ENDPOINT)
            try {
                val generation = ByteArray(16).also(random::nextBytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val pages = mutableListOf<String>()
                val seenCursors = mutableSetOf<String>()
                var cursor: String? = null
                do {
                    currentCoroutineContext().ensureActive()
                    val id = pages.size + 1
                    val requestJson = toolsListRequest(id, cursor)
                    val request = Request.Builder()
                        .url(url)
                        .post(requestJson.toRequestBody(JSON_CONTENT_TYPE))
                        .header("Accept", "application/json, text/event-stream")
                        .header("MCP-Protocol-Version", MCP_VERSION)
                        .header("Mcp-Method", "tools/list")
                        .build()
                    val responseJson = requestPage(request, id)
                        ?: return@withContext McpDiscoveryOutcome.Failure(
                            McpDiscoveryFailure.INVALID_RESPONSE
                        )
                    pages += responseJson
                    val result = Json.parseToJsonElement(responseJson).jsonObject["result"]
                        as? JsonObject ?: return@withContext McpDiscoveryOutcome.Failure(
                            McpDiscoveryFailure.INVALID_RESPONSE
                        )
                    val next = result["nextCursor"]
                    cursor = if (next == null) null else {
                        (next as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                            ?: return@withContext McpDiscoveryOutcome.Failure(
                                McpDiscoveryFailure.INVALID_RESPONSE
                            )
                    }
                    if (cursor != null && (cursor.isNullOrBlank() ||
                            cursor!!.length > MAX_CURSOR_LENGTH ||
                            !seenCursors.add(cursor!!))) {
                        return@withContext McpDiscoveryOutcome.Failure(McpDiscoveryFailure.INVALID_RESPONSE)
                    }
                    if (cursor != null && pages.size >= MAX_CATALOG_PAGES) {
                        return@withContext McpDiscoveryOutcome.Failure(McpDiscoveryFailure.INVALID_RESPONSE)
                    }
                } while (cursor != null)
                val catalog = McpToolCatalog.fromToolsListResponses(serverId, generation, pages)
                if (catalog == null) McpDiscoveryOutcome.Failure(McpDiscoveryFailure.INVALID_RESPONSE)
                else McpDiscoveryOutcome.Success(catalog)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                McpDiscoveryOutcome.Failure(McpDiscoveryFailure.NETWORK_FAILURE)
            } catch (_: Exception) {
                // No provider-owned identifiers or response bodies enter diagnostics.
                McpDiscoveryOutcome.Failure(McpDiscoveryFailure.INVALID_RESPONSE)
            }
            }
        } ?: McpDiscoveryOutcome.Failure(McpDiscoveryFailure.TIMEOUT)

    private fun validateEndpoint(raw: String): HttpUrl? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null) return null
        // IP literals and local-only hostnames are not accepted. DNS resolution is independently
        // filtered against public addresses for every connection.
        if (url.host.contains(':') || url.host.matches(Regex("^[0-9.]+$")) ||
            !url.host.contains('.') || url.host.endsWith(".local")) return null
        return url
    }

    private fun toolsListRequest(id: Int, cursor: String?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "tools/list")
        putJsonObject("params") {
            if (cursor != null) put("cursor", cursor)
            putJsonObject("_meta") {
                put("io.modelcontextprotocol/protocolVersion", MCP_VERSION)
                putJsonObject("io.modelcontextprotocol/clientInfo") {
                    put("name", "aistee")
                    put("version", "1.0")
                }
                put("io.modelcontextprotocol/clientCapabilities", buildJsonObject {})
            }
        }
    }.toString()

    /** Asynchronous OkHttp call: coroutine cancellation immediately closes the active request. */
    private suspend fun requestPage(request: Request, expectedId: Int): String? =
        suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (it.code != 200) return@use null
                            val body = it.body ?: return@use null
                            val mime = body.contentType()?.let { type ->
                                type.type + "/" + type.subtype
                            } ?: return@use null
                            when (mime) {
                                "application/json" ->
                                    verifiedResponse(readBoundedUtf8(body.byteStream()), expectedId)
                                "text/event-stream" ->
                                    readStreamedSse(body.byteStream(), expectedId)
                                else -> null
                            }
                        }
                    }
                    if (continuation.isActive) result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                }
            })
        }

    /** Reads SSE incrementally; an open stream may be closed after the final correlated event. */
    private fun readStreamedSse(input: InputStream, expectedId: Int): String? {
        val bounded = object : FilterInputStream(input) {
            var bytes = 0
            override fun read(): Int {
                val value = super.read()
                if (value >= 0 && ++bytes > MAX_RESPONSE_BYTES) error("MCP SSE limit exceeded")
                return value
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count > 0) {
                    bytes += count
                    if (bytes > MAX_RESPONSE_BYTES) error("MCP SSE limit exceeded")
                }
                return count
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return BufferedReader(InputStreamReader(bounded, decoder)).use { reader ->
            var completed: String? = null
            val event = StringBuilder()
            fun dispatch(): Boolean {
                if (event.isEmpty()) return true
                val data = event.toString().removeSuffix("\n")
                event.clear()
                val envelope = runCatching { Json.parseToJsonElement(data) as? JsonObject }
                    .getOrNull() ?: return false
                if (envelope.containsKey("id")) {
                    val response = verifiedResponse(data, expectedId) ?: return false
                    if (completed != null) return false
                    completed = response
                    return true
                }
                val method = (envelope["method"] as? JsonPrimitive)?.takeIf { it.isString }
                    ?.contentOrNull
                return envelope["jsonrpc"]?.toString() == "\"2.0\"" &&
                    method?.startsWith("notifications/") == true
            }
            var firstLine = true
            while (true) {
                val original = reader.readLine()
                if (original == null) return@use if (dispatch()) completed else null
                val line = if (firstLine) original.removePrefix("\uFEFF") else original
                firstLine = false
                when {
                    line.isEmpty() -> {
                        if (!dispatch()) return@use null
                        if (completed != null) return@use completed
                    }
                    line.startsWith("data:") ->
                        event.append(line.substringAfter("data:").removePrefix(" ")).append('\n')
                    // Comments and unknown SSE extension fields are ignored by the spec.
                    else -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    private fun verifiedResponse(raw: String, expectedId: Int): String? {
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }
            .getOrNull() ?: return null
        val id = obj["id"] as? JsonPrimitive
        if (obj["jsonrpc"]?.toString() != "\"2.0\"" ||
            id == null || id.isString || id.intOrNull != expectedId ||
            obj["error"] != null) return null
        val result = obj["result"] as? JsonObject ?: return null
        val type = result["resultType"]
        if (type != null && (type as? JsonPrimitive)?.contentOrNull != "complete") return null
        return obj.toString()
    }

    private fun readBoundedUtf8(stream: java.io.InputStream): String {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(8_192)
        while (true) {
            val n = stream.read(chunk)
            if (n == -1) break
            if (bytes.size() + n > MAX_RESPONSE_BYTES) error("MCP response too large")
            bytes.write(chunk, 0, n)
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}

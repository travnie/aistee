package ais.tee.data.engine

import ais.tee.data.model.McpToolCatalog
import ais.tee.data.streambench.StreambenchPublicDns
import java.io.ByteArrayOutputStream
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val MCP_VERSION = "2026-07-28"
private const val MAX_RESPONSE_BYTES = 256_000
private const val MAX_CATALOG_PAGES = 16
private const val MAX_CURSOR_LENGTH = 2_048
private val MCP_SERVER_ID = Regex("^[A-Za-z0-9_-]{1,20}$")
private val JSON_CONTENT_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * On-demand, unauthenticated Streamable HTTP tools/list discovery for MCP 2026-07-28.
 *
 * Not a background connection, saved server registry, authentication flow, or tool executor.
 * The caller must be an explicit user "test/discover" action. Only metadata and schemas are
 * returned through the existing McpToolCatalog. No model or WebView may invoke this directly.
 *
 * Earlier, initialize-based MCP versions need a separate, negotiated session transport.
 */
internal class McpHttpDiscovery(
    client: OkHttpClient = OkHttpClient(),
    private val random: SecureRandom = SecureRandom(),
    dns: Dns = Dns.SYSTEM,
) {
    // Always enforce the shared public-address DNS guard, even for injected test clients.
    // No automatic redirects, proxy tunnelling, cookies, bearer tokens or retrying POSTs.
    private val http = client.newBuilder()
        .dns(StreambenchPublicDns(dns))
        .cookieJar(CookieJar.NO_COOKIES)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun discover(endpoint: String, serverId: String): McpToolCatalog? =
        withContext(Dispatchers.IO) {
            if (!MCP_SERVER_ID.matches(serverId)) return@withContext null
            val url = validateEndpoint(endpoint) ?: return@withContext null
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
                    val responseJson = http.newCall(request).execute().use { response ->
                        if (response.code != 200) return@withContext null
                        val mime = response.body?.contentType()?.let {
                            it.type + "/" + it.subtype
                        } ?: return@withContext null
                        val data = response.body?.byteStream()?.use(::readBoundedUtf8)
                            ?: return@withContext null
                        parsePostResponse(mime, data, id) ?: return@withContext null
                    }
                    pages += responseJson
                    val result = Json.parseToJsonElement(responseJson).jsonObject["result"]
                        as? JsonObject ?: return@withContext null
                    val next = result["nextCursor"]
                    cursor = if (next == null) null else {
                        (next as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                            ?: return@withContext null
                    }
                    if (cursor != null && (cursor.isNullOrBlank() ||
                            cursor!!.length > MAX_CURSOR_LENGTH ||
                            !seenCursors.add(cursor!!))) return@withContext null
                    if (cursor != null && pages.size >= MAX_CATALOG_PAGES) return@withContext null
                } while (cursor != null)
                McpToolCatalog.fromToolsListResponses(serverId, generation, pages)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Remote errors, provider-owned identifiers and response bodies are not logged.
                null
            }
        }

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

    private fun parsePostResponse(mime: String, raw: String, expectedId: Int): String? {
        if (mime == "application/json") return verifiedResponse(raw, expectedId)
        if (mime != "text/event-stream") return null

        // SSE notifications may precede the final response; never handle server-initiated
        // requests, sampling, elicitation or roots as part of discovery.
        var finalResponse: String? = null
        val event = StringBuilder()
        fun flush(): Boolean {
            if (event.isEmpty()) return true
            val data = event.toString().removeSuffix("\n")
            event.clear()
            val envelope = runCatching { Json.parseToJsonElement(data) as? JsonObject }
                .getOrNull() ?: return false
            if (envelope.containsKey("id")) {
                val parsed = verifiedResponse(data, expectedId) ?: return false
                if (finalResponse != null) return false
                finalResponse = parsed
                return true
            }
            val method = (envelope["method"] as? JsonPrimitive)?.contentOrNull
            return envelope["jsonrpc"]?.toString() == "\"2.0\"" &&
                method?.startsWith("notifications/") == true
        }
        for (line in raw.lineSequence()) {
            val trimmed = line.removeSuffix("\r")
            if (trimmed.isEmpty()) {
                if (!flush()) return null
            } else if (trimmed.startsWith("data:")) {
                val value = trimmed.substringAfter("data:").removePrefix(" ")
                event.append(value).append('\n')
            } else if (trimmed.startsWith(":") || trimmed.startsWith("event:") ||
                trimmed.startsWith("id:") || trimmed.startsWith("retry:")) {
                // SSE control lines; there is no Last-Event-ID resumption in this version.
            } else return null
        }
        if (!flush()) return null
        return finalResponse
    }

    private fun verifiedResponse(raw: String, expectedId: Int): String? {
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }
            .getOrNull() ?: return null
        if (obj["jsonrpc"]?.toString() != "\"2.0\"" ||
            (obj["id"] as? JsonPrimitive)?.intOrNull != expectedId ||
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

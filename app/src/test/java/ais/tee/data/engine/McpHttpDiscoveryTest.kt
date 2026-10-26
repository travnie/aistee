package ais.tee.data.engine

import java.security.SecureRandom
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHttpDiscoveryTest {
    private val url = "https://tools.example.org/mcp"
    private val tool = """{"name":"sample.search","description":"Find items","inputSchema":{"type":"object"}}"""

    private fun result(id: Int, cursor: String? = null, toolJson: String = tool): String {
        val next = if (cursor == null) "" else ""","nextCursor":"$cursor""""
        return """{"jsonrpc":"2.0","id":$id,"result":{"resultType":"complete","tools":[$toolJson]$next}}"""
    }

    private fun discovery(handler: (Request) -> Response): McpHttpDiscovery {
        val fake = Interceptor { chain -> handler(chain.request()) }
        val client = OkHttpClient.Builder().addInterceptor(fake).build()
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                bytes.fill(0x12.toByte())
            }
        }
        return McpHttpDiscovery(client, random)
    }

    private fun ok(request: Request, body: String, type: String = "application/json"): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body.toResponseBody(type.toMediaType())).build()

    @Test
    fun productionClientHasNoInheritedHooksCredentialsOrRedirects() {
        val client = secureMcpHttpClient()
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar)
    }

    @Test
    fun sanitizedFailuresDistinguishInputNetworkAndMalformedData() = runBlocking {
        val transport = discovery { req -> ok(req, result(1)) }
        assertEquals(
            McpDiscoveryOutcome.Failure(McpDiscoveryFailure.INVALID_ENDPOINT),
            transport.discoverChecked("http://example.org/mcp", "demo")
        )
        val malformed = discovery { req -> ok(req, "{bad-json") }
        assertEquals(
            McpDiscoveryOutcome.Failure(McpDiscoveryFailure.INVALID_RESPONSE),
            malformed.discoverChecked(url, "demo")
        )
        val failing = discovery { throw IOException("Do not expose remote diagnostics") }
        assertEquals(
            McpDiscoveryOutcome.Failure(McpDiscoveryFailure.NETWORK_FAILURE),
            failing.discoverChecked(url, "demo")
        )
    }

    @Test
    fun actualHttpRedirectDoesNotReachSecondEndpoint() {
        // A test-only local server verifies OkHttp's redirect behavior. Production discovery
        // rejects loopback targets independently through endpoint and public-DNS validation.
        val visited = AtomicInteger()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 1_000
        val worker = Thread {
            try {
                repeat(2) { index ->
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        return@Thread
                    }
                    socket.use {
                        visited.incrementAndGet()
                        val input = it.getInputStream().bufferedReader()
                        while (input.readLine()?.isNotEmpty() == true) Unit
                        val response = if (index == 0) {
                            "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${server.localPort}/next\r\n" +
                                "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        }
                        it.getOutputStream().write(response.toByteArray())
                        it.getOutputStream().flush()
                    }
                }
            } catch (_: IOException) {
                // Listener closes after the assertion; absence of a second request is expected.
            }
        }
        worker.start()
        try {
            val testClient = secureMcpHttpClient().newBuilder()
                .dns(okhttp3.Dns.SYSTEM).build()
            testClient.newCall(
                okhttp3.Request.Builder()
                    .url("http://127.0.0.1:${server.localPort}/start").build()
            ).execute().use { assertEquals(302, it.code) }
            worker.join(2_000)
            assertEquals(1, visited.get())
        } finally {
            server.close()
            worker.join(2_000)
        }
    }

    @Test
    fun paginatedHttpDiscoveryUsesRequestMetadataAndCurrentCatalog() = runBlocking {
        val seen = mutableListOf<String?>()
        val client = discovery { request ->
            assertEquals("POST", request.method)
            assertEquals("https", request.url.scheme)
            assertEquals("2026-07-28", request.header("MCP-Protocol-Version"))
            assertEquals("tools/list", request.header("Mcp-Method"))
            assertEquals("application/json, text/event-stream", request.header("Accept"))
            val body = request.body!!.let { b ->
                val sink = okio.Buffer()
                b.writeTo(sink)
                Json.parseToJsonElement(sink.readUtf8()).jsonObject
            }
            val params = body["params"]!!.jsonObject
            assertEquals("2026-07-28", params["_meta"]!!.jsonObject[
                "io.modelcontextprotocol/protocolVersion"
            ]!!.jsonPrimitive.content)
            val cursor = params["cursor"]?.jsonPrimitive?.content
            seen += cursor
            if (cursor == null) ok(request, result(1, "page-two"))
            else ok(request, result(2, toolJson = """{"name":"sample.save","inputSchema":{"type":"object"}}"""))
        }
        val catalog = client.discover(url, "demo")
        assertNotNull(catalog)
        assertEquals(listOf(null, "page-two"), seen)
        assertEquals(2, catalog!!.count)
        val summary = catalog.browse("save").single()
        assertEquals("sample.save", summary.remoteName)
        assertNotNull(catalog.loadDefinition(summary.nativeName))
    }

    @Test
    fun streamedPostAcceptsNotificationsBeforeFinalResponse() = runBlocking {
        val transport = discovery { req ->
            val events = ": heartbeat\n\n" +
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
                "event: message\ndata: " + result(1) + "\n\n"
            ok(req, events, "text/event-stream")
        }
        assertEquals(1, transport.discover(url, "demo")?.count)
    }

    @Test
    fun acceptsBomCrOnlyAndUnknownSseFields() = runBlocking {
        val transport = discovery { req ->
            ok(req, "\uFEFF: comment\rextension: value\rdata: " + result(1) + "\r\r",
                "text/event-stream")
        }
        assertEquals(1, transport.discover(url, "demo")?.count)
    }

    @Test
    fun refusesUncorrelatedResponsesAndServerRequests() = runBlocking {
        val wrongId = discovery { req -> ok(req, result(99)) }
        assertNull(wrongId.discover(url, "demo"))
        val quotedId = discovery { req ->
            ok(req, result(1).replace("\"id\":1", "\"id\":\"1\""))
        }
        assertNull(quotedId.discover(url, "demo"))
        val serverRequest = discovery { req ->
            val events = "data: {\"jsonrpc\":\"2.0\",\"id\":88,\"method\":\"sampling/createMessage\"}\n\n" +
                "data: " + result(1) + "\n\n"
            ok(req, events, "text/event-stream")
        }
        assertNull(serverRequest.discover(url, "demo"))
    }

    @Test
    fun rejectsNonPublicEndpointsAndRedirectsWithoutContactingTarget() = runBlocking {
        var calls = 0
        val transport = discovery { request -> calls++; ok(request, result(1)) }
        for (bad in listOf(
            "http://tools.example.org/mcp", "https://localhost/mcp",
            "https://127.0.0.1/mcp", "https://[::1]/mcp",
            "https://user:password@tools.example.org/mcp",
            "https://tools.example.org/mcp?token=secret", "https://tools.example.org/mcp#fragment",
        )) assertNull(transport.discover(bad, "demo"))
        assertEquals(0, calls)
        val redirecting = discovery { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302)
                .message("Moved").header("Location", "http://localhost/private")
                .body("".toResponseBody()).build()
        }
        assertNull(redirecting.discover(url, "demo"))
    }

    @Test
    fun rejectsUnboundedOrRepeatedPagesAndUnsupportedResultType() = runBlocking {
        val large = discovery { req -> ok(req, result(1) + " ".repeat(256_001)) }
        assertNull(large.discover(url, "demo"))
        var repeatedRequests = 0
        val repeat = discovery { req ->
            repeatedRequests++
            val body = okio.Buffer().also { req.body!!.writeTo(it) }.readUtf8()
            val cursor = Json.parseToJsonElement(body).jsonObject["params"]!!.jsonObject[
                "cursor"
            ]?.jsonPrimitive?.content
            if (cursor == null) ok(req, result(1, "repeat"))
            else ok(req, result(2, "repeat"))
        }
        assertNull(repeat.discover(url, "demo"))
        assertEquals(2, repeatedRequests)
        val inputRequired = discovery { req ->
            ok(req, result(1).replace("\"complete\"", "\"input_required\""))
        }
        assertNull(inputRequired.discover(url, "demo"))
    }
}

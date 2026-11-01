package ais.tee.data.engine

import ais.tee.data.model.*
import java.util.Base64
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

internal const val CALL_ENDPOINT = "https://tools.example.org/mcp"
internal const val CALL_ARGUMENTS = "{\"value\":\"private input\"}"

internal fun toolCallCatalog(): McpToolCatalog = requireNotNull(McpToolCatalog.fromToolsListResponse(
    "demo", "1".repeat(32),
    """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"sample.echo","inputSchema":{"type":"object","properties":{"value":{"type":"string"}}}}]}}"""))

class McpToolCallTest {
    private val catalog = toolCallCatalog()
    private val name = catalog.browse().single().nativeName
    private fun review() = requireNotNull(McpToolCallReview.prepare(CALL_ENDPOINT, catalog, name, CALL_ARGUMENTS, 1))

    @Test fun onlyOneApprovedRequestCanReachTheServerWithExactArgumentsAndNoCredentials() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val request = chain.request()
            assertEquals(CALL_ENDPOINT, request.url.toString())
            assertEquals("tools/call", request.header("Mcp-Method"))
            assertEquals(REMOTE_TOOL_NAME, request.header("Mcp-Name"))
            assertEquals("2026-07-28", request.header("MCP-Protocol-Version"))
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            assertNull(request.header("Mcp-Session-Id"))
            val buffer = okio.Buffer()
            requireNotNull(request.body).writeTo(buffer)
            val params = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("params").jsonObject
            assertEquals(Json.parseToJsonElement(CALL_ARGUMENTS), params["arguments"])
            assertEquals(REMOTE_TOOL_NAME, params.getValue("name").jsonPrimitive.content)
            val meta = params.getValue("_meta").jsonObject
            assertEquals(JsonObject(emptyMap()), meta["io.modelcontextprotocol/clientCapabilities"])
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","content":[{"type":"text","text":"result"}]}}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val transport = McpHttpDiscovery(client)
        val review = review()
        assertEquals(McpToolCallOutcome.Failure(McpToolCallFailure.BLOCKED), transport.callToolChecked(review))
        assertEquals(0, requests)
        assertTrue(review.approveOnce())
        assertFalse(review.approveOnce())
        val outcome = transport.callToolChecked(review) as McpToolCallOutcome.Completed
        assertEquals(review.call.callId, outcome.result.callId)
        assertEquals("result", outcome.result.output.trim())
        assertEquals(McpToolCallOutcome.Failure(McpToolCallFailure.BLOCKED), transport.callToolChecked(review))
        assertEquals(1, requests)
        assertEquals("McpToolCallReview([redacted])", review.toString())
    }

    @Test fun aServerErrorDoesNotRetryOrMakeTheApprovalReusable() = runBlocking {
        var requests = 0
        val transport = McpHttpDiscovery(OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                .body("private error".toResponseBody()).build()
        }.build())
        val review = review().also { it.approveOnce() }
        assertEquals(McpToolCallOutcome.Failure(McpToolCallFailure.INVALID_RESPONSE), transport.callToolChecked(review))
        assertEquals(McpToolCallOutcome.Failure(McpToolCallFailure.BLOCKED), transport.callToolChecked(review))
        assertEquals(1, requests)
    }

    @Test fun namesUseTheRequiredSentinelEncodingWithoutHeaderInjection() {
        assertEquals(REMOTE_TOOL_NAME, mcpNameHeader("sample.echo"))
        for (name in listOf("世界", " x ", "x\r\nCookie: secret", "=?base64?literal?=")) {
            val header = mcpNameHeader(name)
            assertTrue(header.startsWith("=?base64?") && header.endsWith("?="))
            assertFalse(header.contains('\r') || header.contains('\n'))
            assertEquals(name, String(Base64.getDecoder().decode(header.removePrefix("=?base64?").removeSuffix("?=")), Charsets.UTF_8))
        }
    }

    @Test fun realHttp503RetryAfterZeroCannotReplayTheOneShotBody() = runBlocking {
        val requests = AtomicInteger()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 1_500
            val worker = Thread {
                repeat(2) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { return@Thread }
                    socket.use {
                        requests.incrementAndGet()
                        val reader = it.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                length = line.substringAfter(':').trim().toInt()
                            }
                        }
                        repeat(length) { reader.read() }
                        it.getOutputStream().write(("HTTP/1.1 503 Unavailable\r\n" +
                            "Retry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                    }
                }
            }.apply { start() }
            // Test-only loopback rewrite. Real OkHttp follow-up chain, original production body.
            val client = secureMcpHttpClient().newBuilder().dns(Dns.SYSTEM).build()
            val factory = Call.Factory { request ->
                assertTrue(requireNotNull(request.body).isOneShot())
                client.newCall(request.newBuilder().url("http://127.0.0.1:${server.localPort}/mcp").build())
            }
            val review = review().also { it.approveOnce() }
            assertEquals(McpToolCallOutcome.Failure(McpToolCallFailure.INVALID_RESPONSE),
                McpHttpDiscovery(factory).callToolChecked(review))
            worker.join(3_000)
            assertFalse(worker.isAlive)
            assertEquals(1, requests.get())
        }
    }
}

private const val REMOTE_TOOL_NAME = "sample.echo"

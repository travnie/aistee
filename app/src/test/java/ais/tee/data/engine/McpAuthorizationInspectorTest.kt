package ais.tee.data.engine

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

private const val RESOURCE = "https://tools.example.org/mcp"
private const val ISSUER = "https://auth.example.org/tenant"
private const val PRM = """{"resource":"https://tools.example.org/mcp","authorization_servers":["https://auth.example.org/tenant"],"scopes_supported":["tools:read"]}"""
private const val AS_METADATA = """{"issuer":"https://auth.example.org/tenant","authorization_endpoint":"https://auth.example.org/authorize","token_endpoint":"https://auth.example.org/token","code_challenge_methods_supported":["S256"],"client_id_metadata_document_supported":true}"""

class McpAuthorizationInspectorTest {
    private fun client(handler: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> handler(chain.request()) }).build()

    private fun response(request: Request, body: String, code: Int = 200): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun unauthorizedDiscoveryParsesBearerChallengeWithoutFollowingIt() = runBlocking {
        var requests = 0
        val transport = McpHttpDiscovery(client { request ->
            requests++
            response(request, "Do not display this", 401).newBuilder()
                .addHeader("WWW-Authenticate", "Basic realm=\"private, realm\"")
                .addHeader("WWW-Authenticate", "Bearer resource_metadata=\"https://identity.example.org/resource\", scope=\"files:read tools:write\"")
                .build()
        })
        val outcome = transport.discoverChecked(RESOURCE, "demo") as McpDiscoveryOutcome.AuthorizationRequired
        assertEquals(1, requests)
        assertEquals(RESOURCE, outcome.challenge.endpoint)
        assertEquals("https://identity.example.org/resource", outcome.challenge.resourceMetadata)
        assertEquals(listOf("files:read", "tools:write"), outcome.challenge.scopes)
        assertFalse(outcome.toString().contains("identity.example.org"))
    }

    @Test fun resourceFallbackAndChallengeScopesAreBoundToTheEndpoint() = runBlocking {
        val visited = mutableListOf<String>()
        val inspector = McpAuthorizationInspector(client { request ->
            visited += request.url.toString()
            assertEquals("GET", request.method)
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            if (request.url.encodedPath.endsWith("/mcp")) response(request, "", 404)
            else response(request, PRM)
        })
        val result = inspector.inspectResource(McpAuthorizationChallenge(RESOURCE, scopes = listOf("tools:write")))
            as McpAuthorizationInspection.Found
        assertEquals(listOf("https://tools.example.org/.well-known/oauth-protected-resource/mcp",
            "https://tools.example.org/.well-known/oauth-protected-resource"), visited)
        assertEquals(listOf("tools:write"), result.metadata.scopes)
        assertEquals(listOf(ISSUER), result.metadata.authorizationServers)
        assertFalse(result.toString().contains("tools.example.org"))
    }

    @Test fun explicitCrossOriginMetadataIsUsedWithoutGuessingFallbacks() = runBlocking {
        val visited = mutableListOf<String>()
        val inspector = McpAuthorizationInspector(client { request ->
            visited += request.url.toString()
            response(request, PRM)
        })
        assertTrue(inspector.inspectResource(McpAuthorizationChallenge(RESOURCE,
            "https://identity.example.org/resource")) is McpAuthorizationInspection.Found)
        assertEquals(listOf("https://identity.example.org/resource"), visited)
    }

    @Test fun hostileMetadataLocationsAreRejectedBeforeNetworking() = runBlocking {
        var requests = 0
        val inspector = McpAuthorizationInspector(client { request -> requests++; response(request, PRM) })
        listOf("http://identity.example.org/metadata", "https://127.0.0.1/metadata", "https://host.local/metadata",
            "https://user:password@identity.example.org/metadata", "https://identity.example.org/?secret=x",
            "https://identity.example.org/#fragment").forEach { location ->
            assertInvalid(inspector.inspectResource(McpAuthorizationChallenge(RESOURCE, location)))
        }
        assertEquals(0, requests)
    }

    @Test fun invalidResourceAndScopeMetadataFailClosed() = runBlocking {
        listOf(PRM.replace(RESOURCE, "https://tools.example.org/other"),
            PRM.replace(ISSUER, "http://auth.example.org/tenant"),
            PRM.replace("[\"$ISSUER\"]", "[]"),
            PRM.replace("tools:read", "invalid scope"),
            "{\"nested\":".repeat(20) + "0" + "}".repeat(20),
            " ".repeat(65_537) + PRM).forEach { body ->
            var requests = 0
            val inspector = McpAuthorizationInspector(client { request -> requests++; response(request, body) })
            assertInvalid(inspector.inspectResource(McpAuthorizationChallenge(RESOURCE)))
            assertEquals(1, requests)
        }
    }

    @Test fun absentDocumentsMayFallbackButRedirectsAndMalformedDocumentsMayNot() = runBlocking {
        listOf(200, 302, 401, 500).forEach { code ->
            var requests = 0
            val inspector = McpAuthorizationInspector(client { request -> requests++; response(request, "{bad", code) })
            assertInvalid(inspector.inspectResource(McpAuthorizationChallenge(RESOURCE)))
            assertEquals(1, requests)
        }
    }

    @Test fun selectedIssuerUsesAllOrderedOAuthAndOidcLocations() = runBlocking {
        val visited = mutableListOf<String>()
        val inspector = McpAuthorizationInspector(client { request ->
            visited += request.url.toString()
            when {
                request.url.host == "tools.example.org" -> response(request, PRM)
                request.url.encodedPath == "/tenant/.well-known/openid-configuration" -> response(request, AS_METADATA)
                else -> response(request, "", 404)
            }
        })
        val resource = (inspector.inspectResource(McpAuthorizationChallenge(RESOURCE)) as McpAuthorizationInspection.Found).metadata
        assertEquals(1, visited.size)
        val result = inspector.inspectServer(resource, ISSUER) as McpAuthorizationInspection.Found
        assertEquals(listOf("https://auth.example.org/.well-known/oauth-authorization-server/tenant",
            "https://auth.example.org/.well-known/openid-configuration/tenant",
            "https://auth.example.org/tenant/.well-known/openid-configuration"), visited.drop(1))
        assertEquals(ISSUER, result.metadata.issuer)
        assertTrue(result.metadata.supportsS256)
        assertTrue(result.metadata.supportsClientMetadata)
        assertFalse(result.metadata.hasRegistrationEndpoint)
        assertFalse(result.toString().contains("auth.example.org"))
    }

    @Test fun unlistedIssuersAndMismatchedIssuerMetadataAreRejected() = runBlocking {
        var requests = 0
        val inspector = McpAuthorizationInspector(client { request ->
            requests++
            response(request, if (request.url.host == "tools.example.org") PRM
                else AS_METADATA.replace(ISSUER, "$ISSUER/"))
        })
        val resource = (inspector.inspectResource(McpAuthorizationChallenge(RESOURCE)) as McpAuthorizationInspection.Found).metadata
        assertInvalid(inspector.inspectServer(resource, "https://unlisted.example.org"))
        assertEquals(1, requests)
        assertInvalid(inspector.inspectServer(resource, ISSUER))
        assertEquals(2, requests)
    }

    @Test fun transportFailuresExposeOnlyCoarseReasons() = runBlocking {
        val inspector = McpAuthorizationInspector(client { throw IOException("secret URL and body") })
        assertEquals(McpAuthorizationInspection.Failed(McpAuthorizationFailure.NETWORK_FAILURE),
            inspector.inspectResource(McpAuthorizationChallenge(RESOURCE)))
        val broken = McpAuthorizationInspector(Call.Factory { error("private factory details") })
        assertInvalid(broken.inspectResource(McpAuthorizationChallenge(RESOURCE)))
    }

    @Test fun cancellingInspectionCancelsTheActiveCall() = runBlocking {
        val enqueued = CompletableDeferred<Unit>()
        var cancelled = false
        val inspector = McpAuthorizationInspector(Call.Factory { request ->
            object : Call {
                override fun request() = request
                override fun execute(): Response = error("Async only")
                override fun enqueue(responseCallback: Callback) { enqueued.complete(Unit) }
                override fun cancel() { cancelled = true }
                override fun isExecuted() = true
                override fun isCanceled() = cancelled
                override fun timeout() = Timeout.NONE
                override fun clone(): Call = error("No retry")
            }
        })
        val pending = async { inspector.inspectResource(McpAuthorizationChallenge(RESOURCE)) }
        enqueued.await()
        pending.cancelAndJoin()
        assertTrue(cancelled)
    }

    private fun assertInvalid(result: McpAuthorizationInspection<*>) = assertEquals(
        McpAuthorizationInspection.Failed(McpAuthorizationFailure.INVALID_METADATA), result)
}

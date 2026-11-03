package ais.tee.data.engine

import java.io.IOException
import java.io.InterruptedIOException
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
import org.junit.Assert.*
import org.junit.Test

private const val RESOURCE_METADATA_URL = "https://tools.example.org/.well-known/oauth-protected-resource/mcp"
private const val ISSUER_METADATA_URL = "https://auth.example.org/.well-known/oauth-authorization-server/tenant"
private const val METADATA_ADDRESS = "https://identity.example.org/resource"
private const val WRITE_SCOPE = "tools:write"
private const val RESOURCE_HOST = "tools.example.org"
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
        assertEquals(METADATA_ADDRESS, outcome.challenge.resourceMetadata)
        assertEquals(listOf("files:read", WRITE_SCOPE), outcome.challenge.scopes)
        assertFalse(outcome.toString().contains("identity.example.org"))
    }

    @Test fun multipleBearerChallengesKeepTheSelectedMetadataAndItsScopesTogether() = runBlocking {
        val transport = McpHttpDiscovery(client { request ->
            response(request, "", 401).newBuilder()
                .addHeader("WWW-Authenticate", "Bearer realm=\"other\", scope=\"tools:read\"")
                .addHeader("WWW-Authenticate", "Bearer resource_metadata=\"$METADATA_ADDRESS\", scope=\"$WRITE_SCOPE\"")
                .build()
        })
        val outcome = transport.discoverChecked(RESOURCE, "demo") as McpDiscoveryOutcome.AuthorizationRequired
        assertEquals(METADATA_ADDRESS, outcome.challenge.resourceMetadata)
        assertEquals(listOf(WRITE_SCOPE), outcome.challenge.scopes)
    }

    @Test fun okhttpCallTimeoutIsReportedAsTimeout() = runBlocking {
        val inspector = McpAuthorizationInspector(client { throw InterruptedIOException("private timeout details") })
        assertEquals(McpAuthorizationInspection.Failed(McpAuthorizationFailure.TIMEOUT),
            inspector.inspectResource(McpAuthorizationChallenge(RESOURCE)))
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
        val result = inspector.inspectResource(McpAuthorizationChallenge(RESOURCE, scopes = listOf(WRITE_SCOPE)))
            as McpAuthorizationInspection.Found
        assertEquals(listOf(RESOURCE_METADATA_URL,
            "https://tools.example.org/.well-known/oauth-protected-resource"), visited)
        assertEquals(listOf(WRITE_SCOPE), result.metadata.scopes)
        assertEquals(listOf(ISSUER), result.metadata.authorizationServers)
        assertFalse(result.toString().contains(RESOURCE_HOST))
    }

    @Test fun explicitCrossOriginMetadataIsUsedWithoutGuessingFallbacks() = runBlocking {
        val visited = mutableListOf<String>()
        val inspector = McpAuthorizationInspector(client { request ->
            visited += request.url.toString()
            response(request, PRM)
        })
        assertTrue(inspector.inspectResource(McpAuthorizationChallenge(RESOURCE,
            METADATA_ADDRESS)) is McpAuthorizationInspection.Found)
        assertEquals(listOf(METADATA_ADDRESS), visited)
    }

    @Test fun hostileMetadataLocationsAreRejectedBeforeNetworking() = runBlocking {
        var requests = 0
        val inspector = McpAuthorizationInspector(client { request -> requests++; response(request, PRM) })
        listOf("http://identity.example.org/metadata", "https://127.0.0.1/metadata", "https://host.local/metadata",
            "https://user:password@identity.example.org/metadata",
            "https://identity.example.org/#fragment").forEach { location ->
            assertInvalid(inspector.inspectResource(McpAuthorizationChallenge(RESOURCE, location)))
        }
        assertEquals(0, requests)
    }

    @Test fun metadataAndOAuthEndpointQueriesArePreserved() = runBlocking {
        val visited = mutableListOf<String>()
        val inspector = McpAuthorizationInspector(client { request ->
            visited += request.url.toString()
            response(request, if (request.url.toString() == "$METADATA_ADDRESS?tenant=one") PRM
                else AS_METADATA.replace("/authorize", "/authorize?tenant=one").replace("/token", "/token?tenant=one"))
        })
        val address = "$METADATA_ADDRESS?tenant=one"
        val resource = (inspector.inspectResource(McpAuthorizationChallenge(RESOURCE, address)) as McpAuthorizationInspection.Found).metadata
        val server = inspector.inspectServer(resource, ISSUER) as McpAuthorizationInspection.Found
        assertEquals(address, visited.first())
        assertEquals("https://auth.example.org/authorize?tenant=one", server.metadata.authorizationEndpoint)
        assertEquals("https://auth.example.org/token?tenant=one", server.metadata.tokenEndpoint)
        assertEquals(2, visited.size)
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
                request.url.host == RESOURCE_HOST -> response(request, PRM)
                request.url.encodedPath == "/tenant/.well-known/openid-configuration" -> response(request, AS_METADATA)
                else -> response(request, "", 404)
            }
        })
        val resource = (inspector.inspectResource(McpAuthorizationChallenge(RESOURCE)) as McpAuthorizationInspection.Found).metadata
        assertEquals(1, visited.size)
        val result = inspector.inspectServer(resource, ISSUER) as McpAuthorizationInspection.Found
        assertEquals(listOf(ISSUER_METADATA_URL,
            "https://auth.example.org/.well-known/openid-configuration/tenant",
            "https://auth.example.org/tenant/.well-known/openid-configuration"), visited.drop(1))
        assertEquals(ISSUER, result.metadata.issuer)
        assertTrue(result.metadata.supportsS256)
        assertTrue(result.metadata.supportsClientMetadata)
        assertFalse(result.metadata.hasRegistrationEndpoint)
        assertFalse(result.toString().contains("auth.example.org"))
    }

    @Test fun omittedPkceAdvertisementIsValidMetadataButNotS256() = runBlocking {
        val withoutPkce = AS_METADATA.replace(",\"code_challenge_methods_supported\":[\"S256\"]", "")
        val inspector = McpAuthorizationInspector(client { request ->
            response(request, if (request.url.host == RESOURCE_HOST) PRM else withoutPkce)
        })
        val resource = (inspector.inspectResource(McpAuthorizationChallenge(RESOURCE))
            as McpAuthorizationInspection.Found).metadata
        val server = inspector.inspectServer(resource, ISSUER) as McpAuthorizationInspection.Found
        assertFalse(server.metadata.supportsS256)
        assertEquals(ISSUER, server.metadata.issuer)
    }

    @Test fun trailingSlashesAreRemovedOnlyFromDiscoveryPaths() = runBlocking {
        val visited = mutableListOf<String>()
        val inspector = McpAuthorizationInspector(client { request ->
            visited += request.url.toString()
            response(request, if (request.url.host == RESOURCE_HOST)
                PRM.replace(RESOURCE, "$RESOURCE/").replace(ISSUER, "$ISSUER/")
            else AS_METADATA.replace(ISSUER, "$ISSUER/"))
        })
        val resource = (inspector.inspectResource(McpAuthorizationChallenge("$RESOURCE/")) as McpAuthorizationInspection.Found).metadata
        val server = inspector.inspectServer(resource, "$ISSUER/") as McpAuthorizationInspection.Found
        assertEquals(listOf(RESOURCE_METADATA_URL,
            ISSUER_METADATA_URL), visited)
        assertEquals("$RESOURCE/", resource.resource)
        assertEquals("$ISSUER/", server.metadata.issuer)
    }

    @Test fun unlistedIssuersAndMismatchedIssuerMetadataAreRejected() = runBlocking {
        var requests = 0
        val inspector = McpAuthorizationInspector(client { request ->
            requests++
            response(request, if (request.url.host == RESOURCE_HOST) PRM
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
        val unusedClient = OkHttpClient()
        val inspector = McpAuthorizationInspector(Call.Factory { request ->
            val delegate = unusedClient.newCall(request)
            object : Call by delegate {
                override fun enqueue(responseCallback: Callback) { enqueued.complete(Unit) }
                override fun cancel() { cancelled = true; delegate.cancel() }
                override fun isCanceled() = cancelled
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

package ais.tee.data.security

import ais.tee.data.security.ChatGptPlanLoopback.Outcome
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real loopback sockets; every test is time-bounded so a listener bug cannot hang CI. */
class ChatGptPlanLoopbackTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val loopback = ChatGptPlanLoopback.open()
    private val pending = ChatGptPlanOAuth.prepare("urn:uuid:test-host", loopback.port, "oaiapp_test")
    private val state = URI(pending.authorizationUrl).rawQuery.split('&')
        .first { it.startsWith("state=") }.substringAfter('=')

    @After
    fun tearDown() {
        loopback.close()
        executor.shutdownNow()
    }

    @Test(timeout = 15_000)
    fun ignoresProbesAndForgedStateThenReturnsUnconsumedCallback() {
        val result = awaitAsync()
        assertTrue(get("/favicon.ico").startsWith("HTTP/1.1 404"))
        assertTrue(get("/auth/callback?code=forged&state=wrong").startsWith("HTTP/1.1 400"))
        assertTrue(send("POST /auth/callback?code=secret-code&state=$state HTTP/1.1\r\n\r\n")
            .startsWith("HTTP/1.1 405"))
        assertTrue(send("garbage\r\n\r\n").startsWith("HTTP/1.1 400"))
        val response = get("/auth/callback?code=secret-code&state=$state")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("Cache-Control: no-store"))
        assertFalse(response.contains("secret-code"))
        assertFalse(response.contains(state))

        val outcome = result.get(5, TimeUnit.SECONDS) as Outcome.Received
        assertFalse(outcome.toString().contains("secret-code"))
        val callback = pending.acceptCallback(outcome.callbackUrl) as ChatGptPlanOAuth.Callback.Code
        assertEquals("secret-code", callback.authorizationCode)
        assertEquals("oaiapp_test", callback.clientId)
        assertPortClosed()
    }

    @Test(timeout = 15_000)
    fun accessDeniedEndsAndConsumesAttempt() {
        val result = awaitAsync()
        assertTrue(get("/auth/callback?error=access_denied&state=$state").startsWith("HTTP/1.1 200"))
        assertEquals(Outcome.Denied, result.get(5, TimeUnit.SECONDS))
        assertEquals(
            ChatGptPlanOAuth.Callback.Invalid,
            pending.acceptCallback(pending.redirectUri + "?code=late&state=$state"),
        )
    }

    @Test(timeout = 15_000)
    fun providerErrorEndsAttemptAsFailed() {
        val result = awaitAsync()
        get("/auth/callback?error=server_error&state=$state")
        assertEquals(Outcome.Failed, result.get(5, TimeUnit.SECONDS))
    }

    @Test(timeout = 15_000)
    fun deadlineClosesPort() {
        assertEquals(Outcome.TimedOut, loopback.await(pending, 200))
        assertPortClosed()
    }

    @Test(timeout = 15_000)
    fun closeFromAnotherThreadUnblocksAwait() {
        val result = awaitAsync()
        Thread.sleep(100)
        loopback.close()
        val error = assertThrows(ExecutionException::class.java) {
            result.get(5, TimeUnit.SECONDS)
        }
        assertTrue(error.cause is IOException)
    }

    private fun awaitAsync(): Future<Outcome> = executor.submit(Callable { loopback.await(pending, 10_000) })

    private fun get(target: String) = send("GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

    private fun send(request: String): String =
        Socket(LOCALHOST, loopback.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            String(socket.getInputStream().readBytes(), StandardCharsets.UTF_8)
        }

    private fun assertPortClosed() {
        assertThrows(IOException::class.java) { Socket(LOCALHOST, loopback.port).close() }
    }

    private companion object {
        val LOCALHOST: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}

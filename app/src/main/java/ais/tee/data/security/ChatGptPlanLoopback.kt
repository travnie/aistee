package ais.tee.data.security

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * One-attempt SIWC redirect listener on IPv4 `127.0.0.1` (the only documented redirect host).
 * Order: [open], [ChatGptPlanOAuth.prepare] with [port], launch the browser, then [await].
 *
 * Never logs or echoes the request: its query carries the authorization code. Probes, wrong
 * paths and forged state are answered and ignored so they cannot end the real attempt.
 */
internal class ChatGptPlanLoopback private constructor(private val server: ServerSocket) : Closeable {
    val port: Int = server.localPort

    sealed interface Outcome {
        /** Pass to [ChatGptPlanTokenExchange.complete]; this listener did not consume it. */
        class Received(val callbackUrl: String) : Outcome {
            override fun toString(): String = "Received(<redacted>)"
        }
        data object Denied : Outcome
        data object Failed : Outcome
        data object TimedOut : Outcome
    }

    /**
     * Blocks until the attempt ends or [timeoutMillis] passes, then closes the port.
     * [close] from another thread (e.g. coroutine cancellation) unblocks with [IOException].
     */
    fun await(pending: ChatGptPlanOAuth.Pending, timeoutMillis: Long): Outcome {
        require(timeoutMillis > 0)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        try {
            while (true) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) return Outcome.TimedOut
                server.soTimeout = remaining.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    return Outcome.TimedOut
                }
                active = socket
                if (server.isClosed) socket.close() // close() raced the accept.
                val outcome = socket.use {
                    try {
                        it.soTimeout = minOf(READ_TIMEOUT_MS.toLong(), remaining).toInt()
                        handle(it, pending)
                    } catch (_: IOException) {
                        null // Broken or slow client; keep waiting for the browser.
                    } finally {
                        active = null
                    }
                }
                if (outcome != null) return outcome
            }
        } finally {
            close()
        }
    }

    @Volatile private var active: Socket? = null

    /** Also drops an in-flight client so cancellation never waits on a slow read. */
    override fun close() {
        server.close()
        active?.close()
    }

    private fun handle(socket: Socket, pending: ChatGptPlanOAuth.Pending): Outcome? {
        val requestLine = readRequestLine(socket.getInputStream())
        val parts = requestLine?.split(' ')
        if (parts == null || parts.size != 3 || !parts[2].startsWith("HTTP/1.") || !parts[1].startsWith("/")) {
            respond(socket, "400 Bad Request", PAGE_INVALID)
            return null
        }
        if (parts[0] != "GET") {
            respond(socket, "405 Method Not Allowed", PAGE_INVALID)
            return null
        }
        val target = parts[1]
        if (target.substringBefore('?') != ChatGptPlanOAuth.CALLBACK_PATH) {
            respond(socket, "404 Not Found", PAGE_INVALID)
            return null
        }
        val callbackUrl = "http://127.0.0.1:$port$target"
        return when (pending.peekCallback(callbackUrl)) {
            null -> {
                respond(socket, "400 Bad Request", PAGE_INVALID)
                null
            }
            is ChatGptPlanOAuth.Callback.Code -> {
                respond(socket, "200 OK", PAGE_DONE)
                Outcome.Received(callbackUrl)
            }
            ChatGptPlanOAuth.Callback.AccessDenied -> {
                pending.acceptCallback(callbackUrl)
                respond(socket, "200 OK", PAGE_CANCELLED)
                Outcome.Denied
            }
            ChatGptPlanOAuth.Callback.Invalid -> {
                pending.acceptCallback(callbackUrl)
                respond(socket, "200 OK", PAGE_FAILED)
                Outcome.Failed
            }
        }
    }

    /** Request line only; drains headers up to [MAX_HEADER_BYTES]. Null when oversized or cut. */
    private fun readRequestLine(input: InputStream): String? {
        val bytes = ByteArray(MAX_HEADER_BYTES)
        var size = 0
        while (size < bytes.size) {
            val next = input.read()
            if (next == -1) return null
            bytes[size++] = next.toByte()
            if (size >= 4 && bytes[size - 4] == CR && bytes[size - 3] == LF &&
                bytes[size - 2] == CR && bytes[size - 1] == LF
            ) {
                val head = String(bytes, 0, size, StandardCharsets.US_ASCII)
                return head.substringBefore("\r\n")
            }
        }
        return null
    }

    /** Best-effort: the outcome is already decided, a vanished browser must not lose it. */
    private fun respond(socket: Socket, status: String, body: String) {
        val content = body.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${content.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        try {
            socket.getOutputStream().apply {
                write(head.toByteArray(StandardCharsets.US_ASCII))
                write(content)
                flush()
            }
        } catch (_: IOException) {
            // Client gone; nothing to tell it.
        }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_HEADER_BYTES = 8_192
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
        private const val PAGE_DONE = "<!doctype html><title>Aistee</title>" +
            "<p>Sign-in received. You can return to Aistee.</p>"
        private const val PAGE_CANCELLED = "<!doctype html><title>Aistee</title>" +
            "<p>Sign-in cancelled. You can return to Aistee.</p>"
        private const val PAGE_FAILED = "<!doctype html><title>Aistee</title>" +
            "<p>Sign-in failed. Return to Aistee and try again.</p>"
        private const val PAGE_INVALID = "<!doctype html><title>Aistee</title><p>Invalid request.</p>"

        /** Binds the IPv4 literal; `getLoopbackAddress()` may resolve to `::1`. */
        fun open(): ChatGptPlanLoopback =
            ChatGptPlanLoopback(ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))
    }
}

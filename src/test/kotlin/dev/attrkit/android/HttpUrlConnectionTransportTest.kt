package dev.attrkit.android

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.attrkit.core.HttpRequest
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpUrlConnectionTransportTest {
    private class Seen(val method: String, val headers: Map<String, String>, val body: ByteArray)

    private lateinit var server: HttpServer
    private val seen = AtomicReference<Seen>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/echo") { exchange ->
            seen.set(
                Seen(
                    exchange.requestMethod,
                    exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                    exchange.requestBody.readBytes(),
                ),
            )
            exchange.responseHeaders.add("X-Reply", "yes")
            reply(exchange, 200, "ok")
        }
        server.createContext("/fail") { reply(it, 503, "unavailable") }
        server.createContext("/slow") { exchange ->
            Thread.sleep(1_500)
            runCatching { reply(exchange, 200, "late") }
        }
        server.createContext("/moved") { exchange ->
            exchange.responseHeaders.add("Location", "$base/echo")
            reply(exchange, 302, "")
        }
        server.start()
    }

    @After
    fun stopServer() = server.stop(0)

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun transport(
        connect: Int = 2_000,
        read: Int = 2_000,
        mainThread: Boolean = false,
        opened: AtomicReference<HttpURLConnection>? = null,
    ) = HttpUrlConnectionTransport(
        connectTimeoutMillis = connect,
        readTimeoutMillis = read,
        isMainThread = { mainThread },
        open = { url -> (URL(url).openConnection() as HttpURLConnection).also { opened?.set(it) } },
    )

    @Test
    fun aPostCarriesItsMethodHeadersAndBodyAndKeepsTheResponseHeaders() {
        val body = byteArrayOf(0x1f, 0x8b.toByte(), 0, 1, 2)
        val response = transport().execute(
            HttpRequest("POST", "$base/echo", mapOf("Authorization" to "AttriKit-Publishable k", "X-Test" to "1"), body),
        )

        assertEquals(200, response.statusCode)
        assertArrayEquals("ok".toByteArray(), response.body)
        // The JDK server recases the name it was given, so only a case-insensitive lookup finds it.
        assertEquals("yes", response.headers["x-REPLY"])
        val request = seen.get()
        assertEquals("POST", request.method)
        assertEquals("AttriKit-Publishable k", request.headers["authorization"])
        assertEquals("1", request.headers["x-test"])
        assertArrayEquals(body, request.body)
    }

    @Test
    fun anErrorStatusIsAnAnswerWithItsBodyNotAnException() {
        val response = transport().execute(HttpRequest("POST", "$base/fail", emptyMap(), byteArrayOf(1)))
        assertEquals(503, response.statusCode)
        assertArrayEquals("unavailable".toByteArray(), response.body)
    }

    @Test
    fun aServerThatStopsAnsweringTimesOutInsteadOfHoldingTheWorker() {
        val started = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) {
            transport(read = 200).execute(HttpRequest("POST", "$base/slow", emptyMap(), byteArrayOf(1)))
        }
        // The server would have answered after 1.5 s; failing at the read timeout is the point.
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_200)
    }

    @Test
    fun bothTimeoutsAreSetOnTheConnectionAndRedirectsAreOff() {
        val opened = AtomicReference<HttpURLConnection>()
        transport(connect = 1_234, read = 4_321, opened = opened)
            .execute(HttpRequest("POST", "$base/echo", emptyMap(), byteArrayOf(1)))

        assertEquals(1_234, opened.get().connectTimeout)
        assertEquals(4_321, opened.get().readTimeout)
        assertFalse(opened.get().instanceFollowRedirects)
    }

    @Test
    fun aRedirectIsReturnedAndNotFollowed() {
        val response = transport().execute(HttpRequest("POST", "$base/moved", emptyMap(), byteArrayOf(1)))
        assertEquals(302, response.statusCode)
        assertEquals(null, seen.get())
    }

    @Test
    fun theMainThreadIsRefusedBeforeAnySocketIsOpened() {
        val opened = AtomicReference<HttpURLConnection>()
        assertThrows(IllegalStateException::class.java) {
            transport(mainThread = true, opened = opened)
                .execute(HttpRequest("POST", "$base/echo", emptyMap(), byteArrayOf(1)))
        }
        assertEquals(null, opened.get())
    }
}

package dev.attrkit.android

import android.os.Looper
import dev.attrkit.core.HttpRequest
import dev.attrkit.core.HttpResponse
import dev.attrkit.core.HttpTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * HttpURLConnection carries the SDK's three POSTs, so the library adds no HTTP dependency to the
 * host app. Both timeouts are set on every call: a blocked socket would otherwise hold the single
 * worker thread, and with it every later flush, until the OS gives up.
 *
 * Any failure is thrown rather than mapped to a status; the core treats a throw as "offline" and
 * retries on its own ladder.
 */
internal class HttpUrlConnectionTransport(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val isMainThread: () -> Boolean = ::onMainThread,
    private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) : HttpTransport {

    override fun execute(request: HttpRequest): HttpResponse {
        // Network on the main thread throws NetworkOnMainThreadException in a host with StrictMode
        // and an ANR in one without. Refusing is cheaper than finding out in someone's app.
        check(!isMainThread()) { "AttriKit network I/O must not run on the main thread" }
        val connection = open(request.url)
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            // A redirect would replay the Authorization header at whatever host answered.
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            if (request.body.isNotEmpty()) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(request.body.size)
                connection.outputStream.use { it.write(request.body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use(::readBounded) ?: ByteArray(0)
            return HttpResponse(status, body, responseHeaders(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun responseHeaders(connection: HttpURLConnection): Map<String, String> {
        // Header names are case-insensitive, and servers and proxies disagree on their casing.
        val headers = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        for ((name, values) in connection.headerFields) {
            // The status line is keyed null.
            if (name != null && values.isNotEmpty()) headers[name] = values.joinToString(", ")
        }
        return headers
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            // The ingest answers are small JSON documents; a larger one is not ours.
            if (out.size() + read > MAX_RESPONSE_BYTES) throw IOException("response too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 20_000
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}

internal fun onMainThread(): Boolean = Looper.getMainLooper() === Looper.myLooper()

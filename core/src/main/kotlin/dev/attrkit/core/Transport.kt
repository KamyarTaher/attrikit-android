package dev.attrkit.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object Gzip {
    fun compress(input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(input) }
        return output.toByteArray()
    }

    fun decompress(input: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() }
}

internal class RequestFactory(
    endpoint: String,
    private val apiKey: String,
    private val idGenerator: IdGenerator,
) {
    private val baseUrl: String

    init {
        val parsed = URI(endpoint)
        require(parsed.scheme == "https" || parsed.scheme == "http") {
            "endpoint must be HTTP(S)"
        }
        require(parsed.host != null) { "endpoint must have a host" }
        require(parsed.rawQuery == null && parsed.rawFragment == null) {
            "endpoint must not contain a query or fragment"
        }
        baseUrl = endpoint.trimEnd('/')
    }

    fun post(path: String, jsonBody: String, idempotencyKey: String): HttpRequest {
        val compressed = Gzip.compress(jsonBody.toByteArray(StandardCharsets.UTF_8))
        return HttpRequest(
            method = "POST",
            url = "$baseUrl/${path.trimStart('/')}",
            headers = linkedMapOf(
                "Authorization" to "AttriKit-Publishable $apiKey",
                "X-AttriKit-SDK" to "android/$ATTRIKIT_ANDROID_SDK_VERSION",
                "X-AttriKit-Request-ID" to idGenerator.next().toString().lowercase(),
                "Content-Type" to "application/json",
                "Content-Encoding" to "gzip",
                "Idempotency-Key" to idempotencyKey,
                "X-AttriKit-Signature" to signature(compressed),
            ),
            body = compressed,
        )
    }

    /**
     * A bodiless read. Carries the same headers as [post], the signature over the empty body and a
     * fresh Idempotency-Key as the iOS SDK's GET does, plus `If-None-Match` when a validator is held.
     */
    fun get(path: String, etag: String?): HttpRequest {
        val headers = linkedMapOf(
            "Authorization" to "AttriKit-Publishable $apiKey",
            "X-AttriKit-SDK" to "android/$ATTRIKIT_ANDROID_SDK_VERSION",
            "X-AttriKit-Request-ID" to idGenerator.next().toString().lowercase(),
            "Idempotency-Key" to idGenerator.next().toString().lowercase(),
            "X-AttriKit-Signature" to signature(ByteArray(0)),
        )
        if (etag != null) headers["If-None-Match"] = etag
        return HttpRequest(
            method = "GET",
            url = "$baseUrl/${path.trimStart('/')}",
            headers = headers,
        )
    }

    private fun signature(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body))
        return "v1=$encoded"
    }
}

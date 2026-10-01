package dev.attrkit.core

import java.time.Instant
import java.util.UUID

/** Wall-clock dependency. Implementations must return UTC instants. */
fun interface Clock {
    fun now(): Instant
}

/**
 * Minimal atomic persistence contract.
 *
 * A complete map is committed as one transaction. The Android adapter maps this to one
 * SharedPreferences.Editor.commit(), which is required for crash-safe queue transitions.
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun write(changes: Map<String, String?>)
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is HttpRequest &&
            method == other.method &&
            url == other.url &&
            headers == other.headers &&
            body.contentEquals(other.body)

    override fun hashCode(): Int =
        31 * (31 * (31 * method.hashCode() + url.hashCode()) + headers.hashCode()) + body.contentHashCode()
}

data class HttpResponse(
    val statusCode: Int,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is HttpResponse &&
            statusCode == other.statusCode &&
            body.contentEquals(other.body) &&
            headers == other.headers

    override fun hashCode(): Int =
        31 * (31 * statusCode + body.contentHashCode()) + headers.hashCode()
}

fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

/** Raw value returned by Google Play. Parsing and normalization belong to the core. */
data class RawInstallReferrer(val value: String)

fun interface ReferrerClient {
    fun fetchInstallReferrer(): RawInstallReferrer?
}

data class AdvertisingIdentifiers(
    val gaid: String? = null,
    val appSetId: String? = null,
)

/**
 * The core calls this provider only while consent is TRACKING_GRANTED: for the first-open, once
 * per launch, and when tracking is granted. It runs on the thread that called the core's start,
 * setConsent or work, never the main thread in the Android library.
 * Platform implementations must also suppress a zero/LAT advertising ID.
 */
fun interface AdvertisingIdProvider {
    fun identifiers(): AdvertisingIdentifiers
}

fun interface IdGenerator {
    fun next(): UUID
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}

object RandomIdGenerator : IdGenerator {
    override fun next(): UUID = UUID.randomUUID()
}

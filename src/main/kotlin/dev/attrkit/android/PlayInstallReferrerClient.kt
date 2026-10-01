package dev.attrkit.android

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import dev.attrkit.core.RawInstallReferrer
import dev.attrkit.core.ReferrerClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The two calls of Play's referrer service this SDK makes, so a test can stand in for Play. */
internal interface InstallReferrerConnection {
    /** Starts binding; [onFinished] receives an `InstallReferrerResponse` code, once. */
    fun start(onFinished: (responseCode: Int) -> Unit)

    /** Valid only after an OK response. */
    fun referrer(): String?

    fun end()
}

/**
 * Reads Google Play's install referrer, bounded: the bind is awaited on a latch for at most
 * [timeoutMillis]. Play answers on the main looper, so a caller that is itself the main thread
 * would wait on a callback it is blocking; that case returns null instead of deadlocking.
 *
 * Every failure Play can report (service unavailable, feature unsupported, developer error, a
 * disconnect) is absence, never an exception: the core registers an install without a referrer
 * rather than not at all.
 */
internal class PlayInstallReferrerClient(
    private val connect: () -> InstallReferrerConnection,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val isMainThread: () -> Boolean = ::onMainThread,
) : ReferrerClient {

    constructor(context: Context) : this({ RealConnection(context.applicationContext) })

    override fun fetchInstallReferrer(): RawInstallReferrer? {
        if (isMainThread()) return null
        val connection = try {
            connect()
        } catch (_: Exception) {
            return null
        }
        val latch = CountDownLatch(1)
        val responseCode = AtomicInteger(RESPONSE_PENDING)
        try {
            connection.start { code ->
                responseCode.set(code)
                latch.countDown()
            }
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) return null
            if (responseCode.get() != InstallReferrerClient.InstallReferrerResponse.OK) return null
            return connection.referrer()?.takeIf { it.isNotBlank() }?.let(::RawInstallReferrer)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) {
            // RemoteException from a service that died mid-call, SecurityException from a
            // restricted profile.
            return null
        } finally {
            runCatching { connection.end() }
        }
    }

    private class RealConnection(context: Context) : InstallReferrerConnection {
        private val client = InstallReferrerClient.newBuilder(context).build()

        override fun start(onFinished: (Int) -> Unit) {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) = onFinished(responseCode)

                // Fires instead of a setup result when the bind drops; waiting out the timeout
                // for it would stall the worker for nothing.
                override fun onInstallReferrerServiceDisconnected() =
                    onFinished(InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED)
            })
        }

        override fun referrer(): String? = client.installReferrer.installReferrer

        override fun end() = client.endConnection()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        private const val RESPONSE_PENDING = Int.MIN_VALUE
    }
}

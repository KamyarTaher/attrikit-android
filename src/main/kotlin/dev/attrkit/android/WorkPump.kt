package dev.attrkit.android

import dev.attrkit.core.WorkResult
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The one background thread every core call runs on. The core persists with a blocking
 * `commit()`, talks to Play over IPC and to the network, so none of it may happen on the caller's
 * thread, and a single thread also gives calls a total order (start, then a track, then the
 * foreground event) without the host having to think about it.
 *
 * After each job the pump arms at most one retry, running [retryJob], for the moment the core says
 * it wants to run again, clamped to [minRetryDelayMillis]..[maxRetryDelayMillis] so a clock that
 * jumped can neither spin the worker nor park it for days. The core decides what is actually due,
 * so an early wake-up costs a no-op.
 */
internal class WorkPump(
    private val retryJob: () -> WorkResult?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val minRetryDelayMillis: Long = MIN_RETRY_DELAY_MILLIS,
    private val maxRetryDelayMillis: Long = MAX_RETRY_DELAY_MILLIS,
) {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "attrikit-worker").apply { isDaemon = true }
    }.apply {
        // Without this the thread would outlive the work for the life of the process.
        setKeepAliveTime(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
        removeOnCancelPolicy = true
    }

    private val lock = Any()
    private var pendingRetry: ScheduledFuture<*>? = null
    private var failures = 0

    /** Runs [job] on the worker thread; its result, if any, drives the next retry. */
    fun submit(job: () -> WorkResult?) {
        executor.execute { runAndSchedule(job) }
    }

    private fun runAndSchedule(job: () -> WorkResult?) {
        // A throwing job is captured by the executor's future, so it cannot reach the thread's
        // uncaught-exception handler (a crash in the host) or stop later jobs. But that same
        // capture would also swallow the failure of the one scheduled retry, and with it every
        // later retry: a failure re-arms a retry on a doubling, clamped delay instead.
        val result = try {
            job()
        } catch (_: Exception) {
            synchronized(lock) {
                val delay = (minRetryDelayMillis shl failures.coerceAtMost(MAX_BACKOFF_SHIFT))
                    .coerceIn(minRetryDelayMillis, maxRetryDelayMillis)
                failures++
                arm(delay)
            }
            return
        }
        synchronized(lock) {
            failures = 0
            val at = result?.nextAttemptAt?.toEpochMilli()
            if (result == null) return
            pendingRetry?.cancel(false)
            pendingRetry = null
            if (at == null) return
            arm((at - nowMillis()).coerceIn(minRetryDelayMillis, maxRetryDelayMillis))
        }
    }

    // Caller holds [lock]. At most one retry is ever pending.
    private fun arm(delayMillis: Long) {
        pendingRetry?.cancel(false)
        pendingRetry = executor.schedule({ runAndSchedule(retryJob) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    companion object {
        const val MIN_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_RETRY_DELAY_MILLIS = 3_600_000L
        private const val KEEP_ALIVE_SECONDS = 30L
        private const val MAX_BACKOFF_SHIFT = 20
    }
}

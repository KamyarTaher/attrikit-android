package dev.attrkit.android

import dev.attrkit.core.AttriKitCore
import dev.attrkit.core.AttributionListener
import dev.attrkit.core.AttributionResult
import dev.attrkit.core.AttributionUpdate
import dev.attrkit.core.ConsentState
import dev.attrkit.core.DeletionResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Receives [AttriKit.attribution]'s answer on the main thread. */
fun interface AttributionCallback {
    fun onResult(result: AttributionResult)
}

/** Receives [AttriKit.deleteData]'s outcome on the main thread. */
fun interface DeletionCallback {
    fun onResult(result: DeletionResult)
}

/**
 * The facade's behaviour without Android in it: every core call is a job on the [WorkPump], each
 * followed by a `work()` so the delivery it caused happens and the pump learns when to retry.
 *
 * The core is built by the first job, on the worker thread, because building it reads the
 * persisted state from disk and that must not happen on the caller's (usually the main) thread.
 *
 * Four settings are different from every other call: the Google consent choices, the TCF switch
 * and the user id are all read by the first-open snapshot and the first identify, which
 * `core.start()` takes. A choice made before `start()`, or after it but before the worker reaches
 * it, must therefore be in force by then. Such choices are held in [pending] (last one wins) and
 * applied by the start job ahead of `core.start()`; once that job has drained them, later choices
 * are ordinary jobs.
 *
 * Calls that block (`attribution`) never use the single worker: a wait of seconds there would stall
 * every delivery behind it. They run on a small bounded pool and answer on the main thread.
 *
 * @param postToMain runs a block on the main thread; callbacks and listeners are delivered through it.
 */
internal class AttriKitRuntime(
    private val postToMain: (Runnable) -> Unit = { it.run() },
    private val isMainThread: () -> Boolean = ::onMainThread,
) {
    private sealed interface GoogleChoice {
        data class Manual(val eea: Boolean, val adUserData: Boolean, val adPersonalization: Boolean) : GoogleChoice
        data object Clear : GoogleChoice
    }

    private class Pending {
        var tcfEnabled: Boolean? = null
        var google: GoogleChoice? = null
        var userIdSet = false
        var userId: String? = null
    }

    @Volatile private var core: AttriKitCore? = null
    @Volatile private var installationIdSnapshot: String? = null
    private var coreFactory: (() -> AttriKitCore)? = null
    private val foreground = AtomicBoolean(false)

    private val lock = Any()
    private var pending: Pending? = Pending()
    @Volatile private var startRequested = false

    // Counted down when the first start job has finished (even by failing), so an attribution wait
    // does not read an unstarted core as NotStarted.
    private val startApplied = CountDownLatch(1)

    private val listeners = CopyOnWriteArrayList<AttributionListener>()
    private var coreListenerArmed = false // worker thread only

    // Ordering of what listeners see. Updates reach the main thread from several producers: the core
    // calling the dispatcher (a worker or pool thread) and a snapshot taken on the worker when a
    // listener is added. Each carries a key; the main thread drops anything older than the last one
    // that listener saw, and the same state twice. A core update gets an even key from a counter
    // read when it is produced; a snapshot gets the odd key just above the counter read BEFORE it
    // was taken, so it outranks every update produced earlier (its state is at least as new) and
    // is outranked by every later one.
    private val updateCounter = AtomicLong(0)
    private val lastDelivered = ConcurrentHashMap<AttributionListener, Pair<Long, AttributionUpdate>>()

    private val dispatcher = AttributionListener { update ->
        val key = 2 * updateCounter.incrementAndGet()
        for (listener in listeners) postToMain { deliver(listener, key, update) }
    }

    /** Runs on the main thread. Membership is re-checked here, because a removal can land after the post. */
    internal fun deliver(listener: AttributionListener, key: Long, update: AttributionUpdate) {
        if (listener !in listeners) return
        val previous = lastDelivered[listener]
        if (previous != null && (key < previous.first || update == previous.second)) return
        lastDelivered[listener] = key to update
        // A removal from another thread can land between the check above and this record: drop the
        // record it could no longer clear, so the listener is neither called nor kept alive.
        if (listener !in listeners) {
            lastDelivered.remove(listener)
            return
        }
        listener.onAttributionUpdate(update)
    }

    private val attributionExecutor = ThreadPoolExecutor(
        ATTRIBUTION_THREADS, ATTRIBUTION_THREADS, 30, TimeUnit.SECONDS, LinkedBlockingQueue(ATTRIBUTION_QUEUE),
    ) { runnable -> Thread(runnable, "attrikit-attribution").apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }

    internal val pump = WorkPump(retryJob = { refreshing { core?.work() } })

    fun start(factory: () -> AttriKitCore, apiKey: String, consent: ConsentState) {
        synchronized(lock) {
            if (coreFactory == null) coreFactory = factory
            startRequested = true
        }
        pump.submit {
            try {
                refreshing {
                    val core = core()
                    drainPending(core)
                    syncCoreListener(core)
                    val result = core.start(apiKey, consent)
                    // A grant that arrives after the process is already visible still opens a session.
                    openSessionIfForeground()
                    result
                }
            } finally {
                startApplied.countDown()
            }
        }
    }

    fun setConsent(consent: ConsentState) {
        if (!startRequested) return
        pump.submit {
            refreshing {
                val result = core().setConsent(consent)
                openSessionIfForeground()
                result
            }
        }
    }

    fun track(name: String, properties: Map<String, Any?>) {
        if (!startRequested) return
        pump.submit {
            refreshing {
                val core = core()
                core.track(name, properties)
                core.work()
            }
        }
    }

    fun setUserID(userId: String?) {
        synchronized(lock) {
            pending?.let { it.userIdSet = true; it.userId = userId; return }
        }
        pump.submit { refreshing { core().setUserID(userId) } }
    }

    fun setGoogleConsent(eea: Boolean, adUserData: Boolean, adPersonalization: Boolean) =
        googleChoice(GoogleChoice.Manual(eea, adUserData, adPersonalization))

    fun clearGoogleConsent() = googleChoice(GoogleChoice.Clear)

    fun setTcfDataCollectionEnabled(enabled: Boolean) {
        synchronized(lock) {
            pending?.let { it.tcfEnabled = enabled; return }
        }
        pump.submit { refreshing { core().setTcfDataCollectionEnabled(enabled); null } }
    }

    private fun googleChoice(choice: GoogleChoice) {
        synchronized(lock) {
            pending?.let { it.google = choice; return }
        }
        pump.submit { refreshing { apply(core(), choice); null } }
    }

    // Under the same lock the setters take, so a choice lands either in the batch drained here or
    // in a job queued after this one, never in the gap between.
    private fun drainPending(core: AttriKitCore) {
        val drained = synchronized(lock) { pending.also { pending = null } } ?: return
        drained.tcfEnabled?.let(core::setTcfDataCollectionEnabled)
        drained.google?.let { apply(core, it) }
        if (drained.userIdSet) core.setUserID(drained.userId)
    }

    private fun apply(core: AttriKitCore, choice: GoogleChoice) {
        when (choice) {
            is GoogleChoice.Manual -> core.setGoogleConsent(choice.eea, choice.adUserData, choice.adPersonalization)
            GoogleChoice.Clear -> core.clearGoogleConsent()
        }
    }

    /**
     * Waits for the answer of `core.attribution`, on the caller's thread, which must not be the main
     * thread: the core blocks it for up to [timeoutMillis]. A main-thread call answers
     * [AttributionResult.TimedOut] at once and does not wait, rather than stalling the UI.
     */
    fun attributionBlocking(timeoutMillis: Long): AttributionResult {
        if (isMainThread()) return AttributionResult.TimedOut
        return awaitAttribution(deadlineAfter(timeoutMillis))
    }

    fun attribution(timeoutMillis: Long, callback: AttributionCallback) {
        // The deadline starts now, at the call: time spent queued behind other waits is part of the
        // caller's budget, so the answer never arrives later than the timeout it asked for.
        val deadline = deadlineAfter(timeoutMillis)
        try {
            attributionExecutor.execute {
                val result = awaitAttribution(deadline)
                postToMain { callback.onResult(result) }
            }
        } catch (_: RejectedExecutionException) {
            // Every thread and queue slot is busy waiting: unknown, not a refusal.
            postToMain { callback.onResult(AttributionResult.TimedOut) }
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceIn(0L, MAX_ATTRIBUTION_WAIT_MILLIS))

    private fun remainingMillis(deadlineNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    // A budget already spent (a long wait in the queue) waits for nothing: an unapplied start answers
    // TimedOut, and a started core is asked with 0 ms, which answers from memory without a request.
    private fun awaitAttribution(deadlineNanos: Long): AttributionResult {
        if (!startRequested) return AttributionResult.NotStarted
        return try {
            if (!startApplied.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)) {
                return AttributionResult.TimedOut
            }
            core?.attribution(remainingMillis(deadlineNanos)) ?: AttributionResult.NotStarted
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            AttributionResult.TimedOut
        } catch (_: Exception) {
            AttributionResult.Failed
        }
    }

    fun addAttributionListener(listener: AttributionListener) {
        if (!listeners.addIfAbsent(listener)) return
        if (!startRequested) return // the start job arms the core's listener
        pump.submit {
            refreshing {
                val core = core()
                if (coreListenerArmed) {
                    // The core delivered its current state to earlier listeners already.
                    val key = 2 * updateCounter.get() + 1
                    val update = core.attributionUpdate()
                    postToMain { deliver(listener, key, update) }
                } else {
                    syncCoreListener(core)
                }
                // Registering counts as asking for attribution; work() starts the polling.
                core.work()
            }
        }
    }

    fun removeAttributionListener(listener: AttributionListener) {
        if (!listeners.remove(listener)) return
        lastDelivered.remove(listener)
        if (!startRequested) return
        pump.submit { refreshing { syncCoreListener(core()); null } }
    }

    // The core holds one listener: ours, fanning out to the facade's list on the main thread. It is
    // armed while the list is non-empty. Worker thread only.
    private fun syncCoreListener(core: AttriKitCore) {
        val wanted = listeners.isNotEmpty()
        if (wanted == coreListenerArmed) return
        core.setAttributionListener(if (wanted) dispatcher else null)
        coreListenerArmed = wanted
    }

    fun deleteData(callback: DeletionCallback) {
        if (!startRequested) {
            postToMain { callback.onResult(DeletionResult.NotStarted) }
            return
        }
        pump.submit {
            refreshing {
                val core = core()
                val result = try {
                    core.deleteData()
                } catch (_: Exception) {
                    DeletionResult.Failed(null)
                }
                postToMain { callback.onResult(result) }
                // A refused erasure stays pending and work() retries it; its result arms the pump.
                core.work()
            }
        }
    }

    fun onProcessForeground() {
        foreground.set(true)
        pump.submit {
            refreshing {
                val core = core()
                core.onForeground()
                core.work()
            }
        }
    }

    fun onProcessBackground() {
        foreground.set(false)
        pump.submit {
            refreshing {
                val core = core()
                core.onBackground()
                core.work()
            }
        }
    }

    /**
     * A snapshot the worker refreshes after each job, never a call into the core: the core's
     * monitor is held across blocking disk commits, and a main-thread caller must not wait on it.
     * Null until the first job has finished, and whenever consent does not allow measurement.
     */
    fun installationId(): String? = installationIdSnapshot

    private inline fun <T> refreshing(job: () -> T): T =
        try {
            job()
        } finally {
            installationIdSnapshot = core?.installationId()
        }

    // Only ever called from the worker thread, which is why a plain check-then-set is enough.
    private fun core(): AttriKitCore =
        core ?: checkNotNull(coreFactory) { "AttriKit is not started" }().also { core = it }

    private fun openSessionIfForeground() {
        if (foreground.get()) core?.onForeground()
    }

    private companion object {
        const val ATTRIBUTION_THREADS = 2
        const val ATTRIBUTION_QUEUE = 8
        // A caller's own timeout is honoured up to a minute; longer holds a thread for nothing.
        const val MAX_ATTRIBUTION_WAIT_MILLIS = 60_000L
    }
}

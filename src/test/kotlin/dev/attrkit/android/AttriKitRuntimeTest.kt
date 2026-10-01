package dev.attrkit.android

import dev.attrkit.core.AdvertisingIdentifiers
import dev.attrkit.core.AttriKitCore
import dev.attrkit.core.CoarseContext
import dev.attrkit.core.ConsentState
import dev.attrkit.core.CoreConfiguration
import dev.attrkit.core.HttpRequest
import dev.attrkit.core.HttpResponse
import dev.attrkit.core.SystemClock
import dev.attrkit.core.TcfPreferences
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttriKitRuntimeTest {
    private val apiKey = "pk_test_0123456789abcdef"

    // Google (vendor 755) allowed everywhere, so TCF alone derives adUserData and adPersonalization.
    private val allowed = "1".repeat(800)
    private val tcf = TcfPreferences { key ->
        when (key) {
            "IABTCF_gdprApplies" -> 1
            "IABTCF_PurposeConsents", "IABTCF_PurposeLegitimateInterests",
            "IABTCF_VendorConsents", "IABTCF_VendorLegitimateInterests" -> allowed
            else -> null
        }
    }

    private class MemoryStore : dev.attrkit.core.KeyValueStore {
        private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun write(changes: Map<String, String?>) {
            for ((k, v) in changes) if (v == null) map.remove(k) else map[k] = v
        }
    }

    private class Harness(tcf: TcfPreferences) {
        val firstOpenBodies = CopyOnWriteArrayList<String>()
        val firstOpenSeen = CountDownLatch(1)
        val identifyBodies = CopyOnWriteArrayList<String>()
        val identifySeen = CountDownLatch(1)
        val deleteSeen = CountDownLatch(1)
        @Volatile var deleteStatus = 200
        @Volatile var attributionBody: String? = null
        val store = MemoryStore()
        @Volatile var built = false
        @Volatile var core: AttriKitCore? = null
        val factory: () -> AttriKitCore = {
            built = true
            AttriKitCore(
                configuration = CoreConfiguration("https://ingest.example.test", "1.0", CoarseContext()),
                store = store,
                clock = SystemClock,
                transport = { request: HttpRequest ->
                    if (request.url.endsWith("v1/ingest/first-open")) {
                        firstOpenBodies.add(
                            GZIPInputStream(request.body.inputStream()).use { String(it.readBytes()) },
                        )
                        firstOpenSeen.countDown()
                    }
                    if (request.url.endsWith("v1/ingest/identify")) {
                        identifyBodies.add(
                            GZIPInputStream(request.body.inputStream()).use { String(it.readBytes()) },
                        )
                        identifySeen.countDown()
                    }
                    if (request.url.endsWith("v1/privacy/delete")) {
                        deleteSeen.countDown()
                    }
                    val attribution = attributionBody
                    when {
                        request.url.endsWith("v1/privacy/delete") -> HttpResponse(deleteStatus, "{}".toByteArray())
                        request.url.contains("v1/attribution/") ->
                            if (attribution == null) HttpResponse(404) else HttpResponse(200, attribution.toByteArray())
                        else -> HttpResponse(200, "{}".toByteArray())
                    }
                },
                referrerClient = { null },
                advertisingIdProvider = { AdvertisingIdentifiers() },
                tcfPreferences = tcf,
            ).also { core = it }
        }
    }

    /** Holds the worker so calls made now are made "before the worker runs". */
    private fun AttriKitRuntime.holdWorker(): () -> Unit {
        val release = CountDownLatch(1)
        pump.submit { release.await(2, TimeUnit.SECONDS); null }
        return { release.countDown() }
    }

    private fun firstOpen(h: Harness): String {
        assertTrue("no first-open was sent", h.firstOpenSeen.await(3, TimeUnit.SECONDS))
        return h.firstOpenBodies.first()
    }

    @Test(timeout = 10_000)
    fun withoutAnyOptOutTheFirstOpenCarriesTheTcfDerivedValues() {
        val h = Harness(tcf)
        val runtime = AttriKitRuntime()
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)

        // Guards the tests below: they assert an absence, which this proves is observable.
        assertTrue(firstOpen(h).contains("\"source\":\"tcf\""))
    }

    @Test(timeout = 10_000)
    fun disablingTcfBeforeStartKeepsTheFirstOpenFromReadingIt() {
        val h = Harness(tcf)
        val runtime = AttriKitRuntime()
        runtime.setTcfDataCollectionEnabled(false)
        // The idle worker has already run the call: only a held choice survives until start.
        settled(runtime)
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)

        assertFalse(firstOpen(h).contains("\"dma\""))
    }

    @Test(timeout = 10_000)
    fun aGoogleChoiceMadeLongBeforeStartIsStillInTheFirstOpen() {
        val h = Harness(tcf)
        val runtime = AttriKitRuntime()
        runtime.setGoogleConsent(eea = true, adUserData = false, adPersonalization = false)
        settled(runtime)
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)

        assertTrue(firstOpen(h).contains("\"source\":\"manual\""))
    }

    @Test(timeout = 10_000)
    fun disablingTcfRightAfterStartBeatsTheWorkerToTheSnapshot() {
        val h = Harness(tcf)
        val runtime = AttriKitRuntime()
        val release = runtime.holdWorker()
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)
        runtime.setTcfDataCollectionEnabled(false)
        release()

        assertFalse(firstOpen(h).contains("\"dma\""))
    }

    @Test(timeout = 10_000)
    fun manualGoogleConsentSetBeforeOrRightAfterStartIsInTheFirstOpen() {
        for (beforeStart in listOf(true, false)) {
            val h = Harness(tcf)
            val runtime = AttriKitRuntime()
            val release = runtime.holdWorker()
            if (beforeStart) runtime.setGoogleConsent(eea = true, adUserData = false, adPersonalization = false)
            runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)
            if (!beforeStart) runtime.setGoogleConsent(eea = true, adUserData = false, adPersonalization = false)
            release()

            val body = firstOpen(h)
            assertTrue("beforeStart=$beforeStart: $body", body.contains("\"source\":\"manual\""))
            assertFalse("beforeStart=$beforeStart", body.contains("\"source\":\"tcf\""))
        }
    }

    @Test(timeout = 10_000)
    fun theLastChoiceBeforeStartWins() {
        val h = Harness(tcf)
        val runtime = AttriKitRuntime()
        runtime.setGoogleConsent(eea = true, adUserData = false, adPersonalization = false)
        runtime.clearGoogleConsent()
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)

        assertTrue(firstOpen(h).contains("\"source\":\"tcf\""))
    }

    @Test(timeout = 10_000)
    fun trackAndSetConsentBeforeStartAreDroppedNotQueued() {
        val h = Harness(tcf)
        val runtime = AttriKitRuntime()
        runtime.track("early", emptyMap())
        runtime.setConsent(ConsentState.TRACKING_GRANTED)
        val settled = CountDownLatch(1)
        runtime.pump.submit { settled.countDown(); null }
        assertTrue(settled.await(3, TimeUnit.SECONDS))
        // Nothing was submitted for them, so no core was built and nothing was sent.
        assertFalse(h.built)
        assertNull(runtime.installationId())
        assertEquals(0, h.firstOpenBodies.size)
    }

    @Test(timeout = 10_000)
    fun theInstallationIdIsASnapshotTheWorkerRefreshesNotACallIntoTheCore() {
        val h = Harness(tcf)
        val runtime = AttriKitRuntime()
        val release = runtime.holdWorker()
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)

        // The worker has not run: the answer is immediate and null, whatever the core would say.
        assertNull(runtime.installationId())
        release()
        firstOpen(h)
        val done = CountDownLatch(1)
        runtime.pump.submit { done.countDown(); null }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertNotNull(runtime.installationId())
    }

    @Test(timeout = 10_000)
    fun theInstallationIdNeverWaitsOnTheCoresMonitorTheWorkerHoldsAcrossCommits() {
        val h = Harness(tcf)
        val runtime = AttriKitRuntime()
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)
        firstOpen(h)
        val settled = CountDownLatch(1)
        runtime.pump.submit { settled.countDown(); null }
        assertTrue(settled.await(3, TimeUnit.SECONDS))

        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            synchronized(h.core!!) { held.countDown(); release.await(5, TimeUnit.SECONDS) }
        }
        holder.start()
        assertTrue(held.await(2, TimeUnit.SECONDS))
        try {
            var answered = false
            val caller = Thread { runtime.installationId(); answered = true }
            caller.start()
            caller.join(1_000)
            assertTrue("installationId() blocked on the core's monitor", answered)
        } finally {
            release.countDown()
        }
    }

    /** A stand-in main thread: posted blocks queue here and run only when the test drains them. */
    private class MainQueue {
        val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val post: (Runnable) -> Unit = { queue.add(it) }

        /** Runs queued blocks until [done] holds; false if it never did. */
        fun drainUntil(timeoutMs: Long = 5_000, done: () -> Boolean): Boolean {
            val end = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < end) {
                if (done()) return true
                queue.poll(50, TimeUnit.MILLISECONDS)?.run()
            }
            return done()
        }
    }

    private val attributedBody =
        """{"method":"deterministic","finality":"final","policy_version":1,"campaign_id":"c1","status":"attributed"}"""

    private fun settled(runtime: AttriKitRuntime) {
        val latch = CountDownLatch(1)
        runtime.pump.submit { latch.countDown(); null }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Test(timeout = 15_000)
    fun aUserIdSetBeforeStartIsInTheFirstIdentifyAndTheLastOneWins() {
        val h = Harness(tcf)
        val runtime = AttriKitRuntime()
        runtime.setUserID("first-user")
        runtime.setUserID("second-user")
        // The idle worker has already run both calls: only a held choice survives until start.
        settled(runtime)
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)

        assertTrue("no identify was sent", h.identifySeen.await(5, TimeUnit.SECONDS))
        assertEquals(1, h.identifyBodies.size)
        assertTrue(h.identifyBodies.first(), h.identifyBodies.first().contains("second-user"))
        assertFalse(h.identifyBodies.first().contains("first-user"))
    }

    @Test(timeout = 15_000)
    fun aUserIdSetRightAfterStartBeatsTheWorkerToTheFirstIdentify() {
        val h = Harness(tcf)
        val runtime = AttriKitRuntime()
        val release = runtime.holdWorker()
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)
        runtime.setUserID("late-user")
        release()

        assertTrue(h.identifySeen.await(5, TimeUnit.SECONDS))
        assertTrue(h.identifyBodies.first().contains("late-user"))
    }

    @Test(timeout = 15_000)
    fun aUserIdSetAfterStartHasRunIsAnOrdinaryJob() {
        val h = Harness(tcf)
        val runtime = AttriKitRuntime()
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)
        firstOpen(h)
        settled(runtime)
        runtime.setUserID("after-user")

        assertTrue(h.identifySeen.await(5, TimeUnit.SECONDS))
        assertTrue(h.identifyBodies.first().contains("after-user"))
    }

    @Test(timeout = 10_000)
    fun theBlockingAttributionRefusesTheMainThreadWithoutWaiting() {
        val runtime = AttriKitRuntime(isMainThread = { true })
        runtime.start(Harness(tcf).factory, apiKey, ConsentState.MEASUREMENT_GRANTED)
        val started = System.nanoTime()

        assertEquals(dev.attrkit.core.AttributionResult.TimedOut, runtime.attributionBlocking(5_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test(timeout = 15_000)
    fun theBlockingAttributionOffTheMainThreadReturnsTheServersAnswer() {
        val h = Harness(tcf).also { it.attributionBody = attributedBody }
        val runtime = AttriKitRuntime(isMainThread = { false })
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)

        val result = runtime.attributionBlocking(8_000)
        assertTrue(result.toString(), result is dev.attrkit.core.AttributionResult.Attributed)
    }

    @Test(timeout = 10_000)
    fun attributionBeforeStartIsNotStartedAndIsDeliveredThroughTheMainThread() {
        val main = MainQueue()
        val runtime = AttriKitRuntime(postToMain = main.post)
        var got: dev.attrkit.core.AttributionResult? = null
        runtime.attribution(1_000) { got = it }

        // Delivered only when the main thread runs the posted block, never on the waiting thread.
        assertTrue(main.drainUntil { got != null })
        assertEquals(dev.attrkit.core.AttributionResult.NotStarted, got)
    }

    @Test(timeout = 15_000)
    fun attributionCallbackAnswersThroughTheMainThreadAfterStart() {
        val h = Harness(tcf).also { it.attributionBody = attributedBody }
        val main = MainQueue()
        val runtime = AttriKitRuntime(postToMain = main.post)
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)
        var got: dev.attrkit.core.AttributionResult? = null
        val deliveredOn = java.util.concurrent.atomic.AtomicReference<Thread>()
        runtime.attribution(8_000) { got = it; deliveredOn.set(Thread.currentThread()) }

        assertTrue(main.drainUntil { got != null })
        assertTrue(got.toString(), got is dev.attrkit.core.AttributionResult.Attributed)
        assertEquals(Thread.currentThread(), deliveredOn.get())
    }

    @Test(timeout = 15_000)
    fun aListenerAddedBeforeStartReceivesUpdatesOnTheMainThreadUntilRemoved() {
        val h = Harness(tcf).also { it.attributionBody = attributedBody }
        val main = MainQueue()
        val runtime = AttriKitRuntime(postToMain = main.post)
        val updates = CopyOnWriteArrayList<dev.attrkit.core.AttributionUpdate>()
        val threads = CopyOnWriteArrayList<Thread>()
        val listener = dev.attrkit.core.AttributionListener { updates.add(it); threads.add(Thread.currentThread()) }
        runtime.addAttributionListener(listener)
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)

        assertTrue("never attributed: $updates", main.drainUntil {
            updates.any { it.status == dev.attrkit.core.AttributionStatus.ATTRIBUTED }
        })
        assertTrue("a listener ran off the main thread", threads.all { it === Thread.currentThread() })
        runtime.removeAttributionListener(listener)
        settled(runtime)
        main.queue.clear()
        val seen = updates.size
        runtime.setConsent(ConsentState.DENIED)
        settled(runtime)
        main.drainUntil(500) { false }
        assertEquals("a removed listener was still called", seen, updates.size)
    }

    @Test(timeout = 15_000)
    fun deleteDataReportsOnTheMainThreadAndResetsTheSnapshot() {
        val h = Harness(tcf)
        val main = MainQueue()
        val runtime = AttriKitRuntime(postToMain = main.post)
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)
        firstOpen(h)
        settled(runtime)
        assertNotNull(runtime.installationId())
        var got: dev.attrkit.core.DeletionResult? = null
        val deliveredOn = java.util.concurrent.atomic.AtomicReference<Thread>()
        runtime.deleteData { got = it; deliveredOn.set(Thread.currentThread()) }

        assertTrue(main.drainUntil { got != null })
        assertEquals(dev.attrkit.core.DeletionResult.Completed, got)
        assertEquals(Thread.currentThread(), deliveredOn.get())
        assertTrue(h.deleteSeen.await(1, TimeUnit.SECONDS))
        // The snapshot is refreshed when the job ends, after the result was posted.
        settled(runtime)
        assertNull(runtime.installationId())
    }

    @Test(timeout = 10_000)
    fun deleteDataBeforeStartIsNotStarted() {
        val main = MainQueue()
        val runtime = AttriKitRuntime(postToMain = main.post)
        var got: dev.attrkit.core.DeletionResult? = null
        runtime.deleteData { got = it }

        assertTrue(main.drainUntil { got != null })
        assertEquals(dev.attrkit.core.DeletionResult.NotStarted, got)
    }

    @Test(timeout = 15_000)
    fun timeSpentQueuedBehindOtherWaitsCountsAgainstTheCallersTimeout() {
        val h = Harness(tcf)
        val main = MainQueue()
        val runtime = AttriKitRuntime(postToMain = main.post)
        val release = runtime.holdWorker() // the start job never finishes, so every wait waits
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)
        // Both pool threads are now busy for 700 ms each.
        repeat(2) { runtime.attribution(700) { } }
        val asked = System.nanoTime()
        var answeredAt = 0L
        var third: dev.attrkit.core.AttributionResult? = null
        runtime.attribution(300) { third = it; answeredAt = System.nanoTime() }

        assertTrue(main.drainUntil { third != null })
        release()
        assertEquals(dev.attrkit.core.AttributionResult.TimedOut, third)
        // It starts running only after ~700 ms, with its 300 ms already spent: answered at once.
        // Counting only the wait it does itself would put this near 1000 ms.
        val elapsed = (answeredAt - asked) / 1_000_000
        assertTrue("answered after $elapsed ms", elapsed < 850)
    }

    @Test(timeout = 15_000)
    fun aRemovedListenerIsNotCalledByAnUpdateAlreadyPostedToTheMainThread() {
        val h = Harness(tcf).also { it.attributionBody = attributedBody }
        val main = MainQueue()
        val runtime = AttriKitRuntime(postToMain = main.post)
        val updates = CopyOnWriteArrayList<dev.attrkit.core.AttributionUpdate>()
        val listener = dev.attrkit.core.AttributionListener { updates.add(it) }
        runtime.addAttributionListener(listener)
        runtime.start(h.factory, apiKey, ConsentState.MEASUREMENT_GRANTED)
        val end = System.nanoTime() + 5_000_000_000L
        while (main.queue.isEmpty() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("no update was ever posted", main.queue.isNotEmpty())

        runtime.removeAttributionListener(listener) // the post is still waiting for the main thread
        while (main.queue.isNotEmpty()) main.queue.poll()?.run()

        assertTrue("a removed listener was called: $updates", updates.isEmpty())
    }

    @Test
    fun listenersSeeUpdatesInOrderWithoutRepeatsEvenWhenAnOlderOneArrivesLate() {
        val runtime = AttriKitRuntime()
        val seen = mutableListOf<dev.attrkit.core.AttributionStatus>()
        val listener = dev.attrkit.core.AttributionListener { seen.add(it.status) }
        runtime.addAttributionListener(listener) // not started: only the list is touched
        val pending = dev.attrkit.core.AttributionUpdate(dev.attrkit.core.AttributionStatus.PENDING, null)
        val organic = dev.attrkit.core.AttributionUpdate(dev.attrkit.core.AttributionStatus.ORGANIC, null)
        val timedOut = dev.attrkit.core.AttributionUpdate(dev.attrkit.core.AttributionStatus.TIMED_OUT, null)

        runtime.deliver(listener, 4, organic)
        runtime.deliver(listener, 2, pending)  // produced earlier, arrives after: stale
        runtime.deliver(listener, 5, organic)  // the same state again (a duplicate registration)
        runtime.deliver(listener, 6, timedOut) // genuinely newer
        runtime.deliver(listener, 5, pending)  // a snapshot taken before key 6: stale

        assertEquals(
            listOf(dev.attrkit.core.AttributionStatus.ORGANIC, dev.attrkit.core.AttributionStatus.TIMED_OUT),
            seen,
        )
    }

    @Test
    fun startRefusesAContextWithNoApplicationContextOnTheCallersThread() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AttriKit.start(android.content.ContextWrapper(null), apiKey, ConsentState.MEASUREMENT_GRANTED)
        }
    }
}

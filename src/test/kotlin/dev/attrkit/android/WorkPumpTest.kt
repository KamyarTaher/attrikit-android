package dev.attrkit.android

import dev.attrkit.core.WorkResult
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkPumpTest {
    private fun result(next: Long?) = WorkResult(false, 1, next?.let(Instant::ofEpochMilli))

    @Test(timeout = 5_000)
    fun jobsRunOnTheWorkerThreadInSubmissionOrder() {
        val pump = WorkPump(retryJob = { null })
        val order = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val thread = AtomicReference<String>()
        val done = CountDownLatch(3)
        for (i in 1..3) pump.submit { order.add(i); thread.set(Thread.currentThread().name); done.countDown(); null }

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2, 3), order)
        assertEquals("attrikit-worker", thread.get())
        assertNotEquals(Thread.currentThread().name, thread.get())
    }

    @Test(timeout = 5_000)
    fun theCoresRequestedRetryRunsOnceItsTimeComes() {
        val retried = CountDownLatch(1)
        val pump = WorkPump(
            retryJob = { retried.countDown(); null },
            nowMillis = { 1_000 },
            minRetryDelayMillis = 10,
            maxRetryDelayMillis = 1_000,
        )
        pump.submit { result(1_050) }

        assertTrue("retry never ran", retried.await(2, TimeUnit.SECONDS))
    }

    @Test(timeout = 5_000)
    fun noRetryIsArmedWhenTheCoreAsksForNone() {
        val retries = AtomicInteger()
        val pump = WorkPump(retryJob = { retries.incrementAndGet(); null }, minRetryDelayMillis = 10)
        val done = CountDownLatch(1)
        pump.submit { done.countDown(); result(null) }
        done.await(2, TimeUnit.SECONDS)
        Thread.sleep(200)

        assertEquals(0, retries.get())
    }

    @Test(timeout = 5_000)
    fun aFarAwayRetryIsClampedToTheCeilingSoAJumpedClockCannotParkTheWorker() {
        val retried = CountDownLatch(1)
        val pump = WorkPump(
            retryJob = { retried.countDown(); null },
            nowMillis = { 0 },
            minRetryDelayMillis = 10,
            maxRetryDelayMillis = 50,
        )
        pump.submit { result(Long.MAX_VALUE / 4) }

        assertTrue("clamped retry never ran", retried.await(2, TimeUnit.SECONDS))
    }

    @Test(timeout = 5_000)
    fun aJobThatThrowsDoesNotStopLaterJobs() {
        val escaped = AtomicInteger()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // An exception that escapes a job reaches this handler, and in a host app it is a crash.
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> escaped.incrementAndGet() }
        try {
            val pump = WorkPump(retryJob = { null })
            pump.submit { throw IllegalStateException("boom") }
            val later = CountDownLatch(1)
            pump.submit { later.countDown(); null }

            assertTrue(later.await(2, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertEquals(0, escaped.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test(timeout = 5_000)
    fun aRetryThatThrowsIsRearmedInsteadOfEndingTheRetries() {
        val calls = AtomicInteger()
        val succeeded = CountDownLatch(1)
        val pump = WorkPump(
            retryJob = {
                if (calls.incrementAndGet() == 1) throw java.io.IOException("offline")
                succeeded.countDown()
                null
            },
            nowMillis = { 0 },
            minRetryDelayMillis = 10,
            maxRetryDelayMillis = 50,
        )
        pump.submit { result(1) }

        assertTrue("the retry after a failed retry never ran", succeeded.await(2, TimeUnit.SECONDS))
        assertEquals(2, calls.get())
    }

    @Test(timeout = 5_000)
    fun theBackoffAfterRepeatedFailuresStaysUnderTheCeiling() {
        val calls = AtomicInteger()
        val succeeded = CountDownLatch(1)
        val pump = WorkPump(
            retryJob = {
                if (calls.incrementAndGet() <= 6) throw java.io.IOException("offline")
                succeeded.countDown()
                null
            },
            nowMillis = { 0 },
            minRetryDelayMillis = 10,
            maxRetryDelayMillis = 50,
        )
        val started = System.nanoTime()
        pump.submit { result(1) }

        assertTrue(succeeded.await(3, TimeUnit.SECONDS))
        // Clamped: 10+20+40+50+50+50 = 220 ms of delay. Doubling without the ceiling is 630 ms.
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) / 1_000_000 < 450)
    }

    @Test(timeout = 5_000)
    fun aNewResultReplacesThePendingRetryInsteadOfStackingAnotherOne() {
        val retries = AtomicInteger()
        val pump = WorkPump(
            retryJob = { retries.incrementAndGet(); null },
            nowMillis = { 0 },
            minRetryDelayMillis = 150,
            maxRetryDelayMillis = 150,
        )
        val done = CountDownLatch(3)
        repeat(3) { pump.submit { done.countDown(); result(1) } }
        done.await(2, TimeUnit.SECONDS)
        Thread.sleep(600)

        assertEquals(1, retries.get())
    }
}

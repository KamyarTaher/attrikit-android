package dev.attrkit.android

import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import dev.attrkit.core.RawInstallReferrer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayInstallReferrerClientTest {
    private class FakeConnection(
        private val answer: Int?,
        private val startThrows: Boolean = false,
        private val referrer: () -> String? = { "attrkit_c=x" },
    ) : InstallReferrerConnection {
        var ended = 0
        var referrerReads = 0

        override fun start(onFinished: (Int) -> Unit) {
            if (startThrows) throw SecurityException("restricted profile")
            // A null answer is Play never calling back.
            if (answer != null) onFinished(answer)
        }

        override fun referrer(): String? {
            referrerReads++
            return referrer.invoke()
        }

        override fun end() {
            ended++
        }
    }

    private fun client(connection: FakeConnection, timeout: Long = 100, mainThread: Boolean = false) =
        PlayInstallReferrerClient({ connection }, timeout, { mainThread })

    @Test(timeout = 5_000)
    fun anOkAnswerReturnsTheReferrerAndEndsTheConnection() {
        val connection = FakeConnection(InstallReferrerResponse.OK) { "attrkit_c=a&attrkit_k=b" }

        assertEquals(RawInstallReferrer("attrkit_c=a&attrkit_k=b"), client(connection).fetchInstallReferrer())
        assertEquals(1, connection.ended)
    }

    @Test(timeout = 5_000)
    fun everyErrorPlayCanReportIsAbsenceAndStillEndsTheConnection() {
        for (code in listOf(
            InstallReferrerResponse.SERVICE_UNAVAILABLE,
            InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
            InstallReferrerResponse.DEVELOPER_ERROR,
            InstallReferrerResponse.SERVICE_DISCONNECTED,
        )) {
            val connection = FakeConnection(code)
            assertNull("code $code", client(connection).fetchInstallReferrer())
            assertEquals("code $code", 1, connection.ended)
            assertEquals("code $code must not read a referrer", 0, connection.referrerReads)
        }
    }

    @Test(timeout = 5_000)
    fun aPlayThatNeverAnswersIsGivenUpOnAtTheTimeout() {
        val connection = FakeConnection(answer = null)
        val started = System.nanoTime()

        assertNull(client(connection, timeout = 150).fetchInstallReferrer())

        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited $elapsed ms", elapsed in 100..2_000)
        assertEquals(1, connection.ended)
    }

    @Test(timeout = 5_000)
    fun aServiceThatDiesMidCallIsAbsenceNotAnException() {
        val connection = FakeConnection(InstallReferrerResponse.OK, referrer = { throw IllegalStateException("dead") })
        assertNull(client(connection).fetchInstallReferrer())
        assertEquals(1, connection.ended)
    }

    @Test(timeout = 5_000)
    fun aBindThatThrowsIsAbsenceAndStillEndsTheConnection() {
        val connection = FakeConnection(InstallReferrerResponse.OK, startThrows = true)
        assertNull(client(connection).fetchInstallReferrer())
        assertEquals(1, connection.ended)
    }

    @Test(timeout = 5_000)
    fun aBlankReferrerIsAbsence() {
        assertNull(client(FakeConnection(InstallReferrerResponse.OK) { "  " }).fetchInstallReferrer())
        assertNull(client(FakeConnection(InstallReferrerResponse.OK) { null }).fetchInstallReferrer())
    }

    @Test(timeout = 5_000)
    fun theMainThreadNeverWaitsOnACallbackItIsBlocking() {
        var connected = false
        val client = PlayInstallReferrerClient({ connected = true; FakeConnection(InstallReferrerResponse.OK) }, 100, { true })
        assertNull(client.fetchInstallReferrer())
        assertEquals(false, connected)
    }
}

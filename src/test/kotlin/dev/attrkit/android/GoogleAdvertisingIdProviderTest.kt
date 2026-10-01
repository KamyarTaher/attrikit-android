package dev.attrkit.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleAdvertisingIdProviderTest {
    private val gaid = "38400000-8cf0-11bd-b23e-10b96e40000d"

    private fun provider(
        reading: () -> AdvertisingIdReading? = { AdvertisingIdReading(gaid, false) },
        appSet: () -> String? = { "app-set-1" },
    ) = GoogleAdvertisingIdProvider(reading, appSet)

    @Test
    fun anOrdinaryIdIsReturnedWithTheAppSetId() {
        val identifiers = provider().identifiers()
        assertEquals(gaid, identifiers.gaid)
        assertEquals("app-set-1", identifiers.appSetId)
    }

    @Test
    fun anIdTheUserLimitedIsSuppressed() {
        assertNull(provider({ AdvertisingIdReading(gaid, true) }).identifiers().gaid)
    }

    @Test
    fun theAllZeroIdIsSuppressedInEveryShape() {
        for (zero in listOf("00000000-0000-0000-0000-000000000000", "00000000000000000000000000000000", " 0000-0000 ")) {
            assertNull(zero, provider({ AdvertisingIdReading(zero, false) }).identifiers().gaid)
        }
    }

    @Test
    fun anAbsentOrBlankIdIsSuppressed() {
        assertNull(provider({ AdvertisingIdReading(null, false) }).identifiers().gaid)
        assertNull(provider({ AdvertisingIdReading("  ", false) }).identifiers().gaid)
    }

    @Test
    fun theTwoSourcesFailIndependently() {
        val noPlayServices = provider(reading = { throw java.io.IOException("no Play services") }).identifiers()
        assertNull(noPlayServices.gaid)
        assertEquals("app-set-1", noPlayServices.appSetId)

        val noAppSet = provider(appSet = { throw IllegalStateException("timeout") }).identifiers()
        assertEquals(gaid, noAppSet.gaid)
        assertNull(noAppSet.appSetId)
    }

    @Test
    fun aBlankAppSetIdIsAbsence() {
        assertNull(provider(appSet = { "" }).identifiers().appSetId)
    }
}

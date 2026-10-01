package dev.attrkit.android

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceFactsTest {
    private fun facts(
        country: String? = "CH",
        tags: List<String> = listOf("de-CH", "en-US"),
        osRelease: String? = "15",
        widthDp: Int = 411,
        model: String? = "Pixel 9",
        width: Int = 1080,
        height: Int = 2424,
        density: Float = 2.75f,
    ) = DeviceFacts(country, tags, osRelease, widthDp, model, "AP4A.250205.002", "Europe/Zurich", width, height, density)

    @Test
    fun anOrdinaryDeviceFillsEveryField() {
        val coarse = facts().coarseContext()
        assertEquals("CH", coarse.countryCode)
        assertEquals("15", coarse.osMajor)
        assertEquals("phone", coarse.deviceClass)
        assertEquals("de-CH", coarse.locale)
        val signals = facts().deviceSignals()
        assertEquals(1080, signals.screenWidth)
        assertEquals(2.75, signals.screenScale!!, 0.0)
        assertEquals(listOf("de-CH", "en-US"), signals.languages)
    }

    @Test
    fun theCountryMustBeTwoUppercaseLettersOrItIsDropped() {
        assertEquals("CH", facts(country = "ch").coarseContext().countryCode)
        assertNull(facts(country = "419").coarseContext().countryCode)
        assertNull(facts(country = "").coarseContext().countryCode)
    }

    @Test
    fun aLocaleKeepsLanguageScriptAndRegionOnly() {
        val thai = Locale.Builder().setLanguage("th").setRegion("TH").setUnicodeLocaleKeyword("nu", "thai").build()
        assertEquals("th-TH", DeviceFacts.languageTag(thai))
        val hans = Locale.Builder().setLanguage("zh").setScript("Hans").setRegion("CN").build()
        assertEquals("zh-Hans-CN", DeviceFacts.languageTag(hans))
        assertNull(DeviceFacts.languageTag(Locale.ROOT))
    }

    @Test
    fun oversizedOrInvalidSignalsBecomeAbsenceRatherThanA422() {
        val signals = facts(model = "x".repeat(129), width = 0, density = 9f).deviceSignals()
        assertNull(signals.deviceModel)
        assertNull(signals.screenWidth)
        assertNull(signals.screenHeight)
        assertNull(signals.screenScale)
        // Width and height are only ever sent as a pair with a scale.
        assertNull(facts(height = 20_000).deviceSignals().screenWidth)
    }

    @Test
    fun languagesAreDeduplicatedAndCappedAtSixteen() {
        val many = (1..30).map { "l$it" } + "l1"
        assertEquals(16, facts(tags = many).deviceSignals().languages.size)
        assertEquals(listOf("en"), facts(tags = listOf("en", "en")).deviceSignals().languages)
    }

    @Test
    fun aTabletIsRecognisedFromTheSmallestWidth() {
        assertEquals("tablet", facts(widthDp = 600).coarseContext().deviceClass)
        assertEquals("phone", facts(widthDp = 599).coarseContext().deviceClass)
    }

    @Test
    fun theAndroidReleaseIsCutToItsMajor() {
        assertEquals("14", facts(osRelease = "14.1").coarseContext().osMajor)
        assertNull(facts(osRelease = "").coarseContext().osMajor)
    }
}

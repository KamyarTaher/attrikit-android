package dev.attrkit.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedPreferencesTcfPreferencesTest {
    @Test
    fun theStandardsNumberAndStringTypesAreReadAsStored() {
        val tcf = SharedPreferencesTcfPreferences(
            FakeSharedPreferences(mapOf("IABTCF_gdprApplies" to 1, "IABTCF_PurposeConsents" to "1011", "wide" to 5L)),
        )
        assertEquals(1, tcf.value("IABTCF_gdprApplies"))
        assertEquals("1011", tcf.value("IABTCF_PurposeConsents"))
        assertEquals(5L, tcf.value("wide"))
    }

    @Test
    fun anUnsetKeyIsAbsent() {
        assertNull(SharedPreferencesTcfPreferences(FakeSharedPreferences()).value("IABTCF_gdprApplies"))
    }

    @Test
    fun aKeyOfAnUnreadableTypeIsAbsentNotAnException() {
        assertNull(SharedPreferencesTcfPreferences(FakeSharedPreferences(mapOf("k" to 1.5f))).value("k"))
    }
}

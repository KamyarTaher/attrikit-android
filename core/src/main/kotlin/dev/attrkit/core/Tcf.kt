package dev.attrkit.core

/**
 * The IAB TCF values a consent management platform stores on the device, read by key and returned
 * as stored. The Android adapter passes the app's default SharedPreferences
 * (`PreferenceManager.getDefaultSharedPreferences`), which is where every TCF consent management
 * platform writes the `IABTCF_` keys: IAB Tech Lab, "CMP API v2", In-App Details
 * (https://github.com/InteractiveAdvertisingBureau/GDPR-Transparency-and-Consent-Framework/blob/master/TCFv2/IAB%20Tech%20Lab%20-%20CMP%20API%20v2.md,
 * read 2026-09-30). [NONE] reads nothing.
 */
fun interface TcfPreferences {
    fun value(key: String): Any?

    companion object {
        val NONE = TcfPreferences { null }
    }
}

/**
 * Google's DMA values derived from the IAB TCF v2.2 / v2.3 consent, by Google's own mapping (the
 * same derivation as the iOS SDK's TCFConsent.swift, whose comment cites each page):
 * - `IABTCF_gdprApplies` 1 is eea; 0 says the rules do not apply, which is sent alone; unset means
 *   no consent platform decided anything, and nothing is derived; any other value, a fraction
 *   included, is malformed and derives nothing;
 * - ad_user_data needs Google (TCF vendor 755) allowed by consent, Purpose 1 consent, and Purpose 7
 *   on the legal basis that applies to it (below);
 * - ad_personalization needs Google's vendor consent and consent to Purposes 3 and 4;
 * - the publisher's restrictions on Google (`IABTCF_PublisherRestrictions{purpose}`, character n
 *   for vendor n+1: '0' not allowed, '1' require consent, '2' require legitimate interest, '_'
 *   none) apply first, read against Google's registration: Purposes 1, 3 and 4 on consent without
 *   flexibility, so '0' and '2' forbid them and '1' changes nothing; Purpose 7 on legitimate
 *   interest with flexibility, so '0' forbids it and '1' makes consent its basis (the purpose's
 *   and Google's), while '2', '_' or no restriction leave the basis Google declared: legitimate
 *   interest (the purpose's and Google's), for which consent does not stand in. A character the
 *   CMP API does not define forbids the purpose rather than lifting the restriction.
 * Purpose and vendor keys are binary strings whose character at index n is the status of id n+1.
 */
internal object TcfConsent {
    const val GOOGLE_VENDOR_ID = 755

    fun dmaConsent(preferences: TcfPreferences): DmaConsent? =
        when (number(preferences.value("IABTCF_gdprApplies"))) {
            0 -> DmaConsent(eea = false, source = DmaConsent.Source.TCF)
            1 -> {
                val purposes = bits(preferences.value("IABTCF_PurposeConsents"))
                val purposeInterests = bits(preferences.value("IABTCF_PurposeLegitimateInterests"))
                val vendors = bits(preferences.value("IABTCF_VendorConsents"))
                val vendorInterests = bits(preferences.value("IABTCF_VendorLegitimateInterests"))
                fun restriction(purpose: Int): Char? =
                    bits(preferences.value("IABTCF_PublisherRestrictions$purpose")).character(GOOGLE_VENDOR_ID)
                val google = vendors.isSet(GOOGLE_VENDOR_ID)
                // A purpose Google registered on consent, without flexibility.
                fun consented(purpose: Int): Boolean = when (restriction(purpose)) {
                    null, '_', '1' -> purposes.isSet(purpose)
                    else -> false
                }
                val measurement = when (restriction(7)) {
                    '1' -> purposes.isSet(7) && google
                    null, '_', '2' -> purposeInterests.isSet(7) && vendorInterests.isSet(GOOGLE_VENDOR_ID)
                    else -> false
                }
                DmaConsent(
                    eea = true,
                    adUserData = google && consented(1) && measurement,
                    adPersonalization = google && consented(3) && consented(4),
                    source = DmaConsent.Source.TCF,
                )
            }
            else -> null
        }

    /** The standard says Number, which SharedPreferences stores as an Int; some write a String. */
    private fun number(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun bits(value: Any?): String = value as? String ?: ""

    private fun String.character(id: Int): Char? = if (id in 1..length) this[id - 1] else null

    private fun String.isSet(id: Int): Boolean = character(id) == '1'
}

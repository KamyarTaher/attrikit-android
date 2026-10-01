package dev.attrkit.android

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import dev.attrkit.core.CoarseContext
import dev.attrkit.core.CoreConfiguration
import dev.attrkit.core.DeviceSignals
import java.util.Locale
import java.util.TimeZone

/**
 * What the device says about itself, as plain values, and the one place they are cut down to what
 * the ingest schema accepts. The first-open body is validated strictly and a 422 is permanent
 * (the install is never registered), so a locale or a model name over its cap must become absence
 * here rather than reach the wire.
 */
internal data class DeviceFacts(
    val country: String?,
    val languageTags: List<String>,
    val osRelease: String?,
    val smallestWidthDp: Int,
    val model: String?,
    val osBuild: String?,
    val timezone: String?,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val density: Float,
) {
    fun coarseContext(): CoarseContext =
        CoarseContext(
            countryCode = country?.uppercase(Locale.ROOT)?.takeIf { COUNTRY.matches(it) },
            osMajor = osRelease?.substringBefore('.')?.trim()?.takeIf { it.isNotEmpty() && it.length <= 16 },
            deviceClass = if (smallestWidthDp >= TABLET_MIN_WIDTH_DP) "tablet" else "phone",
            locale = languageTags.firstOrNull(),
        )

    fun deviceSignals(): DeviceSignals {
        val hasScreen = screenWidthPx in 1..MAX_SCREEN_PX && screenHeightPx in 1..MAX_SCREEN_PX &&
            density.isFinite() && density > 0f && density <= MAX_SCALE
        return DeviceSignals(
            deviceModel = model.bounded(),
            osBuild = osBuild.bounded(),
            timezone = timezone.bounded(),
            screenWidth = screenWidthPx.takeIf { hasScreen },
            screenHeight = screenHeightPx.takeIf { hasScreen },
            screenScale = density.toDouble().takeIf { hasScreen },
            languages = languageTags.distinct().take(MAX_LANGUAGES),
        )
    }

    fun configuration(endpoint: String, appVersion: String): CoreConfiguration =
        CoreConfiguration(
            endpoint = endpoint,
            appVersion = appVersion,
            coarseContext = coarseContext(),
            deviceSignals = deviceSignals(),
        )

    private fun String?.bounded(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 }

    companion object {
        private val COUNTRY = Regex("^[A-Z]{2}$")
        private const val TABLET_MIN_WIDTH_DP = 600
        private const val MAX_SCREEN_PX = 16_384
        private const val MAX_SCALE = 8f
        private const val MAX_LANGUAGES = 16
        private const val MAX_TAG_LENGTH = 35

        /**
         * language-script-region only. A full BCP-47 tag can carry Unicode extensions
         * (`th-TH-u-nu-thai`) that are not coarse context and can pass the length cap.
         */
        fun languageTag(locale: Locale): String? {
            val tag = listOf(locale.language, locale.script, locale.country)
                .filter { it.isNotEmpty() }
                .joinToString("-")
            return tag.takeIf { it.isNotEmpty() && it.length <= MAX_TAG_LENGTH }
        }

        fun read(context: Context): DeviceFacts {
            val resources = context.resources
            val metrics = resources.displayMetrics
            val locales = deviceLocales(resources.configuration)
            val tags = locales.mapNotNull(::languageTag)
            val primary = locales.firstOrNull()
            return DeviceFacts(
                country = primary?.country,
                languageTags = tags,
                osRelease = Build.VERSION.RELEASE,
                smallestWidthDp = resources.configuration.smallestScreenWidthDp
                    .takeIf { it != Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED } ?: 0,
                model = Build.MODEL,
                osBuild = Build.ID,
                timezone = TimeZone.getDefault().id,
                screenWidthPx = metrics.widthPixels,
                screenHeightPx = metrics.heightPixels,
                density = metrics.density,
            )
        }

        // LocaleList arrived in API 24; below it the configuration holds the one locale.
        @Suppress("DEPRECATION")
        private fun deviceLocales(configuration: Configuration): List<Locale> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val list = configuration.locales
                (0 until list.size()).map { list.get(it) }
            } else {
                listOfNotNull(configuration.locale)
            }

        fun appVersion(context: Context): String =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Exception) {
                null
            }?.takeIf { it.isNotBlank() } ?: "unknown"
    }
}

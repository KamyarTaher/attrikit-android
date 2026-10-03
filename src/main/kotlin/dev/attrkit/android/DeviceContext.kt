package dev.attrkit.android

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
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
    val density: Double,
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
            density.isFinite() && density > 0.0 && density <= MAX_SCALE
        return DeviceSignals(
            deviceModel = model.bounded(),
            osBuild = osBuild.bounded(),
            timezone = timezone.bounded(),
            screenWidth = screenWidthPx.takeIf { hasScreen },
            screenHeight = screenHeightPx.takeIf { hasScreen },
            screenScale = density.takeIf { hasScreen },
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
        private const val MAX_SCALE = 8.0
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
            val (widthPx, heightPx) = fullDisplayPixels(context, metrics)
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
                screenWidthPx = widthPx,
                screenHeightPx = heightPx,
                density = scaleFromDpi(metrics.densityDpi),
            )
        }

        /**
         * The browser reports `screen.width`/`screen.height` and `devicePixelRatio`, which describe the
         * whole panel. `resources.displayMetrics` describes the app's usable area (no navigation bar,
         * different again in multi-window), so the full display comes from the display itself.
         * DisplayManager rather than WindowManager: the SDK holds the application context, and
         * WindowManager on a non-visual context is an incorrect-context-use violation from API 30.
         * `getRealMetrics` is deprecated from API 31 and still returns the whole display's logical
         * size, which is what Chrome's `screen` is. Falls back to the resources metrics if the display
         * cannot be read.
         */
        @Suppress("DEPRECATION")
        private fun fullDisplayPixels(context: Context, fallback: DisplayMetrics): Pair<Int, Int> =
            try {
                // getSystemService(String), not getSystemService(Class): the Class overload arrived
                // in API 23 and minSdk is 21, where it throws NoSuchMethodError, which no
                // `catch (Exception)` sees.
                val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display == null) {
                    fallback.widthPixels to fallback.heightPixels
                } else {
                    val real = DisplayMetrics()
                    display.getRealMetrics(real)
                    real.widthPixels to real.heightPixels
                }
            } catch (_: Exception) {
                fallback.widthPixels to fallback.heightPixels
            }

        /**
         * The same quantity as `DisplayMetrics.density`, computed in Double. The Float widens with
         * noise (a 411 dpi panel gives 2.568749904632568), which never equals the browser's 2.56875.
         */
        fun scaleFromDpi(densityDpi: Int): Double = densityDpi / 160.0

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

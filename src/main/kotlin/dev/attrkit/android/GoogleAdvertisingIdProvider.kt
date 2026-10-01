package dev.attrkit.android

import android.content.Context
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.appset.AppSet
import com.google.android.gms.tasks.Tasks
import dev.attrkit.core.AdvertisingIdProvider
import dev.attrkit.core.AdvertisingIdentifiers
import java.util.concurrent.TimeUnit

internal class AdvertisingIdReading(val id: String?, val limitAdTracking: Boolean)

/**
 * The advertising identifiers of the device. The core calls this only while consent is
 * TRACKING_GRANTED; this class additionally never returns an id the user has switched off.
 *
 * The two sources fail independently: Play services missing or slow for the ad id must not cost
 * the App Set ID, and the reverse.
 */
internal class GoogleAdvertisingIdProvider(
    private val advertisingId: () -> AdvertisingIdReading?,
    private val appSetId: () -> String?,
) : AdvertisingIdProvider {

    constructor(context: Context) : this(
        advertisingId = {
            val info = AdvertisingIdClient.getAdvertisingIdInfo(context.applicationContext)
            AdvertisingIdReading(info.id, info.isLimitAdTrackingEnabled)
        },
        appSetId = {
            val task = AppSet.getClient(context.applicationContext).appSetIdInfo
            Tasks.await(task, APP_SET_TIMEOUT_SECONDS, TimeUnit.SECONDS).id
        },
    )

    override fun identifiers(): AdvertisingIdentifiers =
        AdvertisingIdentifiers(
            gaid = readGaid(),
            appSetId = runCatching { appSetId() }.getOrNull()?.takeIf { it.isNotBlank() },
        )

    private fun readGaid(): String? {
        val reading = runCatching { advertisingId() }.getOrNull() ?: return null
        // A user with "Delete advertising ID" or limited ad tracking gets an all-zero id (or a
        // flagged one), and an app without the AD_ID permission on Android 13+ gets zeros too.
        // Either is the user's refusal, not an identifier.
        if (reading.limitAdTracking) return null
        val id = reading.id?.trim().orEmpty()
        if (id.isEmpty() || id.all { it == '0' || it == '-' }) return null
        return id
    }

    private companion object {
        const val APP_SET_TIMEOUT_SECONDS = 3L
    }
}

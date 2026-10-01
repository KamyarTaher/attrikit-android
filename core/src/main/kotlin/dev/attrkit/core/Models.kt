package dev.attrkit.core

import java.time.Instant
import java.util.UUID

const val ATTRIKIT_ANDROID_SDK_VERSION = "1.3.0"

/**
 * Consent state machine:
 *
 * UNKNOWN -> MEASUREMENT_GRANTED | TRACKING_GRANTED | DENIED | REVOKED
 * MEASUREMENT_GRANTED <-> TRACKING_GRANTED
 * MEASUREMENT_GRANTED | TRACKING_GRANTED -> DENIED | REVOKED
 * DENIED -> MEASUREMENT_GRANTED | TRACKING_GRANTED | REVOKED
 * REVOKED -> MEASUREMENT_GRANTED | TRACKING_GRANTED | DENIED
 *
 * UNKNOWN/DENIED/REVOKED perform no measurement. REVOKED additionally rotates the
 * install epoch and clears queued measurement data. Advertising identifiers are read
 * and serialized only in TRACKING_GRANTED.
 */
enum class ConsentState(val wireValue: String) {
    UNKNOWN("unknown"),
    MEASUREMENT_GRANTED("measurement_granted"),
    TRACKING_GRANTED("tracking_granted"),
    DENIED("denied"),
    REVOKED("revoked");

    val allowsMeasurement: Boolean
        get() = this == MEASUREMENT_GRANTED || this == TRACKING_GRANTED

    val allowsAdvertisingIdentifiers: Boolean
        get() = this == TRACKING_GRANTED

    companion object {
        fun fromWire(value: String?): ConsentState =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

data class InstallationIdentity(
    val installationId: UUID,
    val installEpochId: UUID,
    val localLineagePresent: Boolean,
    val localEpochPresent: Boolean,
)

data class CoarseContext(
    val countryCode: String? = null,
    val regionCode: String? = null,
    val osMajor: String? = null,
    val deviceClass: String? = null,
    val locale: String? = null,
)

data class DeviceSignals(
    val deviceModel: String? = null,
    val osBuild: String? = null,
    val timezone: String? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val screenScale: Double? = null,
    val languages: List<String> = emptyList(),
) {
    init {
        screenWidth?.let { require(it > 0) { "screenWidth must be positive" } }
        screenHeight?.let { require(it > 0) { "screenHeight must be positive" } }
        screenScale?.let {
            require(it.isFinite() && it > 0.0) { "screenScale must be finite and positive" }
        }
    }
}

data class PlayInstallReferrer(
    val referrer: String,
    val capturedAt: Instant,
)

sealed interface EventValue {
    data class Text(val value: String) : EventValue
    data class Numeric(val value: Double) : EventValue {
        init {
            require(value.isFinite()) { "event numeric properties must be finite" }
        }
    }
    data class Flag(val value: Boolean) : EventValue
    data object Null : EventValue

    companion object {
        fun from(value: Any?): EventValue = when (value) {
            null -> Null
            is String -> Text(value)
            is Boolean -> Flag(value)
            is Byte -> Numeric(value.toDouble())
            is Short -> Numeric(value.toDouble())
            is Int -> Numeric(value.toDouble())
            is Long -> {
                require(value in -9_007_199_254_740_992L..9_007_199_254_740_992L) {
                    "event Long properties must be exactly representable as JSON numbers"
                }
                Numeric(value.toDouble())
            }
            // Through the shortest decimal form: `0.1f.toDouble()` is 0.10000000149011612, which is
            // not the number the caller wrote.
            is Float -> Numeric(value.toString().toDouble())
            is Double -> Numeric(value)
            else -> throw IllegalArgumentException("event properties must be scalar")
        }
    }
}

data class FirstOpenEnvelope(
    val installationId: UUID,
    val installEpochId: UUID,
    val occurredAt: Instant,
    val appVersion: String,
    val coarseContext: CoarseContext,
    val consent: ConsentState,
    val playInstallReferrer: PlayInstallReferrer? = null,
    val deviceSignals: DeviceSignals? = null,
    /*
     * Shared ingest v1 still names its two UUID platform slots idfa/idfv. Android maps a
     * UUID-shaped GAID/App Set ID into those existing slots so the strict server schema
     * remains the source of truth. Both are gated more strictly than the schema requires:
     * TRACKING_GRANTED only.
     */
    val advertisingId: UUID? = null,
    val appSetId: UUID? = null,
    val localLineagePresent: Boolean,
    val localEpochPresent: Boolean,
    val localSignalsConflict: Boolean = false,
    /** Google's DMA values when the first-open body was built; omitted from the JSON when null. */
    val dma: DmaConsent? = null,
) {
    /**
     * 1.1.0's constructor, its default-argument bridge, and below its copy and copy$default: adding
     * [dma] gave each a new JVM signature, and a binary compiled against 1.1.0 links to these by
     * exact descriptor. Hidden, so Kotlin source resolves to the generated ones.
     */
    @Deprecated("The 1.1.0 constructor, kept so a binary compiled against 1.1.0 still links", level = DeprecationLevel.HIDDEN)
    constructor(
        installationId: UUID,
        installEpochId: UUID,
        occurredAt: Instant,
        appVersion: String,
        coarseContext: CoarseContext,
        consent: ConsentState,
        playInstallReferrer: PlayInstallReferrer? = null,
        deviceSignals: DeviceSignals? = null,
        advertisingId: UUID? = null,
        appSetId: UUID? = null,
        localLineagePresent: Boolean,
        localEpochPresent: Boolean,
        localSignalsConflict: Boolean = false,
    ) : this(
        installationId, installEpochId, occurredAt, appVersion, coarseContext, consent, playInstallReferrer,
        deviceSignals, advertisingId, appSetId, localLineagePresent, localEpochPresent, localSignalsConflict, null,
    )

    /** 1.1.0's copy, which cannot name [dma] and so keeps it. */
    @Deprecated("The 1.1.0 copy, kept so a binary compiled against 1.1.0 still links", level = DeprecationLevel.HIDDEN)
    fun copy(
        installationId: UUID = this.installationId,
        installEpochId: UUID = this.installEpochId,
        occurredAt: Instant = this.occurredAt,
        appVersion: String = this.appVersion,
        coarseContext: CoarseContext = this.coarseContext,
        consent: ConsentState = this.consent,
        playInstallReferrer: PlayInstallReferrer? = this.playInstallReferrer,
        deviceSignals: DeviceSignals? = this.deviceSignals,
        advertisingId: UUID? = this.advertisingId,
        appSetId: UUID? = this.appSetId,
        localLineagePresent: Boolean = this.localLineagePresent,
        localEpochPresent: Boolean = this.localEpochPresent,
        localSignalsConflict: Boolean = this.localSignalsConflict,
    ): FirstOpenEnvelope = FirstOpenEnvelope(
        installationId, installEpochId, occurredAt, appVersion, coarseContext, consent, playInstallReferrer,
        deviceSignals, advertisingId, appSetId, localLineagePresent, localEpochPresent, localSignalsConflict, dma,
    )

    fun toJson(): String = Json.stringify(toJsonValue())

    internal fun toJsonValue(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "schema_version" to 1,
        "installation_id" to installationId.toString().lowercase(),
        "install_epoch_id" to installEpochId.toString().lowercase(),
        "occurred_at" to occurredAt.toWireTimestamp(),
        "app_version" to appVersion,
        "coarse_context" to coarseContext.toJsonValue(),
        "consent" to linkedMapOf<String, Any?>(
            "state" to consent.wireValue,
            "policy_version" to 1,
        ).also { consentJson -> dma?.let { consentJson["dma"] = it.toJsonValue() } },
    ).also { json ->
        playInstallReferrer?.let {
            json["play_install_referrer"] = linkedMapOf(
                "referrer" to it.referrer,
                "captured_at" to it.capturedAt.toWireTimestamp(),
            )
        }
        deviceSignals?.toJsonValue()?.takeIf { it.isNotEmpty() }?.let {
            json["device_signals"] = it
        }
        advertisingId?.let { json["idfa"] = it.toString().lowercase() }
        appSetId?.let { json["idfv"] = it.toString().lowercase() }
        json["local_lineage_present"] = localLineagePresent
        json["local_epoch_present"] = localEpochPresent
        json["local_signals_conflict"] = localSignalsConflict
    }
}

data class EventConsent(
    val measurement: String,
    val tracking: String,
    val policyVersion: Int = 1,
    /** Google's DMA values when the event was recorded; omitted from the JSON when null. */
    val dma: DmaConsent? = null,
) {
    /** 1.1.0's constructor and default-argument bridge, as on [FirstOpenEnvelope]. */
    @Deprecated("The 1.1.0 constructor, kept so a binary compiled against 1.1.0 still links", level = DeprecationLevel.HIDDEN)
    constructor(measurement: String, tracking: String, policyVersion: Int = 1) :
        this(measurement, tracking, policyVersion, null)

    /** 1.1.0's copy, which cannot name [dma] and so keeps it. */
    @Deprecated("The 1.1.0 copy, kept so a binary compiled against 1.1.0 still links", level = DeprecationLevel.HIDDEN)
    fun copy(
        measurement: String = this.measurement,
        tracking: String = this.tracking,
        policyVersion: Int = this.policyVersion,
    ): EventConsent = EventConsent(measurement, tracking, policyVersion, dma)
}

/**
 * Google's three DMA values (`consent.dma` on the wire, packages/shared `dmaConsentSchema`): read
 * from the IAB TCF keys a consent management platform stored, or set by the app. An ad flag is
 * null when there is nothing to say, and null is omitted from the JSON.
 */
data class DmaConsent(
    val eea: Boolean,
    val adUserData: Boolean? = null,
    val adPersonalization: Boolean? = null,
    val source: Source,
) {
    enum class Source(val wireValue: String) {
        TCF("tcf"),
        MANUAL("manual"),
    }

    internal fun toJsonValue(): Map<String, Any?> = linkedMapOf<String, Any?>("eea" to eea).also { json ->
        adUserData?.let { json["ad_user_data"] = it }
        adPersonalization?.let { json["ad_personalization"] = it }
        json["source"] = source.wireValue
    }
}

data class EventEnvelope(
    val eventId: UUID,
    val eventName: String,
    val eventVersion: Int,
    val occurredAt: Instant,
    val sentAt: Instant,
    val installationId: UUID,
    val installEpochId: UUID,
    val sessionId: UUID,
    val consent: EventConsent,
    val properties: Map<String, EventValue>,
) {
    val isProtectedRevenueEvent: Boolean
        get() = eventName == "purchase" || eventName == "refund" ||
            eventName.endsWith(".purchase") || eventName.endsWith(".refund")

    fun toJson(): String = Json.stringify(toJsonValue())

    internal fun toJsonValue(): Map<String, Any?> = linkedMapOf(
        "schema_version" to 1,
        "event_id" to eventId.toString().lowercase(),
        "event_name" to eventName,
        "event_version" to eventVersion,
        "occurred_at" to occurredAt.toWireTimestamp(),
        "sent_at" to sentAt.toWireTimestamp(),
        "installation_id" to installationId.toString().lowercase(),
        "install_epoch_id" to installEpochId.toString().lowercase(),
        "session_id" to sessionId.toString().lowercase(),
        "source" to "android_sdk",
        "consent" to linkedMapOf<String, Any?>(
            "measurement" to consent.measurement,
            "tracking" to consent.tracking,
            "policy_version" to consent.policyVersion,
        ).also { consentJson -> consent.dma?.let { consentJson["dma"] = it.toJsonValue() } },
        "properties" to properties.mapValues { (_, value) -> value.toJsonScalar() },
    )
}

data class EventBatch(
    val batchId: String,
    val events: List<EventEnvelope>,
) {
    fun toJson(): String = Json.stringify(
        linkedMapOf(
            "batch_id" to batchId,
            "events" to events.map { it.toJsonValue() },
        ),
    )
}

enum class TrackResult {
    BUFFERED,
    QUEUED,
    REJECTED_NOT_CONSENTED,
    REJECTED_INVALID,
    REJECTED_QUEUE_FULL,
}

data class WorkResult(
    val firstOpenSettled: Boolean,
    val queuedEventCount: Int,
    val nextAttemptAt: Instant? = null,
)

private fun CoarseContext.toJsonValue(): Map<String, Any?> =
    linkedMapOf<String, Any?>().also { json ->
        countryCode?.let { json["country_code"] = it }
        regionCode?.let { json["region_code"] = it }
        osMajor?.let { json["os_major"] = it }
        deviceClass?.let { json["device_class"] = it }
        locale?.let { json["locale"] = it }
    }

private fun DeviceSignals.toJsonValue(): Map<String, Any?> =
    linkedMapOf<String, Any?>().also { json ->
        deviceModel?.let { json["device_model"] = it }
        osBuild?.let { json["os_build"] = it }
        timezone?.let { json["timezone"] = it }
        if (screenWidth != null && screenHeight != null && screenScale != null) {
            json["screen"] = linkedMapOf(
                "w" to screenWidth,
                "h" to screenHeight,
                "scale" to screenScale,
            )
        }
        if (languages.isNotEmpty()) json["languages"] = languages
    }

private fun EventValue.toJsonScalar(): Any? = when (this) {
    is EventValue.Text -> value
    is EventValue.Numeric -> value
    is EventValue.Flag -> value
    EventValue.Null -> null
}

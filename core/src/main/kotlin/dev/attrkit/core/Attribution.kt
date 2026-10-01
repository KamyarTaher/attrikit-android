package dev.attrkit.core

import java.time.Instant
import java.util.UUID

/**
 * An install's attribution as `GET /v1/attribution/{install_epoch_id}` answers it. The same fields
 * as the iOS SDK's `Attribution`, read from the same body.
 */
data class Attribution(
    val method: String,
    val sourceType: String?,
    val network: String?,
    /** AttriKit's own campaign id, the one the dashboard and the management API use. */
    val campaignID: String?,
    /**
     * `provisional` until the server's finality window closes (72 hours after first open), then
     * `final`. A provisional answer can still change, and the core keeps asking while it is one.
     */
    val finality: String,
    val policyVersion: Int,
    /** The server's own verdict, absent from servers before 2026-09-30. Read it through [status]. */
    val attributionStatus: String? = null,
    val adsetID: String? = null,
    val adID: String? = null,
    /** The server's match confidence, 0 to 1, for a device match; null otherwise. */
    val confidence: Double? = null,
    val campaignName: String? = null,
    /** The campaign id as the ad network itself names it; null when none is on file. */
    val networkCampaignID: String? = null,
) {
    /**
     * What this answer says about the install: the server's `attribution_status` when it sent one,
     * otherwise the derivation the server makes (packages/shared/src/attribution-context.ts), so a
     * build talking to an older server reads the same verdict. A device match is recognised by
     * EITHER field, because the route may send it as `method: "unattributed",
     * source_type: "device_match"`.
     */
    val status: AttributionStatus
        get() {
            AttributionStatus.fromWire(attributionStatus)?.let { verdict ->
                if (verdict != AttributionStatus.PENDING && verdict != AttributionStatus.TIMED_OUT) return verdict
            }
            if (sourceType == "unattributed") return AttributionStatus.ORGANIC
            if (sourceType == "device_match" || method == "device_matched") return AttributionStatus.DEVICE_MATCHED
            return AttributionStatus.ATTRIBUTED
        }

    /** True while the server may still change this answer. */
    val isProvisional: Boolean
        get() = finality == "provisional"
}

/**
 * Why the campaign context is what it is. The first five are the server's own `attribution_status`
 * values; [TIMED_OUT] is the SDK's.
 */
enum class AttributionStatus(val wireValue: String) {
    /** A deterministic or platform-verified match. */
    ATTRIBUTED("attributed"),
    /** Matched to a click by device matching. */
    DEVICE_MATCHED("device_matched"),
    /** The server answered, and no campaign claimed this install. */
    ORGANIC("organic"),
    /** No answer yet. The core is still asking, or has not started. */
    PENDING("pending"),
    /** Consent does not allow measurement, was withdrawn, or a data deletion is in progress. */
    CONSENT_REQUIRED("consent_required"),
    /** The core stopped asking inside its window without an answer; a later launch asks again. */
    TIMED_OUT("timed_out");

    companion object {
        fun fromWire(value: String?): AttributionStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/** What [AttriKitCore.attribution] answers. Mirrors the iOS SDK's `AttributionResult`. */
sealed interface AttributionResult {
    data class Attributed(val attribution: Attribution) : AttributionResult
    /** The server answered and no campaign claimed this install. */
    data object Unattributed : AttributionResult
    /**
     * No answer arrived: the wait ran out, or the poll gave up. Not a claim about the install. After
     * the poll has given up, later calls answer this at once until the next launch or consent change.
     */
    data object TimedOut : AttributionResult
    data object NotStarted : AttributionResult
    data object ConsentRequired : AttributionResult
    data object Failed : AttributionResult
}

/** One state of this install's attribution, as an [AttributionListener] receives it. */
data class AttributionUpdate(
    val status: AttributionStatus,
    /** The server's answer when there is one, whatever its status. */
    val attribution: Attribution?,
)

/**
 * Receives the attribution state when it changes: once on registration with the current state, then
 * on every change (a provisional answer becoming final, consent being withdrawn, the poll giving
 * up). It runs on whichever host thread was inside the core when the state changed, never while the
 * core holds a lock the host's own calls take, so it may call back into the core.
 */
fun interface AttributionListener {
    fun onAttributionUpdate(update: AttributionUpdate)
}

/** What [AttriKitCore.deleteData] reports. */
sealed interface DeletionResult {
    /** The server acknowledged the erasure and everything the core stored was removed. */
    data object Completed : DeletionResult
    /** [AttriKitCore.start] has not run, so there is no key to send the request with. */
    data object NotStarted : DeletionResult
    /**
     * The request was not acknowledged: [statusCode] is the HTTP status, or null for a transport
     * failure or a stored deletion record the core cannot read. The deletion stays pending, so
     * measurement stays halted until it completes; [AttriKitCore.work] retries it.
     */
    data class Failed(val statusCode: Int?) : DeletionResult
}

internal data class IdentifyEnvelope(
    val installationId: UUID,
    val installEpochId: UUID,
    val occurredAt: Instant,
    val customerUserID: String,
) {
    fun toJson(): String = Json.stringify(
        linkedMapOf(
            "schema_version" to 1,
            "installation_id" to installationId.toString().lowercase(),
            "install_epoch_id" to installEpochId.toString().lowercase(),
            "occurred_at" to occurredAt.toWireTimestamp(),
            "customer_user_id" to customerUserID,
        ),
    )
}

/**
 * The body of a 200 from the attribution route. Typed strictly, as the iOS decoder is: a recognised
 * field of the wrong type refuses the whole body rather than reading as absent, so a malformed
 * answer is never applied. Keys it does not name are ignored, which is what lets the server add
 * keys without breaking the binaries already in apps.
 */
internal data class AttributionResponse(
    val status: String?,
    val method: String?,
    val sourceType: String?,
    val network: String?,
    val campaignID: String?,
    val finality: String?,
    val policyVersion: Int?,
    val attributionStatus: String?,
    val adsetID: String?,
    val adID: String?,
    val confidence: Double?,
    val campaignName: String?,
    val networkCampaignID: String?,
) {
    /** A 200 that is not an answer yet: a pending body with no method must not read as organic. */
    val isPending: Boolean
        get() = method == null && (status == "pending" || attributionStatus == "pending")

    val attribution: Attribution?
        get() {
            val method = method ?: return null
            val finality = finality ?: return null
            val policyVersion = policyVersion ?: return null
            return Attribution(
                method = method,
                sourceType = sourceType,
                network = network,
                campaignID = campaignID,
                finality = finality,
                policyVersion = policyVersion,
                attributionStatus = attributionStatus,
                adsetID = adsetID,
                adID = adID,
                confidence = confidence,
                campaignName = campaignName,
                networkCampaignID = networkCampaignID,
            )
        }

    companion object {
        /** Throws IllegalArgumentException when the body is not a JSON object of the expected shape. */
        fun parse(body: ByteArray): AttributionResponse {
            val root = Json.parse(body.decodeToString())
            require(root is Map<*, *>) { "attribution body must be a JSON object" }
            fun text(key: String): String? = when (val value = root[key]) {
                null -> null
                is String -> value
                else -> throw IllegalArgumentException("$key must be a string")
            }
            fun number(key: String): Double? = when (val value = root[key]) {
                null -> null
                is Long -> value.toDouble()
                is Double -> value
                else -> throw IllegalArgumentException("$key must be a number")
            }
            val policy = number("policy_version")
            require(policy == null || (policy == Math.rint(policy) && policy in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())) {
                "policy_version must be an integer"
            }
            return AttributionResponse(
                status = text("status"),
                method = text("method"),
                sourceType = text("source_type"),
                network = text("network"),
                campaignID = text("campaign_id"),
                finality = text("finality"),
                policyVersion = policy?.toInt(),
                attributionStatus = text("attribution_status"),
                adsetID = text("adset_id"),
                adID = text("ad_id"),
                confidence = number("confidence"),
                campaignName = text("campaign_name"),
                networkCampaignID = text("network_campaign_id"),
            )
        }
    }
}

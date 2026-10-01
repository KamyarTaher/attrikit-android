package dev.attrkit.core

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

data class CoreConfiguration(
    val endpoint: String,
    val appVersion: String,
    val coarseContext: CoarseContext,
    val deviceSignals: DeviceSignals? = null,
    val maxQueueEvents: Int = 100,
    val maxQueueBytes: Int = 1_048_576,
    val maxQueueAge: Duration = Duration.ofHours(72),
)

class AttriKitCore(
    private val configuration: CoreConfiguration,
    private val store: KeyValueStore,
    private val clock: Clock,
    private val transport: HttpTransport,
    private val referrerClient: ReferrerClient,
    private val advertisingIdProvider: AdvertisingIdProvider,
    private val ids: IdGenerator = RandomIdGenerator,
    /// Where the consent management platform stored the IAB TCF keys: the app's default
    /// SharedPreferences, which the Android adapter passes. [TcfPreferences.NONE] reads nothing.
    private val tcfPreferences: TcfPreferences = TcfPreferences.NONE,
) {
    /**
     * 1.1.0's constructor and, through its default, 1.1.0's default-argument bridge. Adding
     * [tcfPreferences] gave the primary constructor new JVM signatures; a binary compiled against
     * 1.1.0 links to these by exact descriptor. Hidden, so Kotlin source always resolves to the
     * primary constructor; it reads no TCF keys, as 1.1.0 did not.
     */
    @Deprecated("The 1.1.0 constructor, kept so a binary compiled against 1.1.0 still links", level = DeprecationLevel.HIDDEN)
    constructor(
        configuration: CoreConfiguration,
        store: KeyValueStore,
        clock: Clock,
        transport: HttpTransport,
        referrerClient: ReferrerClient,
        advertisingIdProvider: AdvertisingIdProvider,
        ids: IdGenerator = RandomIdGenerator,
    ) : this(configuration, store, clock, transport, referrerClient, advertisingIdProvider, ids, TcfPreferences.NONE)

    private data class BufferedEvent(
        val name: String,
        val version: Int,
        val properties: Map<String, EventValue>,
        val occurredAt: Instant,
    )

    private data class ActiveSession(val index: Int, val startedAt: Instant)

    private val identities = IdentityRepository(store, ids)
    private val queue = EventQueue(
        store,
        ids,
        configuration.maxQueueEvents,
        configuration.maxQueueBytes,
        configuration.maxQueueAge,
        clock,
    )
    private var apiKey: String? = null
    private var consent = ConsentState.fromWire(store.get(StorageKeys.CONSENT))
    private var identity: InstallationIdentity? = null
    private var sessionId: UUID = ids.next()
    private var buffered = mutableListOf<BufferedEvent>()
    private var activeSession: ActiveSession? = null
    private var lastSessionEndedAt: Instant? = null
    private var lastSessionIndex: Int? = null
    private var readsTcf = true

    /// The id the host set with setUserID. Held in memory before measurement is allowed and
    /// persisted once it is, exactly as the iOS SDK does; cleared on a denial, a revocation and a
    /// completed deletion.
    private var userId: String? = null

    /// The epoch whose advertising ids this process has read, so a launch reads them once. Cleared
    /// when tracking consent is lost, so the next grant reads them again.
    private var deviceIdsReadEpoch: String? = null

    /// True from the moment an erasure is recorded until the server acknowledges it. Read from the
    /// tombstone at construction, so a relaunch in the middle of a deletion stays halted.
    private var deletionPending = store.get(StorageKeys.DELETION_TOMBSTONE) != null

    // Attribution state lives in memory only, as it does on iOS: a relaunch asks again.
    /// The host asked for attribution (attribution() or a listener). Nothing polls before that, so a
    /// host that never reads attribution never pays for the requests.
    private var attributionRequested = false
    private var attributionCache: AttributionResult? = null
    private var attributionETag: String? = null
    /// The poll ran out inside its window with no answer: reported as TIMED_OUT, never as organic.
    private var attributionPollStopped = false
    /// The poll will not run again this session: it settled, was refused, or ran out.
    private var attributionPollOver = false
    private var attributionPoll: AttributionPoll? = null
    @Volatile
    private var attributionListener: AttributionListener? = null
    private var lastPublishedAttribution: AttributionUpdate? = null
    private val attributionDirty = AtomicBoolean(false)

    init {
        recoverInterruptedConsentCleanup()
    }

    /**
     * Serialises the WORKER, and is never held across NETWORK I/O together with the instance
     * monitor. The two locks do nest, always `worker` then `this` and never the reverse: work()
     * takes this lock and then enters `synchronized(this)` to read state and to build its result,
     * which is the order applyStart's doc names as the one every other path must not invert. What
     * happens outside `this` is every round trip -- so a host calling track(), onForeground() or
     * onBackground() from the main thread is never queued behind someone else's I/O, which is the
     * property this lock exists for. Reading the rule as "never held together at all" would make
     * work() itself a violation and hide the ordering that actually prevents the deadlock.
     *
     * It used to be: work() was `@Synchronized` and drains up to 100 batches sequentially, so the
     * main thread of an app we do not control blocked for the whole drain. An ANR in a customer's
     * app is the worst failure this SDK can cause, and nothing in this package could have observed
     * it -- every test here is single-threaded.
     *
     * tryLock rather than lock: a second work() would drain the very queue the running worker is
     * draining, so waiting for it buys nothing and costs the caller its thread. It reports the
     * attempts already written to disk instead of doing the work twice.
     */
    private val worker = ReentrantLock()

    /// Held across one attribution request so work() and attribution() never poll twice at once.
    /// Taken with tryLock and never while waiting on anything else: `worker` then this, never back.
    private val pollLock = ReentrantLock()

    /// Serialises delivery to the attribution listener. Host code runs under it, so nothing else the
    /// host's own calls take (the instance monitor, `worker`) is ever held when it is entered.
    private val publishLock = ReentrantLock()

    private data class AttributionPoll(
        val epoch: String,
        val startedAt: Instant,
        val nextAt: Instant,
        val fastAttempts: Int,
        val fastDelayMillis: Long,
        val ladderIndex: Int,
    )

    private data class PollAttempt(val request: HttpRequest, val poll: AttributionPoll)

    private data class IdentifyAttempt(
        val request: HttpRequest,
        val epoch: String,
        val userId: String,
        val retry: RetryState?,
        val now: Instant,
    )

    private data class DeviceIdentifiersAttempt(
        val request: HttpRequest,
        val marker: String,
        val retry: RetryState?,
        val now: Instant,
    )

    private data class DeletionAttempt(val request: HttpRequest, val retry: RetryState?, val now: Instant)

    private data class DeletionOutcome(val result: DeletionResult, val nextAttemptAt: Instant?)

    private data class ReceiptAttempt(
        val request: HttpRequest,
        val body: String,
        val idempotencyKey: String,
        val retry: RetryState?,
        val now: Instant,
    )

    private data class FirstOpenAttempt(
        val request: HttpRequest,
        val body: String,
        val epoch: String,
        val retry: RetryState?,
        val now: Instant,
    )

    private data class FlushWindow(val now: Instant, val retry: RetryState?)

    /**
     * The installation id the SDK is measuring under, in the lowercase spelling first-open and
     * events carry, or null when it is not measuring. Hand it to your own backend wherever revenue
     * is recorded -- RevenueCat's app user id (the server joins a webhook whose `app_user_id`
     * equals it directly), Stripe metadata -- so revenue the SDK never saw joins this install.
     *
     * It is the identity the SDK's own initialization established, never one read from storage or
     * created on the side, so it always equals the id on the SDK's requests. [start] initializes
     * before it returns, so the id is available as soon as [start] returns with a consent that
     * allows measurement.
     *
     * Exactly what it returns:
     * - null before [start], whenever consent does not allow measurement (UNKNOWN, DENIED,
     *   REVOKED), and while a [deleteData] request is pending;
     * - DENIED wipes the identity and REVOKED keeps it and rotates the install epoch, but neither
     *   removes the stored installation id, so a later grant returns the SAME id as before (after a
     *   revocation, under a new install epoch).
     */
    fun installationId(): String? = synchronized(this) {
        if (!consent.allowsMeasurement || deletionPending) null else identity?.installationId?.toString()?.lowercase()
    }

    /**
     * The user's answers to Google's EU consent questions: whether EEA, UK or Swiss rules apply to
     * them, and their ad_user_data and ad_personalization consents. Google requires these for every
     * user in those regions. They go to Google with every event from now on, win over the IAB TCF
     * consent the SDK otherwise reads, and are kept across launches until [clearGoogleConsent].
     * The SDK's own consent ([setConsent]) is separate and unchanged.
     */
    fun setGoogleConsent(eea: Boolean, adUserData: Boolean, adPersonalization: Boolean): Unit = synchronized(this) {
        val encoded = listOf(eea, adUserData, adPersonalization).joinToString("|") { if (it) "1" else "0" }
        store.write(mapOf(StorageKeys.MANUAL_DMA_CONSENT to encoded))
    }

    /** Forgets the values set with [setGoogleConsent], so the IAB TCF consent applies again. */
    fun clearGoogleConsent(): Unit = synchronized(this) {
        store.write(mapOf(StorageKeys.MANUAL_DMA_CONSENT to null))
    }

    /**
     * The SDK reads the IAB TCF consent your consent management platform stores on the device and
     * passes Google the answers it gives. Pass false before [start] to stop reading it.
     */
    fun setTcfDataCollectionEnabled(enabled: Boolean): Unit = synchronized(this) {
        readsTcf = enabled
    }

    /// Google's DMA values for what is being recorded now: the app's explicit values, else what
    /// the TCF keys say at this moment. Read for every event and first-open rather than cached, so
    /// a choice the user changes in the consent platform applies from the next event.
    private fun dmaConsent(): DmaConsent? {
        manualDmaConsent()?.let { return it }
        return if (readsTcf) TcfConsent.dmaConsent(tcfPreferences) else null
    }

    private fun manualDmaConsent(): DmaConsent? {
        val parts = store.get(StorageKeys.MANUAL_DMA_CONSENT)?.split('|') ?: return null
        if (parts.size != 3 || parts.any { it != "0" && it != "1" }) return null
        return DmaConsent(parts[0] == "1", parts[1] == "1", parts[2] == "1", DmaConsent.Source.MANUAL)
    }

    fun start(apiKey: String, consent: ConsentState): WorkResult {
        require(apiKey.toByteArray().size in 16..512) { "apiKey must be 16..512 bytes" }
        if (applyStart(apiKey, consent)) ensureFirstOpenSnapshot()
        refreshDeviceIdentifiers()
        return work()
    }

    /**
     * The STATE half of start(). Every public entry point is split this way: the part that mutates
     * this object runs under the monitor, and work() -- which talks to the network -- runs after
     * the monitor has been released. Calling work() from inside a `@Synchronized` method would
     * also invert the lock order (`this` then `worker`, against work()'s `worker` then `this`) and
     * deadlock the two.
     *
     * Returns whether measurement JUST began, which is the caller's cue to take the first-open
     * snapshot. It is a return value rather than a call inside beginMeasurement() because that
     * snapshot makes two Play IPC round trips: `synchronized(this)` is re-entrant, so a call from
     * here would have run them holding the monitor -- the same reason work() is not called here,
     * and the same reason applyBackground() hands its session_end back to onBackground() instead of
     * sending it. Only the caller, which holds nothing, can honour that rule.
     */
    @Synchronized
    private fun applyStart(apiKey: String, consent: ConsentState): Boolean {
        if (this.apiKey != null) {
            return if (this.consent != consent) applyConsent(consent) else false
        }
        this.apiKey = apiKey
        if (deletionPending) {
            // An erasure the server has not acknowledged outranks everything the host asks for: the
            // consent is held in memory only, nothing is written, and measurement does not begin.
            // work() retries the deletion; see deleteData.
            this.consent = consent
            haltForPendingDeletion()
            return false
        }
        rearmRefusedFirstOpenForLaunch()
        if (!consent.allowsMeasurement) {
            // Reuse setConsent's DENIED/REVOKED arms (receipt + wipe / revoke). Returning here
            // used to leave a previous grant's on-disk queue intact; a later grant then uploaded it.
            return applyConsent(consent)
        }
        val previous = this.consent
        this.consent = consent
        val rewritten = rewrittenPendingFirstOpenForConsent(previous, consent)
        val writes = mutableMapOf<String, String?>(StorageKeys.CONSENT to consent.wireValue)
        if (rewritten != null) writes[StorageKeys.FIRST_OPEN_PENDING_BODY] = rewritten
        // Identifiers a build without this erase left on disk: an app rolled back to 1.3.0 changes
        // consent without knowing these keys.
        if (!consent.allowsAdvertisingIdentifiers) writes.putAll(DEVICE_ID_KEYS.associateWith { null })
        store.write(writes)
        beginMeasurement()
        return true
    }

    fun setConsent(newConsent: ConsentState): WorkResult {
        if (applyConsent(newConsent)) ensureFirstOpenSnapshot()
        refreshDeviceIdentifiers()
        return work()
    }

    /// The state half of setConsent(); see applyStart(), including what the Boolean is for.
    @Synchronized
    private fun applyConsent(newConsent: ConsentState): Boolean {
        val previous = consent
        if (previous == newConsent) return false
        if (identity == null && previous.allowsMeasurement &&
            (newConsent == ConsentState.DENIED || newConsent == ConsentState.REVOKED)
        ) {
            identity = identities.initialize()
        }
        if (newConsent == ConsentState.DENIED || newConsent == ConsentState.REVOKED) {
            val withdrawnEpoch = identity?.installEpochId?.toString()?.lowercase()
                ?: store.get(StorageKeys.INSTALL_EPOCH_ID)?.lowercase()
                ?: "none"
            val rewritten = if (newConsent != ConsentState.REVOKED) rewrittenPendingFirstOpenForConsent(previous, newConsent) else null
            // This marker is the write-ahead record. If the process dies anywhere before the
            // atomic final write, the next core finishes the wipe (and revocation rotation) before
            // it can measure. Persisting CONSENT first made a restart skip this cleanup forever.
            store.write(
                mapOf(
                    CONSENT_CLEANUP_PENDING to
                        "${newConsent.wireValue}|$withdrawnEpoch",
                ),
            )
            consent = newConsent
            completeConsentCleanup(newConsent)
            val writes = mutableMapOf<String, String?>(
                StorageKeys.CONSENT to newConsent.wireValue,
                CONSENT_CLEANUP_PENDING to null,
            )
            if (rewritten != null) writes[StorageKeys.FIRST_OPEN_PENDING_BODY] = rewritten
            store.write(writes)
            return false
        }
        consent = newConsent
        val rewritten = rewrittenPendingFirstOpenForConsent(previous, newConsent)
        val writes = mutableMapOf<String, String?>(StorageKeys.CONSENT to newConsent.wireValue)
        if (rewritten != null) writes[StorageKeys.FIRST_OPEN_PENDING_BODY] = rewritten
        // In the consent's own write, so no crash can leave an advertising id on disk under a
        // consent that no longer allows it.
        if (!newConsent.allowsAdvertisingIdentifiers) {
            writes.putAll(DEVICE_ID_KEYS.associateWith { null })
            deviceIdsReadEpoch = null
        }
        store.write(writes)
        if (!newConsent.allowsMeasurement || previous.allowsMeasurement || deletionPending) return false
        beginMeasurement()
        return true
    }

    private fun completeConsentCleanup(target: ConsentState) {
        when (target) {
            ConsentState.REVOKED -> revoke()
            ConsentState.DENIED -> {
                enqueueConsentReceipt(ConsentState.DENIED)
                eraseUserStateOnWithdrawal()
                queue.wipe()
                buffered.clear()
                activeSession = null
                identity = null
            }
            else -> return
        }
    }

    /** Completes a withdrawal whose write-ahead marker survived a process death. */
    private fun recoverInterruptedConsentCleanup() {
        val pending = store.get(CONSENT_CLEANUP_PENDING) ?: return
        val parts = pending.split('|', limit = 2)
        val target = ConsentState.fromWire(parts.firstOrNull())
        val withdrawnEpoch = parts.getOrNull(1)
        if ((target != ConsentState.DENIED && target != ConsentState.REVOKED) || withdrawnEpoch == null) {
            store.write(mapOf(CONSENT_CLEANUP_PENDING to null))
            return
        }
        consent = target
        val previous = ConsentState.fromWire(store.get(StorageKeys.CONSENT))
        if (previous.allowsMeasurement) identity = identities.initialize()
        val storedEpoch = store.get(StorageKeys.INSTALL_EPOCH_ID)?.lowercase()
        if (target == ConsentState.REVOKED && storedEpoch != null && storedEpoch != withdrawnEpoch) {
            // revoke() writes the receipt and wipes before rotating. A different epoch therefore
            // proves those irreversible cleanup steps completed before the crash; do not rotate
            // again or replace the old epoch's receipt with one naming the new epoch.
            queue.wipe()
            buffered.clear()
            activeSession = null
        } else {
            completeConsentCleanup(target)
        }
        val rewritten = if (target != ConsentState.REVOKED) rewrittenPendingFirstOpenForConsent(previous, target) else null
        val writes = mutableMapOf<String, String?>(
            StorageKeys.CONSENT to target.wireValue,
            CONSENT_CLEANUP_PENDING to null,
        )
        if (rewritten != null) writes[StorageKeys.FIRST_OPEN_PENDING_BODY] = rewritten
        store.write(writes)
    }

    private fun rearmRefusedFirstOpenForLaunch() {
        // The same per-launch second chance for an identify or a tracking receipt the server
        // refused outright.
        val refused = listOf(
            StorageKeys.IDENTIFY_REFUSED,
            StorageKeys.DEVICE_IDS_REFUSED,
            StorageKeys.TRACKING_RECEIPT_REFUSED,
        ).filter { store.get(it) != null }
        if (refused.isNotEmpty()) store.write(refused.associateWith { null })
        val epoch = store.get(StorageKeys.INSTALL_EPOCH_ID)?.lowercase() ?: return
        if (store.get(StorageKeys.FIRST_OPEN_REFUSED_EPOCH) == epoch) {
            store.write(mapOf(StorageKeys.FIRST_OPEN_REFUSED_EPOCH to null))
        }
    }

    /**
     * A failed first-open remains crash-safe, but advertising identifiers must not outlive
     * the tracking consent that allowed their collection. Keep the original first-open and
     * referrer timestamps while rewriting the SDK-owned canonical JSON before any retry.
     * Identifiers are removed on downgrade and are never added on a later upgrade; a later grant
     * sends them in an identify instead (submitDeviceIdentifiersIfDue).
     *
     * Returns the rewritten pending body if changed, or null if unchanged or not pending.
     * Written atomically with consent in a single KeyValueStore write to eliminate crash windows.
     */
    private fun rewrittenPendingFirstOpenForConsent(
        previous: ConsentState,
        current: ConsentState,
    ): String? {
        if (previous == current) return null
        val pending = store.get(StorageKeys.FIRST_OPEN_PENDING_BODY) ?: return null
        var rewritten = pending.replace(
            consentJsonPattern,
            """"consent":{"state":"${current.wireValue}","policy_version":1""",
        )
        if (!current.allowsAdvertisingIdentifiers) {
            rewritten = rewritten
                .replace(advertisingIdJsonPattern, "")
                .replace(appSetIdJsonPattern, "")
        }
        return if (rewritten != pending) rewritten else null
    }

    private fun rewritePendingFirstOpenForConsent(
        previous: ConsentState,
        current: ConsentState,
    ) {
        val rewritten = rewrittenPendingFirstOpenForConsent(previous, current) ?: return
        store.write(mapOf(StorageKeys.FIRST_OPEN_PENDING_BODY to rewritten))
    }

    fun track(
        eventName: String,
        properties: Map<String, Any?> = emptyMap(),
        eventVersion: Int = 1,
    ): TrackResult {
        val result = applyTrack(eventName, properties, eventVersion)
        if (result == TrackResult.QUEUED) work()
        return result
    }

    /// The state half of track(); see applyStart().
    @Synchronized
    private fun applyTrack(
        eventName: String,
        properties: Map<String, Any?>,
        eventVersion: Int,
    ): TrackResult {
        val normalized = runCatching {
            validateEvent(eventName, eventVersion, properties)
        }.getOrElse { return TrackResult.REJECTED_INVALID }
        // A deletion the server has not acknowledged halts collection, before start() as well as
        // after it: nothing may be buffered for a launch that is about to be erased.
        if (deletionPending) return TrackResult.REJECTED_NOT_CONSENTED
        val occurredAt = clock.now()
        if (apiKey == null) {
            // A recorded REFUSAL outranks the pre-start buffer. This checked apiKey before consent,
            // so a process starting with CONSENT=denied on disk buffered anyway and
            // beginMeasurement() drained it on the next grant -- collection under a refusal the user
            // had already given, and unlike the in-session case nothing about it looks like a race,
            // because no withdrawal arm runs in that process to clear anything.
            //
            // UNKNOWN is deliberately still buffered: nobody has decided, and pre-start buffering is
            // the advertised contract. `!consent.allowsMeasurement` would be the wrong predicate
            // here precisely because it swallows UNKNOWN too.
            if (consent == ConsentState.DENIED || consent == ConsentState.REVOKED) {
                return TrackResult.REJECTED_NOT_CONSENTED
            }
            buffered.add(BufferedEvent(eventName, eventVersion, normalized, occurredAt))
            if (buffered.size > 100) buffered.removeAt(0)
            return TrackResult.BUFFERED
        }
        if (!consent.allowsMeasurement) return TrackResult.REJECTED_NOT_CONSENTED
        val currentIdentity = identity ?: identities.initialize().also { identity = it }
        return enqueue(eventName, eventVersion, normalized, occurredAt, currentIdentity)
    }

    @Synchronized
    fun onForeground() {
        if (apiKey == null || !consent.allowsMeasurement || deletionPending || activeSession != null) return
        val now = clock.now()
        val recent = lastSessionEndedAt?.let {
            val gap = Duration.between(it, now)
            !gap.isNegative && gap <= Duration.ofSeconds(30)
        } ?: false
        val index = if (recent && lastSessionIndex != null) {
            lastSessionIndex!!
        } else {
            val next = (store.get(StorageKeys.SESSION_INDEX)?.toIntOrNull() ?: 0)
                .let { if (it == Int.MAX_VALUE) Int.MAX_VALUE else it + 1 }
            store.write(mapOf(StorageKeys.SESSION_INDEX to next.toString()))
            sessionId = ids.next()
            next
        }
        activeSession = ActiveSession(index, now)
    }

    fun onBackground() {
        if (applyBackground() == TrackResult.QUEUED) work()
    }

    /// The state half of onBackground(); see applyStart(). It calls applyTrack rather than track so
    /// the session_end is enqueued under the monitor and delivered after it has been released.
    @Synchronized
    private fun applyBackground(): TrackResult? {
        val session = activeSession ?: return null
        activeSession = null
        if (!consent.allowsMeasurement) return null
        val endedAt = clock.now()
        lastSessionEndedAt = endedAt
        lastSessionIndex = session.index
        val durationMs = Duration.between(session.startedAt, endedAt).toMillis().coerceAtLeast(0)
        return applyTrack(
            "session_end",
            mapOf(
                "duration_ms" to durationMs,
                "session_index" to session.index,
            ),
            1,
        )
    }

    fun work(): WorkResult {
        val result = drain()
        // After `worker` is released and with no monitor held: the listener is host code.
        publishAttribution()
        return result
    }

    private fun drain(): WorkResult {
        // Another thread is already draining; see `worker`. Report what is scheduled and hand the
        // caller its thread back rather than queueing it behind someone else's network round trips.
        if (!worker.tryLock()) return synchronized(this) { currentWorkResult(pendingNextAttempt()) }
        try {
            // An erasure the server has not acknowledged is the only work there is: collection is
            // halted, and a withdrawal receipt or an event for an install being erased is not sent.
            if (synchronized(this) { deletionPending }) {
                val deletionNext = drainDeletionIfDue()
                return synchronized(this) { currentWorkResult(deletionNext) }
            }
            // Raised before the drain below so it is sent in this pass.
            synchronized(this) { reconcileTrackingReceipt() }
            // Ahead of the consent gate below, and deliberately so: a withdrawal receipt is only
            // ever raised by consent going away, so anything gated on allowsMeasurement can never
            // send it.
            var receiptNext = drainConsentReceiptIfDue()
            // Again once that drain has run: delivering this epoch's withdrawal frees the slot a
            // tracking grant waits for. Raises at most one receipt, so this cannot loop.
            if (synchronized(this) { reconcileTrackingReceipt() }) receiptNext = drainConsentReceiptIfDue()
            val measuring = synchronized(this) {
                if (apiKey == null || !consent.allowsMeasurement) {
                    false
                } else {
                    if (identity == null) identity = identities.initialize()
                    true
                }
            }
            if (!measuring) return synchronized(this) { currentWorkResult(receiptNext) }
            ensureFirstOpenSnapshot()
            val firstNext = submitFirstOpenIfDue()
            val identifyNext = minInstant(submitIdentifyIfDue(), submitDeviceIdentifiersIfDue())
            val eventNext = flushEventsIfDue()
            val pollNext = pollAttributionIfDue()
            return synchronized(this) {
                currentWorkResult(
                    minInstant(
                        receiptNext,
                        minInstant(firstNext, minInstant(identifyNext, minInstant(eventNext, pollNext))),
                    ),
                )
            }
        } finally {
            worker.unlock()
        }
    }

    /// What a work() that declined to run can still tell the host: the earliest attempt already on
    /// disk. Answering `null` there would read as "there is nothing left to schedule", which is the
    /// one thing a host uses this field to decide.
    private fun pendingNextAttempt(): Instant? = minInstant(
        RetryState.decode(store.get(StorageKeys.CONSENT_RECEIPT_RETRY))?.nextAttemptAt,
        minInstant(
            RetryState.decode(store.get(StorageKeys.FIRST_OPEN_RETRY))?.nextAttemptAt,
            minInstant(
                queue.retryState()?.nextAttemptAt,
                minInstant(
                    minInstant(
                        RetryState.decode(store.get(StorageKeys.IDENTIFY_RETRY))?.nextAttemptAt,
                        RetryState.decode(store.get(StorageKeys.DEVICE_IDS_RETRY))?.nextAttemptAt,
                    ),
                    minInstant(
                        RetryState.decode(store.get(StorageKeys.DELETION_RETRY))?.nextAttemptAt,
                        attributionPoll?.nextAt,
                    ),
                ),
            ),
        ),
    )

    /**
     * Runs under the monitor, so it does only the state half: the first-open snapshot its callers
     * owe is taken by applyStart's and applyConsent's own callers, once the monitor is released.
     * See applyStart.
     */
    private fun beginMeasurement() {
        identity = identities.initialize()
        // The id the host set before measurement was allowed is persisted now; with none, the one a
        // previous launch stored is the user's. The iOS SDK reads it the same way.
        val held = userId
        if (held != null) store.write(mapOf(StorageKeys.USER_ID to held)) else userId = store.get(StorageKeys.USER_ID)
        val pending = buffered.toList()
        buffered.clear()
        pending.forEach {
            enqueue(it.name, it.version, it.properties, it.occurredAt, identity!!)
        }
    }

    /**
     * Captures Play referrer exactly once for an install epoch and persists the complete
     * first-open body before transport. Relaunches/retries reuse this body and never fetch a
     * later referrer, preventing the worker's five-minute timestamp-skew rejection.
     */
    private fun ensureFirstOpenSnapshot() {
        val (current, allowsIdentifiers) = synchronized(this) {
            val identified = identity ?: return
            val epoch = identified.installEpochId.toString().lowercase()
            if (store.get(StorageKeys.FIRST_OPEN_SETTLED_EPOCH) == epoch) return
            // A refused epoch is terminal for this install. Without this, clearing the pending
            // snapshot would simply rebuild it here, and since track() calls work(), a permanently
            // refused install would send one first-open request per tracked event.
            if (store.get(StorageKeys.FIRST_OPEN_REFUSED_EPOCH) == epoch) return
            if (store.get(StorageKeys.FIRST_OPEN_PENDING_EPOCH) == epoch &&
                store.get(StorageKeys.FIRST_OPEN_PENDING_BODY) != null
            ) return
            identified to consent.allowsAdvertisingIdentifiers
        }
        val epoch = current.installEpochId.toString().lowercase()

        // Guarded, like every transport.execute() in this file and for the same reason. These two
        // are host-implemented `fun interface`s (Platform.kt) that wrap Play Services, and both of
        // those APIs throw as a matter of course: InstallReferrerClient raises SecurityException,
        // RemoteException and DeadObjectException when the Play Store is disabled, restricted or
        // being updated, and AdvertisingIdClient raises GooglePlayServicesNotAvailableException,
        // IOException and IllegalStateException. Unguarded, that exception left ensureFirstOpenSnapshot,
        // left work(), left track() -- which every integration calls on the main thread -- and
        // crashed the CUSTOMER'S app. transport.execute() was already wrapped; these two, which are
        // the ones that actually throw, were not.
        //
        // A throw is treated as ABSENCE rather than as a reason to retry the snapshot later. Both
        // values are optional evidence the server already handles being without (FEATURE_NOT_SUPPORTED
        // is a normal Play answer on many devices), whereas not persisting the snapshot on a
        // provider that throws every time would mean the install never registers at all -- losing
        // the whole install to protect one of its attributes.
        //
        // Both run OFF the instance monitor. They are Play IPC -- a bound-service round trip and a
        // Google Play services call -- so they block for as long as the Play Store takes to answer,
        // and holding `this` across them is exactly the stall the worker lock exists to prevent.
        //
        // That is a claim about EVERY caller, not about the two `synchronized(this)` blocks around
        // these lines, and it was false for two of the three. work() reaches here holding `worker`
        // and not `this`, so it was always true there; applyStart and applyConsent are
        // `@Synchronized` and used to reach here through beginMeasurement(), where the re-entrant
        // `synchronized(this)` above released nothing and both round trips ran holding the host's
        // monitor. They now return a Boolean instead and their callers, holding nothing, call this
        // function. Pinned by thePlayInstallReferrerCallDoesNotHoldTheMonitorTheHostsCallbacksTake,
        // which blocks in the referrer client rather than in the transport.
        val normalizedReferrer = runCatching { referrerClient.fetchInstallReferrer() }
            .getOrNull()?.value
            ?.let(PlayInstallReferrerParser::normalize)
        val identifiers = if (allowsIdentifiers) {
            runCatching { advertisingIdProvider.identifiers() }.getOrDefault(AdvertisingIdentifiers())
        } else {
            AdvertisingIdentifiers()
        }
        synchronized(this) {
            // Re-establish what the reads above were made under: the epoch can rotate and consent
            // can be withdrawn while Play is answering. A snapshot describing an identity that no
            // longer exists must not be written over the one that does.
            if (identity?.installEpochId != current.installEpochId) return
            // The two TERMINAL markers, re-read for the same reason the epoch is. They were checked
            // in the entry guard above, which runs BEFORE the two unguarded Play calls; a settle or
            // a refusal landing while Play answers makes this epoch terminal, and without these two
            // lines this thread still passes the pending-pair guard below (a settle and a refusal
            // both CLEAR that pair) and re-persists a pending body for an epoch that is finished.
            // Cheap and symmetric: the snapshot must be written under the same conditions it was
            // decided under, and settled/refused are two of them.
            if (store.get(StorageKeys.FIRST_OPEN_SETTLED_EPOCH) == epoch) return
            if (store.get(StorageKeys.FIRST_OPEN_REFUSED_EPOCH) == epoch) return
            if (store.get(StorageKeys.FIRST_OPEN_PENDING_EPOCH) == epoch &&
                store.get(StorageKeys.FIRST_OPEN_PENDING_BODY) != null
            ) return
            // Removed on a downgrade, never added on an upgrade -- the same rule
            // rewritePendingFirstOpenForConsent applies to an already-persisted body.
            val includeIdentifiers = allowsIdentifiers && consent.allowsAdvertisingIdentifiers
            val capturedAt = clock.now()
            val envelope = FirstOpenEnvelope(
                installationId = current.installationId,
                installEpochId = current.installEpochId,
                occurredAt = capturedAt,
                appVersion = configuration.appVersion,
                coarseContext = configuration.coarseContext,
                consent = consent,
                playInstallReferrer = normalizedReferrer?.let {
                    PlayInstallReferrer(it, capturedAt)
                },
                deviceSignals = configuration.deviceSignals,
                advertisingId = if (includeIdentifiers) {
                    PlayInstallReferrerParser.uuidOrNull(identifiers.gaid)
                } else null,
                appSetId = if (includeIdentifiers) {
                    PlayInstallReferrerParser.uuidOrNull(identifiers.appSetId)
                } else null,
                localLineagePresent = current.localLineagePresent,
                localEpochPresent = current.localEpochPresent,
                dma = dmaConsent(),
            )
            // This launch's read of the identifiers as well, so refreshDeviceIdentifiers does not
            // ask Play a second time.
            val identifierWrites = if (includeIdentifiers) {
                deviceIdentifierWrites(epoch, identifiers.gaid, identifiers.appSetId)
            } else {
                emptyMap()
            }
            store.write(
                mapOf(
                    StorageKeys.FIRST_OPEN_PENDING_EPOCH to epoch,
                    StorageKeys.FIRST_OPEN_PENDING_BODY to envelope.toJson(),
                ) + identifierWrites,
            )
        }
    }

    private fun submitFirstOpenIfDue(): Instant? {
        val attempt = synchronized(this) {
            val currentIdentity = identity ?: return null
            val key = apiKey ?: return null
            val epoch = currentIdentity.installEpochId.toString().lowercase()
            if (store.get(StorageKeys.FIRST_OPEN_SETTLED_EPOCH) == epoch) return null
            if (store.get(StorageKeys.FIRST_OPEN_REFUSED_EPOCH) == epoch) return null
            if (store.get(StorageKeys.FIRST_OPEN_PENDING_EPOCH) != epoch) return null
            val body = store.get(StorageKeys.FIRST_OPEN_PENDING_BODY) ?: return null
            val retry = RetryState.decode(store.get(StorageKeys.FIRST_OPEN_RETRY))
            val now = clock.now()
            if (retry != null && retry.nextAttemptAt > now) return retry.nextAttemptAt
            FirstOpenAttempt(
                request = RequestFactory(configuration.endpoint, key, ids)
                    .post("v1/ingest/first-open", body, epoch),
                body = body,
                epoch = epoch,
                retry = retry,
                now = now,
            )
        }
        val status = runCatching { transport.execute(attempt.request).statusCode }.getOrNull()
        return synchronized(this) {
            // The epoch can rotate while the request is in flight -- a revocation does exactly that
            // -- and this answer describes the epoch that was SENT. Settling on it would open the
            // delivery gate for an epoch the server has never seen.
            if (identity?.installEpochId?.toString()?.lowercase() != attempt.epoch) return null
            when {
                status != null && status in 200..299 -> settleFirstOpen(attempt.epoch, attempt.body)
                // 409 `idempotency_conflict` is a REGISTRATION, not a refusal. The server answers it
                // only after finding an occurrence already stored under this install_epoch_id whose
                // payload hash differs from ours (apps/link/src/ingestion/routes.ts:381, and
                // repository.ts:155 -- `state: evidence.sourceRef === payloadHash ? "duplicate" :
                // "conflict"`). The epoch therefore EXISTS, and the events naming it will be
                // accepted, so the delivery gate must open.
                //
                // It is reachable here, not theoretical: rewritePendingFirstOpenForConsent edits the
                // persisted body on any consent change, so the retry that follows carries different
                // bytes under the same idempotency key and conflicts. Classifying it as permanent
                // refused the epoch for the LIFE OF THE INSTALL -- refuseFirstOpen writes
                // FIRST_OPEN_REFUSED_EPOCH, which ensureFirstOpenSnapshot and this method both treat
                // as terminal, and the event gate never opens again.
                //
                // The shipped iOS SDK already does this (CoreRuntime.swift:950, `case 409:
                // registerFirstOpen()`); Android was the outlier.
                status == 409 -> settleFirstOpen(attempt.epoch, storedBody = null)
                // A refusal is NOT a registration. This used to mark the epoch settled, which let
                // the event flush proceed against an epoch the server had never accepted: every
                // batch then came back unknown_install_epoch and was destroyed as a permanent
                // failure. Leave it unsettled so the queue stays gated and the events survive to a
                // later attempt.
                status != null && isPermanentClientFailure(status) -> refuseFirstOpen(attempt.epoch)
                else -> return scheduleFirstOpenRetry(attempt.retry, attempt.now)
            }
            null
        }
    }

    /// The server rejected this install outright, so the epoch will never exist and no batch can be
    /// ingested. Clear the pending first-open work without marking the epoch settled: the delivery
    /// gate stays shut, queued events are held rather than sent into a guaranteed rejection, and a
    /// later launch re-snapshots and re-attempts registration from scratch.
    private fun refuseFirstOpen(epoch: String) {
        // The same permanent answer the iOS SDK gives attribution when first-open is refused: there
        // is no install for the server to attribute, and asking about it would only get a pending.
        attributionPoll = null
        attributionPollOver = true
        setAttributionCache(AttributionResult.Failed)
        store.write(
            mapOf(
                StorageKeys.FIRST_OPEN_REFUSED_EPOCH to epoch,
                StorageKeys.FIRST_OPEN_PENDING_EPOCH to null,
                StorageKeys.FIRST_OPEN_PENDING_BODY to null,
                StorageKeys.FIRST_OPEN_RETRY to null,
            ),
        )
    }

    /// [storedBody] is the body a 2xx stored. A 409 passes null: the server holds a different body,
    /// so the consent and identifiers this one carried prove nothing about the occurrence, and the
    /// tracking state it holds is recorded as unknown (see reconcileTrackingReceipt).
    private fun settleFirstOpen(epoch: String, storedBody: String?) {
        store.write(
            mapOf(
                StorageKeys.FIRST_OPEN_SETTLED_EPOCH to epoch,
                StorageKeys.FIRST_OPEN_PENDING_EPOCH to null,
                StorageKeys.FIRST_OPEN_PENDING_BODY to null,
                StorageKeys.FIRST_OPEN_RETRY to null,
            ) + (
                storedBody?.let { firstOpenAcknowledgement(epoch, it) }
                    ?: mapOf(StorageKeys.TRACKING_STATE_ACKNOWLEDGED to "$epoch\t$TRACKING_STATE_UNKNOWN")
                ),
        )
    }

    /// What a stored first-open tells the SDK: the occurrence's consent is the body's, and the
    /// advertising id and App Set ID it carried are delivered. The delivered record is not written
    /// once tracking consent is gone, since it quotes the advertising id.
    private fun firstOpenAcknowledgement(epoch: String, body: String): Map<String, String?> {
        val json = jsonObject(body) ?: return emptyMap()
        val state = (json["consent"] as? Map<*, *>)?.get("state") as? String ?: return emptyMap()
        if (state != ConsentState.MEASUREMENT_GRANTED.wireValue && state != ConsentState.TRACKING_GRANTED.wireValue) {
            return emptyMap()
        }
        val writes = mutableMapOf<String, String?>(StorageKeys.TRACKING_STATE_ACKNOWLEDGED to "$epoch\t$state")
        if (state == ConsentState.TRACKING_GRANTED.wireValue && consent.allowsAdvertisingIdentifiers) {
            val carried = DeviceIdentifiers(
                epoch,
                PlayInstallReferrerParser.uuidOrNull(json["idfa"] as? String),
                PlayInstallReferrerParser.uuidOrNull(json["idfv"] as? String),
            )
            if (carried.gaid != null || carried.appSetId != null) {
                writes[StorageKeys.DEVICE_IDS_DELIVERED] = carried.encode()
            }
        }
        return writes
    }

    private fun scheduleFirstOpenRetry(current: RetryState?, now: Instant): Instant? {
        val attempt = (current?.attempt ?: 0) + 1
        val firstAttemptAt = current?.firstAttemptAt ?: now
        if (attempt > 6 || Duration.between(firstAttemptAt, now) >= Duration.ofHours(24)) {
            // Stop the hot ladder without destroying the pending body or writing a permanent
            // refusal: a day of our downtime (or of the device being offline) must not cost this
            // install its attribution in a binary we cannot update. Park 24h so work() can still
            // post once a later attempt is due.
            val parked = RetryState(
                attempt,
                firstAttemptAt,
                now.plusSeconds(Duration.ofHours(24).seconds),
            )
            store.write(mapOf(StorageKeys.FIRST_OPEN_RETRY to parked.encode()))
            return parked.nextAttemptAt
        }
        val seconds = listOf(5L, 30L, 300L, 3_600L, 10_800L, 21_600L)[attempt - 1]
        val state = RetryState(attempt, firstAttemptAt, now.plusSeconds(seconds))
        store.write(mapOf(StorageKeys.FIRST_OPEN_RETRY to state.encode()))
        return state.nextAttemptAt
    }

    private fun flushEventsIfDue(): Instant? {
        val window = synchronized(this) {
            if (apiKey == null) return null
            // The delivery gate, and the reason it is keyed on the EPOCH rather than a bare flag: a
            // rotation must re-close it. `events:batch` is meaningless until first-open has
            // registered this epoch — the API answers it unknown_install_epoch until then, which
            // this SDK classified as a permanent failure and used to DESTROY the batch (a
            // single-event batch was acknowledged and deleted below). work() calls
            // submitFirstOpenIfDue() first, so the plain cold launch was serialized and safe; the
            // hole was the retry ladder, which returns early when an attempt is deferred — up to
            // hours — while this flush ran on regardless.
            val currentEpoch = identity?.installEpochId?.toString()?.lowercase() ?: return null
            if (store.get(StorageKeys.FIRST_OPEN_SETTLED_EPOCH) != currentEpoch) return null
            val now = clock.now()
            if (queue.count(now) == 0) {
                queue.setRetryState(null)
                return null
            }
            val retry = queue.retryState()
            if (retry != null && retry.nextAttemptAt > now) return retry.nextAttemptAt
            FlushWindow(now, retry)
        }
        var batches = 0
        while (batches < 100) {
            val prepared = synchronized(this) {
                val key = apiKey ?: return null
                // Re-read every iteration. The transport call below runs off the monitor, so a
                // revocation or an epoch rotation can land between two batches of one drain; before
                // the split this loop could not observe one because nothing else could run.
                if (!consent.allowsMeasurement) return null
                val epoch = identity?.installEpochId?.toString()?.lowercase() ?: return null
                if (store.get(StorageKeys.FIRST_OPEN_SETTLED_EPOCH) != epoch) return null
                val batch = queue.nextBatch(clock.now()) ?: run {
                    queue.setRetryState(null)
                    return null
                }
                batch to RequestFactory(configuration.endpoint, key, ids)
                    .post("v1/ingest/events:batch", batch.toJson(), batch.batchId)
            }
            val batch = prepared.first
            val response = runCatching { transport.execute(prepared.second) }.getOrNull()
            val status = response?.statusCode
            synchronized(this) {
                when {
                    status != null && status in 200..299 -> {
                        queue.acknowledge(batch.batchId)
                        queue.setRetryState(null)
                    }
                    // Belt to the gate above, and the arm that protects an already-installed app:
                    // this is a RACE, not a verdict — the identical bytes succeed once the epoch
                    // exists — so it must never take the permanent path below, which deletes the
                    // batch.
                    response != null && isUnknownInstallEpoch(response) -> {
                        // We are gated on a REGISTERED epoch, so being told the epoch is unknown
                        // means the server lost it after registering it (retention purge, restore
                        // from backup). On iOS the in-memory flag resets at relaunch and first-open
                        // re-registers; Android persists it, so without this the gate would stay
                        // open across every relaunch, first-open would never be re-sent, and the
                        // head batch would block the queue permanently. Re-arm registration instead.
                        // The ids that server had are gone with it: if the re-registration cannot
                        // carry them (Play failing at that moment), the next read must send them.
                        store.write(
                            mapOf(
                                StorageKeys.FIRST_OPEN_SETTLED_EPOCH to null,
                                StorageKeys.DEVICE_IDS_DELIVERED to null,
                            ),
                        )
                        return scheduleEventRetry(window.retry, window.now, longBackoff = false).nextAttemptAt
                    }
                    status != null && isPermanentClientFailure(status) -> {
                        if (batch.events.size == 1) queue.acknowledge(batch.batchId)
                        else queue.splitPending(batch.batchId)
                        queue.setRetryState(null)
                    }
                    else -> {
                        val isReclassified = status != null && isReclassifiedStatus(status)
                        return scheduleEventRetry(window.retry, window.now, longBackoff = isReclassified).nextAttemptAt
                    }
                }
            }
            batches += 1
        }
        return clock.now()
    }

    private fun scheduleEventRetry(current: RetryState?, now: Instant, longBackoff: Boolean = false): RetryState {
        val attempt = (current?.attempt ?: 0) + 1
        val firstAttemptAt = current?.firstAttemptAt ?: now
        // For reclassified 4xx responses (404, 405, 410, 451), preserves the initial 65 attempts
        // (initial hour at 60s ceiling) and then transitions to exponential intervals up to 3600s (1h),
        // bounding total retry attempts to 140 over the 72h retention period.
        // Transport failures (status == null), 5xx, 408, 429, and unknown_install_epoch preserve
        // the historical 60-second ceiling indefinitely so recovering offline devices do not wait 1h.
        val seconds = if (longBackoff && attempt > 65) {
            minOf(3_600L, 60L * (1L shl minOf(attempt - 65, 6)))
        } else {
            minOf(60L, 1L shl minOf(attempt - 1, 6))
        }
        val state = RetryState(
            attempt,
            firstAttemptAt,
            now.plusSeconds(seconds),
        )
        queue.setRetryState(state)
        return state
    }

    private fun enqueue(
        name: String,
        version: Int,
        properties: Map<String, EventValue>,
        occurredAt: Instant,
        currentIdentity: InstallationIdentity,
    ): TrackResult {
        val envelope = EventEnvelope(
            eventId = ids.next(),
            eventName = name,
            eventVersion = version,
            occurredAt = occurredAt,
            sentAt = occurredAt,
            installationId = currentIdentity.installationId,
            installEpochId = currentIdentity.installEpochId,
            sessionId = sessionId,
            consent = eventConsent(),
            properties = LinkedHashMap(properties),
        )
        return try {
            queue.enqueue(envelope, clock.now())
            TrackResult.QUEUED
        } catch (_: EventQueue.QueueFull) {
            TrackResult.REJECTED_QUEUE_FULL
        }
    }

    private fun revoke() {
        // BEFORE the wipe and the rotation, both of which destroy what the receipt refers to.
        enqueueConsentReceipt(ConsentState.REVOKED)
        // Before the rotation, which is what recoverInterruptedConsentCleanup reads as proof that
        // the cleanup finished: a new epoch on disk must imply the user id is already gone.
        eraseUserStateOnWithdrawal()
        queue.wipe()
        buffered.clear()   // see the DENIED arm: the buffer outlives the queue otherwise
        identity = identities.rotateEpoch()
        activeSession = null
        lastSessionEndedAt = null
        lastSessionIndex = null
        sessionId = ids.next()
    }

    /**
     * Persists the record that consent was withdrawn or denied, under the identity in force at
     * that moment. Storage first and network later on purpose: collection must stop immediately
     * whether or not the device is online, and a receipt that only exists in memory is lost to
     * the process death that follows an app being closed right after a user revokes.
     *
     * Silent when there is no identity yet, which is a first-run denial before anything was ever
     * collected: the schema is keyed on installation_id and install_epoch_id, and there is no
     * install to attribute a receipt to.
     *
     * [scope] is `tracking` for the receipt reconcileTrackingReceipt raises for a tracking change.
     */
    private fun enqueueConsentReceipt(state: ConsentState, scope: String = "measurement") {
        val current = identity ?: return
        val body = Json.stringify(
            mapOf(
                "installation_id" to current.installationId.toString().lowercase(),
                "install_epoch_id" to current.installEpochId.toString().lowercase(),
                "scope" to scope,
                "consent" to mapOf("state" to state.wireValue, "policy_version" to 1),
                "occurred_at" to clock.now().toWireTimestamp(),
                "source" to "android_sdk",
            ),
        )
        archiveDisplacedConsentReceipt(current.installEpochId.toString().lowercase())
        store.write(
            mapOf(
                StorageKeys.CONSENT_RECEIPT_PENDING to body,
                StorageKeys.CONSENT_RECEIPT_RETRY to null,
                StorageKeys.CONSENT_RECEIPT_IDEMPOTENCY to ids.next().toString(),
                StorageKeys.CONSENT_RECEIPT_EPOCH to current.installEpochId.toString().lowercase(),
            ),
        )
    }

    /**
     * Moves an undelivered receipt out of the way instead of destroying it, when the receipt about
     * to replace it names a DIFFERENT install epoch.
     *
     * Same epoch means supersession and the older receipt is genuinely obsolete: two denials of one
     * epoch are one legal fact, and "a newer denial replaces an older pending receipt" pins that.
     * A different epoch means a REVOCATION happened in between -- that is the only thing that
     * rotates the epoch -- so the displaced receipt is the withdrawal for the epoch whose data the
     * server is still holding, and overwriting it left the server believing consent was never
     * taken away.
     *
     * An epoch of `null` is a receipt written before this key existed, and is treated as different:
     * archiving a receipt that turns out to be obsolete costs one duplicate POST the server
     * deduplicates on the idempotency key, while dropping one that was not costs a consent signal.
     */
    private fun archiveDisplacedConsentReceipt(nextEpoch: String) {
        val pending = store.get(StorageKeys.CONSENT_RECEIPT_PENDING) ?: return
        // A torn multi-key write or an older build can leave a body without its key. Preserve the
        // consent fact under a fresh replay key rather than silently destroying it on overwrite.
        val idempotency = store.get(StorageKeys.CONSENT_RECEIPT_IDEMPOTENCY)
            ?: ids.next().toString()
        if (store.get(StorageKeys.CONSENT_RECEIPT_EPOCH) == nextEpoch) return
        val existing = store.get(StorageKeys.CONSENT_RECEIPT_ARCHIVE)
            ?.lineSequence()?.filter { it.isNotEmpty() }?.toList().orEmpty()
        // Oldest first, and the cap drops from the FRONT. Reaching it means the device stayed
        // offline across sixteen revocations, at which point something other than this queue is
        // wrong; the alternative is unbounded growth in a store the host app also uses.
        val next = (existing + "$idempotency\t$pending").takeLast(MAX_ARCHIVED_CONSENT_RECEIPTS)
        store.write(mapOf(StorageKeys.CONSENT_RECEIPT_ARCHIVE to next.joinToString("\n")))
    }

    /** Moves the oldest archived receipt into the pending slot, if the slot is free. */
    private fun promoteArchivedConsentReceipt() {
        if (store.get(StorageKeys.CONSENT_RECEIPT_PENDING) != null) return
        while (true) {
            val lines = store.get(StorageKeys.CONSENT_RECEIPT_ARCHIVE)
                ?.lineSequence()?.filter { it.isNotEmpty() }?.toList().orEmpty()
            val head = lines.firstOrNull() ?: return
            val separator = head.indexOf('\t')
            val remaining = lines.drop(1)
            if (separator <= 0) {
                // Discard every malformed prefix in this promotion pass. Returning after the
                // first one could strand a valid receipt behind it until unrelated work occurred.
                store.write(
                    mapOf(
                        StorageKeys.CONSENT_RECEIPT_ARCHIVE to
                            remaining.joinToString("\n").ifEmpty { null },
                    ),
                )
                continue
            }
            store.write(
                mapOf(
                    StorageKeys.CONSENT_RECEIPT_PENDING to head.substring(separator + 1),
                    StorageKeys.CONSENT_RECEIPT_IDEMPOTENCY to head.substring(0, separator),
                    StorageKeys.CONSENT_RECEIPT_RETRY to null,
                    // The promoted receipt's epoch is not carried: it is already displaced, so the only
                    // question this key answers -- may the next receipt overwrite it -- is settled by
                    // treating it as unknown, which archives rather than destroys.
                    StorageKeys.CONSENT_RECEIPT_EPOCH to null,
                    StorageKeys.CONSENT_RECEIPT_ARCHIVE to remaining.joinToString("\n").ifEmpty { null },
                ),
            )
            return
        }
    }

    /**
     * Delivers a stored consent receipt. Called from work() BEFORE the consent gate, because the
     * receipt that matters most is the one recording that consent was taken away, and gating its
     * delivery on the consent it revokes is the defect this exists to close.
     *
     * The body is written once and replayed verbatim, so a retry cannot produce a second distinct
     * receipt, and the row stays stored until the server acknowledges it or refuses it
     * permanently.
     */
    private fun drainConsentReceiptIfDue(): Instant? {
        // One in flight at a time, newest first: the pending slot always holds the most recent
        // receipt, and a displaced one is promoted only once that slot is free. Both are delivered;
        // the order is deliberate, because the newest transition is the one the server most needs.
        val attempt = synchronized(this) {
            promoteArchivedConsentReceipt()
            val body = store.get(StorageKeys.CONSENT_RECEIPT_PENDING) ?: return null
            val key = apiKey ?: return null
            val idempotencyKey = store.get(StorageKeys.CONSENT_RECEIPT_IDEMPOTENCY) ?: return null
            val retry = RetryState.decode(store.get(StorageKeys.CONSENT_RECEIPT_RETRY))
            val now = clock.now()
            if (retry != null && retry.nextAttemptAt > now) return retry.nextAttemptAt
            ReceiptAttempt(
                request = RequestFactory(configuration.endpoint, key, ids)
                    .post("v1/ingest/consent", body, idempotencyKey),
                body = body,
                idempotencyKey = idempotencyKey,
                retry = retry,
                now = now,
            )
        }
        val status = runCatching { transport.execute(attempt.request).statusCode }.getOrNull()
        return synchronized(this) {
            // setConsent() is allowed to enqueue a newer receipt while transport is in flight.
            // A response only owns the slot identified by the key captured with its request.
            if (store.get(StorageKeys.CONSENT_RECEIPT_IDEMPOTENCY) != attempt.idempotencyKey) {
                return@synchronized clock.now()
            }
            when {
                status != null && status in 200..299 -> {
                    clearConsentReceipt(trackingReceiptOutcome(attempt.body, StorageKeys.TRACKING_STATE_ACKNOWLEDGED))
                    nextArchivedConsentReceiptAt(attempt.now)
                }
                // A permanent refusal is terminal: retrying it forever would send one request per
                // work() tick for the life of the install.
                status != null && isPermanentClientFailure(status) -> {
                    clearConsentReceipt(trackingReceiptOutcome(attempt.body, StorageKeys.TRACKING_RECEIPT_REFUSED))
                    nextArchivedConsentReceiptAt(attempt.now)
                }
                else -> scheduleConsentReceiptRetry(attempt.retry, attempt.now)
            }
        }
    }

    /** Re-arm the host immediately when clearing the pending slot exposes archived consent work. */
    private fun nextArchivedConsentReceiptAt(now: Instant): Instant? =
        if (store.get(StorageKeys.CONSENT_RECEIPT_ARCHIVE)
                ?.lineSequence()?.any { it.isNotEmpty() } == true
        ) now else null

    /**
     * Same ladder shape as the first-open retry. On exhaustion park 24h and keep the body:
     * dropping a withdrawal receipt is a consent signal the backend never learns about, and
     * retrying it on every work() tick would cost one request per event for the life of the
     * install.
     */
    private fun scheduleConsentReceiptRetry(current: RetryState?, now: Instant): Instant? {
        val attempt = (current?.attempt ?: 0) + 1
        val firstAttemptAt = current?.firstAttemptAt ?: now
        if (attempt > 6 || Duration.between(firstAttemptAt, now) >= Duration.ofHours(24)) {
            val parked = RetryState(
                attempt,
                firstAttemptAt,
                now.plusSeconds(Duration.ofHours(24).seconds),
            )
            store.write(mapOf(StorageKeys.CONSENT_RECEIPT_RETRY to parked.encode()))
            return parked.nextAttemptAt
        }
        // The same ladder the first-open retry uses, deliberately not a second schedule.
        val seconds = listOf(5L, 30L, 300L, 3_600L, 10_800L, 21_600L)[attempt - 1]
        val next = RetryState(attempt, firstAttemptAt, now.plusSeconds(seconds))
        store.write(mapOf(StorageKeys.CONSENT_RECEIPT_RETRY to next.encode()))
        return next.nextAttemptAt
    }

    private fun clearConsentReceipt(outcome: Map<String, String?> = emptyMap()) {
        store.write(
            mapOf(
                StorageKeys.CONSENT_RECEIPT_PENDING to null,
                StorageKeys.CONSENT_RECEIPT_RETRY to null,
                StorageKeys.CONSENT_RECEIPT_IDEMPOTENCY to null,
                StorageKeys.CONSENT_RECEIPT_EPOCH to null,
            ) + outcome,
        )
    }

    /// For a tracking receipt about the current epoch, `key` set to `<epoch>\t<state>`: the state
    /// the server now holds (TRACKING_STATE_ACKNOWLEDGED) or refused (TRACKING_RECEIPT_REFUSED).
    /// Nothing for any other receipt.
    private fun trackingReceiptOutcome(body: String, key: String): Map<String, String?> {
        val json = jsonObject(body) ?: return emptyMap()
        val epoch = identity?.installEpochId?.toString()?.lowercase() ?: return emptyMap()
        if (json["scope"] != "tracking" || json["install_epoch_id"] != epoch) return emptyMap()
        val state = (json["consent"] as? Map<*, *>)?.get("state") as? String ?: return emptyMap()
        return mapOf(key to "$epoch\t$state")
    }

    /**
     * Raises a `tracking` consent receipt when the tracking consent the server holds for this epoch
     * differs from the SDK's, as the iOS SDK does when tracking is granted or taken back. The server
     * keeps an identify's advertising id only for an occurrence it holds as tracking_granted, and an
     * occurrence holds its first-open's consent until a receipt changes it. An app that asks for
     * tracking after onboarding registered its first-open under measurement consent, so without
     * this receipt the server would drop every advertising id the app sent afterwards.
     *
     * Decided from state rather than on the transition, so a grant made while this epoch's
     * withdrawal is still undelivered, a launch started with a different consent than the stored
     * one, and a first-open settled by a 409 all converge on the next work(). A downgrade is sent
     * only to take back a tracking_granted the server holds, or may hold after a 409 left its state
     * unknown. Under the monitor.
     */
    private fun reconcileTrackingReceipt(): Boolean {
        if (apiKey == null || deletionPending || !consent.allowsMeasurement) return false
        val epoch = identity?.installEpochId?.toString()?.lowercase() ?: return false
        // Before registration the server has no occurrence to apply it to, and the pending first-open
        // is rewritten to the current consent on every change, so it registers under the right one.
        if (store.get(StorageKeys.FIRST_OPEN_SETTLED_EPOCH) != epoch) return false
        val desired = consent.wireValue
        if (store.get(StorageKeys.TRACKING_RECEIPT_REFUSED) == "$epoch\t$desired") return false
        val pending = if (store.get(StorageKeys.CONSENT_RECEIPT_EPOCH) == epoch) {
            store.get(StorageKeys.CONSENT_RECEIPT_PENDING)?.let(::jsonObject)
                ?.let { (it["consent"] as? Map<*, *>)?.get("state") as? String }
        } else {
            null
        }
        val expected = pending ?: acknowledgedTrackingState(epoch)
        if (expected == desired) return false
        if (consent.allowsAdvertisingIdentifiers) {
            // The pending slot holds one receipt per epoch. An undelivered withdrawal is sent
            // first, not overwritten, and the grant follows once it is delivered.
            if (pending == ConsentState.DENIED.wireValue || pending == ConsentState.REVOKED.wireValue) return false
        } else if (expected != ConsentState.TRACKING_GRANTED.wireValue && expected != TRACKING_STATE_UNKNOWN) {
            // A downgrade takes back only a tracking_granted the server holds or may hold.
            return false
        }
        enqueueConsentReceipt(consent, scope = "tracking")
        return true
    }

    private fun acknowledgedTrackingState(epoch: String): String? {
        val parts = store.get(StorageKeys.TRACKING_STATE_ACKNOWLEDGED)?.split('\t') ?: return null
        return if (parts.size == 2 && parts[0] == epoch) parts[1] else null
    }

    /**
     * Sets the opaque id your own backend knows this user by -- the RevenueCat app user id -- and
     * sends it to AttriKit (`POST v1/ingest/identify`) so a purchase recorded under it joins this
     * install. The id is kept across launches and sent once per install epoch.
     *
     * - null or "" clears the stored id, as on iOS; nothing is sent, because an identify with no
     *   identity in it is invalid.
     * - An id of more than 256 UTF-8 bytes, or one containing '@', is refused and the id already set
     *   is unchanged: an email is not an opaque id, and a refused value must not erase a valid one.
     * - Without measurement consent the id is held in memory only and nothing is sent; it is stored
     *   and sent once consent allows measurement. A denial or a revocation erases it.
     * - The send waits for first-open to be registered, as every other request about an install
     *   does, and is retried by [work] on the first-open ladder.
     *
     * The advertising id and App Set ID go in an identify of their own (see
     * [submitDeviceIdentifiersIfDue]). The funnel hashes and the exact link token the iOS SDK adds
     * to the same request have no Android source in this core and are not sent.
     */
    fun setUserID(userId: String?): WorkResult {
        applySetUserID(userId)
        return work()
    }

    /// The state half of setUserID(); see applyStart().
    @Synchronized
    private fun applySetUserID(raw: String?) {
        val sanitized = when {
            raw == null || raw.isEmpty() -> null
            raw.toByteArray(Charsets.UTF_8).size <= MAX_USER_ID_BYTES && '@' !in raw -> raw
            else -> return
        }
        // A backoff and its attempt count belong to the id that earned them: a different id starts
        // from the first rung rather than waiting out the previous one's.
        if (sanitized != userId && store.get(StorageKeys.IDENTIFY_RETRY) != null) {
            store.write(mapOf(StorageKeys.IDENTIFY_RETRY to null))
        }
        userId = sanitized
        if (!consent.allowsMeasurement || deletionPending) return
        store.write(mapOf(StorageKeys.USER_ID to sanitized))
    }

    /// Everything the SDK kept about WHO the user is and what was learned about them, dropped when
    /// consent is withdrawn. The user id and the markers that quote it go together: a delivered
    /// marker left behind would keep the id on disk after the user said no.
    private fun eraseUserStateOnWithdrawal() {
        userId = null
        deviceIdsReadEpoch = null
        store.write(
            mapOf(
                StorageKeys.USER_ID to null,
                StorageKeys.IDENTIFY_DELIVERED to null,
                StorageKeys.IDENTIFY_REFUSED to null,
                StorageKeys.IDENTIFY_RETRY to null,
                // A withdrawal receipt replaces whatever tracking state the server held.
                StorageKeys.TRACKING_STATE_ACKNOWLEDGED to null,
                StorageKeys.TRACKING_RECEIPT_REFUSED to null,
            ) + DEVICE_ID_KEYS.associateWith { null },
        )
        resetAttributionState()
    }

    private fun submitIdentifyIfDue(): Instant? {
        val attempt = synchronized(this) {
            val key = apiKey ?: return null
            if (!consent.allowsMeasurement || deletionPending) return null
            val current = identity ?: return null
            val id = userId ?: return null
            val epoch = current.installEpochId.toString().lowercase()
            // identify mutates an occurrence the server only has once first-open registered it; before
            // that the API answers unknown_install_epoch, so the send waits rather than failing.
            if (store.get(StorageKeys.FIRST_OPEN_SETTLED_EPOCH) != epoch) return null
            val marker = "$epoch\t$id"
            if (store.get(StorageKeys.IDENTIFY_DELIVERED) == marker) return null
            if (store.get(StorageKeys.IDENTIFY_REFUSED) == marker) return null
            val retry = RetryState.decode(store.get(StorageKeys.IDENTIFY_RETRY))
            val now = clock.now()
            if (retry != null && retry.nextAttemptAt > now) return retry.nextAttemptAt
            IdentifyAttempt(
                request = RequestFactory(configuration.endpoint, key, ids).post(
                    "v1/ingest/identify",
                    IdentifyEnvelope(current.installationId, current.installEpochId, now, id).toJson(),
                    ids.next().toString().lowercase(),
                ),
                epoch = epoch,
                userId = id,
                retry = retry,
                now = now,
            )
        }
        val response = runCatching { transport.execute(attempt.request) }.getOrNull()
        val status = response?.statusCode
        return synchronized(this) {
            // The answer describes the pair that was SENT. A withdrawal while it was in flight erased
            // the id (and a revocation rotated the epoch), so it no longer matches and nothing is
            // written: least of all a marker quoting the id that was just erased. deleteData holds
            // `worker`, as this drain does, so a deletion cannot begin while a request is out.
            if (userId != attempt.userId ||
                identity?.installEpochId?.toString()?.lowercase() != attempt.epoch
            ) {
                return clock.now()
            }
            val marker = "${attempt.epoch}\t${attempt.userId}"
            when {
                status != null && status in 200..299 -> {
                    store.write(
                        mapOf(StorageKeys.IDENTIFY_DELIVERED to marker, StorageKeys.IDENTIFY_RETRY to null),
                    )
                    null
                }
                // The epoch race, which the same bytes outlive: retry, never refuse. 422 is in the
                // permanent set below, so this must be asked first.
                response != null && isUnknownInstallEpoch(response) ->
                    scheduleLadderRetry(StorageKeys.IDENTIFY_RETRY, attempt.retry, attempt.now)
                status != null && isPermanentClientFailure(status) -> {
                    store.write(
                        mapOf(StorageKeys.IDENTIFY_REFUSED to marker, StorageKeys.IDENTIFY_RETRY to null),
                    )
                    null
                }
                else -> scheduleLadderRetry(StorageKeys.IDENTIFY_RETRY, attempt.retry, attempt.now)
            }
        }
    }

    /**
     * Sends the advertising id and App Set ID read under tracking consent in an identify of their
     * own, in its `idfa` and `idfv` slots, once per epoch and pair. This is how they reach the server
     * when tracking is granted after first-open, the usual order for an app that asks after
     * onboarding: first-open never adds them later, so without it Google never received the
     * advertising id of those installs.
     *
     * Never without TRACKING_GRANTED, and not before the server is known to hold the occurrence as
     * tracking_granted (see reconcileTrackingReceipt): it would answer 200 and drop the advertising
     * id. Retried on the first-open ladder like the user id's identify; a pair the first-open
     * already carried counts as delivered.
     */
    private fun submitDeviceIdentifiersIfDue(): Instant? {
        val attempt = synchronized(this) {
            val key = apiKey ?: return null
            if (!consent.allowsAdvertisingIdentifiers || deletionPending) return null
            val current = identity ?: return null
            val epoch = current.installEpochId.toString().lowercase()
            if (store.get(StorageKeys.FIRST_OPEN_SETTLED_EPOCH) != epoch) return null
            if (acknowledgedTrackingState(epoch) != ConsentState.TRACKING_GRANTED.wireValue) return null
            val marker = store.get(StorageKeys.DEVICE_IDS) ?: return null
            val identifiers = DeviceIdentifiers.decode(marker)?.takeIf { it.epoch == epoch } ?: return null
            if (store.get(StorageKeys.DEVICE_IDS_DELIVERED) == marker) return null
            if (store.get(StorageKeys.DEVICE_IDS_REFUSED) == marker) return null
            val retry = RetryState.decode(store.get(StorageKeys.DEVICE_IDS_RETRY))
            val now = clock.now()
            if (retry != null && retry.nextAttemptAt > now) return retry.nextAttemptAt
            DeviceIdentifiersAttempt(
                request = RequestFactory(configuration.endpoint, key, ids).post(
                    "v1/ingest/identify",
                    IdentifyEnvelope(
                        current.installationId,
                        current.installEpochId,
                        now,
                        advertisingId = identifiers.gaid,
                        appSetId = identifiers.appSetId,
                    ).toJson(),
                    ids.next().toString().lowercase(),
                ),
                marker = marker,
                retry = retry,
                now = now,
            )
        }
        val response = runCatching { transport.execute(attempt.request) }.getOrNull()
        val status = response?.statusCode
        return synchronized(this) {
            // The pair sent is no longer the one to deliver. Either tracking consent was lost in
            // flight, which erased it, and a marker quoting it must not be written back; or a new
            // read replaced it, which the next pass sends.
            if (store.get(StorageKeys.DEVICE_IDS) != attempt.marker) return clock.now()
            when {
                status != null && status in 200..299 -> {
                    store.write(
                        mapOf(
                            StorageKeys.DEVICE_IDS_DELIVERED to attempt.marker,
                            StorageKeys.DEVICE_IDS_RETRY to null,
                        ),
                    )
                    null
                }
                response != null && isUnknownInstallEpoch(response) ->
                    scheduleLadderRetry(StorageKeys.DEVICE_IDS_RETRY, attempt.retry, attempt.now)
                status != null && isPermanentClientFailure(status) -> {
                    store.write(
                        mapOf(
                            StorageKeys.DEVICE_IDS_REFUSED to attempt.marker,
                            StorageKeys.DEVICE_IDS_RETRY to null,
                        ),
                    )
                    null
                }
                else -> scheduleLadderRetry(StorageKeys.DEVICE_IDS_RETRY, attempt.retry, attempt.now)
            }
        }
    }

    /**
     * Reads the advertising id and App Set ID once per launch while consent is TRACKING_GRANTED, and
     * again on a grant, for [submitDeviceIdentifiersIfDue]. Reading at every launch is what picks up
     * an id the user reset; a pair already delivered is not sent again. Play IPC, so it runs off the
     * monitor and only from callers that hold nothing ([start], [setConsent]), for the reason
     * ensureFirstOpenSnapshot gives. A provider that throws leaves what is stored.
     */
    private fun refreshDeviceIdentifiers() {
        val epoch = synchronized(this) {
            if (apiKey == null || deletionPending || !consent.allowsAdvertisingIdentifiers) return
            val epoch = identity?.installEpochId?.toString()?.lowercase() ?: return
            if (deviceIdsReadEpoch == epoch) return
            epoch
        }
        val read = runCatching { advertisingIdProvider.identifiers() }.getOrNull() ?: return
        synchronized(this) {
            // Consent can be withdrawn and the epoch rotated while Play answers.
            if (deletionPending || !consent.allowsAdvertisingIdentifiers) return
            if (identity?.installEpochId?.toString()?.lowercase() != epoch) return
            val writes = deviceIdentifierWrites(epoch, read.gaid, read.appSetId)
            if (writes.isNotEmpty()) store.write(writes)
        }
    }

    /// The writes that make a pair read just now, under tracking consent, the epoch's identifiers.
    /// A read with neither id removes the stored pair: the user switched the advertising id off, and
    /// an id read before must not be sent after. Under the monitor.
    private fun deviceIdentifierWrites(epoch: String, gaid: String?, appSetId: String?): Map<String, String?> {
        deviceIdsReadEpoch = epoch
        val read = DeviceIdentifiers(
            epoch,
            PlayInstallReferrerParser.uuidOrNull(gaid),
            PlayInstallReferrerParser.uuidOrNull(appSetId),
        )
        val next = if (read.gaid == null && read.appSetId == null) null else read.encode()
        if (store.get(StorageKeys.DEVICE_IDS) == next) return emptyMap()
        // A new pair starts the ladder from its first rung, as a new user id does.
        return mapOf(StorageKeys.DEVICE_IDS to next, StorageKeys.DEVICE_IDS_RETRY to null)
    }

    /// The first-open ladder (5s, 30s, 5m, 1h, 3h, 6h), parking for 24h on exhaustion or after a
    /// day, for the requests that share it: dropping one would lose a fact the server never learns.
    private fun scheduleLadderRetry(key: String, current: RetryState?, now: Instant): Instant {
        val attempt = (current?.attempt ?: 0) + 1
        val firstAttemptAt = current?.firstAttemptAt ?: now
        val parked = attempt > RETRY_LADDER_SECONDS.size ||
            Duration.between(firstAttemptAt, now) >= Duration.ofHours(24)
        val seconds = if (parked) Duration.ofHours(24).seconds else RETRY_LADDER_SECONDS[attempt - 1]
        val state = RetryState(attempt, firstAttemptAt, now.plusSeconds(seconds))
        store.write(mapOf(key to state.encode()))
        return state.nextAttemptAt
    }

    /**
     * Erases this install's data on the server (`POST v1/privacy/delete`) and then on the device.
     * Blocking, like [work]: call it from a background thread.
     *
     * The request is recorded BEFORE it is sent. From that moment collection is halted -- [track]
     * refuses, no event, receipt or identify is sent, [attribution] answers FAILED -- and stays
     * halted across relaunches until the server acknowledges the erasure. [work] retries a pending
     * deletion on the first-open ladder, which is additive to iOS, where only a new call retries.
     * On a 2xx every key the core stores is removed in one write, the in-memory state is reset and
     * the core returns to before [start]: it must be started again, under a new installation id.
     */
    fun deleteData(): DeletionResult {
        // `worker` for the whole call: a drain in flight must not send events for an install being
        // erased, and a second work() reports what is scheduled instead of racing the request.
        worker.lock()
        val result = try {
            synchronized(this) { prepareDeletion() } ?: attemptDeletion(respectBackoff = false).result
        } finally {
            worker.unlock()
        }
        publishAttribution()
        return result
    }

    /// The state half of deleteData(). Returns the terminal answer, or null when the request is to
    /// be sent. The tombstone is written first and the in-memory halt follows, so a crash between
    /// them leaves a deletion that is pending on disk and halts the next launch.
    private fun prepareDeletion(): DeletionResult? {
        if (apiKey == null) return DeletionResult.NotStarted
        val stored = store.get(StorageKeys.DELETION_TOMBSTONE)
        if (stored != null) {
            if (DeletionTombstone.decode(stored) == null) {
                haltForPendingDeletion()
                return DeletionResult.Failed(null)
            }
        } else {
            val current = identity ?: identities.initialize()
            store.write(
                mapOf(
                    StorageKeys.DELETION_TOMBSTONE to
                        DeletionTombstone(current.installationId, current.installEpochId).encode(),
                ),
            )
        }
        haltForPendingDeletion()
        return null
    }

    private fun haltForPendingDeletion() {
        deletionPending = true
        buffered.clear()
        activeSession = null
        lastSessionEndedAt = null
        lastSessionIndex = null
        resetAttributionState()
    }

    private fun drainDeletionIfDue(): Instant? = attemptDeletion(respectBackoff = true).nextAttemptAt

    private fun attemptDeletion(respectBackoff: Boolean): DeletionOutcome {
        val attempt = synchronized(this) {
            val key = apiKey ?: return DeletionOutcome(DeletionResult.NotStarted, null)
            val tombstone = store.get(StorageKeys.DELETION_TOMBSTONE)?.let(DeletionTombstone::decode)
                ?: return DeletionOutcome(DeletionResult.Failed(null), null)
            val retry = RetryState.decode(store.get(StorageKeys.DELETION_RETRY))
            val now = clock.now()
            if (respectBackoff && retry != null && retry.nextAttemptAt > now) {
                return DeletionOutcome(DeletionResult.Failed(null), retry.nextAttemptAt)
            }
            DeletionAttempt(
                request = RequestFactory(configuration.endpoint, key, ids).post(
                    "v1/privacy/delete",
                    tombstone.toJson(),
                    tombstone.installEpochId.toString().lowercase(),
                ),
                retry = retry,
                now = now,
            )
        }
        val status = runCatching { transport.execute(attempt.request).statusCode }.getOrNull()
        return synchronized(this) {
            if (status != null && status in 200..299) {
                completeDeletion()
                DeletionOutcome(DeletionResult.Completed, null)
            } else {
                DeletionOutcome(
                    DeletionResult.Failed(status),
                    scheduleLadderRetry(StorageKeys.DELETION_RETRY, attempt.retry, attempt.now),
                )
            }
        }
    }

    /// One write removes every key, the tombstone with them, so a crash can never leave an erased
    /// install with a pending deletion or a pending deletion with its data already gone.
    private fun completeDeletion() {
        store.write(ERASED_ON_DELETION.associateWith { null })
        buffered.clear()
        identity = null
        userId = null
        deviceIdsReadEpoch = null
        activeSession = null
        lastSessionEndedAt = null
        lastSessionIndex = null
        sessionId = ids.next()
        consent = ConsentState.UNKNOWN
        apiKey = null
        deletionPending = false
        resetAttributionState()
    }

    /**
     * Waits up to [timeoutMillis] for this install's attribution and answers it, on the calling
     * thread: call it from a background thread. It asks the server itself (and registers the
     * install first when that is still outstanding), so it needs nothing else to be running; [work]
     * keeps asking on the same schedule when the host is driving it.
     *
     * The answer is what the iOS SDK's `attribution(timeout:)` gives: NOT_STARTED before [start],
     * CONSENT_REQUIRED without measurement consent, FAILED while a deletion is pending or after the
     * server's permanent refusal, UNATTRIBUTED for an organic install, and TIMED_OUT when no answer
     * arrived. TIMED_OUT is never UNATTRIBUTED: an exhausted poll is a fact about our asking, not
     * about the install, so it can still be answered by a later call or launch. A provisional answer
     * is returned as it is (see [Attribution.isProvisional]) and the core keeps asking for the
     * final one; [setAttributionListener] reports it.
     *
     * A call that has run out of time starts no network work: with [timeoutMillis] <= 0 it answers
     * from memory (the cached answer, else TIMED_OUT) and makes no request. A request already
     * started is not interrupted, so a call can overrun its timeout by one request; that request is
     * bounded by the transport's own connect and read timeouts, which the host's [HttpTransport]
     * must set (the Android wrapper's HttpUrlConnectionTransport does). Once the poll has given up
     * the answer stays TIMED_OUT until the next launch or consent change, as on iOS, where nothing
     * restarts it either. A thread interrupt returns TIMED_OUT (unknown) and keeps the flag set.
     */
    fun attribution(timeoutMillis: Long): AttributionResult {
        val budgetMillis = timeoutMillis.coerceIn(0L, MAX_ATTRIBUTION_WAIT_MILLIS)
        val deadline = System.nanoTime() + budgetMillis * 1_000_000L
        synchronized(this) { attributionRequested = true }
        while (true) {
            synchronized(this) { attributionAnswer() }?.let {
                publishAttribution()
                return it
            }
            // Out of time before any network work that could not finish: answer from memory.
            if (deadline - System.nanoTime() <= 0) return AttributionResult.TimedOut
            val registered = synchronized(this) {
                val epoch = identity?.installEpochId?.toString()?.lowercase()
                epoch != null && store.get(StorageKeys.FIRST_OPEN_SETTLED_EPOCH) == epoch
            }
            val workNext = if (registered) null else work().nextAttemptAt
            val pollNext = pollAttributionIfDue()
            publishAttribution()
            val remainingMillis = (deadline - System.nanoTime() + 999_999L) / 1_000_000L
            synchronized(this) {
                attributionAnswer()?.let { return it }
                if (remainingMillis <= 0) return AttributionResult.TimedOut
                val next = minInstant(workNext, pollNext)
                val untilNext = next?.let { Duration.between(clock.now(), it).toMillis() } ?: ATTRIBUTION_IDLE_WAIT_MILLIS
                try {
                    // The floor: a due time already past means another thread holds the request (or
                    // work()), and this thread would otherwise spin until it returns. Waking early on
                    // notifyAll still ends the wait the moment an answer lands.
                    awaitAttributionChange(minOf(remainingMillis, untilNext.coerceAtLeast(ATTRIBUTION_MIN_WAIT_MILLIS)))
                } catch (_: InterruptedException) {
                    // Unknown, not FAILED: FAILED is the server's permanent refusal.
                    Thread.currentThread().interrupt()
                    return AttributionResult.TimedOut
                }
            }
        }
    }

    /// The answer attribution() can give right now, or null to keep waiting.
    private fun attributionAnswer(): AttributionResult? = when {
        apiKey == null -> AttributionResult.NotStarted
        deletionPending -> AttributionResult.Failed
        !consent.allowsMeasurement -> AttributionResult.ConsentRequired
        attributionCache != null -> publicAttributionResult(attributionCache!!)
        // The poll gave up: nothing more is coming, so waiting out the timeout would only delay it.
        attributionPollStopped -> AttributionResult.TimedOut
        else -> null
    }

    /**
     * The attribution state as one value, without waiting: what [setAttributionListener] receives.
     * PENDING before an answer, ORGANIC for an organic install, TIMED_OUT when the poll gave up or
     * the server refused it, CONSENT_REQUIRED without measurement consent or during a deletion.
     */
    fun attributionUpdate(): AttributionUpdate = synchronized(this) { currentAttributionUpdate() }

    /**
     * Registers the one listener for attribution changes, or removes it with null. It receives the
     * current state at once and then every change, never the same state twice. Registering counts as
     * asking for attribution: the core starts polling on the next [work] or [attribution].
     *
     * It runs on whichever host thread was inside the core when the state changed (a [work] call,
     * an [attribution] wait, [setConsent], [deleteData]), after the core released its locks, so it
     * may call back into the core. An exception it throws is swallowed.
     */
    fun setAttributionListener(listener: AttributionListener?) {
        publishLock.lock()
        try {
            lastPublishedAttribution = null
            synchronized(this) {
                attributionListener = listener
                if (listener != null) attributionRequested = true
            }
        } finally {
            publishLock.unlock()
        }
        publishAttribution()
    }

    private fun currentAttributionUpdate(): AttributionUpdate {
        if (apiKey == null) return AttributionUpdate(AttributionStatus.PENDING, null)
        if (deletionPending || !consent.allowsMeasurement) {
            return AttributionUpdate(AttributionStatus.CONSENT_REQUIRED, null)
        }
        return when (val cached = attributionCache) {
            is AttributionResult.Attributed -> AttributionUpdate(cached.attribution.status, cached.attribution)
            AttributionResult.Unattributed -> AttributionUpdate(AttributionStatus.ORGANIC, null)
            AttributionResult.Failed, AttributionResult.TimedOut -> AttributionUpdate(AttributionStatus.TIMED_OUT, null)
            AttributionResult.ConsentRequired -> AttributionUpdate(AttributionStatus.CONSENT_REQUIRED, null)
            AttributionResult.NotStarted -> AttributionUpdate(AttributionStatus.PENDING, null)
            null -> AttributionUpdate(
                if (attributionPollStopped) AttributionStatus.TIMED_OUT else AttributionStatus.PENDING,
                null,
            )
        }
    }

    /**
     * Delivers the state to the listener if it changed since the last delivery. Never blocks behind
     * another delivery: a caller that finds one running marks the state dirty and leaves, and the
     * running delivery re-checks the mark after it unlocks and delivers again, so a slow host
     * callback cannot stall track() on the main thread and no change is lost.
     */
    private fun publishAttribution() {
        if (attributionListener == null) return
        attributionDirty.set(true)
        while (attributionDirty.get()) {
            if (!publishLock.tryLock()) return
            try {
                if (attributionDirty.getAndSet(false)) {
                    val listener = attributionListener
                    val update = synchronized(this) { currentAttributionUpdate() }
                    if (listener == null) {
                        lastPublishedAttribution = null
                    } else if (update != lastPublishedAttribution) {
                        lastPublishedAttribution = update
                        try {
                            listener.onAttributionUpdate(update)
                        } catch (_: Exception) {
                            // Host code. It must not take the drain down with it.
                        }
                    }
                }
            } finally {
                publishLock.unlock()
            }
        }
    }

    private fun setAttributionCache(result: AttributionResult?) {
        attributionCache = result
        wakeAttributionWaiters()
    }

    private fun resetAttributionState() {
        attributionCache = null
        attributionETag = null
        attributionPollStopped = false
        attributionPollOver = false
        attributionPoll = null
        wakeAttributionWaiters()
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun wakeAttributionWaiters() {
        synchronized(this) { (this as Object).notifyAll() }
    }

    /// Releases the instance monitor while it waits; the caller holds it.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun awaitAttributionChange(millis: Long) {
        (this as Object).wait(millis)
    }

    /**
     * One attribution request if one is due: the schedule is the iOS poll's. A fast ramp (250ms
     * doubling to a 5s ceiling, 20 attempts, 60s) while there is no answer at all; then the
     * 5s, 30s, 5m, 1h, 3h, 6h ladder, which is also all that is spent watching a provisional answer;
     * a server `Retry-After` raises a delay and is clamped to 6h; everything inside a 24h window. A
     * settled answer ends it, and so does running out, which leaves the result unknown.
     *
     * Returns when the next request is due, or null when none is scheduled.
     */
    private fun pollAttributionIfDue(): Instant? {
        // Someone else is mid-request; report what is scheduled and let them finish.
        if (!pollLock.tryLock()) return synchronized(this) { attributionPoll?.nextAt }
        try {
            val attempt = synchronized(this) {
                if (!attributionRequested || attributionPollOver) return null
                val key = apiKey ?: return null
                if (!consent.allowsMeasurement || deletionPending) return null
                val current = identity ?: return null
                val epoch = current.installEpochId.toString().lowercase()
                // The same gate as every request about an install: first-open registers the epoch.
                if (store.get(StorageKeys.FIRST_OPEN_SETTLED_EPOCH) != epoch) return null
                val now = clock.now()
                val poll = attributionPoll?.takeIf { it.epoch == epoch }
                    ?: AttributionPoll(epoch, now, now, 0, ATTRIBUTION_FAST_FLOOR_MILLIS, 0)
                        .also { attributionPoll = it }
                if (poll.nextAt > now) return poll.nextAt
                PollAttempt(
                    request = RequestFactory(configuration.endpoint, key, ids)
                        .get("v1/attribution/$epoch", attributionETag),
                    poll = poll,
                )
            }
            val response = runCatching { transport.execute(attempt.request) }.getOrNull()
            return synchronized(this) { applyPollResponse(attempt, response) }
        } finally {
            pollLock.unlock()
        }
    }

    private fun applyPollResponse(attempt: PollAttempt, response: HttpResponse?): Instant? {
        // Reset (consent withdrawn, epoch rotated, deletion) replaces or clears the poll; an answer
        // to a request from the one that no longer exists describes nothing.
        if (attributionPoll !== attempt.poll || !consent.allowsMeasurement || deletionPending) return null
        var cooldownMillis: Long? = null
        if (response != null) {
            cooldownMillis = retryAfterMilliseconds(headerValue(response, "retry-after"))
            when (response.statusCode) {
                200 -> applyAttributionBody(response)
                204 -> setAttributionCache(AttributionResult.Unattributed)
                // The route's only permanent 4xx, a malformed epoch id this build keeps sending. Every
                // other status leaves the result UNKNOWN, which is what it is: a wrongly permanent
                // answer cannot be corrected within the session, a wrongly transient one costs a request.
                400 -> setAttributionCache(AttributionResult.Failed)
                else -> Unit
            }
        }
        return scheduleNextPoll(attempt.poll, clock.now(), cooldownMillis)
    }

    private fun applyAttributionBody(response: HttpResponse) {
        // A body that does not parse is not applied and leaves no validator behind: a stored ETag
        // for an answer never read would earn a 304 for the rest of the session.
        val decoded = runCatching { AttributionResponse.parse(response.body) }.getOrNull() ?: return
        // A 200 that is not an answer yet: leave the cache and the validator alone, keep polling.
        if (decoded.isPending) return
        setAttributionCache(
            decoded.attribution?.let(AttributionResult::Attributed) ?: AttributionResult.Unattributed,
        )
        headerValue(response, "etag")?.let { attributionETag = it }
    }

    private fun scheduleNextPoll(poll: AttributionPoll, now: Instant, cooldownMillis: Long?): Instant? {
        val cached = attributionCache
        if (cached != null && !isProvisionalAnswer(cached)) {
            attributionPoll = null
            attributionPollOver = true
            return null
        }
        val elapsed = Duration.between(poll.startedAt, now)
        if (elapsed >= ATTRIBUTION_POLL_WINDOW) return finishExhaustedPoll()
        var delayMillis: Long
        val advanced = if (cached == null && poll.fastAttempts < ATTRIBUTION_FAST_ATTEMPTS &&
            elapsed < ATTRIBUTION_FAST_WINDOW
        ) {
            delayMillis = poll.fastDelayMillis
            poll.copy(
                fastAttempts = poll.fastAttempts + 1,
                fastDelayMillis = minOf(poll.fastDelayMillis * 2, ATTRIBUTION_FAST_CEILING_MILLIS),
            )
        } else {
            if (poll.ladderIndex >= RETRY_LADDER_SECONDS.size) return finishExhaustedPoll()
            delayMillis = RETRY_LADDER_SECONDS[poll.ladderIndex] * 1_000L
            poll.copy(ladderIndex = poll.ladderIndex + 1)
        }
        if (cooldownMillis != null) delayMillis = maxOf(delayMillis, cooldownMillis)
        // Up to a quarter on top, so installs launched together do not poll in step. Derived from the
        // installation id rather than a random source: the spread is the same, and it is repeatable.
        val spread = (delayMillis / 4).coerceAtLeast(1L) + 1
        val jitter = Math.floorMod(identity?.installationId?.leastSignificantBits ?: 0L, spread)
        val nextAt = now.plusMillis(delayMillis + jitter)
        attributionPoll = advanced.copy(nextAt = nextAt)
        return nextAt
    }

    /// The poll gave up inside its window. It does NOT write UNATTRIBUTED: that is a claim about the
    /// install, whereas giving up is a fact about our asking, and an answer is returned for the life
    /// of the session once set, so a premature one would turn a match that had not landed yet into a
    /// permanent wrong answer. With an answer already in hand (a provisional one) the poll just ends.
    private fun finishExhaustedPoll(): Instant? {
        attributionPoll = null
        attributionPollOver = true
        if (attributionCache == null) {
            attributionPollStopped = true
            wakeAttributionWaiters()
        }
        return null
    }

    private fun eventConsent(): EventConsent = EventConsent(
        measurement = if (consent.allowsMeasurement) "granted" else "denied",
        tracking = when {
            consent.allowsAdvertisingIdentifiers -> "granted"
            consent == ConsentState.UNKNOWN -> "unknown"
            else -> "denied"
        },
        dma = dmaConsent(),
    )

    private fun currentWorkResult(next: Instant? = null): WorkResult {
        val currentEpoch = identity?.installEpochId?.toString()?.lowercase()
        // Two different questions, and they must not share an answer.
        //
        // This flag is the HOST-FACING one: "is there any first-open work left to schedule?" A
        // refused epoch is terminal for that purpose — the host should stop arming background
        // work — so it belongs here, or an app re-arms a job that can never succeed. Exhausted
        // delivery is not refused: nextAttemptAt carries the park, and a later work() can still
        // post.
        //
        // The DELIVERY gate in flushEventsIfDue() asks the opposite question, "was the epoch
        // REGISTERED?", and keys strictly on FIRST_OPEN_SETTLED_EPOCH. Collapsing the two is what
        // let events flush against an epoch the server never accepted.
        val settled = currentEpoch != null &&
            (
                store.get(StorageKeys.FIRST_OPEN_SETTLED_EPOCH) == currentEpoch ||
                    store.get(StorageKeys.FIRST_OPEN_REFUSED_EPOCH) == currentEpoch
                )
        return WorkResult(
            firstOpenSettled = settled,
            queuedEventCount = runCatching { queue.count(clock.now()) }.getOrDefault(0),
            nextAttemptAt = next,
        )
    }

    companion object {
        private const val CONSENT_CLEANUP_PENDING = "io.attrikit.consent-cleanup.pending"
        /// See StorageKeys.CONSENT_RECEIPT_ARCHIVE.
        private const val MAX_ARCHIVED_CONSENT_RECEIPTS = 16

        /// The opaque user id's cap, in UTF-8 bytes: the iOS SDK's, and the server's 256.
        private const val MAX_USER_ID_BYTES = 256

        /// The published first-open ladder, shared by identify and deletion retries.
        private val RETRY_LADDER_SECONDS = listOf(5L, 30L, 300L, 3_600L, 10_800L, 21_600L)

        /// The attribution poll shares that ladder's 24h window. The fast ramp is for the wait for a
        /// FIRST answer; the attempt cap bounds it without trusting the clock.
        private val ATTRIBUTION_POLL_WINDOW: Duration = Duration.ofHours(24)
        private val ATTRIBUTION_FAST_WINDOW: Duration = Duration.ofSeconds(60)
        private const val ATTRIBUTION_FAST_FLOOR_MILLIS = 250L
        private const val ATTRIBUTION_FAST_CEILING_MILLIS = 5_000L
        private const val ATTRIBUTION_FAST_ATTEMPTS = 20

        /// Upper bound on a server-declared cooldown: never wait longer than the ladder's own ceiling.
        private const val MAX_RETRY_AFTER_SECONDS = 21_600

        /// What attribution() waits between looks when nothing is scheduled, and the most it accepts.
        private const val ATTRIBUTION_IDLE_WAIT_MILLIS = 250L
        private const val ATTRIBUTION_MIN_WAIT_MILLIS = 50L
        private const val MAX_ATTRIBUTION_WAIT_MILLIS = 86_400_000L

        /// Every key the core stores, for a deletion. StorageKeys.ALL plus the one that lives here.
        private val ERASED_ON_DELETION = StorageKeys.ALL + CONSENT_CLEANUP_PENDING

        /// TRACKING_STATE_ACKNOWLEDGED's state after a 409: the server holds an earlier body whose
        /// consent this SDK cannot know. Never a consent this SDK sends, which is what keeps it apart.
        private const val TRACKING_STATE_UNKNOWN = "unknown"

        /// What was read under tracking consent, erased when it is lost. See StorageKeys.DEVICE_IDS.
        private val DEVICE_ID_KEYS = listOf(
            StorageKeys.DEVICE_IDS,
            StorageKeys.DEVICE_IDS_DELIVERED,
            StorageKeys.DEVICE_IDS_REFUSED,
            StorageKeys.DEVICE_IDS_RETRY,
        )

        /// A body this SDK wrote, read back as a JSON object, or null.
        private fun jsonObject(body: String): Map<*, *>? = runCatching { Json.parse(body) as? Map<*, *> }.getOrNull()

        /// Parses the delta-seconds form of `Retry-After`, the only form the AttriKit API emits, and
        /// clamps it to the ladder's ceiling. An HTTP-date, a non-numeric or a non-positive value
        /// returns null, so the caller keeps its own schedule, which a hostile header cannot slow.
        internal fun retryAfterMilliseconds(raw: String?): Long? {
            val seconds = raw?.trim()?.toIntOrNull() ?: return null
            if (seconds <= 0) return null
            return minOf(seconds, MAX_RETRY_AFTER_SECONDS) * 1_000L
        }

        private fun headerValue(response: HttpResponse, name: String): String? =
            response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        private fun isProvisionalAnswer(result: AttributionResult): Boolean =
            result is AttributionResult.Attributed && result.attribution.isProvisional

        /// What attribution() reports for a cached answer. An organic install is UNATTRIBUTED, not
        /// ATTRIBUTED with an `unattributed` method, which an app testing for ATTRIBUTED would have
        /// read as paid; an install whose consent the server records as withdrawn is CONSENT_REQUIRED.
        /// The cache keeps the full answer, because its finality decides whether the poll goes on.
        internal fun publicAttributionResult(cached: AttributionResult): AttributionResult {
            if (cached !is AttributionResult.Attributed) return cached
            return when (cached.attribution.status) {
                AttributionStatus.ORGANIC -> AttributionResult.Unattributed
                AttributionStatus.CONSENT_REQUIRED -> AttributionResult.ConsentRequired
                else -> cached
            }
        }

        /// See isPermanentClientFailure.
        private val PERMANENT_CLIENT_FAILURE_STATUSES = setOf(400, 413, 415, 422)

        /// Reclassified HTTP status codes whose payloads are retained and retried under the
        /// long backoff ladder rather than discarded. Intermediaries (proxies, captive portals, CDNs)
        /// answer these codes for reasons unrelated to payload validity.
        private val RECLASSIFIED_STATUSES = setOf(404, 405, 410, 451)

        private val consentJsonPattern = Regex(
            """"consent":\{"state":"(?:unknown|measurement_granted|tracking_granted|denied|revoked)","policy_version":1""",
        )
        private val advertisingIdJsonPattern = Regex(
            ""","idfa":"[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"""",
        )
        private val appSetIdJsonPattern = Regex(
            ""","idfv":"[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"""",
        )
        private val eventNamePattern = Regex("^[a-z][a-z0-9_.-]{0,127}$")
        private val forbiddenPropertyKey =
            Regex("email|e-mail|phone|mobile|address|name", RegexOption.IGNORE_CASE)
        private val emailPattern =
            Regex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", RegexOption.IGNORE_CASE)
        private val phonePattern =
            Regex("(?:^|\\D)(?:\\+?\\d[\\d\\s().-]{7,}\\d)(?:$|\\D)")

        /// The statuses on which this SDK will never send the same bytes again. On the event path
        /// that is not a pause: the batch is bisected and each event is DELETED once it is alone
        /// (`queue.acknowledge` below), so a status classified here is a customer's data destroyed.
        ///
        /// An ALLOWLIST, and that is the whole change. It was `400..499` minus {401, 403, 408, 429}
        /// — every 4xx nobody had thought of counted as a verdict on the payload. But the 4xx an
        /// install meets in the field is often not ours at all: a captive portal, a corporate proxy
        /// or a CDN answers 404, 405, 407, 410 or 451 to a request that never reached the API, and
        /// each of those destroyed the frame it interrupted.
        ///
        /// The set is read off the wire contract rather than guessed. apps/link/src/ingestion/
        /// routes.ts declares `ContractError` as exactly `400 | 413 | 415 | 422` — 400 invalid JSON
        /// or invalid gzip, 413 over the 64 KB cap, 415 wrong content type or content encoding —
        /// and answers 422 for `validation_failed`, `measurement_consent_required` and the
        /// `clipboard_*` refusals. Those four are the only ones where the server has judged the
        /// BYTES and would judge the identical bytes the same way forever. Every other 4xx it can
        /// emit is about something else and must not destroy anything: 401 `invalid_app_key` and
        /// 403 `disabled_app_key` (a key can be fixed or re-enabled), 409 `idempotency_conflict`
        /// (which PROVES the epoch exists — see submitFirstOpenIfDue), 429 rate limiting.
        ///
        /// The opposite mistake is bounded and the right one to make: events held under a status
        /// that really was permanent age out on the queue's own 72h clock and cost radio, not data.
        fun isPermanentClientFailure(statusCode: Int): Boolean =
            statusCode in PERMANENT_CLIENT_FAILURE_STATUSES

        fun isReclassifiedStatus(statusCode: Int): Boolean =
            statusCode in RECLASSIFIED_STATUSES

        /// Anchored to the FIRST field of the object on purpose. A loose search of the raw body
        /// would also match this phrase quoted inside another field — a validation `issues[].message`
        /// echoing it, say — and would then promote a genuinely permanent 422 into an endless retry.
        private val unknownInstallEpochPattern =
            Regex("""^\s*\{\s*"error"\s*:\s*"unknown_install_epoch"""")

        /// True only when the server refused because it has not registered this install epoch yet.
        /// Accepts both encodings deliberately: the API answers 503 now, because that is the class
        /// every already-installed SDK retries rather than deletes, and answered 422 before — an app
        /// in the field can meet either during a rollout. Deliberately narrow otherwise, so any
        /// other body falls through to isPermanentClientFailure — whose allowlist is exactly
        /// {400, 413, 415, 422}, so a 4xx outside it is RETRIED, and a 422 that is not this error
        /// is destroyed like the other three allowlisted statuses; an empty or unparseable body
        /// answers false, because it must never become an unbounded retry.
        fun isUnknownInstallEpoch(response: HttpResponse): Boolean {
            if (response.statusCode != 422 && response.statusCode != 503) return false
            if (response.body.isEmpty()) return false
            return unknownInstallEpochPattern.containsMatchIn(response.body.decodeToString())
        }

        private fun validateEvent(
            name: String,
            version: Int,
            raw: Map<String, Any?>,
        ): Map<String, EventValue> {
            require(eventNamePattern.matches(name))
            require(version > 0)
            // The producer refuses what the queue decoder cannot read back. Key size (64 bytes) and
            // text value size (1 KiB) were capped here; the COUNT was not, while QueueCodec bounds
            // it at 10,000. Ten thousand small properties encode to well under the 1 MiB queue byte
            // cap, so such an event was accepted, persisted, and then failed to decode -- and a
            // failed decode discards the whole persisted queue, so one malformed track() call cost
            // every other event waiting on that device. Rejecting it here is a REJECTED_INVALID
            // return to the caller instead -- by the ONE route every refusal in this function
            // takes: require() throws IllegalArgumentException, and applyTrack wraps this call in
            // runCatching { }.getOrElse { return TrackResult.REJECTED_INVALID }. The throw is the
            // signal; the result is what track() returns. Nothing reaches the host app, and
            // nothing else may call this function -- it is private, and applyTrack is its only
            // call site, which is what makes that guarantee checkable rather than hoped for.
            require(raw.size <= QueueCodec.MAX_EVENT_PROPERTIES)
            return linkedMapOf<String, EventValue>().also { normalized ->
                raw.forEach { (key, value) ->
                    require(key.isNotEmpty() && key.toByteArray().size <= 64)
                    require(!forbiddenPropertyKey.containsMatchIn(key))
                    val converted = EventValue.from(value)
                    if (converted is EventValue.Text) {
                        require(converted.value.toByteArray().size <= 1_024)
                        require(!emailPattern.containsMatchIn(converted.value))
                        require(!phonePattern.containsMatchIn(converted.value))
                    }
                    if (converted is EventValue.Numeric) require(converted.value.isFinite())
                    normalized[key] = converted
                }
            }
        }

        private fun minInstant(left: Instant?, right: Instant?): Instant? = when {
            left == null -> right
            right == null -> left
            left <= right -> left
            else -> right
        }
    }
}

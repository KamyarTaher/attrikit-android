package dev.attrkit.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal object StorageKeys {
    const val INSTALLATION_ID = "io.attrikit.installation-id"
    const val INSTALL_EPOCH_ID = "io.attrikit.install-epoch"
    const val CONSENT = "io.attrikit.consent"
    /// Google's DMA values as the app set them explicitly (`eea|ad_user_data|ad_personalization`,
    /// each 0 or 1); they win over the TCF keys until cleared.
    const val MANUAL_DMA_CONSENT = "io.attrikit.manual-dma-consent"
    const val FIRST_OPEN_PENDING_EPOCH = "io.attrikit.first-open.pending-epoch"
    const val FIRST_OPEN_PENDING_BODY = "io.attrikit.first-open.pending-body"
    const val FIRST_OPEN_SETTLED_EPOCH = "io.attrikit.first-open.settled-epoch"

    /// An epoch the server REFUSED, or whose retry ladder ran out. Deliberately distinct from
    /// SETTLED: settled opens the event-delivery gate, refused must not. It exists because
    /// clearing the pending snapshot alone is not enough — `ensureFirstOpenSnapshot()` would
    /// simply rebuild it, and since `track()` calls `work()`, a permanently refused install would
    /// then send one first-open request per tracked event.
    const val FIRST_OPEN_REFUSED_EPOCH = "io.attrikit.first-open.refused-epoch"
    const val FIRST_OPEN_RETRY = "io.attrikit.first-open.retry"
    const val EVENT_QUEUE = "io.attrikit.events-v1"

    /// The instant a persisted queue failed to decode and was discarded. The discard itself is not
    /// optional -- the bytes are unreadable and the alternative is an exception raised inside the
    /// customer's app on every track() for the life of the install -- but a silent discard of a
    /// user's queued events is not something to do without leaving a mark. Nothing reads this yet;
    /// it exists so that when someone asks "did we drop events on this device", the device can
    /// answer.
    const val EVENT_QUEUE_DISCARDED_AT = "io.attrikit.events-v1.discarded-at"
    const val EVENT_RETRY = "io.attrikit.events-retry"
    const val SESSION_INDEX = "io.attrikit.session-index"

    /// The consent receipt for a withdrawal or a denial, persisted BEFORE the queue is wiped and
    /// the epoch rotated. Revocation used to destroy the queue and rotate the identity and tell
    /// the server nothing, so the record that the user said no existed only on the device that
    /// had just erased it. It is written under the identity in force at the moment of the
    /// decision, because after rotateEpoch() there is nothing left to attribute it to.
    const val CONSENT_RECEIPT_PENDING = "io.attrikit.consent-receipt.pending"
    const val CONSENT_RECEIPT_RETRY = "io.attrikit.consent-receipt.retry"

    /// Minted once when the receipt is stored and replayed on every attempt, so a retry after an
    /// unclear failure cannot register a second distinct withdrawal.
    const val CONSENT_RECEIPT_IDEMPOTENCY = "io.attrikit.consent-receipt.idempotency"

    /// The install epoch the PENDING receipt names. Kept beside the body because this package has
    /// a JSON writer and no reader, so the epoch cannot be recovered from the body itself. Absent
    /// on a receipt written by a version before this key existed, which is treated as "unknown"
    /// and therefore as a DIFFERENT epoch -- the conservative direction, since the cost of
    /// guessing wrong is destroying a withdrawal.
    const val CONSENT_RECEIPT_EPOCH = "io.attrikit.consent-receipt.epoch"

    /// Undelivered receipts displaced by a receipt for a DIFFERENT epoch, oldest first, one
    /// `<idempotency-key>\t<body>` per line. Both separators are escaped by the JSON writer, so
    /// neither can occur inside a body.
    ///
    /// The pending slot holds ONE receipt and every consent transition overwrote it. That is right
    /// for two denials of the same epoch -- the newer one supersedes the older, which is what
    /// "a newer denial replaces an older pending receipt" pins -- and wrong across a REVOCATION,
    /// which rotates the epoch: the revocation receipt names the epoch whose data the server is
    /// holding, and a later denial under the new epoch destroyed it. The server then never learned
    /// the user withdrew.
    ///
    /// A separate key rather than a new format for the pending slot, deliberately: an app rolled
    /// back to an older SDK still finds a single valid JSON body under the old key and ignores
    /// this one, instead of POSTing a container format it cannot parse.
    const val CONSENT_RECEIPT_ARCHIVE = "io.attrikit.consent-receipt.archive"

    /// The opaque user id the host set with setUserID (the RevenueCat app user id the server joins a
    /// purchase to). Written only while measurement is allowed and erased with every other trace of
    /// the install on a withdrawal or a deletion.
    const val USER_ID = "io.attrikit.user-id"

    /// `<install epoch>\t<user id>` of the identify the server acknowledged, and of the one it
    /// refused outright. Keyed on BOTH because a new epoch (a revocation) or a new id is a new fact
    /// to deliver, and on nothing less because an identify for the same pair is one the server
    /// already has. The refused marker is dropped at every launch, as FIRST_OPEN_REFUSED_EPOCH is,
    /// so a server-side correction recovers it.
    const val IDENTIFY_DELIVERED = "io.attrikit.identify.delivered"
    const val IDENTIFY_REFUSED = "io.attrikit.identify.refused"
    const val IDENTIFY_RETRY = "io.attrikit.identify.retry"

    /// `<installation id>|<install epoch>` of an erasure the user asked for and the server has not
    /// yet acknowledged. Persisted BEFORE the request, so a process that dies mid-flight, or a
    /// server that is down, leaves measurement halted rather than resumed over a deletion request.
    const val DELETION_TOMBSTONE = "io.attrikit.deletion-tombstone"
    const val DELETION_RETRY = "io.attrikit.deletion-retry"

    /// Every key above. A completed deletion removes all of them in one write, so a key added here
    /// and left out of this list would survive an erasure the user was told had completed.
    val ALL: List<String> = listOf(
        INSTALLATION_ID, INSTALL_EPOCH_ID, CONSENT, MANUAL_DMA_CONSENT,
        FIRST_OPEN_PENDING_EPOCH, FIRST_OPEN_PENDING_BODY, FIRST_OPEN_SETTLED_EPOCH,
        FIRST_OPEN_REFUSED_EPOCH, FIRST_OPEN_RETRY, EVENT_QUEUE, EVENT_QUEUE_DISCARDED_AT, EVENT_RETRY,
        SESSION_INDEX, CONSENT_RECEIPT_PENDING, CONSENT_RECEIPT_RETRY, CONSENT_RECEIPT_IDEMPOTENCY,
        CONSENT_RECEIPT_EPOCH, CONSENT_RECEIPT_ARCHIVE, USER_ID, IDENTIFY_DELIVERED, IDENTIFY_REFUSED,
        IDENTIFY_RETRY, DELETION_TOMBSTONE, DELETION_RETRY,
    )
}

internal data class DeletionTombstone(val installationId: UUID, val installEpochId: UUID) {
    fun encode(): String = "${installationId.toString().lowercase()}|${installEpochId.toString().lowercase()}"

    fun toJson(): String = Json.stringify(
        linkedMapOf(
            "installation_id" to installationId.toString().lowercase(),
            "install_epoch_id" to installEpochId.toString().lowercase(),
        ),
    )

    companion object {
        /// Null for anything but two canonical UUIDs: an unreadable record is never guessed at.
        fun decode(value: String): DeletionTombstone? {
            val parts = value.split('|')
            if (parts.size != 2) return null
            val installation = parts[0].toUuid() ?: return null
            val epoch = parts[1].toUuid() ?: return null
            if (installation.toString() != parts[0] || epoch.toString() != parts[1]) return null
            return DeletionTombstone(installation, epoch)
        }
    }
}

internal class IdentityRepository(
    private val store: KeyValueStore,
    private val ids: IdGenerator,
) {
    fun initialize(): InstallationIdentity {
        val storedInstallation = store.get(StorageKeys.INSTALLATION_ID)?.toUuid()
        val storedEpoch = store.get(StorageKeys.INSTALL_EPOCH_ID)?.toUuid()
        val installation = storedInstallation ?: ids.next()
        val epoch = storedEpoch ?: ids.next()
        if (storedInstallation == null || storedEpoch == null) {
            store.write(
                mapOf(
                    StorageKeys.INSTALLATION_ID to installation.toString().lowercase(),
                    StorageKeys.INSTALL_EPOCH_ID to epoch.toString().lowercase(),
                ),
            )
        }
        return InstallationIdentity(
            installationId = installation,
            installEpochId = epoch,
            localLineagePresent = storedInstallation != null,
            localEpochPresent = storedEpoch != null,
        )
    }

    fun rotateEpoch(): InstallationIdentity {
        val current = initialize()
        val nextEpoch = ids.next()
        store.write(
            mapOf(
                StorageKeys.INSTALL_EPOCH_ID to nextEpoch.toString().lowercase(),
                StorageKeys.FIRST_OPEN_PENDING_EPOCH to null,
                StorageKeys.FIRST_OPEN_PENDING_BODY to null,
                StorageKeys.FIRST_OPEN_SETTLED_EPOCH to null,
                StorageKeys.FIRST_OPEN_RETRY to null,
                StorageKeys.SESSION_INDEX to null,
            ),
        )
        return InstallationIdentity(
            installationId = current.installationId,
            installEpochId = nextEpoch,
            localLineagePresent = true,
            localEpochPresent = false,
        )
    }
}

internal data class PendingBatch(
    val batchId: String,
    val eventIds: List<UUID>,
)

internal data class QueueState(
    val events: List<EventEnvelope> = emptyList(),
    val pendingBatch: PendingBatch? = null,
)

internal data class RetryState(
    val attempt: Int,
    val firstAttemptAt: Instant,
    val nextAttemptAt: Instant,
) {
    fun encode(): String = "$attempt|${firstAttemptAt.toEpochMilli()}|${nextAttemptAt.toEpochMilli()}"

    companion object {
        fun decode(value: String?): RetryState? {
            val parts = value?.split('|') ?: return null
            if (parts.size != 3) return null
            return runCatching {
                val attempt = parts[0].toInt()
                require(attempt >= 0) { "retry attempt must be non-negative" }
                RetryState(
                    attempt,
                    Instant.ofEpochMilli(parts[1].toLong()),
                    Instant.ofEpochMilli(parts[2].toLong()),
                )
            }.getOrNull()
        }
    }
}

internal class EventQueue(
    private val store: KeyValueStore,
    private val ids: IdGenerator,
    private val maxEvents: Int = 100,
    private val maxBytes: Int = 1_048_576,
    private val maxAge: Duration = Duration.ofHours(72),
    /// Only reached when a persisted queue fails to decode, to date the discard. Last and defaulted
    /// so the four existing positional call sites keep their meaning.
    private val clock: Clock = SystemClock,
) {
    /// The cap this queue actually enforces, and it is never past what QueueCodec.decode() can read
    /// back. CoreConfiguration.maxQueueEvents is public and settable, so a host asking for more than
    /// MAX_QUEUE_EVENTS used to be obeyed by enqueue(), persisted by save(), and then refused by the
    /// next load() -- which discards the queue, so the larger cap destroyed exactly the events it
    /// was raised to keep. Bounded here rather than thrown, for the same reason the corrupt-queue
    /// path below stopped throwing: a host binary already shipping that configuration must not
    /// start raising inside the customer's app.
    internal val enforcedMaxEvents: Int = minOf(maxEvents, QueueCodec.MAX_QUEUE_EVENTS)

    class QueueFull : Exception("event queue is full")

    /**
     * Kept DECLARED, never thrown. This exception was removed with the self-healing reversal
     * below, and that removal was a compatibility break on a shipped binary: a host app that
     * wrote `catch (e: CorruptQueue)` against an earlier release stops compiling against this one.
     * Removing a path a consumer we do not control may still call is a product decision, not a
     * refactor (CLAUDE.md §1); until the owner takes it, the name survives so existing catch
     * sites compile, and the behaviour they guarded against simply never happens any more.
     */
    @Deprecated("Never thrown: the queue self-heals instead. Retained only so host code that catches it still compiles.")
    class CorruptQueue(cause: Throwable) : Exception("persisted event queue is corrupt", cause)

    fun enqueue(event: EventEnvelope, now: Instant): Boolean {
        var state = prune(load(), now)
        if (state.events.any { it.eventId == event.eventId }) return false
        var events = state.events + event
        val pendingIds = state.pendingBatch?.eventIds?.toSet().orEmpty()
        while (events.size > enforcedMaxEvents || encodedSize(events) > maxBytes) {
            val removable = events.indexOfFirst {
                it.eventId !in pendingIds && !it.isProtectedRevenueEvent
            }
            if (removable < 0) throw QueueFull()
            val removed = events[removable]
            events = events.filterIndexed { index, _ -> index != removable }
            if (removed.eventId == event.eventId) throw QueueFull()
        }
        state = state.copy(events = events)
        save(state)
        return true
    }

    fun count(now: Instant): Int {
        val loaded = load()
        val pruned = prune(loaded, now)
        if (pruned !== loaded || store.get(StorageKeys.EVENT_QUEUE) == null) save(pruned)
        return pruned.events.size
    }

    fun nextBatch(now: Instant): EventBatch? {
        val loaded = load()
        var state = prune(loaded, now)
        var changed = state !== loaded
        state.pendingBatch?.let { pending ->
            val byId = state.events.associateBy { it.eventId }
            val events = pending.eventIds.mapNotNull(byId::get)
            if (events.size == pending.eventIds.size) {
                if (changed || store.get(StorageKeys.EVENT_QUEUE) == null) save(state)
                return EventBatch(pending.batchId, events)
            }
            state = state.copy(pendingBatch = null)
            changed = true
        }
        if (state.events.isEmpty()) {
            if (changed || store.get(StorageKeys.EVENT_QUEUE) == null) save(state)
            return null
        }

        // Payload size is monotonic in the number of events. Binary search bounds this to seven
        // serialisations for the 100-event cap; the old linear scan encoded 2, 3, ... 100 events
        // before encoding the queue again, synchronously on the caller's thread.
        // MINTED BEFORE THE SEARCH, because the batch id is part of the payload the search bounds.
        // The probe used an empty one while the batch this returns carries a 36-character uuid, so
        // it measured a body 36 bytes shorter than the one that is sent: a queue within 36 bytes of
        // the limit passed the check and produced a batch that broke it.
        val batchId = ids.next().toString().lowercase()
        var lower = 1
        var upper = minOf(QueueCodec.MAX_PENDING_BATCH_EVENTS, state.events.size)
        while (lower < upper) {
            val candidateCount = (lower + upper + 1) / 2
            val candidate = state.events.take(candidateCount).map { it.copy(sentAt = now) }
            if (EventBatch(batchId, candidate).toJson().toByteArray().size <= 56 * 1_024) {
                lower = candidateCount
            } else {
                upper = candidateCount - 1
            }
        }
        val count = lower
        val selectedIds = state.events.take(count).map { it.eventId }.toSet()
        val updated = state.events.map {
            if (it.eventId in selectedIds) it.copy(sentAt = now) else it
        }
        val pending = PendingBatch(
            batchId = batchId,
            eventIds = updated.take(count).map { it.eventId },
        )
        state = QueueState(updated, pending)
        save(state)
        return EventBatch(pending.batchId, updated.take(count))
    }

    fun acknowledge(batchId: String) {
        val state = load()
        val pending = state.pendingBatch ?: return
        if (pending.batchId != batchId) return
        val acknowledged = pending.eventIds.toSet()
        save(QueueState(state.events.filterNot { it.eventId in acknowledged }, null))
    }

    fun splitPending(batchId: String) {
        val state = load()
        val pending = state.pendingBatch ?: return
        if (pending.batchId != batchId || pending.eventIds.size <= 1) return
        val firstHalf = pending.eventIds.take(pending.eventIds.size / 2)
        save(
            state.copy(
                pendingBatch = PendingBatch(
                    ids.next().toString().lowercase(),
                    firstHalf,
                ),
            ),
        )
    }

    fun wipe() {
        store.write(
            mapOf(
                StorageKeys.EVENT_QUEUE to null,
                StorageKeys.EVENT_RETRY to null,
            ),
        )
    }

    fun retryState(): RetryState? = RetryState.decode(store.get(StorageKeys.EVENT_RETRY))

    fun setRetryState(state: RetryState?) {
        store.write(mapOf(StorageKeys.EVENT_RETRY to state?.encode()))
    }

    private fun prune(state: QueueState, now: Instant): QueueState {
        val retained = state.events.filter {
            Duration.between(it.occurredAt, now) <= maxAge
        }
        val retainedIds = retained.map { it.eventId }.toSet()
        val pending = state.pendingBatch?.let { p ->
            val remainingIds = p.eventIds.filter { it in retainedIds }
            if (remainingIds.isEmpty()) null
            else if (remainingIds.size == p.eventIds.size) p
            else PendingBatch(p.batchId, remainingIds)
        }
        return if (retained.size == state.events.size && pending == state.pendingBatch) state
        else QueueState(retained, pending)
    }

    private fun encodedSize(events: List<EventEnvelope>): Int =
        QueueCodec.encode(QueueState(events)).length

    /**
     * Self-heals instead of throwing, and that is a deliberate reversal.
     *
     * `CorruptQueue` was raised here by every read path -- enqueue, the batch builder, the ack --
     * and caught NOWHERE in the package: `QueueFull` is caught in AttriKitCore.track(), this one
     * never was. So a truncated SharedPreferences commit, an app downgraded to an older codec, or
     * a string cleared by a device tool turned every subsequent `track()` into an exception thrown
     * inside the CUSTOMER'S app, on their main thread, for the life of the install. There is no
     * recovery a caller could have performed that this does not perform: the bytes are unreadable
     * either way, and the only open question was whether the SDK takes the host app down with them.
     *
     * The discard is recorded rather than silent. Losing a user's queued events is a real loss even
     * when it is the only option left.
     */
    private fun load(): QueueState {
        val encoded = store.get(StorageKeys.EVENT_QUEUE) ?: return QueueState()
        return decodeOrDiscard(encoded)
    }

    /**
     * The discard, and the one failure it does NOT apply to.
     *
     * A VirtualMachineError is not evidence about the bytes. An OutOfMemoryError raised while the
     * decoder allocates says the process was short of heap at that instant; the identical bytes
     * decode on the next attempt, so discarding them turns a transient condition into permanent
     * data loss -- the exact outcome the self-healing path exists to avoid, aimed at the user's
     * events instead of at the host app. Every other failure IS a property of the stored value: a
     * truncated commit, an older codec, a string a device tool cleared.
     *
     * `decode` is a parameter because no persisted bytes can make the decoder run out of heap on
     * demand, so the only way to give this branch a contract is to inject the failure. Production
     * takes the default, which is what load() above passes.
     */
    internal fun decodeOrDiscard(
        encoded: String,
        decode: (String) -> QueueState = QueueCodec::decode,
    ): QueueState = try {
        decode(encoded)
    } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        store.write(
            mapOf(
                StorageKeys.EVENT_QUEUE to null,
                StorageKeys.EVENT_QUEUE_DISCARDED_AT to clock.now().toString(),
            ),
        )
        QueueState()
    }

    private fun save(state: QueueState) {
        store.write(mapOf(StorageKeys.EVENT_QUEUE to QueueCodec.encode(state)))
    }
}

internal object QueueCodec {
    /// Version 2 carries each event's DMA values. A version 1 queue, written before they existed,
    /// still decodes: its events carry none, exactly as they were recorded.
    private const val VERSION = 2
    private const val VERSION_WITHOUT_DMA = 1

    /// The decoder's own bound on a single event's property count, named so the PRODUCER can refuse
    /// what this would later fail to read. An event carrying more encodes fine and throws on the
    /// next decode, which -- now that a failed decode discards the queue rather than crashing the
    /// host -- would cost every other queued event on the device.
    const val MAX_EVENT_PROPERTIES = 10_000

    /// The decoder's own bound on how many events one persisted queue may carry, named for exactly
    /// the reason above and previously spelled only as a literal inside decode(). encode() writes
    /// `state.events.size` with no bound, so a queue configured to hold more was persisted happily
    /// and refused by the next load() -- and a refused decode DISCARDS the whole queue, so a cap
    /// past this cost the device every event it was configured to keep. EventQueue reads it.
    const val MAX_QUEUE_EVENTS = 10_000

    /// The decoder's own bound on one pending batch's event ids. This is the CODEC's limit, and it
    /// is NOT the queue's maxEvents: a batch built to a larger cap encodes and then fails to
    /// decode, at the same cost. nextBatch() read it as a literal 100, which read as an arbitrary
    /// batch-size choice rather than as the persisted format's bound that it is.
    const val MAX_PENDING_BATCH_EVENTS = 100

    fun encode(state: QueueState): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeInt(state.events.size)
            state.events.forEach { output.writeEvent(it) }
            output.writeBoolean(state.pendingBatch != null)
            state.pendingBatch?.let { pending ->
                output.writeUTF(pending.batchId)
                output.writeInt(pending.eventIds.size)
                pending.eventIds.forEach { output.writeUuid(it) }
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(encoded: String): QueueState {
        val input = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
        // The stream is bound to a NAMED parameter: `List(n) { it }` shadows an implicit `it`
        // with the list index, which silently retargets readEvent()/readUuid() onto an Int.
        input.use { stream ->
            val version = stream.readInt()
            require(version == VERSION || version == VERSION_WITHOUT_DMA) { "unsupported queue version" }
            val eventCount = stream.readInt()
            require(eventCount in 0..MAX_QUEUE_EVENTS) { "invalid event count" }
            val events = List(eventCount) { stream.readEvent(version) }
            val pending = if (stream.readBoolean()) {
                val batchId = stream.readUTF()
                val idCount = stream.readInt()
                require(idCount in 1..MAX_PENDING_BATCH_EVENTS) { "invalid pending batch" }
                PendingBatch(batchId, List(idCount) { stream.readUuid() })
            } else {
                null
            }
            require(stream.available() == 0) { "trailing queue bytes" }
            return QueueState(events, pending)
        }
    }

    private fun DataOutputStream.writeEvent(event: EventEnvelope) {
        writeUuid(event.eventId)
        writeUTF(event.eventName)
        writeInt(event.eventVersion)
        writeInstant(event.occurredAt)
        writeInstant(event.sentAt)
        writeUuid(event.installationId)
        writeUuid(event.installEpochId)
        writeUuid(event.sessionId)
        writeUTF(event.consent.measurement)
        writeUTF(event.consent.tracking)
        writeInt(event.consent.policyVersion)
        val dma = event.consent.dma
        writeBoolean(dma != null)
        if (dma != null) {
            writeBoolean(dma.eea)
            writeByte(optionalFlag(dma.adUserData))
            writeByte(optionalFlag(dma.adPersonalization))
            writeUTF(dma.source.wireValue)
        }
        writeInt(event.properties.size)
        event.properties.forEach { (key, value) ->
            writeUTF(key)
            when (value) {
                is EventValue.Text -> {
                    writeByte(1)
                    writeUTF(value.value)
                }
                is EventValue.Numeric -> {
                    writeByte(2)
                    writeDouble(value.value)
                }
                is EventValue.Flag -> {
                    writeByte(3)
                    writeBoolean(value.value)
                }
                EventValue.Null -> writeByte(4)
            }
        }
    }

    private fun DataInputStream.readEvent(version: Int): EventEnvelope {
        val eventId = readUuid()
        val eventName = readUTF()
        val eventVersion = readInt()
        val occurredAt = readInstant()
        val sentAt = readInstant()
        val installationId = readUuid()
        val installEpochId = readUuid()
        val sessionId = readUuid()
        val measurement = readUTF()
        val tracking = readUTF()
        val policyVersion = readInt()
        val dma = if (version >= VERSION && readBoolean()) readDma() else null
        val consent = EventConsent(measurement, tracking, policyVersion, dma)
        val propertyCount = readInt()
        require(propertyCount in 0..QueueCodec.MAX_EVENT_PROPERTIES) { "invalid property count" }
        val properties = linkedMapOf<String, EventValue>()
        repeat(propertyCount) {
            val key = readUTF()
            properties[key] = when (readByte().toInt()) {
                1 -> EventValue.Text(readUTF())
                2 -> EventValue.Numeric(readDouble())
                3 -> EventValue.Flag(readBoolean())
                4 -> EventValue.Null
                else -> error("invalid property type")
            }
        }
        return EventEnvelope(
            eventId, eventName, eventVersion, occurredAt, sentAt,
            installationId, installEpochId, sessionId, consent, properties,
        )
    }

    private fun optionalFlag(value: Boolean?): Int = when (value) {
        null -> 0
        false -> 1
        true -> 2
    }

    private fun DataInputStream.readOptionalFlag(): Boolean? = when (readByte().toInt()) {
        0 -> null
        1 -> false
        2 -> true
        else -> error("invalid dma flag")
    }

    private fun DataInputStream.readDma(): DmaConsent {
        val eea = readBoolean()
        val adUserData = readOptionalFlag()
        val adPersonalization = readOptionalFlag()
        val wire = readUTF()
        val source = DmaConsent.Source.entries.firstOrNull { it.wireValue == wire } ?: error("invalid dma source")
        return DmaConsent(eea, adUserData, adPersonalization, source)
    }

    private fun DataOutputStream.writeUuid(value: UUID) {
        writeLong(value.mostSignificantBits)
        writeLong(value.leastSignificantBits)
    }

    private fun DataInputStream.readUuid(): UUID = UUID(readLong(), readLong())

    private fun DataOutputStream.writeInstant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.readInstant(): Instant {
        val epochSecond = readLong()
        val nano = readInt()
        require(nano in 0..999_999_999) { "invalid instant nanosecond" }
        return Instant.ofEpochSecond(epochSecond, nano.toLong())
    }
}

private fun String.toUuid(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

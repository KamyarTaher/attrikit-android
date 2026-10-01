package dev.attrkit.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.attrkit.core.AttriKitCore
import dev.attrkit.core.ConsentState
import dev.attrkit.core.RandomIdGenerator
import dev.attrkit.core.SystemClock

/**
 * AttriKit for Android. Call [start] once, from `Application.onCreate`, with the consent the user
 * has already given; every other call is a no-op until then.
 *
 * The main thread never does disk or network work. Calls return at once: state changes, delivery,
 * Play and persistence run on one background thread, and [attribution] waits on a small separate
 * pool (two threads) so a wait never holds that thread. The blocking form of [attribution] waits on
 * the thread that calls it, which must not be the main thread. Callbacks and listeners run on the
 * main thread. All methods are safe to call from any thread.
 */
object AttriKit {
    /** The one ingest host for every workspace; the publishable key routes the event. */
    const val DEFAULT_ENDPOINT = "https://attrikit.io"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val runtime = AttriKitRuntime(postToMain = { mainHandler.post(it) })
    private val observing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Begins measurement. [consent] is the SDK's own consent (see [ConsentState]); advertising
     * identifiers are read only for [ConsentState.TRACKING_GRANTED]. A second call changes the
     * consent, nothing else. [endpoint] exists for a local capture stack; leave it out.
     *
     * [setTcfDataCollectionEnabled], [setGoogleConsent] and [clearGoogleConsent] may be called
     * before this and are in force for the first-open snapshot it takes.
     *
     * @throws IllegalArgumentException if [apiKey] is not a 16 to 512 byte publishable key, or
     * [context] has no application context.
     */
    @JvmStatic
    @JvmOverloads
    fun start(
        context: Context,
        apiKey: String,
        consent: ConsentState,
        endpoint: String = DEFAULT_ENDPOINT,
    ) {
        // The core's own rule, checked here so the mistake surfaces on the caller's thread rather
        // than as a swallowed failure on the worker.
        require(apiKey.toByteArray().size in 16..512) { "apiKey must be 16..512 bytes" }
        // A context not yet attached (an early ContentProvider callback) has none, and the worker
        // job that would hit it would die silently.
        val app = requireNotNull(context.applicationContext) {
            "AttriKit.start needs a Context with an application context"
        }
        runtime.start({ buildCore(app, endpoint) }, apiKey, consent)
        if (observing.compareAndSet(false, true)) observeProcessLifecycle(runtime)
    }

    /** The user changed their answer to the SDK's own consent question. */
    @JvmStatic
    fun setConsent(consent: ConsentState) {
        runtime.setConsent(consent)
    }

    /**
     * Records [name] with scalar [properties] (String, Boolean, numbers, null). Do not put email
     * addresses or phone numbers in them. An invalid event is dropped.
     */
    @JvmStatic
    @JvmOverloads
    fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        // Copied here: the host may keep mutating its map while the worker is still waiting.
        runtime.track(name, HashMap(properties))
    }

    /**
     * Google's EU consent answers for this user, when the app collects them itself instead of
     * through an IAB TCF consent platform. They win over TCF until [clearGoogleConsent].
     */
    @JvmStatic
    fun setGoogleConsent(eea: Boolean, adUserData: Boolean, adPersonalization: Boolean) {
        runtime.setGoogleConsent(eea, adUserData, adPersonalization)
    }

    /** Forgets [setGoogleConsent]; the IAB TCF consent applies again. */
    @JvmStatic
    fun clearGoogleConsent() {
        runtime.clearGoogleConsent()
    }

    /** Pass false to stop reading the IAB TCF consent from the app's default SharedPreferences. */
    @JvmStatic
    fun setTcfDataCollectionEnabled(enabled: Boolean) {
        runtime.setTcfDataCollectionEnabled(enabled)
    }

    /**
     * Sets the opaque id your own backend knows this user by (RevenueCat's app user id) and sends it
     * so a purchase recorded under it joins this install. `null` or an empty string clears it. An id
     * over 256 UTF-8 bytes or containing '@' (an email is not an opaque id) is refused and the id
     * already set stays. Like the Google consent calls it may be called before [start]; the last
     * value is in force for the first identify.
     */
    @JvmStatic
    fun setUserID(userId: String?) {
        runtime.setUserID(userId)
    }

    /**
     * Asks for this install's attribution and answers on the main thread, after at most
     * [timeoutMillis] (capped at 60 s). A provisional answer is returned as it is. Waiting happens
     * on a small background pool, never the SDK's delivery thread. Answers
     * [dev.attrkit.core.AttributionResult.NotStarted] before [start].
     */
    @JvmStatic
    fun attribution(timeoutMillis: Long, callback: AttributionCallback) {
        runtime.attribution(timeoutMillis, callback)
    }

    /**
     * The blocking form of [attribution]: waits up to [timeoutMillis] on the CALLING thread, so call
     * it from a background thread only. On the main thread it does not wait and answers
     * [dev.attrkit.core.AttributionResult.TimedOut] at once.
     */
    @JvmStatic
    fun attribution(timeoutMillis: Long): dev.attrkit.core.AttributionResult =
        runtime.attributionBlocking(timeoutMillis)

    /**
     * Receives the attribution state now and on every change, on the main thread. Registering
     * counts as asking for attribution. Register before or after [start]; a listener is held once
     * however many times it is added. It is held until [removeAttributionListener], so a listener
     * that captures an Activity leaks it: remove it in `onStop` or `onDestroy`. A removal also stops
     * an update already on its way to the main thread.
     */
    @JvmStatic
    fun addAttributionListener(listener: dev.attrkit.core.AttributionListener) {
        runtime.addAttributionListener(listener)
    }

    @JvmStatic
    fun removeAttributionListener(listener: dev.attrkit.core.AttributionListener) {
        runtime.removeAttributionListener(listener)
    }

    /**
     * Erases this install's data on the server and then on the device, and reports on the main
     * thread. Measurement is halted from the call until the server acknowledges; a refused request
     * is retried in the background. After [dev.attrkit.core.DeletionResult.Completed] the SDK is
     * back to before [start]: call [start] again to resume under a new installation id.
     */
    @JvmStatic
    fun deleteData(callback: DeletionCallback) {
        runtime.deleteData(callback)
    }

    /**
     * The installation id the SDK measures under, to hand to your own backend wherever revenue is
     * recorded. Null until the SDK's background thread has finished its first pass after
     * [start], and whenever consent does not allow measurement. Never blocks the caller. Read it when you need
     * it, not on the line after [start].
     */
    @JvmStatic
    fun installationId(): String? = runtime.installationId()

    private fun buildCore(context: Context, endpoint: String): AttriKitCore {
        val facts = DeviceFacts.read(context)
        return AttriKitCore(
            configuration = facts.configuration(endpoint, DeviceFacts.appVersion(context)),
            store = SharedPreferencesKeyValueStore.create(context),
            clock = SystemClock,
            transport = HttpUrlConnectionTransport(),
            referrerClient = PlayInstallReferrerClient(context),
            advertisingIdProvider = GoogleAdvertisingIdProvider(context),
            ids = RandomIdGenerator,
            tcfPreferences = SharedPreferencesTcfPreferences.create(context),
        )
    }

    private fun observeProcessLifecycle(target: AttriKitRuntime) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = target.onProcessForeground()
            override fun onStop(owner: LifecycleOwner) = target.onProcessBackground()
        }
        // The lifecycle registry must be touched on the main thread, and start() may be called
        // from any. A process that is already visible replays onStart to a new observer.
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        }
    }
}

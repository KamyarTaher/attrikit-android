# AttriKit for Android: changes

## 1.3.0 (first public release)

AttriKit for Android measures installs and in-app events and matches them to the campaign that
caused them. It is the Android counterpart of the iOS SDK and speaks the same ingest contract.

### What it does

- **Install attribution.** At first open it reads Google Play's install referrer (bounded to 5
  seconds) and sends it with the installation id, so the server can match the install to a click.
  `AttriKit.attribution(timeoutMillis) { result -> ... }` answers with the match, an organic
  verdict or a timeout on the main thread; a blocking form exists for background threads, and
  `addAttributionListener` reports every change.
- **Events.** `AttriKit.track(name, properties)` queues events on disk and delivers them in
  batches with retries. The queue keeps up to 100 events, 1 MiB and 72 hours and drops the oldest
  beyond that. The SDK sends `session_end` by itself from the app lifecycle.
- **Consent.** `ConsentState` governs what is measured. The advertising id and App Set ID are read
  only for `TRACKING_GRANTED` when the first open is built, and never when the user has turned the
  advertising id off.
- **Google EU consent.** The SDK reads the IAB TCF consent a consent platform stores in the app's
  default SharedPreferences and sends Google the user's `ad_user_data` and `ad_personalization`
  answers. `setGoogleConsent`, `clearGoogleConsent` and `setTcfDataCollectionEnabled` may be called
  before `start`, and take effect for the first-open snapshot.
- **Users.** `AttriKit.setUserID(id)` links your own user id (RevenueCat's app user id) to the
  install; `AttriKit.installationId()` returns the installation id to hand to your backend.
- **Erasure.** `AttriKit.deleteData { result -> ... }` erases the install's data on the server and
  the device. Measurement stays halted until the server acknowledges, and a refused request is
  retried in the background.

### Requirements

- `minSdk` 21. The library uses `java.time`, so a host with a `minSdk` below 26 must enable core
  library desugaring (see the README).
- Your main thread never does disk or network work. One background thread runs persistence, delivery
  and Play; `attribution` waits on a small separate pool (two threads), and the blocking form waits
  on the thread that calls it. Callbacks and listeners run on the main thread.

### Not in this release

- Device evidence the iOS SDK adds to the identify request (funnel hashes, an exact link token) has
  no Android source yet and is not sent.

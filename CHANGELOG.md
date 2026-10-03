# AttriKit for Android: changes

## 1.4.0

- **The screen size and scale now describe the whole display.** The SDK used to send the size of the
  app's window, which on most phones leaves out the navigation bar, and a scale rounded through a
  float (a 411 dpi screen went out as 2.568749904632568). AttriKit compares them with what the
  browser reports for the full screen at the click, so an Android install almost never agreed with
  its click on screen. The SDK now sends the full display size and the exact scale, so the
  comparison can match and add its evidence.

No API change. Upgrade by changing the dependency to `1.4.0`.

## 1.3.1

- **The advertising id after a later tracking grant.** 1.3.0 sent the advertising id and App Set ID
  only with the first open, and only if consent was `TRACKING_GRANTED` at that moment. An app that
  asks for tracking after onboarding therefore never sent them, and Google never received the
  advertising id for those installs. When tracking is granted after the first open, by `setConsent`
  or by the consent passed to a later `start`, the SDK now records the grant with AttriKit (a consent
  receipt with scope `tracking`) and then sends both ids in an identify request.
- **Read at every start.** While consent is `TRACKING_GRANTED` the ids are read once per launch, so an
  advertising id the user resets is sent. A pair already sent is not sent again, and an id the user
  turns off is never sent.
- **Taking tracking back.** Lowering consent from `TRACKING_GRANTED` erases the stored ids from the
  device and sends a consent receipt with the new state. Nothing is read or sent without
  `TRACKING_GRANTED`, as before.

No API change. Upgrade by changing the dependency to `1.3.1`.

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

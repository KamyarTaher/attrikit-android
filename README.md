# AttriKit Android SDK

The installable Android library for AttriKit. It compiles the pure-Kotlin core (`core/` in the
public repository, `packages/sdk-android-core` in the monorepo) into one AAR and adds the Android parts: persistence, HTTP, the Google Play
install referrer, the advertising id, IAB TCF consent and the process lifecycle hook.

Build it locally with `./gradlew assembleRelease`; the AAR lands in
`build/outputs/aar/attrikit-android-release.aar`.

## Install

The library is served by JitPack from the public repository. In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

and in your app module:

```kotlin
dependencies {
    implementation("com.github.KamyarTaher:attrikit-android:1.4.0")
}
```

The library needs core library desugaring in the **host app**, because the core uses `java.time`
and `java.util.Base64`, which Android has only from API 26 and the library supports API 21:

```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

If your `minSdk` is 26 or higher you can skip this.

Requirements: `minSdk` 21 or higher. The library adds the `INTERNET` permission to your manifest. It
adds no `AD_ID` permission of its own, but `play-services-ads-identifier` already merges
`com.google.android.gms.permission.AD_ID` into your app. If you do not want the advertising id
declared, remove it with `tools:node="remove"`. On Android 13 and later the SDK then receives no
advertising id; below Android 13 the permission does not gate it, so the SDK still reads the id under
`TRACKING_GRANTED`.

Exclude the SDK's preferences file from backup, or a restored backup will hand a new device the old
installation id. The two manifest attributes take two different files, because
`android:fullBackupContent` expects a `<full-backup-content>` root and `android:dataExtractionRules`
(Android 12 and later) expects `<data-extraction-rules>`:

```xml
<!-- AndroidManifest.xml -->
<application
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules" ... >

<!-- res/xml/backup_rules.xml -->
<full-backup-content>
    <exclude domain="sharedpref" path="dev.attrkit.sdk.xml" />
</full-backup-content>

<!-- res/xml/data_extraction_rules.xml -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="dev.attrkit.sdk.xml" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="dev.attrkit.sdk.xml" />
    </device-transfer>
</data-extraction-rules>
```

## Start

Call `start` once, from `Application.onCreate`, with the consent the user has already given. `track`
and `setConsent` before `start` are dropped. `setUserID`, `setGoogleConsent`, `clearGoogleConsent`,
`setTcfDataCollectionEnabled` and `addAttributionListener` are held and take effect when `start` runs;
every other call does nothing until then.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AttriKit.start(this, "YOUR_PUBLISHABLE_KEY", ConsentState.MEASUREMENT_GRANTED)
    }
}
```

The key is your publishable key (`pk_...`); every workspace uses the one ingest host
`https://attrikit.io`, and the key routes events to your app. A second `start` call changes the
consent and nothing else. A key that is not 16 to 512 bytes throws `IllegalArgumentException`.

## Consent

`ConsentState` is the SDK's own consent, separate from Google's (below):

| State | Measures | Advertising id |
|---|---|---|
| `UNKNOWN` | no | no |
| `MEASUREMENT_GRANTED` | yes | no |
| `TRACKING_GRANTED` | yes | yes |
| `DENIED` | no, and stored data is wiped | no |
| `REVOKED` | no, stored data is wiped and the install epoch rotates | no |

```kotlin
AttriKit.setConsent(ConsentState.TRACKING_GRANTED)
```

The advertising id and App Set ID are read only under `TRACKING_GRANTED`: when the first open is
built, at every `start`, and when tracking is granted. Most apps ask for tracking after onboarding, so
their first open goes out without the ids. When tracking is granted later, by `setConsent` or by the
consent you pass to a later `start`, the SDK records the grant with AttriKit and then sends both ids,
so Google receives the advertising id for those installs too. Each pair is sent once, and again if
the user resets the advertising id. Lowering consent from `TRACKING_GRANTED` erases the ids from the
device and records the change with AttriKit.

## Track events

```kotlin
AttriKit.track("trial_started", mapOf("plan" to "annual", "price" to 4.99))
```

The event name must match `^[a-z][a-z0-9_.-]{0,127}$`: lowercase, starting with a letter. Property
values are `String`, `Boolean`, numbers or `null`. A property key may be at most 64 bytes, and a
text value at most 1024 bytes. One rule catches people out: a key containing `email`, `e-mail`,
`phone`, `mobile`, `address` or `name` (in any case, as a substring) is refused, and the refusal
drops the whole event, not the property. `product_name` and `screen_name` are refused as well as
`email`. A text value that looks like an email address or a phone number refuses the event the same
way. An invalid event is dropped without an error, so check a new event in Live events.

```kotlin
AttriKit.track("Trial Started", mapOf("plan_name" to "annual")) // dropped: bad name, "name" in a key
AttriKit.track("trial_started", mapOf("plan" to "annual"))      // sent
```

`track` and `setConsent` before `start` are dropped, not buffered. Events are queued on disk and
delivered in batches with retries. The queue keeps at most 100 events, 1 MiB and 72 hours, and drops the
oldest beyond that, so a device offline for longer than that loses its oldest events. The SDK also
sends a `session_end` event by itself when the app goes to the background.

## Link your user id

```kotlin
AttriKit.setUserID("rc_app_user_id") // null or "" clears it
```

Use the opaque id your backend knows the user by, for example RevenueCat's app user id, so a
purchase recorded under it joins the install. An id over 256 bytes or containing `@` is refused (an
email is not an opaque id) and the id already set stays. Like the Google calls below, it may be
called before `start`.

## Attribution

Result types (`AttributionResult`, `AttributionListener`, `DeletionResult`, `ConsentState`) are in
`dev.attrkit.core`.

```kotlin
AttriKit.attribution(timeoutMillis = 5_000) { result ->
    when (result) {
        is AttributionResult.Attributed -> result.attribution.campaignID
        AttributionResult.Unattributed -> { /* organic */ }
        else -> { /* TimedOut, ConsentRequired, NotStarted, Failed */ }
    }
}
```

The callback runs on the main thread. A provisional answer is returned as it is; use a listener to
learn when it becomes final:

```kotlin
val listener = AttributionListener { update -> /* update.status, update.attribution */ }
AttriKit.addAttributionListener(listener)    // current state now, then every change, on the main thread
AttriKit.removeAttributionListener(listener)
```

A listener is held until you remove it, so one that captures an Activity leaks it: remove it in
`onStop` or `onDestroy`. Removing it also stops an update already on its way to the main thread.

`AttriKit.attribution(timeoutMillis)` without a callback blocks the calling thread for up to the
timeout: call it from a background thread. On the main thread it does not wait and answers
`TimedOut` at once. Waits are capped at 60 seconds.

## Delete the user's data

```kotlin
AttriKit.deleteData { result -> /* DeletionResult.Completed, Failed(statusCode) or NotStarted */ }
```

Erases the install's data on the server and then on the device. Measurement is halted from the call
until the server acknowledges; a refused request is retried in the background. After `Completed`
the SDK is back to before `start`: call `start` again to resume, under a new installation id.

## Google consent and IAB TCF

For users in the EEA, UK or Switzerland, Google needs `ad_user_data` and `ad_personalization`
answers. There are two sources:

- **Automatic, from a consent platform.** The SDK reads the `IABTCF_*` keys that a TCF consent
  management platform writes to the app's default SharedPreferences and derives Google's values from
  them (Google is TCF vendor 755, with the publisher's restrictions applied first). Nothing to call.
  `AttriKit.setTcfDataCollectionEnabled(false)` stops this read.
- **Manual, when you collect the answers yourself.**

```kotlin
AttriKit.setGoogleConsent(eea = true, adUserData = true, adPersonalization = false)
AttriKit.clearGoogleConsent() // the TCF values apply again
```

Manual values win over TCF and persist across launches until cleared. `setTcfDataCollectionEnabled`, `setGoogleConsent` and `clearGoogleConsent` may be called before `start` (call them first, in `Application.onCreate`): they are in force when the first-open snapshot is taken, so an opt-out is never read past.

## Installation id

```kotlin
val id = AttriKit.installationId() // null until the first background pass has finished, or when consent forbids it
```

Pass it to your own backend where revenue is recorded (RevenueCat's app user id, Stripe metadata) so
revenue the SDK never saw joins the install. It returns a snapshot the SDK thread refreshes after each job, so it never blocks your thread, and initialisation runs on that thread, so
read it when you need it, not on the line after `start`.

## Threading

Every call returns at once, and your main thread never does disk or network work. The SDK's one
background thread runs persistence, delivery and Play. `attribution(timeoutMillis, callback)` waits on
a small separate pool of two threads, so a wait never holds that thread, and the blocking
`attribution(timeoutMillis)` waits on the thread you call it from. Callbacks and listeners run on the
main thread. Failed deliveries are retried on the schedule the core reports, between one second and
one hour. All methods are safe to call from any thread.

## What is read from the device, and when

| What | When | Source |
|---|---|---|
| App version | at start, on the SDK thread | `PackageManager` |
| Country, languages, OS major, phone or tablet, device model, build id, timezone, screen size and density | at start | `Locale`, `Resources`, `Build` |
| Google Play install referrer | once, at first open, bounded to 5 seconds | Play Install Referrer API |
| Advertising id (GAID) and App Set ID | only while consent is `TRACKING_GRANTED`: when the first open is built, at every start, and when tracking is granted after the first open; never if the user turned off or deleted the advertising id | Play services |
| IAB TCF keys | whenever an event or first open is built, unless disabled | the app's default SharedPreferences |

Everything the SDK itself persists is in the private preferences file `dev.attrkit.sdk`. No
location, contacts, accounts or other apps' data are read. Locale tags are cut to
language-script-region and every value is bounded to the ingest schema's caps before it is sent.

## Development

```
./gradlew assembleRelease   # the AAR
./gradlew testDebugUnitTest # JVM unit tests
./gradlew lint
```

Needs JDK 17 or newer and an Android SDK with platform 36 and build-tools 36.0.0; point
`ANDROID_HOME` at it or put `sdk.dir=...` in the git-ignored `local.properties`. From the repo root,
in the monorepo, `bun run gate:sdk-android-build` builds the AAR, runs the unit tests and checks the AAR holds the
core and the facade; it prints `SKIP` where no Android SDK exists.

Android Gradle Plugin 9.4.1 with Gradle 9.7.1 (AGP 9.4 requires Gradle 9.6.0 or newer). Two
dependencies are pinned below their latest release because newer ones raise `minSdk` past 21; see
the comment in `build.gradle.kts`.

# Test-build remote switch

The IPA and APK handed to testers from the GitHub releases can be switched off remotely, so a test build that has been
passed around can be retired and people pointed to the App Store / Google Play. The iPhone and Android do the same
thing and look the same; the decision lives in one shared type. This file is the Android mapping (DESIGN.md D32); the
iPhone's side is `SatMap/Core/TestBuildSwitch.swift`, `TestBuildConfig`/`TestBuildGate` in its app.

## 1. Channel

A build-time channel, `test` or `store`:

- **Gradle** property `satmapChannel` → `BuildConfig.SATMAP_CHANNEL` (`app/build.gradle.kts`). The CI release APK
  (`.github/workflows/android.yml`, the Release build) leaves it unset, which is `test`. A future Play Store build sets
  it to `store` — in `android/gradle.properties`, or on the command line with `-PsatmapChannel=store`.
- `TestBuildConfig.channel` reads it: `store` → never checks, never locks; `test` → checks; **null in debug builds**
  (`BuildConfig.DEBUG`), which never check and never lock. A Release build with no explicit channel counts as `test`
  (the safer default — a store build that forgot to set it would lock, which is noticed at once, rather than a test
  build that never locks).
- The iPhone's counterpart is an Xcode build setting `SATMAP_CHANNEL` feeding an Info.plist key, read the same way;
  its Debug builds never check.

## 2. Status file

Read from the URLs in `TestBuildConfig.statusURLs`, in order, until one is readable:

1. `https://raw.githubusercontent.com/JacobusOettle/satmap/app-status/test-builds.json`
2. (a Cloudflare mirror, to be added before it later)

Each is fetched with `?t=<unix seconds>` appended to defeat caches, a 10 s timeout, normal HTTPS certificate checks,
and **no identifiers**: a plain GET, no device or user id, `User-Agent: SatMap/<version>`.

Format (schema 1):

```json
{"schema":1,"testBuilds":"on"|"off","title":string,"message":string,
 "appStoreURL":string|null,"playStoreURL":string|null,"websiteURL":string|null,"updated":string}
```

Unknown fields are ignored. Anything unreadable, a `schema` other than 1, or a `testBuilds` other than "on"/"off"
counts as **no information** (keep the last known state). `title` is cut to 120 characters and `message` to 500
(plain text). A link is used only if it is `https` and on `apps.apple.com`, `play.google.com`, or `github.com` with a
path under `/JacobusOettle/`; anything else (including `http`) is ignored.

The owner flips it with the **Switch test builds** workflow in the Actions tab, or by editing `test-builds.json` on the
`app-status` branch (README, "Test builds can be switched off").

## 3. Decision (shared core)

`com.jacobusoettle.satmap.core.TestBuildSwitch` (`core/.../TestBuildSwitch.kt`), the same names and behaviour as the
Swift core, holds the whole decision with no app or platform in it:

- `parse(text)` → `Status?`: parse and validate a fetched file (section 2); `null` means no information.
- `merged(saved, fetched)`: a readable fetch replaces the saved state; an unreadable one keeps it.
- `decide(channel, saved, fetched)` → `Lock?`: a `store` build is never locked; otherwise the fresh fetch wins when
  readable, else the saved state stands, and a locked state becomes a `Lock` with the default title/message filled in
  (`defaultTitle`, `defaultMessage`). `Lock` carries all three links; the UI picks which buttons to show.

Both cores' unit tests read the shared fixture `Tests/SatMapCoreTests/Fixtures/testbuild/cases.json` (valid on/off,
bad schema, bad values, long texts, disallowed links, http links, missing fields; and the `decide` cases).

## 4. The gate (app)

`TestBuildGate` (`app/.../TestBuildGate.kt`), owned by `AppModel` (like groups: it registers no `AppLifecycle`
observer of its own; `AppModel` drives it):

- Keeps the last known `Status` in SharedPreferences (`testBuildStatus`) and shows the lock it implies at once at
  launch, **before** the network answers, so a build that has seen *off* stays locked even offline. Never fetched
  means *on*. A later *on* unlocks.
- Checks at launch, on `willEnterForeground`, and every 5 minutes while in the foreground (`TestBuildConfig
  .foregroundInterval`). **Nothing in the background.**
- Publishes `lock: StateFlow<TestBuildSwitch.Lock?>`.

## 5. When off

`AppModel.applyLock` acts on the lock, keeping every user file (offline maps, the track, KML, groups):

- **The lock screen** (`TestBuildLockScreen`) over everything — the map and any sheet are out of reach, Back does
  nothing, the sheets are closed so nothing is behind it. It shows the title, the message, and the allowed-link
  buttons: "Get it on Google Play" (`playStoreURL`) and "Website" (`websiteURL`). The "Get it on the App Store" button
  is the iPhone's.
- **Downloads pause** (`OfflineManager.setLockedOut`): the running one is re-queued so it continues on unlock, nothing
  new starts, the files saved are kept, a user-paused area stays paused.
- **Recording stops** (`LocationRecorder.stopForLock`): GPS, the track and the foreground service, whatever the
  lifecycle says.
- **Groups pause** (`GroupsModel.setLockedOut`): ticks, uploads and the socket stop; the memberships and queued data
  stay.
- **Android Auto** shows a `MessageTemplate` ("…switched off…") instead of the map (`CarMapScreen`).

Nothing is ever deleted. When the switch is turned on again, unlock and everything resumes as before: a paused
download continues, recording restarts (on screen), groups wake.

## 6. Tests and walkthrough

- Core: `TestBuildSwitchTests` (the shared fixture). App: `TestBuildGateTest` (the glue: the saved *off* locks before
  the fetch and persists; a later *on* unlocks; a store build and a debug build never lock; an unreadable file keeps
  the last state; the test hooks). `ScreenRendersTestBuildTest` renders the lock screen (41).
- The walkthrough never checks the live switch — `TestBuildGate.disableForTests()` is called in the app's own process
  (never an intent extra, which a release build would read; the earlier security review found exactly that risk, D24).
  Screenshot **41-test-build-off** forces the locked state with sample texts and links through `forceLockedForTests`,
  as the iPhone forces it with a launch argument.

## 7. Privacy

No data is collected or sent beyond the plain cache-busted GET with a version User-Agent (section 2), so the iPhone's
purpose/privacy files (`SatMap/PrivacyInfo.xcprivacy`, Info.plist) need no change.

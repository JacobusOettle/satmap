# Test-build remote switch

The IPA and APK handed to testers from the GitHub releases can be switched off remotely, so a test build that has been
passed around can be retired and people pointed to the App Store / Google Play. The iPhone and Android do the same
thing and look the same; the rule lives in one shared type with the same names in both cores. This file is the Android
mapping (DESIGN.md D32); the iPhone's side is `SatMap/Core/TestBuildSwitch.swift` and `TestBuildConfig` /
`TestBuildGate` / `TestBuildLockView` in its app.

## 1. Channel

A build-time channel, `test` or `store`, in one place (`TestBuildConfig` in `app/.../Config.kt`):

- **Gradle** property `satmapChannel` → `BuildConfig.SATMAP_CHANNEL` (`app/build.gradle.kts`, default `test`). The CI
  release APK (`.github/workflows/android.yml`, `:app:assembleRelease`) leaves it alone, so it is `test`. A Play Store
  build sets it to exactly `store`: `./gradlew :app:bundleRelease -PsatmapChannel=store`, or `satmapChannel=store` in
  `android/gradle.properties`. Anything but exactly `store` is `test` (`TestBuildSwitch.Channel.from`), the safer
  default: a store build that forgot to set it would lock, which is noticed at once, rather than a test build that
  never locks.
- `TestBuildConfig.isDebugBuild` is `BuildConfig.DEBUG`: the debug build type (Android Studio, the walkthrough) never
  checks and never locks.
- `TestBuildSwitch.checks(channel, isDebugBuild)`: only a `test` build that isn't a debug build ever fetches, keeps or
  locks. A store build never sends a request.
- The iPhone's counterpart is the Xcode build setting `SATMAP_CHANNEL` feeding the Info.plist key `SatMapChannel`
  (`xcodebuild archive … SATMAP_CHANNEL=store` for the App Store); its Debug configuration never checks.

## 2. Status file

Read from `TestBuildConfig.statusURLs`, in order, until one gives information:

1. `https://raw.githubusercontent.com/JacobusOettle/satmap/app-status/test-builds.json`
2. (a Cloudflare mirror, to be added later)

Each is fetched with `?t=<unix seconds>` added (`TestBuildSwitch.requestURL`), a 10 s limit (`TestBuildSwitch.timeout`),
normal HTTPS certificate checks and no redirect from https to http, and **no identifiers**: a plain GET with no cookies
or cache, `User-Agent: Spoor/<version>` (`TestBuildSwitch.userAgent`) and `Accept: application/json`. Only a 200
counts, and at most 64 KiB + 1 byte is read.

Format (schema 1):

```json
{"schema":1,"testBuilds":"on"|"off","title":string,"message":string,
 "appStoreURL":string|null,"playStoreURL":string|null,"websiteURL":string|null,"updated":string}
```

`TestBuildSwitch.parse` gives **no information** (null: the last known state is kept) for anything that isn't one JSON
object in UTF-8 as RFC 8259 writes it (Swift's decoder is that strict; kotlinx alone would also take unquoted words or
a line break typed inside a string, so `StrictJson` checks first), a file over 64 KiB, a `schema` that isn't the
number 1, or a `testBuilds` that isn't exactly "on" or "off". Unknown fields are ignored; a text or link that isn't a
string counts as not given.

- `title` and `message` are plain text (`clean`): control characters removed (line breaks and tabs become spaces in
  the title; the message keeps line breaks), trimmed, and cut to 120 / 500 Unicode code points (an emoji is one, the
  same count as the iPhone's), trimmed again; empty is not given. Markup is shown as it is.
- A link (`allowedLink`) is kept only if it holds only URL characters, is `https`, has no user, password or port, its
  host is exactly `apps.apple.com`, `play.google.com` or `github.com` (any case), on github.com its path starts with
  `/JacobusOettle/`, and no path segment is `.` or `..` (also percent-encoded, which could leave `/JacobusOettle/`
  once resolved). Anything else is ignored.

The owner flips it with the **Switch test builds** workflow in the Actions tab, or by editing `test-builds.json` on the
`app-status` branch (README, "Test builds can be switched off").

## 3. The rule (shared core)

`com.jacobusoettle.satmap.core.TestBuildSwitch` (`core/.../TestBuildSwitch.kt`), the same names and behaviour as the
Swift core, holds the whole rule with no app or platform in it:

- `Status(on, title?, message?, appStoreURL?, playStoreURL?, websiteURL?)`: a checked file (null texts and links: not
  given).
- `parse(bytes)`, `clean`, `allowedLink`: section 2.
- `savedText(status)` / `saved(text)`: the kept state is the text of a status file (sorted keys, nulls left out), read
  back with `parse`; unreadable or missing is null (never fetched).
- `known(saved, fetched)`: a fetch that gives information replaces the kept state; otherwise it stays.
- `lock(channel, isDebugBuild, status, platform)` → `Lock(title, message, buttons)?`: only a build that `checks`, with
  "off" known, locks; never fetched counts as on. The title and message are the file's or `defaultTitle` /
  `defaultMessage`; the buttons are the platform's store (`Platform.android`: "Get it on Google Play" for
  `playStoreURL`; `Platform.iPhone`: "Get it on the App Store" for `appStoreURL`), then "Website" (`websiteURL`) on
  both.
- `sample`: the walkthroughs' locked state (section 6).

Both cores' tests read the shared fixture `Tests/SatMapCoreTests/Fixtures/testbuild/cases.json` (files: valid on/off,
missing and wrong-typed fields, long texts and the code-point count, plain text, markup, disallowed and http links,
bad schemas and values, not JSON; links: the allowlist, including `..` and `%2e%2e`; decisions: saved + fetched →
known and each platform's lock, store and debug builds; channels; request URLs). The Kotlin tests add the strict JSON
cases kotlinx would otherwise take.

## 4. The gate (app)

`TestBuildGate` (`app/.../TestBuildGate.kt`), owned by `AppModel` (like groups, it registers no `AppLifecycle`
observer of its own; `AppModel` drives it):

- `start()` (in `AppModel`'s init, before the recorder starts): reads the kept state (SharedPreferences,
  `testBuildStatus`) and shows the lock it implies at once, **before** the network answers, so a build that has seen
  *off* starts locked even offline. Never fetched means *on*. A later *on* unlocks.
- Checks at launch, on `willEnterForeground`, and every 5 minutes while in the foreground (`TestBuildSwitch
  .checkInterval`). **Nothing in the background.** One check at a time; a launch that is also a foregrounding fetches
  once.
- A store or debug build returns from all of these at once: no request, nothing kept, never locked.
- Publishes `lock: StateFlow<TestBuildSwitch.Lock?>`.

## 5. When off

`AppModel.applyLock` acts on the lock, keeping every user file (offline maps, the track, KML, groups):

- **The lock screen** (`TestBuildLockScreen`, as the iPhone's `TestBuildLockView`: black, a yellow lock, the title, the
  message, the buttons at the bottom). `MapScreen` composes it **alone**: no map overlay, sheet, dialog, menu, alert
  or share host is composed, so no window can show over it and nothing behind it can be focused or pressed with a
  keyboard or TalkBack; the map view is `GONE` (its surface released, drawing and loading nothing). What was open
  over the map is kept, only not composed, and is there again once it is switched on, as on the iPhone (which keeps
  it behind its lock): a sheet, an area drawn but not saved yet (`draftArea`, `pendingArea`), a pin's or an invite's
  sheet, the "Clear track" question. A long message or a large font scrolls, so the buttons stay reachable. Back
  leaves Spoor, as on the bare map.
- **Links and files are ignored** (`MainActivity.handleIncoming` returns at once, as the iPhone's `onOpenURL`): an
  invite link does nothing (`GroupsModel.openInvite` is guarded too) and a shared or opened KML/KMZ/GPX is not
  imported. The intent counts as handled, so nothing waits to pop up after the unlock. (A backup's share that was
  already open when it locked is kept like the rest: "Save to a folder" chosen in its chooser, which
  `SaveToFolderActivity` notes itself, asks for the folder once it is switched on again.)
- **Downloads pause** (`OfflineManager.setLockedOut`): the running one is re-queued so it continues on unlock, nothing
  new starts, the files saved are kept, a user-paused area stays paused. On unlock `releaseHold` runs, so an area
  paused for an export whose share sheet closed meanwhile goes on too.
- **Recording stops** (`LocationRecorder.stopForLock`): GPS, motion, the compass and the foreground location service
  with its "Recording your track" notification, whatever the lifecycle says; no lifecycle callback or permission
  answer starts them again, and `PermissionFlow` asks for nothing while locked (as the iPhone asks for no permission).
- **Groups pause** (`GroupsModel.setLockedOut`): ticks, uploads, the socket and the upload alarm stop, and the network
  coming back sends nothing; the memberships and queued data stay.
- **Android Auto** shows a `MessageTemplate` with the lock's title and "Get Spoor from Google Play on your phone to
  keep using it." instead of the map (`CarMapScreen`), as CarPlay's `CarPlayLockView`.

Nothing is ever deleted. When the switch is turned on again, everything resumes as before: a paused download continues,
recording and the compass restart (on screen), the permissions skipped are asked, groups wake and send what waited.

## 6. Tests and walkthrough

- Core: `TestBuildSwitchTests` (the shared fixture, and the strict JSON cases).
- App: `TestBuildGateTest` (the kept *off* locks before the fetch and offline; a later *on* unlocks; an unreadable file
  keeps the state; the addresses in order with `?t=`; a store build and a debug build never fetch, keep or lock; one
  fetch for a launch that is also a foregrounding; the hooks), `MapScreenTestBuildTest` (the lock alone, nothing
  behind it; an open sheet, a drawn area and the "Clear track" question there again after; links and files opened
  while locked ignored), `LocationRecorderTest` (the service stopped, nothing restarts behind the
  lock, all back after), `GroupsModelTest` (nothing sent when the network comes back while locked), `BackupTest`
  (downloads held and resumed, an export's paused area resumed), `CarMapScreenTest` (the car's message),
  `ScreenRendersTestBuildTest` (renders 41, and the longest texts at twice the font size scroll to the buttons).
- The walkthrough never checks the live switch: `TestBuildGate.disableForTests()` is called in the app's own process
  (never an intent extra, which a release build would read; the earlier security review found exactly that risk,
  D24). Screenshot **41-test-build-off** forces `TestBuildSwitch.sample`'s lock through `forceLockedForTests`, as the
  iPhone forces it with its launch argument, checks both buttons, that the map's buttons are gone and that UiAutomator
  finds the lock by its resource id `testBuildLock` (what the CI launch check looks for, section 8), then unlocks
  (whatever happened, as `InviteIntentTest` follows in the same process) and checks that the map comes back.

## 7. Privacy

Nothing is collected or sent beyond the plain cache-busted GET with a version User-Agent (section 2), so the iPhone's
purpose and privacy files (`SatMap/PrivacyInfo.xcprivacy`, Info.plist purpose strings) need no change.

## 8. Known effect on CI

`android.yml`'s "Check that the app opens" starts the release APK, a test build reading the live switch, on an
emulator and expects the map in the middle of the screen. While test builds are switched off, that build shows the lock
instead, so the check first reads the accessibility tree of every window on screen (`adb shell uiautomator dump
--windows`, as a system dialog can be the active window; `MapScreen` sets `testTagsAsResourceId`): when it finds
`resource-id="testBuildLock"`, the lock is counted as a good launch and the
map-pixel check is skipped (the crash, ANR and resumed-activity checks still run). So pushes stay green and the
`android-test-build` release is still updated while the switch is off; that build shows the lock too, until the switch
is on again.

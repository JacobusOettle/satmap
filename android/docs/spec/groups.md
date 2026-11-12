# Spoor Android spec: groups

Covers the Android app side of groups: `docs/groups/SPEC.md` §5.6, §8, §9, §10, §11.5, §11.6 and §12.3 ("groups
§9.3" below means that section of the groups spec). The logic is in the Kotlin core (`android/core`, `GroupHub` and
its parts, ported test for test from the Swift core); the app adds what needs the platform. The iPhone app's
counterparts are in `SatMap/Groups/` and `SatMap/UI/Groups/`.

Kotlin package root: `com.jacobusoettle.satmap`.

| Groups spec / iPhone | Kotlin (android/app/src/main/kotlin/com/jacobusoettle/satmap/…) |
| --- | --- |
| `GroupsModel` (`ObservableObject`, §8.1) | `groups/GroupsModel.kt`: `GroupsModel` (StateFlows over the hub), `GroupsRoute`, `AfterPro`, `GroupsNavigation`, `JoinRequest`, `ShownGroup` |
| `GroupHTTP.swift`, `GroupSocket.swift` | `groups/GroupHttp.kt`: `GroupHttp` (core `GroupTransport` and `GroupSocketFactory` on OkHttp) |
| `KeychainSecrets.swift` | `groups/KeystoreSecrets.kt`: `KeystoreSecrets` (core `GroupSecretStore`) |
| `ProStore.swift` (StoreKit 2) | `groups/ProStore.kt`: `ProStore`, `BillingGateway`, `PlayBillingGateway` (Play Billing 9.1.0), `FakeBillingGateway` |
| `QRCode.swift`, `QRScanner.swift` | `groups/QRCode.kt` (ZXing core 3.5.4), `groups/QRScanner.kt` (`QRScannerView`, CameraX 1.6.2) |
| `UI/Groups/*.swift` (S1–S17) | `ui/groups/*.kt`: `GroupsSheet.kt` (the three sheets, `GroupsNav`, titles, alerts), one file per screen group (below), `GroupTexts.kt`, `GroupComponents.kt`, `GroupIcons.kt` |
| `Map/GroupLayer.swift` | `map/GroupLayers.kt`: `GroupLayers` |
| `GroupConfig` (`App/Config.swift`) | `app/Config.kt`: `GroupConfig` |
| `SatMapApp.onOpenURL` | `MainActivity.handleIncoming` / `MainActivity.invite` |

---

## 1. Model and lifecycle

- **Made by `AppModel`** (`model.groups`), after the recorder, the KML library and the offline manager, because
  recording runs with no screen. It registers no lifecycle observer of its own: `AppModel.didBecomeActive` and
  `didEnterBackground` call `groups.didBecomeActive()` / `didEnterBackground()` (the observers stay recorder, offline
  manager, model). `AppModel.appendTrack` calls `groups.trackPoint`, `locationUpdated` calls `groups.fix` (the
  `GpsFix` with its `course` and `speed`), `onHeading` calls `groups.headingUpdated`; Connectivity's online edge calls
  `connectivityChanged(true)`; the recorder's authorisation and `reducedAccuracy` give `setLocationAccess` (§8.5).
- **Threads.** Every hub call is on the main thread. The model's scope (and the hub's) is
  `SupervisorJob() + Dispatchers.Main`, not `Main.immediate`: work the hub starts runs on a later turn, never in the
  middle of a hub call (DESIGN.md §2.4 rule 2). The transport suspends off the main thread inside OkHttp.
- **Start.** `install` makes the hub and the Pro store, then `hub.start()` in the scope; `ready()` waits for it, and
  every action that needs the memberships calls it first.
- **Ticks** (§8.6): in the foreground a `Handler` on the main looper calls `hub.tick(foreground = true)` every 5 s,
  while `nextTickDelay(true)` says there is something (a group or a membership to restore); the hub's `Groups` event
  starts it again. A Handler, not a coroutine `delay`: the Robolectric tests' test dispatchers never spin on it. In the
  background a `WakeTimer` ("groups", `ELAPSED_REALTIME_WAKEUP`) fires after `nextTickDelay(false)` (15 or 60 s, none
  when nothing shares or waits), and each fix rearms it if it isn't set, while `LocationService` keeps the process.
- **Events** (`hub.onChange`): `Groups` → `groups` (S2's rows), `shown`, `sharingNames`; `Group(id)` → `revision`
  (screens read the hub again); `Map` → `mapRevision` and the pending camera fit; `Alert` → `alerts` (a queue; the
  first shows over the map, `GroupAlerts`), and a map sheet about that group closes.
- **Shown group** (`ShownGroup`): id, name, paused, reason and whether you own it, for the Groups button (yellow), the
  paused banner and the drop-pin capsule.
- **Camera fit** (§9.5): `setShown(id, true)` (S5) and `join` (S8) ask for a fit; it happens at the first `Map` event
  within 20 s whose content has a live member (the members' positions and yours) through `AppModel.map.fit(points)`
  (`MapHost.fit`, a default no-op for other hosts). `MapController.fit` grows the bounds to at least 300 m each way
  (`GroupLayers.fitBounds`), pads them by the safe area and 60 dp, centres them in the safe area and stops following.
- **Navigation.** `open(routes)` sets `navigation` and shows `ActiveSheet.groups`; the sheet replaces its stack with
  them and calls `navigationTaken`. `openInvite(parsed)` (links, scans, pastes) opens S8 (`pendingInvite`), or, for a
  group you are in, S5 alone (`[Detail]`, with Done) and the alert "You're already in {group}." with OK (`notice`,
  shown by `GroupsSheet`; §5.6). `openItem(route)` opens the map's sheet (`groupItem`).
- **Terms and names** (§8.13, §8.3 preferences): `groupsTermsAccepted` (`acceptTerms`, `needsTerms`, kept equal to
  `hub.acceptedTermsVersion`), `groupsDisplayName` ("the last display name typed, to prefill": `rememberDisplayName`,
  from S4's field as it is typed, after a join and after S15's Save, when the name is a valid one).
- **Stop everywhere** (§11.6): `stopSharingEverywhere()` switches sharing off in every group (the notification's Stop).

## 2. Launch arguments and demo mode

All three are read in debug builds only (`BuildConfig.DEBUG`; the walkthrough and `InviteIntentTest` run the debug
build), and `MainActivity.launchArguments` gives no extras at all for an intent with `CATEGORY_BROWSABLE`: a web page's
`intent://` link can carry any `S.` extra, and any app can start the exported activity with extras, so neither may move
member passes to another server or swap in the demo hub (iOS's launch arguments never come from a URL).

- `groupsDemo YES` (`--es groupsDemo YES`, through `Defaults.setLaunchArguments` in `MainActivity.onCreate`, then
  `AppModel.applyLaunchArguments` → `groups.applyLaunchArguments`): `useDemo()` swaps in `GroupDemo.makeHub` (the core's
  `FakeGroupServer` with the §8.15 seed, memberships in memory, no sockets) in a store of its own
  (`cacheDir/groups-demo.sqlite`, made afresh), and a `FakeBillingGateway` (not Pro at launch; any plan buys yearly Pro
  at once at R79.99 / R399.99 / R1,199.99; the proof is `GroupDemo.proProof`). The hub made at launch is dropped
  (its events go nowhere). The model was made in `Application.onCreate`, before the activity reads its extras: hence
  the switch. The new hub gets what the model was told before it (location access, the last fix), so sharing sends
  your position at once, as on iPhone.
- `groupsBaseURL <url>`: `GroupConfig.baseURL` reads it at each request (and each socket), never saved; invite links
  always use `GroupConfig.domain`. A release build always uses `https://` + domain.
- `fakePro YES`, outside demo mode (§12.6): a `FakeBillingGateway.localTest()` (Pro at once,
  product "pro", token "local-test-" and 20 base64url characters).

## 3. Transport and socket (`GroupHttp`)

- OkHttp, 20 s connect, 30 s read and write, User-Agent `Spoor/1.0 (Android)`, no cache. One request per hub call:
  `baseURL + path` (the core's paths start with `/v1`), `Authorization: Bearer {pass}` when there is one, JSON body
  (an empty one for a body-less POST). The status and the bytes are handed back as they are (`GroupResponse`); no
  header reaches the core.
- Failures: `GroupTransportError.Offline` when `Connectivity.isOnlineNow` says no network, or the failure is
  `UnknownHostException`, `NoRouteToHostException` or "network unreachable"; any other (a timeout, a refused
  connection, a broken answer) is `Failed`.
- The socket: `wss://` (`ws://` for an `http://` base) + host + `/v1/groups/{id}/ws` with the Bearer header (§6.6).
  Events are posted to the main thread and reported (`SocketEvents` → `hub.socketDidOpen/Receive/Close`) only for the
  socket opened last and not yet closed by the hub; a close is reported once (§8.7). A refused upgrade
  (`onFailure` with a response) is a close: `401` and `404 group_not_found` → 4401, `410 removed` → 4403,
  `410 group_deleted` → 4404, anything else or no answer → 1006 (`refusedUpgradeCode`). The hub sends its own `ping`
  text every 30 s; OkHttp's protocol pings stay off.

## 4. Secrets (`KeystoreSecrets`)

- Android Keystore key `satmap.groups.v1` (AES-256, GCM, no padding, randomised, no user authentication), made on first
  use. The JSON array of secrets (`GroupSecret.encodeAll`) is sealed into `noBackupFilesDir/groups/secrets.bin` as
  `0x01 ‖ IV (12) ‖ ciphertext ‖ tag`, written whole with `AtomicFile` (§8.3).
- No file: no memberships. A file that can't be opened (a Keystore error, a damaged file, another key) throws: the hub
  deletes nothing and reads again at the next `becameActive`. `delete` deletes nothing it can't read.
- A file that will never open (`AEADBadTagException`: the Keystore key was lost and made anew while the app's data
  stayed; or not a secrets file: another version, too short, or JSON that isn't a list of secrets) would block every
  create and join: `save` copies it to `secrets.bin.unreadable` and writes the list from empty, so the hub's next read
  keeps the new membership and drops the old ones (they can't be used without their passes). A Keystore error, which
  may pass, is never taken for that.
- The local store is `noBackupFilesDir/groups/groups.sqlite` on `AndroidSqlDriver` (a fresh copy in the cache if the
  file can't be opened; the memberships are restored from the server). Both rule files exclude `root/no_backup/`
  (`backup_rules.xml`, and `data_extraction_rules.xml` under cloud backup and device transfer).
- Robolectric has no AndroidKeyStore: the class takes the key as a function, and the tests give a software key.

## 5. Pro (`ProStore`)

- Behind `BillingGateway` (§10.4). `PlayBillingGateway`: the client is made on first use with
  `enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())` and
  `enableAutoServiceReconnection()`, and connects when first needed (15 s at most: a phone without Play may not
  answer). Prices: `queryProductDetails` for `pro` (SUBS; the base plans `monthly` and `yearly`, the offer without an
  offer id) and `pro_lifetime` (INAPP; `oneTimePurchaseOfferDetailsList`, else `oneTimePurchaseOfferDetails`):
  `formattedPrice` and `priceAmountMicros`. Purchases: `queryPurchasesAsync` for SUBS and INAPP. A purchase sheet
  (`launchBillingFlow` with the product and offer token) waits for `onPurchasesUpdated`; purchases announced outside a
  sheet (a pending purchase approved) are taken up at once on the model's scope (`takeUpdates`): Play's whole list is
  read again, or, when Play can't be asked, the announced purchases replace those with the same token (an announcement
  never takes away a purchase it doesn't name).
- `refresh()` (after start, on every `becameActive`, and Restore): acknowledges purchased, unacknowledged Pro
  purchases, then Pro is the best purchased, not suspended one, Lifetime first, else the newest subscription. Pro writes
  `proCache` (`{"productId", "expiresAt": null, "checkedAt"}`, §10.6) and calls the hub's
  `updatePro(proof, productId, expiresAt = null)` (`ProProof.Google(productId, purchaseToken)`); none clears the cache.
  The cache makes `isPro` true at once at launch.
- `buy`: `Done` (the paywall closes and the flow goes on), the pending text, nothing (cancelled), or the store text.
  `restore`: `Done`, pending, "No Spoor Pro purchase was found for this Google account." or the store text.
- "Save {n}%": `savingPercent(yearlyMicros, monthlyMicros)`, floored, hidden unless above 0.
- "The phone has Pro" for an action that sends a proof (S4's create, S5's Renew and Accept) is `hasConfirmedPro`: the
  store has confirmed a purchase since launch (`proof()` isn't null). The cached `isPro` (S2's button, the paywall's
  first screen) can't give a proof offline, so it opens the paywall instead, whose "Restore purchases" asks Play again;
  `renewPro` and `acceptOwnership` throw `ProNotConfirmed` ("…Tap Restore purchases, then try again.") rather than
  doing nothing.

## 6. Invite links in (`MainActivity`)

- Manifest (§5.6): on `MainActivity`, `<intent-filter android:autoVerify="true">` VIEW, DEFAULT, BROWSABLE,
  `https://${groupsDomain}` with `pathPrefix="/j/"`; and VIEW, DEFAULT, BROWSABLE, `satmap://join`. The domain is
  `groupsDomain` in `gradle.properties`, as a manifest placeholder and `BuildConfig.GROUPS_DOMAIN`.
- `handleIncoming` (from `onCreate` with no saved state, and `onNewIntent`) ignores replays from Recents, then tries
  `invite(intent)` before files: `InviteLink.parse(dataString)`; when the data is `satmap://join/<token>` without a
  fragment and the intent has the extra `groupKey` (the landing page's `intent://` link, §5.4), it parses
  `"satmap://join/" + token + "#" + groupKey`. Anything but `NotInvite` goes to `groups.openInvite` and the intent's
  action is cleared. Opening a link never joins.

## 7. Sheets and screens (§9.2, §9.3)

- Three `SheetHost`s in `MapScreen`: `ActiveSheet.groups` (`GroupsSheet`, its root S1 until the terms are accepted,
  else S2), `groups.groupItem` (`GroupItemSheet`: S11, S12 or S10 at a tapped spot, with Report and your name pushed on
  it) and `groups.pendingInvite` (`JoinSheet`: S8, with S1 first when the terms aren't accepted).
- `GroupsRoute` is the spec's list plus `DropPin(gid, lat, lon)` (S10, from the map's capsule and from S5) and
  `Join(link)` (S8; pushed from S7 in the groups sheet, the root of the join sheet). Routes are plain data; what a
  screen remembers (typed names) is `rememberSaveable` under its entry. `GroupsNav` pushes, pops (closing at the root),
  replaces the stack (a new `RouteStack`, so the root can change) and replaces the top screen.
- Flows: Create → `[List, Consent(gid, afterCreate)]` → `[List, Detail(gid)]`; Join → `open([List, Consent])` (the join
  sheet closes); switching sharing on without consent pushes `Consent(gid, false)`, which pops back; the paywall is
  pushed for Create, Renew and Accept and, on success, is replaced by S4 or pops after E5 / E17. S5 opened from
  outside the list (the paused banner, an invite to a group you are in, S8's "Open group") is `[Detail]` alone, so its
  leading button is Done (`leading()`: Back only with a screen below).
- Files: `TermsScreen.kt` (S1), `GroupListScreen.kt` (S2), `PaywallScreen.kt` (S3), `CreateGroupScreen.kt` (S4),
  `GroupDetailScreen.kt` (S5, with `SwitchRow`, the Trails menu and its alerts), `InviteScreen.kt` (S6),
  `JoinScreens.kt` (S7, S8, S17), `ConsentScreen.kt` (S9), `PinScreens.kt` (S10, S11, `BlockAlert`),
  `MemberScreens.kt` (S12, S14, S15, S16), `ReportScreen.kt` (S13).
- House style (`Form.kt`): sections with upper-case headers and plain footers, link rows with chevrons, button rows in
  the accent colour (red when destructive, white when disabled; while working, a spinner and a text at the start when
  the spec has one, as S4's "Creating…", otherwise the row dimmed with a spinner at the end, as iOS's label, spacer
  and `ProgressView`; a form's main button, `PrimaryButtonRow`, keeps its title with the spinner at the end: S3's
  Subscribe, S8's "Join group"), iOS switches, choice rows with a check, alerts driven by an optional message
  (`MessageAlert`, `AlertBox` with "Cancel" or "Keep" as the spec says), a toast capsule for "Link copied" and
  "Support code copied" only.
- S5 "Invite someone" makes the invite (E6) on S5, the row dimmed with its spinner, then pushes
  `Invite(gid, made)`; a failure is the alert "Couldn't make an invite. Check your connection and try again." on S5.
  S6 only shows an invite it is given (the one just made, or a pending one this phone made).
- S5 "Share my location" with consent recorded checks location as S9 does: allowed, sharing goes on; never asked,
  the permission is asked (fine and coarse) and sharing goes on only if it is given; refused, denied before or
  location off, the alert "Location is off for Spoor. …" (`LOCATION_OFF`), or with location off for the whole
  phone "Location is off. Turn it on in Settings › Location to share." (`LOCATION_SERVICES_OFF`), and sharing stays
  off (§8.13).
- S13: "Thanks for reporting" offers Block only for someone who can still be blocked (`blockable`: a pin whose
  dropper can be, a member who can be and isn't yet); otherwise it has OK only.
- S10: "Drop pin" is enabled once a type is chosen and, for Animal, something is typed; a name that is too long is
  the hub's "Type the animal, up to 40 characters." under the button on the tap.
- S6 Share link goes through `ShareHost` (`SharePresenter.presentText`: a plain-text chooser); Copy link puts a
  `ClipData` labelled "Invite link" with `EXTRA_IS_SENSITIVE` (the string key below API 33) in its description's
  extras (§9.9).
- S9 asks for location (fine and coarse together, as `PermissionFlow`, marking `locationPermissionAsked`) when it was
  never asked; refused (or denied before, or location off), the alert "Location is off for Spoor. Turn it on in
  Settings › Apps › Spoor › Permissions › Location to share." (the Android settings path, like the map's location
  hint; the iPhone says Settings › Privacy › Location Services) and sharing stays off. With location off for the
  whole phone (`servicesOff`) the alert is "Location is off. Turn it on in Settings › Location to share."
  (`LOCATION_SERVICES_OFF`, like the map's services-off hint).
- S17 asks for `CAMERA` when it opens; without it, "Camera access is off for Spoor. Turn it on in Settings to scan a
  code." with "Open Settings" (the app's details page). `QRScannerView`: CameraX preview and an `ImageAnalysis`
  (latest frame only) decoding the Y plane with ZXing (`PlanarYUVLuminanceSource`); the first invite found replaces
  the scanner with S8. A code that is an incomplete invite link or no invite link shows S7's text for it
  (`GroupTexts.linkProblem`) in orange above "Point the camera at the invite QR code.", and scanning goes on (as iOS;
  the spec is silent).
- Texts built from data are in `GroupTexts` (tested); fixed texts are written where they show. Dates "9 Nov" (with the
  year when it isn't this year), times in the phone's 12- or 24-hour form (`DateFormat.is24HourFormat`).

## 8. Map screen (§9.1)

- **Groups button**: a `MapButton` (`SatIcons.Person3`, drawn at 38 dp to be as big as iOS's `person.3.fill`,
  DESIGN.md D30; "Groups", testTag `groupsButton`), active while a group is shown. The first button of `Controls`:
  under the compass slot, 12 dp over the terrain button, as the others. Shown whenever the column is (not while
  drawing). Where the column doesn't fit the height `MapOverlay`'s `Column` leaves it, `Controls` lays the buttons
  out in a grid instead (`mapButtonGrid`, UI spec §2.4.5, groups §9.1).
- **Paused banner**: `Hint` (now with `closeLabel` and `modifier`) under the location hints, tag `groupPausedHint`,
  texts from `GroupTexts.pausedBanner`; a tap opens S5 alone (`[Detail]`, Done), its close button ("Hide" to
  TalkBack, as VoiceOver reads it) hides it for that group until the next launch.
- **Drop pin**: over a tapped spot, beside the name capsule, while the shown group is active (`dropPin`): a capsule
  with a 1 dp white outline at 30 % (iOS's `Capsule().stroke`), so it reads as a button; opens S10 at the spot in the
  map sheet. Dropping clears the tapped spot.
- **Alerts**: `GroupAlerts` over everything.

## 9. Map drawing (`GroupLayers`, §9.5)

- Sources and layers (`MapIds`, `MapStyle.sources` and `overlayLayers`; the car map installs them too and they stay
  empty, SD7): `group-trails` (line: `["get","colour"]`, 85 % opaque, `zoomScaled(3)` or `zoomScaled(2)` when the
  core says 2, round) first in the overlay stack, under `track`; `group-pins` (icon from `img`, allow-overlap, takes
  room), `group-pin-labels` (on `group-pins`, filter `has label`, anchored at its top 17 dp below the pin, gives way),
  `group-member-arrows` (on `group-members`, filter `has heading`, rotated with the map) and `group-members` (icon from
  `img`, allow-overlap) after `selected` and before `measure-markers`.
- KML files shown later insert their layers below `track` and `measure-markers` (BlockLayers, once each file is drawn
  off the main thread): `restack()`, run from `BlockLayers.onChanged` as each file's layers arrive or go, puts the
  group's layers back just under those anchors, so the order stays KML fills and lines,
  group trails, your track …, KML points, group pins, labels, arrows, dots, measuring points, you.
- Images (app-drawn, the map's density): a member's dot (`group-member/{colour}/{initials}/{live|stale}`: 26 dp, a 2 dp
  white ring, white bold 11 sp initials; stale: grey fill, ring in their colour), the heading's triangle
  (`group-member-arrow`, 8 × 6 dp just outside the ring, the image centred on the dot), a pin
  (`group-pin/{type|unknown}`: 30 dp disc in the type's colour, 2 dp white ring, the white glyph drawn from the icon's
  vector paths in a 20 dp box, so about 16 dp shows, as SF's 16 pt symbol; DESIGN.md D30) and an animal pin's label (`group-pin-label/{text}`: white bold 12 sp on black 65 %, removed
  when no longer shown).
- Binding (`MapController.bind`): `groups.mapRevision` and a minute's timer (the "… ago" labels, expiring pins) call
  `groupsChanged`: `hub.mapContent(now)` read on the main thread, features and images made on `Dispatchers.Default`
  (`prepare`), handed to MapLibre on the main thread (`apply`); an overtaken request isn't shown.
- Taps (`MapController.tapped`), before the existing chain and only while not measuring (a tap then places a point at
  the crosshair): a member dot within 22 dp (the nearest), then a pin within 22 dp → `groups.openItem(Member | Pin)`.
- Accessibility: each dot and pin is a virtual view of `MapContainerView` with its core `a11yLabel`; activating it
  taps it. The walkthrough's accessor `MapController.groupItemScreenPoints()` gives each one's kind, id, label and
  screen point.

## 10. Location notification (§11.6)

`LocationService.notification(context, sharing)`: the title "Spoor" and content text "Recording your track" stay;
while groups share (`groups.sharingNames`: sharing on and not paused) a `BigTextStyle` adds "Sharing your location
with {group}." or "… with {n} groups.". The service collects `sharingNames` while it runs and posts the notification
again when it changes. Stop also calls `groups.stopSharingEverywhere()` (an Android-only difference, §9.9).

## 11. Tests

| Test | What |
| --- | --- |
| `groups/GroupsModelTest` | Demo mode: rows, the shown group, the camera fit, the notification's groups and Stop, invites (in already: S5 alone and the alert; another; incomplete), joining, the name kept, whom a report offers to block, terms, the demo store, hidden banners, alerts, location access kept for a later hub |
| `groups/GroupPartsTest` | `GroupTexts` (with a pasted or scanned link's problem), `GroupLayers` features, images and fit, QR codes round trip, `KeystoreSecrets`, `ProStore` decisions, `GroupConfig.baseURL` |
| `groups/GroupHttpTest` | MockWebServer: the request and its Bearer pass, status and body as they are, offline and failed, the socket's URL and header, refused upgrades, only the latest socket |
| `ui/ScreenRendersGroupsTest` | Renders 28–40 at `IPHONE_SCREEN` (36 and 40: overlays only) |
| `ui/GroupScreensTest` | S4's name kept and prefilled in S8; S5's "Invite someone" making the invite, then S6, and its failure alert; S5's sharing switch with location off, then allowed; S10's animal checked on the tap |
| `groups/GroupIconsTest` | The map pins' glyphs about 16 dp as drawn; the solid drop, the house's door and chimney, person.3's proportions |
| `ui/MapScreenGroupsTest` | The Groups button's place (the top of the column; the grid in a small landscape window, mirrored right to left), its sheet, the paused banner (S5 alone with Done; "Hide"), the drop-pin capsule |
| `MainActivityTest` | The https and satmap filters resolve to MainActivity; a link opens S8 once (not from Recents) and never joins; the `intent://` form with `groupKey` |
| `location/LocationServiceTest` | The big-text lines; Stop ends sharing everywhere |
| `map/MapStyleTest` | The overlay stack with the group's layers |
| androidTest `ScreenshotTest` | §9.7 steps 28–40 in demo mode, after the existing steps |
| androidTest `InviteIntentTest` | §5.6: the App Link and the `intent://` form open the join sheet for "Braai club" |

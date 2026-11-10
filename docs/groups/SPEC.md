# SatMap Groups: implementation spec (v1)

Status: ready for implementation, 2 November 2026, revised after the security and implementability reviews (the
points not taken are in "Review notes" at the end). This document is the single source of truth for groups. Five
parts are built from it in parallel (§13): the server (`server/`), the Swift core (`SatMap/Core` and
`Tests/SatMapCoreTests`), the Kotlin core (`android/core`), the iPhone app UI (`SatMap/`, `SatMapUITests/`) and the
Android app UI (`android/app`). The parts must work together without talking to each other, so where this spec
gives a name, a byte layout, a JSON key, a text or a number, use exactly that.

How to read it:

- "Must" is a requirement. "Should" is the default; depart from it only with a reason written in the code.
- Numbered rules (R1, R2 …) and spec decisions (SD1, SD2 …) can be cited in code comments as "groups R7".
- **[Unconfirmed]** marks a fact the scouts could not confirm. The spec then picks a safe default and says how to
  check it.
- "Both apps" means the iPhone app and the Android app. "Core" means both cores. Swift names are given; the Kotlin
  core uses the same names (PORTING.md rules: `Data` → `ByteArray`, `Int64` → `Long`, `Int32` → `Int`,
  `throws` → a sealed `Failure` exception, `async` → `suspend`).
- The machine-readable test vectors are in `Tests/SatMapCoreTests/Fixtures/groups/vectors.json`, made by
  `docs/groups/vectors.py` (§3.6, §4.8, plus the shared tables for links, text, the word filter, times, initials
  and live rounding). Both are owned by this spec: implementers read them and never edit them. Where the prose and
  `vectors.json` seem to disagree, the vectors win and the prose is fixed.

---

## 1. Scope and rules

### 1.1 What groups are

A group is a small set of people (a drone team, friends on a Kruger trip) who see each other on the map: each
member's live position, the trail each member leaves, and pins they drop (animal sightings, shelter, water, danger,
potholes, police). Everything a group shares is encrypted on the phones; the server stores and forwards ciphertext.

### 1.2 Rules (owner decisions; do not re-open)

| # | Rule |
| --- | --- |
| R1 | Users are drone teams and friends. There are no accounts. A person's identity in a group is a per-group member pass kept on their phone (§2.2). |
| R2 | The only way to join is a single-use invite link or its QR code. There is no searchable or typeable group code. |
| R3 | An invite works once and expires 7 days after it was made if unused. The owner and admins can list pending invites and cancel them. |
| R4 | There is no group key rotation. A removed member loses server access at once, and that is enough. |
| R5 | Roles: owner, admin, member, with exactly the permissions in §1.3. |
| R6 | Only the group's owner needs Pro (a paid in-app purchase). Members join free. Group size is not capped. |
| R7 | If the owner's Pro ends, the group stops at once: no live sharing, no new pins, and members see that it is paused. It comes back when the owner renews or when ownership passes to a member who has Pro. "Ends" means the store says the entitlement is no longer active (after the store's own billing grace period, if any). Refunds and revocations count. |
| R8 | Stored group data is deleted 30 days after the group stops, unless it comes back first (POPIA storage limitation). |
| R9 | Members see live positions and each member's trail. You see other members' trails only from the moment you joined. Trails are otherwise kept forever. "Clear my trail" deletes your trail in that group from the server. Leaving or being removed deletes your trail and your pins in that group and hides the group's data from you. |
| R10 | Pins: animal sighting (the user types the animal: any animal, including rhino), shelter, water, danger, pothole, police, each with an optional short note. Animal sightings disappear after 12 hours; other pins stay until deleted. No photos. |
| R11 | Every pin and member can be reported and blocked (store user-generated-content rules). Names and notes pass an on-device word filter. Terms are accepted before first use. A contact address is shown. |
| R12 | End-to-end encryption: a 256-bit group key made on the creator's phone travels only inside invite links and QR codes (in the URL fragment) and never reaches the server. Positions, trails, pins, display names and the group name are encrypted with AES-256-GCM, in chunks, not point by point. The server sees only ids, times, roles and sizes. A report sends the reporter's decrypted copy to the developer. |
| R13 | Backend: Cloudflare Workers and one Durable Object per group (SQLite storage, WebSocket hibernation for live updates while the map is open, HTTP POST batches otherwise). Trail chunks cover 10 minutes, are delta-encoded then encrypted, and are uploaded every 60 s, re-sending the open chunk. History since the last sync is pulled when the app opens. Old chunks are archived to R2. Senders slow down when nobody is watching. Points queue on the phone without signal and upload later. |
| R14 | Pro: StoreKit 2 on iPhone, Play Billing (current major, 9.1.0) on Android. The Worker verifies the creator's purchase when a group is created and keeps the entitlement's expiry so it can stop the group on time (store API checks at expiry and store server notifications). Restore Purchases button on iPhone. Family Sharing off. Pro is cached on the phone for offline use. Pro does not carry between iPhone and Android in v1. |
| R15 | Consent: an in-app consent screen before first sharing says your location is shared with group members. Updated iOS purpose strings. Google "prominent disclosure". Someone who declines location can still view the group and drop pins. |
| R16 | The server lives in `server/` (TypeScript, wrangler), with tests that run offline in CI. Nothing is deployed from this repo; secrets and the domain are configuration. The invite domain is one constant per app and one variable on the server. |
| R17 | The iPhone app is compiled, and its simulator walkthrough run, only in CI on macOS. Android builds and Robolectric tests run locally; the emulator walkthrough runs in CI. |
| R18 | Both apps look and work the same. Allowed differences are listed in §9.9. |
| R19 | South Africa first: English texts, prices in rand, times in the phone's own time zone and format. |

### 1.3 Permissions

| Action | Owner | Admin | Member |
| --- | --- | --- | --- |
| Share own location, drop pins, delete own pins, clear own trail | yes | yes | yes |
| Change own display name and colour | yes | yes | yes |
| Report any member or pin; block any member (blocking is on the phone only) | yes | yes | yes |
| Report the group name | no (renames it instead) | yes | yes |
| Leave the group | no: must hand over or delete (SD12) | yes | yes |
| Create invites; list and cancel any pending invite | yes | yes | no |
| Remove a member whose role is member | yes | yes | no |
| Remove an admin | yes | no | no |
| Make a member an admin, or an admin a member | yes | no | no |
| Rename the group | yes | yes | no |
| Delete any pin | yes | yes | own only |
| Offer ownership to a member | yes | no | no |
| Accept an ownership offer | the member it was offered to, with Pro | | |
| Delete the group | yes | no | no |
| Send a new Pro proof | yes | no | no |

Nobody can remove the owner. The server enforces this table; the apps hide actions a role cannot do. Reporting and
blocking also work while the group is paused.

### 1.4 Spec decisions (made here, where the owner's decisions left a gap)

| # | Decision |
| --- | --- |
| SD1 | The map shows one group at a time (the "shown group", chosen in the group's screen). Sharing is switched on or off per group, so a person can share with several groups at once. |
| SD2 | Pins are seen by every current member, whenever they joined. Only trails are limited by join time (R9). |
| SD3 | The server sees each pin's expiry time, so it can enforce the 12-hour rule; it therefore knows which pins are animal sightings (not which animal). |
| SD4 | Joining needs a `joinProof` derived from the group key (§3.3), so an invite token seen without its fragment (a server log, a link-preview robot) cannot join. |
| SD5 | Phones make the group id, member ids, pin ids and member passes. The server makes invite ids and invite secrets. |
| SD6 | No push notifications in v1. Live updates arrive only while the app is open. |
| SD7 | Groups are not shown on CarPlay or Android Auto in v1. |
| SD8 | If the server cannot confirm a renewal when a subscription reaches its expiry time, it pauses the group ("couldn't be confirmed") and keeps checking; a later confirmation resumes it automatically. |
| SD9 | One Pro purchase can own at most 20 groups that are not deleted (anti-abuse; server variable). |
| SD10 | Reports never include positions (protects rhino and other sighting locations). |
| SD11 | Member colours come from a fixed 12-colour palette (§4.6). Colours can repeat in big groups; initials tell people apart. |
| SD12 | The owner cannot leave. They hand over ownership first, or delete the group. |
| SD13 | Both apps show "Restore purchases" on the paywall (Android re-reads Play's purchases), so the screens match. |
| SD14 | Both apps can scan an invite QR code in the app, and accept a pasted link. The phone's own camera app also works, through the link. |
| SD15 | `satmap://join/<token>#<key>` exists only as the landing page's "Open in SatMap" fallback (on Android the page uses an `intent://` link that names the app's package, §5.4). Apps never share it. |
| SD16 | On the map, other members' trails default to the last 24 hours; "Everything" and "Off" are choices. Full history stays on the server and the phone. |
| SD17 | Walkthroughs use a demo mode with an in-core fake server (§8.15), not a live Worker. |
| SD18 | Invites still work while a group is paused, so a friend with Pro can be invited and take over. |
| SD19 | Coordinates travel as integer micro-degrees (1e-6°, about 11 cm). |
| SD20 | While `ALLOW_TEST_PURCHASES` is true (the default), the server accepts App Store Sandbox purchases (App Review and TestFlight use them) and Google test purchases, and treats each as ending 14 days after its original purchase date (Apple `originalPurchaseDate`; Google subscription `startTime`, one-time `purchaseCompletionTime`), so a tester's sandbox Lifetime is not free Pro for good. The "Xcode" environment is accepted only when a test variable is set. |
| SD21 | The Groups button is the top button of the right-hand button column, directly above the terrain button (under the compass's place). Where the taller column doesn't fit (small phones, landscape, tall panels at the top), the buttons go in columns side by side, with My location still at the bottom right (§9.1). |
| SD22 | For 30 days after removal, a removed member's requests get "removed" (so the app can say so); a deleted group answers "deleted". After that both get the generic answers. |
| SD23 | An invite dies with its maker's admin role: removing an admin, demoting an admin to member, or an admin leaving cancels every pending invite they made (R4: a removed admin's links must not let anyone back in). |
| SD24 | Deleted ids are never reused. A `pinId` or `memberId` that appears in `removals` is refused (§6.5 E10, E23), so a retry can't bring back a pin a moderator deleted. |
| SD25 | Writers pad the JSON records (profiles to a multiple of 32 bytes, pins to 64) so record sizes don't give away name lengths, for example which animal a note-less sighting names (§2.6). |
| SD26 | Trail and live records use a key per member (§3.3), so no key comes near the random-nonce limit, whatever the group size. |
| SD27 | Blocking is one-way and on the phone only (there are no accounts). The blocked person still sees you while you share; the Block alert says so and offers "Block and stop sharing" (§8.11). |
| SD28 | A report carries the reporter's decrypted copy, which the server can't check. The server adds the facts it can check (§7.7), and the developer acts on a report only when it is corroborated. |

### 1.5 Out of scope for v1

Photos, chat, push notifications, key rotation, Pro across platforms, a web viewer, searching for groups, SOS,
showing groups on car screens, decrypting the group name on the invite landing page.

---

## 2. Identifiers and encodings

### 2.1 base64url

All binary values in text (URLs, JSON, headers) are base64url (RFC 4648 §5): alphabet `A–Z a–z 0–9 - _`, no
padding, no line breaks.

- Writers never add `=`.
- Readers reject any `=`, `+`, `/`, whitespace or other character outside the alphabet, and any text whose length
  mod 4 is 1. Readers may accept non-zero unused trailing bits; writers never produce them.
- Swift: check the alphabet, map `-`→`+` and `_`→`/`, pad to a multiple of 4, then `Data(base64Encoded:)`.
- Kotlin: check the alphabet first (`java.util.Base64.getUrlDecoder()` accepts padding), then decode.
- Server: its own `b64url.ts` with the same checks. For **id texts** (`groupId`, `memberId`, `pinId`, `inviteId`,
  in paths and bodies) the server also requires the canonical form: the text must equal the base64url encoding of
  its decoded bytes (unused bits zero), otherwise `400 bad_request`. One id then has exactly one text, so E1,
  member paths and invite routing always reach the same Durable Object (§7.2).
- Vectors: `vectors.json` `mustFail.badBase64url` lists texts every decoder must reject;
  `mustFail.nonCanonicalIds` lists id texts the server must reject (clients may accept them).

### 2.2 Identifiers

| Name | Bytes | Made by | As text | Notes |
| --- | --- | --- | --- | --- |
| `groupId` | 16 random | creator's phone | 22 chars | In URL paths, in every AAD, and the HKDF salt (as raw bytes). |
| `memberId` | 16 random | the member's phone, at create or redeem | 22 chars | Unique within the group and never reused: a clash with a current member, or with a removed or departed one still in `removals`, gets `409 member_exists` and the phone retries with a new id. A person who rejoins gets a new one. |
| `pinId` | 16 random | dropper's phone | 22 chars | Unique within the group and never reused (a deleted pin's id gets `410 pin_deleted`, SD24). |
| `inviteId` | 16 random | server | 22 chars | Public handle to list and cancel an invite. Not a secret and not in the link. |
| `inviteSecret` | 16 random | server | never shown alone | The server keeps only SHA-256(secret). |
| `inviteToken` | 32 = `groupId` ‖ `inviteSecret` | server | 43 chars | The path segment of the invite link. Its first 16 bytes route the request to the group. |
| `memberPass` | 32 random | the member's phone | 43 chars | The Bearer token. The server keeps only SHA-256 of the 32 raw bytes, never the pass. |
| `groupKey` | 32 random | creator's phone | 43 chars | Only in the invite link's fragment and on phones. Never sent to the server. |
| `joinProof` | 32, derived (§3.3) | anyone who holds `groupKey` | 43 chars | Sent at create, preview and redeem. The server keeps only its SHA-256. |
| `reportId` | 16 random | server | 22 chars | |

Random bytes come from the system's cryptographic generator: `SecRandomCopyBytes` (Swift on Apple; `RAND_bytes` on
Linux tests), `java.security.SecureRandom` (Kotlin), `crypto.getRandomValues` (server). An id is valid text only if
it decodes to exactly its byte length (22 chars → 16 bytes, 43 chars → 32 bytes).

### 2.3 Time

- Every time on the wire is Unix time in whole seconds (Int64 / Long / JS number). A trail point's time is the floor
  of its fix timestamp.
- Durations are in seconds. Months (archive keys) are `YYYY-MM` in UTC.
- The server's clock is `Math.floor(Date.now() / 1000)`, overridable in tests (§7.3). Alarms use milliseconds
  inside the server only.
- `seq` and cursors are positive integers per group, not times (§6.3).

### 2.4 Coordinates

Group payloads carry coordinates as Int32 micro-degrees (SD19): latitude in −90 000 000…90 000 000, longitude in
−180 000 000…180 000 000.

- Degrees to micro-degrees: `x = degrees * 1e6` in IEEE-754 double, then round half away from zero:
  `sign(x) * floor(|x| + 0.5)`.
  - Swift: `(degrees * 1e6).rounded()` (the default rule is `.toNearestOrAwayFromZero`), then `Int32(...)`.
  - Kotlin: use the core's Swift `Double.rounded()` port in `Foundation.kt`. Never `Math.round` (rounds −2.5 to −2)
    or `kotlin.math.round` (rounds half to even).
- Micro-degrees to degrees: `Double(e6) / 1e6`.
- Vectors: `vectors.json` `coordinates` (1.5e-06 → 2 and −2.5e-06 → −3 pin the rounding rule).

### 2.5 Text

- All text is UTF-8. Lengths count Unicode scalar values (code points): Swift `unicodeScalars.count`, Kotlin
  `codePointCount(0, length)`, JS `[...s].length`.
- Before saving or sending, a text field is cleaned (`GroupText.clean`):
  1. Normalise to NFC.
  2. Trim leading and trailing code points that are Unicode White_Space, exactly this set: U+0009–U+000D, U+0020,
     U+0085, U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000. Write the set out in code: do
     not use Kotlin `trim()` (it also strips U+001C–U+001F) or `Character.isWhitespace`. U+200B (zero-width space)
     is not in the set.
  3. The result is invalid if it has a control character (general category Cc) or U+2028/U+2029 anywhere; notes
     and report comments may contain U+000A (newline), at most 3 of them. Text holding half a surrogate pair (a
     Kotlin or JS string can, a Swift string can't) is invalid too, and JSON readers refuse such strings.
  4. Then the limits below apply. An empty note or report comment is valid and cleans to `""`. An empty note is
     left out of the pin record (§4.5); an empty report comment is sent as `"comment": ""` (the server reads an
     absent comment the same way, §6.5 E25).
- Vectors: `vectors.json` `groupText` (`output` null means invalid).

| Field | Min | Max code points | Max UTF-8 bytes |
| --- | --- | --- | --- |
| Group name | 1 | 40 | 160 |
| Display name | 1 | 30 | 120 |
| Animal name | 1 | 40 | 160 |
| Pin note | 0 | 140 | 560 |
| Report comment | 0 | 500 | 2000 |

Display names are not unique. The word filter (§8.12) runs after cleaning.

### 2.6 JSON

- UTF-8, no byte-order mark.
- Encrypted record plaintexts (§4.5–4.7) are compact JSON with keys sorted by code point and no spaces; non-ASCII
  characters are written as themselves, not `\u` escapes; `/` is not escaped.
  - Both cores write the three records with a small canonical writer (`GroupJSON`): keys sorted by code point,
    strings escaped as Python's `json.dumps(ensure_ascii=False)` escapes them (`\"`, `\\`, `\n`, `\r`, `\t`, `\b`,
    `\f`, other control characters as `\u00xx`). Swift's `JSONEncoder` is not used for them, so the bytes can't
    vary between Foundation versions on Linux and Darwin. The output equals every `jsonWriters` vector.
  - Readers accept any key order and ignore unknown keys, but reject wrong types.
  - Readers drop a record whose `v` is not 1 (quietly, as an envelope of another version), and one whose texts
    break their §2.5 limits: an empty or too long display, group or animal name, or a too long note.
- **Padding (SD25).** Writers of the three JSON records (group profile, member profile, pin) always add the key
  `pad`, a string of ASCII spaces, chosen so the encoded JSON's byte length is a multiple of the record's block:
  32 for both profiles, 64 for pins. Rule: encode the record with `"pad":""`, giving length L; then
  `pad = " " × ((B − L mod B) mod B)` and encode again. `pad` sorts between `note`/`name` and `type`/`v`.
  Readers ignore `pad` and also accept records without it. Vectors: `jsonWriters` (what a writer must produce).
- API bodies (§6) use camelCase keys. All integers fit in 2^53. Readers ignore unknown keys.

### 2.7 Binary integers

- Fixed-size integers are little-endian.
- `uvarint`: unsigned LEB128, 7 bits per byte, low bits first, high bit set on every byte but the last. Values are
  below 2^32, so at most 5 bytes. Readers reject a sixth byte, or a value of 2^32 or more.
- `svarint`: a signed 32-bit value zigzag-encoded (`(v << 1) ^ (v >> 31)`), then written as a `uvarint`.
- Vectors: `vectors.json` `varints` and `zigzag`.

---

## 3. Cryptography

### 3.1 Algorithms and libraries

| Use | Algorithm |
| --- | --- |
| Records | AES-256-GCM, 96-bit random nonce, 128-bit tag |
| Subkeys and join proof | HKDF-SHA256 (RFC 5869), 32-byte output |
| Server-side hashes | SHA-256 |

- **Swift core:** CryptoKit (`AES.GCM`, `HKDF<SHA256>`) on Apple platforms. On Linux, where only the tests run, use
  OpenSSL EVP for AES-GCM, as `TileCrypto` does. HKDF on Linux is RFC 5869 built on `HMAC(EVP_sha256(), …)`, so add
  `#include <openssl/hmac.h>` to `Tests/Shims/COpenSSL/shim.h`. Copy TileCrypto's dual structure, but take the key
  and the AAD as parameters.
- **Kotlin core:** `javax.crypto` (`AES/GCM/NoPadding`); HKDF is RFC 5869 on `Mac.getInstance("HmacSHA256")`.
- **Server:** needs only SHA-256 and random bytes (WebCrypto). Its test client (`server/test/helpers/crypto.ts`) also
  implements AES-GCM and HKDF with WebCrypto and checks the same vectors.

### 3.2 Record envelope

Every encrypted record (group profile, member profile, trail chunk, live position, pin) is an envelope:

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 1 | Format version, `0x01` |
| 1 | 12 | Nonce (random for every encryption) |
| 13 | n | Ciphertext (n = plaintext length) |
| 13 + n | 16 | GCM tag |

- The shortest envelope is 29 bytes.
- A reader that sees another version reports `unsupportedVersion` (not "damaged"), so a newer app's records are
  skipped quietly.
- In JSON an envelope is a base64url string.
- There is no magic prefix. Envelopes are never mixed with other data.

### 3.3 Keys

- `groupKey` K: 32 random bytes, made once by the creator's phone.
- `subkey(purpose)` = HKDF-SHA256 with:
  - IKM = K
  - salt = the 16 raw bytes of `groupId`
  - info = UTF-8 `"satmap.group.v1|key|" + purpose`
  - L = 32
- The group-wide purposes are `group`, `member` and `pin` (one AES key per record kind) and `join`.
- Trail chunks and live positions use a key **per member** (SD26): the purposes `trail|{memberId}` and
  `live|{memberId}`, where `{memberId}` is the 22-char text of the member whose record it is. For example the info
  for the vector member is `satmap.group.v1|key|trail|sLGys7S1tre4ubq7vL2-vw`. A reader derives the key from the
  record's plain `memberId`.
- `subkey("join")` is not an encryption key. It is the `joinProof`, sent to the server, which stores only
  SHA-256(joinProof) and compares in constant time. HKDF outputs are independent, so the proof reveals nothing about
  the other subkeys or K.
- Phones derive the four group-wide values once per group, and each member's two values on first use, and keep them
  in memory (core type `GroupKeys`, §8.2).

### 3.4 Associated data

The AAD is UTF-8 text: fields joined by `|`. Ids are their 22-char base64url text. Integers are decimal ASCII with
no sign or leading zeros (all are positive).

| Record | Subkey | AAD |
| --- | --- | --- |
| Group profile | `group` | `satmap.group.v1\|group\|{groupId}` |
| Member profile | `member` | `satmap.group.v1\|member\|{groupId}\|{memberId}` |
| Trail chunk | `trail\|{memberId}` | `satmap.group.v1\|trail\|{groupId}\|{memberId}\|{windowStart}` |
| Live position | `live\|{memberId}` | `satmap.group.v1\|live\|{groupId}\|{memberId}\|{at}` |
| Pin | `pin` | `satmap.group.v1\|pin\|{groupId}\|{pinId}\|{memberId}\|{createdAt}\|{expiresAt}` (expiresAt is `-` when the pin doesn't expire) |

- The separator is the plain character `|` (0x7C). In the raw Markdown, tables write it as `\|` so the table
  renders; `vectors.json` has the exact AAD strings.
- A reader builds the AAD from the plain fields the server returned with the record (the change item's `memberId`,
  `start` and so on).
- A record the server moved to another group, member, window, time or expiry fails to open. The reader drops it,
  logs one line (no content), and does not retry it.

### 3.5 What the server can and cannot do

The server sees:

- ids, roles, join times, member counts and record sizes. Profiles and pins are padded (SD25), so their sizes give
  only a rough length; a trail chunk's size shows roughly how many points it holds.
- upload times, and IP addresses (in transit only; never stored or logged by us, §7.9; Cloudflare processes them
  at its edge)
- when each member shares: live `at` times
- which 10-minute windows hold trail, so when someone was moving
- pin created and expiry times, so which pins are animal sightings (SD3)

The server can:

- drop or delay records
- replay an old group or member profile
- withhold removals
- serve an old live position. Readers ignore a live record older than the newest they have for that member.

The server cannot read or forge any content.

A removed member keeps what they already downloaded and still knows K. They cannot fetch anything new (R4).

Where K can leak (accepted, R4 and R12; listed in §14 R2):

- K travels in the invite link, so it sits in chat apps, e-mail, the clipboard and browser history wherever a link
  went. Anyone who ever held **any** invite link for the group, even a used, cancelled or expired one, keeps K for
  good. With a copy of the server's data (a breach, or the operator) they could read the group.
- The operator serves the landing page. An operator who changed its script could read K from links opened in a
  browser. The page keeps the key out of history (§5.4), but nothing technical stops a malicious operator.
- On iPhone, another app that registers the `satmap` scheme could receive K from the landing page's fallback
  link. Universal links, paste and scan avoid it; on Android the fallback names the package (§5.4).

**Nonce safety.** Nonces are random 96-bit values, so each key must stay far below 2^32 encryptions. Every
re-upload is a fresh encryption with a fresh nonce.

- Trail and live keys are per member (SD26). One member sealing a live record every 5 s all year is about
  6.3·10^6 encryptions, and a chunk version every minute about 5.3·10^5, whatever the group size.
- The group-wide keys are used rarely. Profiles change by hand. Pins are limited to 60 per member per hour, so even
  1000 members dropping pins at the full rate all year stay under 5.3·10^8 (2^32 is 4.3·10^9).

### 3.6 Shared test vectors

Fixed inputs (also in `vectors.json` `inputs`):

| Input | Hex | base64url |
| --- | --- | --- |
| `groupKey` | `000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f` | `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8` |
| `groupId` | `a0a1a2a3a4a5a6a7a8a9aaabacadaeaf` | `oKGio6SlpqeoqaqrrK2urw` |
| `memberId` | `b0b1b2b3b4b5b6b7b8b9babbbcbdbebf` | `sLGys7S1tre4ubq7vL2-vw` |
| `pinId` | `d0d1d2d3d4d5d6d7d8d9dadbdcdddedf` | `0NHS09TV1tfY2drb3N3e3w` |
| `inviteSecret` | `e0e1e2e3e4e5e6e7e8e9eaebecedeeef` | |
| `memberPass` | `404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f` | `QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8` |
| window start | 1793613600 (2026-11-02 10:00:00 UTC) | |

Subkeys (HKDF as in §3.3; `vectors.json` `subkeys`, with each exact info string in `subkeyInfo`):

| Name in `vectors.json` | Purpose | Subkey (hex) |
| --- | --- | --- |
| `group` | `group` | `7dd4b0a7e0a2cefccb0f0e734c673fb2fc605ab955f871da137b40ece87980cf` |
| `member` | `member` | `91ac506b12998b583cb40e899b49dd814408b28b78d6593879e0dea1db8288dd` |
| `pin` | `pin` | `c49315f92ea6fe8ecd88afec5346f585d57a4ba38e506b24b52f5bc4bbc562c7` |
| `join` (= joinProof) | `join` | `7cf4d18070d73e26d64b14c97bfc03cba723888c1c980a1bee270eb5f8634ce2` |
| `trail` | `trail\|sLGys7S1tre4ubq7vL2-vw` (the vector member's trail key) | `3b8d33d35ce4291d14512ed4435b72a34928b93780c2dc51d5e54c53bf4e2ec3` |
| `live` | `live\|sLGys7S1tre4ubq7vL2-vw` (the vector member's live key) | `425ce23999dfd28543a3e88c87d43efd1a78943915984c42ba2d792bf1c17ab9` |

Server hashes:

| Value | SHA-256 (hex) |
| --- | --- |
| `memberPass` (32 raw bytes) | `ca2a4fe727faaecf16ecd130a86e0885c5540c05375340445071c0657555fd42` |
| `joinProof` (32 raw bytes; text `fPTRgHDXPibWSxTJe_wDy6cjiIwcmAob7icOtfhjTOI`) | `a4fb0217ad539dacce35fd9c3ad6b28a498c2f6fe592b62ce32ed46f67048869` |
| `inviteSecret` (16 raw bytes) | `c8cdc84b0dd9cec0f179823b70bd226598e888b45adc8dc8b8301e734be7965d` |

Records. Each uses the nonce shown (a test-only fixed nonce; real nonces are random). The plaintexts are in §4.8.

| Vector | AAD | Nonce | Envelope (hex) |
| --- | --- | --- | --- |
| `groupProfile` | `satmap.group.v1\|group\|oKGio6SlpqeoqaqrrK2urw` | `11`×12 | `011111111111111111111111115ff24f0b02e4b203cc6eec4bab16fe271094fe9d2b30b585990f4c7ebf6eb4392802050b06e66f5b4e51e99c` |
| `groupProfilePadded` | same as above | `13`×12 | `01131313131313131313131313d4a412286180e4305c4d47be01cbab8da4bfd4481945aaaeb5ed1e30741935c0bcc215d23ec9fcd80411c780332ade9513b786cf4f4b6272db755caccd33485588cf6f532c72a84bdfce056ce0b9e80e` |
| `memberProfile` | `satmap.group.v1\|member\|oKGio6SlpqeoqaqrrK2urw\|sLGys7S1tre4ubq7vL2-vw` | `21`×12 | `0121212121212121212121212189307f9db79814f2043e655a0196b6161a3ce4437f736691c825175b5aed565595421f32d93297cbce41377c7cf65017afd0` |
| `memberProfileUnicode` | same as above | `22`×12 | `01222222222222222222222222710dc47a34c726a2421621f5a976478d8a5fa9c4ff9efdd97457fb8856fa1047807e286caa49571d5be20d9eaef726a6c04e24e4b4` |
| `memberProfilePadded` | same as above | `23`×12 | `0123232323232323232323232310583e8b5ece25a63c73299581b87c6952ae96fa8ea624c4a0b323bafaae2039cb47d5f3023e7eba0a1df9acd7b8b5a18d5c94e61c0cfbb0933218d6692c63313f62d377fd0f0b9b1503a06f7d6e11ea` |
| `trailChunk` | `satmap.group.v1\|trail\|oKGio6SlpqeoqaqrrK2urw\|sLGys7S1tre4ubq7vL2-vw\|1793613600` | `31`×12 | `01313131313131313131313131c3b4b12a898fda7607e1946f15d1f1a0d63040637e8e1565ee2f14e7d8a03394589acc5bb946e8cc1c000850d7` |
| `trailChunkDeflated` | same as above | `32`×12 | `01323232323232323232323232a5ad17ba943493782d2218c9511858fbe4a6fd178ccab6a3fbdc8db6ff275dd7600dc550` |
| `live` | `satmap.group.v1\|live\|oKGio6SlpqeoqaqrrK2urw\|sLGys7S1tre4ubq7vL2-vw\|1793613620` | `41`×12 | `0141414141414141414141414173bdd266ed77e25669aa194d9a7c152f40b5ec00a0e0d6abbbb18034e72bfdcdfc21e0f4f8ee32f3` |
| `liveUnknowns` | `satmap.group.v1\|live\|oKGio6SlpqeoqaqrrK2urw\|sLGys7S1tre4ubq7vL2-vw\|1793613625` | `42`×12 | `01424242424242424242424242fd4af2eadbdec095fdb67d60206505174a100c53b0d5dc72f25220053f155eec6fb7d84e2636c733` |
| `pinAnimal` | `satmap.group.v1\|pin\|oKGio6SlpqeoqaqrrK2urw\|0NHS09TV1tfY2drb3N3e3w\|sLGys7S1tre4ubq7vL2-vw\|1793613900\|1793657100` | `51`×12 | 140 bytes, given in full under this table |
| `pinWater` | `satmap.group.v1\|pin\|oKGio6SlpqeoqaqrrK2urw\|0NHS09TV1tfY2drb3N3e3w\|sLGys7S1tre4ubq7vL2-vw\|1793613900\|-` | `52`×12 | `01525252525252525252525252ad9f306e27a2cdba2324821b033681ea3cd2e188b07df6337bf34342f43aeca62ebf1c85315438e1a43ac336a4577dd09b10fddf82936f75b9f704071a27d3be03e98b81a4` |
| `pinAnimalPadded` | same as `pinAnimal` | `53`×12 | 157 bytes, given in full under this table |

`pinAnimal` envelope (hex):

```
0151515151515151515151515174813a5752f7fae28f2344ff143d8b1b2c987dc066810e255104e4eb905f93c3c49f8bfb4afb9898ff0ed5c0ede7a7f71e1f26a97de4fefa479b8ac058555fad50836376e7b6cc6737bc1f5eb6a39b721cd52da3417137be8352d299e7dbb3106b88aa980d27684c146cc3632044b39de7185c7d4fbef09890f43e1d2606f0
```

`pinAnimalPadded` envelope (hex):

```
01535353535353535353535353217fec53e325206c8ffe0a7b7ecdb662fb2f8686acb5d6a8e0a7941c8128c36be8392c8a67fa9b4033088c7d6a1a8fed4618d0450e43e68ebf5b789b5f5369a84da4e87af3930fb60ae431493a34a1c1527102d4d1daa47cd60a1fc9ae3ec272ddbd701f891950fa81b166a8c61357ac8c504e0697e7b0e0cf03e6e2c0bd3845b4ff79c44babba9fb5e3d9e3c8cba8a2
```

Cases that must fail (`vectors.json` `mustFail`):

| Case | Expected result |
| --- | --- |
| `tamperedTrailEnvelopeHex` (last tag byte flipped) | `unreadable` |
| `trailChunk` envelope opened with `trailWrongWindowAad` (window 1793614200) | `unreadable` |
| `unknownVersionEnvelopeHex` (first byte `02`) | `unsupportedVersion` |
| `shortEnvelopeHex` (28 bytes) | `unreadable` |
| each of `badBase64url` | the decoder returns nil or throws |
| each of `nonCanonicalIds` | the server answers `400 bad_request` (clients may accept them) |
| `chunkPlainHex.deflateLongerThanDeclared` (the stream inflates to one byte more than declared) | `TrailChunk.decode` rejects it |
| `chunkPlainHex.deflateTrailingInput` (a byte follows the end of the DEFLATE stream) | rejected |
| `chunkPlainHex.deflateShorterThanDeclared` (declared one byte longer than the stream) | rejected |

What each part checks:

- **Each core** (Swift `GroupCryptoTests`, Kotlin `GroupCryptoTests`): derives the six subkeys from the
  `subkeyInfo` purposes; seals every record plaintext with its key, AAD and nonce and gets exactly `envelopeHex`;
  opens every envelope back to `plainHex`; gets the expected failure for every `mustFail` case. They read
  `vectors.json` through the existing `fixture(_:)` helpers.
- **The server tests:** the three hashes, the token split (`invite.token` → `groupId` + secret), base64url
  rejection, canonical ids, and envelope shape checks. The test client also seals and opens the record vectors.

---

## 4. Payload formats (before encryption)

### 4.1 Trail windows

- A trail chunk holds one member's trail points for one 10-minute window aligned to UTC.
- `windowStart = t - (t mod 600)` for t ≥ 0. The window covers `windowStart ≤ time < windowStart + 600`.
- The "open" chunk is the one whose window contains the current time.

### 4.2 Trail chunk layout

Header:

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 1 | Format, `0x01` |
| 1 | 1 | Encoding: `0x00` = the body follows as is; `0x01` = the body is raw DEFLATE (RFC 1951, no zlib header) |
| 2 | … | Encoding 0: the body. Encoding 1: `uvarint` body length N (decompressed size, at most 8192), then the DEFLATE bytes to the end. |

Body:

| Field | Type | Meaning |
| --- | --- | --- |
| n | uvarint | Number of points, 1…600 |
| then n times: | | |
| head | uvarint | `(dt << 1) \| s`. dt = seconds since the previous point (the first point: since `windowStart`); s = 1 if a new line segment starts at this point. |
| dLat | svarint | Latitude in micro-degrees minus the previous point's (the first point: minus 0) |
| dLon | svarint | Longitude in micro-degrees, likewise |

Readers reject a chunk (drop it, log it) if:

- n is 0 or more than 600
- a time is outside the window, or earlier than the point before it (dt ≥ 0 always holds)
- a coordinate is out of range
- bytes are left over
- the body length is wrong, or larger than 8192
- encoding 1: the DEFLATE stream does not end (final block) exactly after producing N bytes, or any input byte
  follows the end of the stream

**Compression.** Writers may use encoding 1 when the deflated body is smaller; v1 writers should try it when the
body is 256 bytes or more. Readers must support both encodings.

- Swift core: add `Deflate.raw(_:)` and `Deflate.inflateExact(_:size:)` in `Deflate.swift`.
  - zlib on every platform: the system's `zlib` module on Apple platforms, the `CZlib` shim on Linux. Apple's
    Compression framework is not used: macOS CI showed it reports no source bytes left even when input follows the
    end of the stream, so it can't apply the rule above.
  - Compress: `deflateInit2_` with level 6 and windowBits −15 (raw DEFLATE), the same as the Kotlin core.
  - Inflate exactly: `inflateInit2_` with windowBits −15 into an N + 1 byte buffer; accept only `Z_STREAM_END` with
    `total_out == N` and `avail_in == 0`.
  - Do not use the existing `Inflate.raw` for chunks: on Apple it decodes into an exact-size buffer and so accepts a
    stream longer than N, which Linux rejects.
- Kotlin core: `Deflater(6, true)`; `Inflater(true)` into an N + 1 byte buffer, accepting only `finished()` with
  `totalOut == N` and `remaining == 0`.
- Different compressors give different bytes, so the vectors fix only the bytes a reader must decode, plus three
  encoding-1 chunks every reader must reject (`mustFail.chunkPlainHex`).

**Segments.** Draw a line from each point to the next point of the same member, across chunks in time order, unless
the next point has s = 1 or the gap is more than 600 s. Writers set s = 1:

- when the `TrackPoint` has `startsSegment`
- on the first point after sharing is switched on
- on the first point after the group resumes from a pause

**Source of points.** The points are exactly the points the app adds to "Where I've been" (`AppModel.appendTrack`),
recorded only while sharing is on for that group and the group is active. Each point's time is the floor of
`TrackPoint.time`.

### 4.3 When a chunk is (re)sent

- The open chunk is re-sent at the next upload whenever points were added since its last acknowledged upload. It is
  not re-sent when nothing changed.
- A closed chunk is sent until it has been acknowledged once with all its points.
- Each send is a new encryption of the whole chunk, which replaces the server's copy.
- **Packing.** An upload (E18 or a WebSocket `upload`) takes chunks oldest first until it holds 50 chunks or its
  JSON body would pass 192 KiB (196 608 bytes), whichever comes first; the rest go in the next upload, at once.
- **Answers,** per window in `own_points` (§8.3):
  - in `accepted`: set `sent_version` to the `version` that was sent.
  - rejected `before_join`, `window_paused` or `bad_record`: delete the row (it can never be accepted).
  - rejected `bad_time`: keep the row, retry it once after the next E2 (which refreshes `serverTime`), then delete
    it if it is rejected again.
- **Acknowledged windows** (`sent_version ≥ version`) are deleted from `own_points` once they have been closed for
  `GroupPolicy.lateWindowGrace` (60 s): `start + 600 + 60 ≤ now`. This is checked when an acceptance arrives and in
  a sweep at the start of every upload pass, because a window whose last upload went while it was still open gets
  no later answer. The grace lets a point the recorder delivers a moment late (or a fix whose time is a little
  behind the phone's clock) still join its whole window: a point for a window already deleted would start a new
  one-point row, whose upload replaces the server's whole chunk with that point alone.
- **Clearing.** While an E22 (clear my trail) for a group waits in `ops`, no chunks are uploaded for that group, so
  a chunk recorded after the clear can't reach the server first and be deleted by it. Points keep collecting in
  `own_points` and go after E22 succeeds.

### 4.4 Live position (24 bytes, fixed)

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 1 | Format, `0x01` |
| 1 | 1 | Flags: bit 0 accuracy known, bit 1 heading known, bit 2 speed known; other bits 0 |
| 2 | 8 | Int64 time (Unix seconds): the floor of the fix's timestamp; must equal the plain `at` sent with it |
| 10 | 4 | Int32 latitude, micro-degrees |
| 14 | 4 | Int32 longitude, micro-degrees |
| 18 | 2 | UInt16 horizontal accuracy in metres, rounded, capped at 65535 |
| 20 | 2 | UInt16 heading in whole degrees, 0…359 |
| 22 | 2 | UInt16 speed in cm/s, rounded, capped at 65535 |

- A field whose flag is clear is written as 0 and ignored by readers.
- "Rounded" is round half away from zero (§2.4). A negative or missing accuracy or speed (iOS reports −1 when
  invalid) is unknown. Speed is `round(metres per second × 100)`.
- Heading is chosen in this order, then written as `round(x) mod 360` (so 359.6 is 0):
  1. the fix's course, when the speed is known and at least 0.5 m/s and the course is valid (not negative)
  2. otherwise the compass heading the recorder reports (true heading if valid, else magnetic), if not negative
  3. otherwise unknown
- Vectors: `vectors.json` `liveRounding` (fix fields in, live fields out; null is unknown).
- Readers drop a live record whose time differs from `at`, or that is older than the newest live record they hold
  for that member.

### 4.5 Pin record

The pin plaintext is JSON (§2.6) with these keys:

| Key | Type | Rule |
| --- | --- | --- |
| `v` | integer | 1 |
| `type` | string | One of `animal`, `shelter`, `water`, `danger`, `pothole`, `police` |
| `lat`, `lon` | integer | Micro-degrees |
| `animal` | string | Required when `type` is `animal` (1–40 code points); absent otherwise |
| `note` | string | Optional, 1–140 code points; absent when empty |
| `pad` | string | ASCII spaces; writers pad the JSON to a multiple of 64 bytes (§2.6); readers ignore it |

The rest of a pin is in plain fields that the AAD binds, not repeated inside the plaintext:

- `pinId`
- `memberId` (the dropper)
- `createdAt` (the dropper's phone time when the pin was dropped)
- `expiresAt`: `createdAt + 43200` for `animal`, `null` for every other type

Readers drop a pin whose `expiresAt` breaks that rule; a pin whose `type` this version doesn't know must have
`expiresAt` null (a future type that expires needs a new record version). Otherwise a pin with an unknown `type` is
shown as a plain pin titled "Pin" (not dropped), so older apps cope with newer types. The type labels shown in the
UI are in §9.4.

### 4.6 Member profile

The member profile is JSON: `{"colour":<0–11>,"name":"<display name>","pad":"<spaces>","v":1}`, padded to a
multiple of 32 bytes (§2.6).

| Index | Name | Hex | | Index | Name | Hex |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | red | `#E5484D` | | 6 | plum | `#AB4ABA` |
| 1 | green | `#46A758` | | 7 | olive | `#5C7C2F` |
| 2 | purple | `#8E4EC6` | | 8 | berry | `#C2298A` |
| 3 | brown | `#A18072` | | 9 | indigo | `#3E63DD` |
| 4 | teal | `#12A594` | | 10 | brick | `#B54548` |
| 5 | violet | `#6E56CF` | | 11 | forest | `#2F7C57` |

The palette avoids the colours the map already uses: blue (you), cyan (your track), pink (measuring),
yellow/orange/amber (contours and the drone pin) and white (blocks).

How a colour is chosen:

- The creator takes 0.
- A joiner sends a random index at redeem. After its first pull of changes, if another current member has the same
  colour and a free index exists, it sends a new profile with the lowest free index.
- A member can change colour in "Your name and colour" (§9.3 S15).
- That change (E12 at once, `setDisplayName`) must land last: it waits for a queued profile change (the colour fix
  above) already on its way, and no queued profile change goes while it is on its way; it replaces them.

### 4.7 Group profile

The group profile is JSON: `{"name":"<group name>","pad":"<spaces>","v":1}`, padded to a multiple of 32 bytes
(§2.6).

### 4.8 Payload vectors

All in `vectors.json`. The trail chunk (`trailChunk`, window 1793613600) has these points:

| time | lat | lon | s |
| --- | --- | --- | --- |
| 1793613605 | −24996352 | 31592345 | 1 |
| 1793613609 | −24996310 | 31592401 | 0 |
| 1793613613 | −24996255 | 31592470 | 0 |
| 1793613620 | −24996190 | 31592512 | 0 |
| 1793613940 | −24994000 | 31590000 | 1 |

| Vector | Plaintext (hex unless noted) |
| --- | --- |
| Trail chunk, encoding 0 | `0100050bffa7eb17b2be901e085470086e8a010e82015481059c229f27` |
| Trail chunk, encoding 1 (40 points every 10 s; the body is in `trailChunkDeflated.bodyHex`) | `01017fd360fcbffcb5f8a67d13e444bc82e88f00`. Readers must decode it to the 40 points; writers need not make these bytes. |
| Live (time 1793613620, lat −24996190, lon 31592512, accuracy 5, heading 87, speed 312) | `0107345fe86a00000000a29682fe4010e201050057003801` |
| Live with unknowns (time 1793613625, lat −24996150, lon 31592560) | `0100395fe86a00000000ca9682fe7010e201000000000000` |
| Group profile, padded (writer output; 64 bytes) | `{"name":"Kruger trip","pad":"                           ","v":1}` (27 spaces) |
| Member profile, padded (writer output; 64 bytes) | `{"colour":3,"name":"Thandi","pad":"                     ","v":1}` (21 spaces) |
| Animal pin "Rhino", no note, padded (writer output; 128 bytes; createdAt 1793613900, expiresAt 1793657100) | `{"animal":"Rhino","lat":-24996352,"lon":31592345,"pad":"                                                ","type":"animal","v":1}` (48 spaces) |
| Group profile, unpadded (reader test) | `{"name":"Kruger trip","v":1}` |
| Member profile, unpadded (reader test) | `{"colour":3,"name":"Thandi","v":1}` |
| Member profile with non-ASCII, unpadded (reader test; `Zoë 🦏`: 5 code points, 9 bytes) | `{"colour":0,"name":"Zoë 🦏","v":1}` |
| Animal pin, unpadded (reader test; createdAt 1793613900, expiresAt 1793657100) | `{"animal":"Rhino","lat":-24996352,"lon":31592345,"note":"Two adults near the water hole","type":"animal","v":1}` |
| Water pin, unpadded (reader test; createdAt 1793613900, never expires) | `{"lat":-24990001,"lon":31600000,"type":"water","v":1}` |

Both cores must:

- encode the points to exactly the encoding-0 bytes
- decode both chunk vectors to their points, and reject the three `mustFail.chunkPlainHex` chunks
- encode both live records to the bytes shown and decode them back
- encode each `jsonWriters` record exactly as shown (sorted keys, padded) and decode it
- decode every JSON record above, padded or not
- pass the `coordinates`, `varints`, `zigzag` and `liveRounding` tables

### 4.9 Archive object (server R2; clients read it through the archive endpoint)

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 4 | ASCII `SMGA` |
| 4 | 1 | Version, `0x01` |
| then repeated: | | |
| | 8 | Int64 `windowStart` |
| | 4 | UInt32 length L |
| | L | The chunk's envelope, exactly as uploaded |

Records are sorted by `windowStart`, at most one per window.

---

## 5. Invite link and QR

### 5.1 The domain: one value per app, one variable on the server

The domain is not decided (the app will probably be renamed). Everything uses one value, with the placeholder
`example.invalid`:

| Where | The one value | Derived from it |
| --- | --- | --- |
| iPhone | Build setting `GROUPS_DOMAIN` on the SatMap target (Debug and Release) in `project.pbxproj` | Info.plist key `SatMapGroupsDomain` = `$(GROUPS_DOMAIN)`; entitlement `applinks:$(GROUPS_DOMAIN)`; Swift `GroupConfig.domain` (in `App/Config.swift`) reads the Info.plist key |
| Android | `groupsDomain=example.invalid` in `android/gradle.properties` | `manifestPlaceholders["groupsDomain"]` (intent-filter host) and `buildConfigField("String", "GROUPS_DOMAIN", …)`; Kotlin `GroupConfig.domain` (in `app/Config.kt`) reads `BuildConfig.GROUPS_DOMAIN` |
| Server | Variable `PUBLIC_BASE_URL` = `https://example.invalid` | Invite pages, the API host, links in pages |

- The base URL is `"https://" + domain`, the API is `base + "/v1"`, and WebSockets use `wss://` + domain.
- For tests, the launch argument `-groupsBaseURL <url>` (iOS) or the launch extra `--es groupsBaseURL <url>`
  (Android) replaces the base URL for API and WebSocket requests only, for example `http://localhost:8787` or
  `http://10.0.2.2:8787`. Invite links are always made and parsed with `GroupConfig.domain`. It goes through the
  existing launch-argument mechanisms and is never saved.
- The same files hold `GroupConfig.contactEmail` (placeholder `groups@example.invalid`; a release blocker until
  the owner sets it) and `GroupConfig.appStoreURL` / `playStoreURL`.

### 5.2 Link format

```
https://{domain}/j/{inviteToken}#{groupKey}
```

- The token and the key are each 43 base64url characters. Nothing else is in the link.
- Example from the vectors:
  `https://example.invalid/j/oKGio6SlpqeoqaqrrK2ur-Dh4uPk5ebn6Onq6-zt7u8#AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8`
- The landing page fallback (SD15) is `satmap://join/{inviteToken}#{groupKey}`.

Parsing (core `InviteLink.parse(text, domain:)`) works on any text, such as a whole pasted message. The patterns
are case-sensitive; only the host comparison ignores ASCII case. `domain` is always `GroupConfig.domain`.

| Name | Pattern |
| --- | --- |
| Full https | `https://([A-Za-z0-9.-]+)/j/([A-Za-z0-9_-]{43})/?#([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])` |
| Full scheme | `satmap://join/([A-Za-z0-9_-]{43})/?#([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])` |
| Keyless https | `https://([A-Za-z0-9.-]+)/j/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])` |
| Keyless scheme | `satmap://join/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])` |

1. Collect every match of "Full https" whose host equals `domain` (ignoring ASCII case), and every match of "Full
   scheme". If there is any, the result is `invite` from the one that starts earliest in the text: the token is
   the 43-char path segment, `groupId` its first 16 bytes, and the key the 43-char fragment (each always decodes
   to 32 bytes).
2. Otherwise, if the text has a match of "Keyless https" whose host equals `domain`, or of "Keyless scheme", the
   result is `missingKey` (the app shows the "incomplete link" text). This covers a missing, short or long
   fragment and anything between the token and `#`.
3. Otherwise the result is `notInvite` (wrong host, `http://`, a token that isn't 43 characters, no link).

Swift uses `NSRegularExpression`, Kotlin `Regex`; both give the same matches for these patterns. Vectors:
`vectors.json` `inviteParse` (each case gives the text, the result and, for `invite`, the token, `groupIdHex` and
`groupKeyHex`).

### 5.3 QR code

- The QR content is the https link string, exactly. Byte mode, error correction level M, a quiet zone of 4 modules,
  black on white, drawn at least 240 pt/dp wide with nearest-neighbour scaling.
- Generating: iOS uses CoreImage `CIQRCodeGenerator` (`inputCorrectionLevel` `"M"`). Android uses ZXing
  `com.google.zxing:core:3.5.4` `QRCodeWriter` (`EncodeHintType.ERROR_CORRECTION` M, `MARGIN` 4).
- Scanning in the app (SD14):
  - iOS: VisionKit `DataScannerViewController` when `isSupported && isAvailable`, otherwise AVFoundation
    `AVCaptureMetadataOutput` with `.qr`.
  - Android: CameraX 1.6.2 (`camera-camera2`, `camera-lifecycle`, `camera-view`) with ZXing decoding frames through
    `PlanarYUVLuminanceSource`. No Play services.
  - Camera permission is asked on the first tap of "Scan QR code".
  - A scanned text goes through `InviteLink.parse`.
- Simulators and emulators have no camera, so walkthroughs use the paste path.

### 5.4 Landing page (served by the Worker)

`GET /j/{token}`: if the token doesn't match `^[A-Za-z0-9_-]{43}$` (one trailing `/` after it is accepted), the
answer is a 404 page with the same look and the text "This isn't a valid invite link." Otherwise it is a 200 HTML
page.

The page never looks the token up and never consumes it. Link-preview robots (WhatsApp, iMessage) fetch it without
the fragment and learn nothing.

Headers:

- `Content-Type: text/html; charset=utf-8`
- `Cache-Control: no-store`
- `Referrer-Policy: no-referrer`
- `X-Robots-Tag: noindex`
- `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; img-src data:; script-src 'sha256-<hash of the one inline script>'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`
  (the hash is computed once per isolate from the script text after `ANDROID_PACKAGE` is filled in)

There are no external resources, no cookies and no analytics.

Exact content:

- `<title>`: "Join a SatMap group"
- Heading: "You're invited to a SatMap group"
- Paragraph: "Open this invite in the SatMap app to join. It works once and expires 7 days after it was sent."
- Button "Open in SatMap". The inline script reads `location.hash` once, then at once calls
  `history.replaceState(null, "", location.pathname)` so the key doesn't stay in the browser's history or address
  bar. It then sets the button's `href`:
  - on Android (`/Android/` in `navigator.userAgent`):
    `"intent://join/" + token + "#Intent;scheme=satmap;package=" + ANDROID_PACKAGE + ";S.groupKey=" + key + ";end"`,
    so only SatMap can receive the key (and Chrome offers the Play Store if it isn't installed)
  - otherwise: `"satmap://join/" + token + "#" + key`
  - If the hash is not `#` followed by 43 base64url characters, the script hides the button and shows: "This link
    is incomplete. Open it again from the message you received."
- Paragraph: "Don't have SatMap yet?"
- Links "Download on the App Store" (`APP_STORE_URL`) and "Get it on Google Play" (`PLAY_STORE_URL`). Each is left
  out when its variable is empty.
- Small paragraph: "After installing, go back to the message and tap the invite link again."
- Footer: "Privacy notice" (link to `/privacy`) · "Terms of use" (`/terms`) · the `CONTACT_EMAIL` address.

### 5.5 Well-known files (served by the Worker)

`GET /.well-known/apple-app-site-association`:

- `Content-Type: application/json`, status 200, no redirect, `Cache-Control: public, max-age=3600`.
- Body:

```json
{"applinks":{"details":[{"appIDs":["TEAMID.com.jacobusoettle.satmap"],"components":[{"/":"/j/*","comment":"Group invites"}]}]}}
```

- `appIDs` is the variable `APPLE_APP_IDS` split on commas.

`GET /.well-known/assetlinks.json`:

- `Content-Type: application/json`, same caching.
- Body:

```json
[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"com.jacobusoettle.satmap","sha256_cert_fingerprints":["AA:BB:…"]}}]
```

- `package_name` comes from `ANDROID_PACKAGE` and the fingerprints from `ANDROID_CERT_SHA256`, split on commas.
  With Play App Signing, the fingerprint comes from Play Console (Release > Setup > App signing); add the debug
  fingerprint too for local tests.

### 5.6 Receiving a link in the apps

**iPhone**

- `SatMap.entitlements` adds `com.apple.developer.associated-domains` = [`applinks:$(GROUPS_DOMAIN)`].
- Info.plist adds `CFBundleURLTypes`: one entry with name `com.jacobusoettle.satmap.invite`, role Viewer and
  scheme `satmap`.
- In `SatMapApp.onOpenURL`, first call `InviteLink.parse(url.absoluteString, domain:)`. If the result is not
  `notInvite`, hand it to the groups model (`model.groups.openInvite(result)`) and return. Only otherwise call the
  existing `model.kml.importFile(at:)`. (Today every URL goes to the KML import.)
- Universal links arrive through `onOpenURL`, fragment included.
  - **[Unconfirmed]** No Apple page says outright that the fragment survives.
  - Safe default: if it is missing, show the incomplete-link text. The landing page button and the paste and scan
    paths still work.
  - Check (custom scheme only): the last walkthrough step (§9.7, step 41) opens the `satmap://` form of
    `GroupDemo.inviteLink2` with `XCUIDevice.shared.system.open(_:)` and waits for `app.buttons["joinGroup"]`. Whether universal links keep the fragment stays a device check after deployment
    (§14 U1).

**Android**

- `AndroidManifest.xml`, on MainActivity:
  - `<intent-filter android:autoVerify="true">` with VIEW, DEFAULT, BROWSABLE and
    `<data android:scheme="https" android:host="${groupsDomain}" android:pathPrefix="/j/"/>`
  - a second filter with VIEW, DEFAULT, BROWSABLE and `<data android:scheme="satmap" android:host="join"/>`
- `MainActivity.handleIncoming` tries `InviteLink.parse` first, on `intent.dataString`; when the data is
  `satmap://join/<token>` with no fragment and the intent has the string extra `groupKey` (the landing page's
  `intent://` link, §5.4), it parses `"satmap://join/" + token + "#" + groupKey` instead.
- It ignores replays (`isReplay(intent)`, launched from Recents) and clears the action after handling, as it does
  for files.
- **[Unconfirmed]** that Chrome and verified App Links keep the fragment in `intent.data`. Safe default as on iOS.
  Check (intent handling only): the instrumented test `InviteIntentTest` launches MainActivity in demo mode with
  `Intent(ACTION_VIEW, Uri.parse(GroupDemo.inviteLink2))`, and again with the `intent://` form's data
  (`satmap://join/<token>`) and `groupKey` extra, and asserts the join sheet (`joinGroup`) appears for "Braai club". Whether a
  real browser tap keeps the fragment stays a device check after deployment (§14 U1).

**Both apps**

- Opening a link never joins by itself. It opens the join confirmation (§9.3 S8), and joining needs a tap.
- If the phone already holds a pass for that `groupId`, the app opens that group's screen and shows "You're already
  in {group}." instead.

---

## 6. Server HTTP and WebSocket API

### 6.1 General

- **Base:** `https://{domain}/v1`. Requests and responses are `application/json; charset=utf-8`, except the archive
  download (§6.5 E20) and the WebSocket.
- **Body size:** at most 256 KiB (65 536 bytes for E1, E9, E10, E25). Check `Content-Length` and the bytes read;
  answer `413 too_large`.
- **Auth:** every `/v1/groups/{groupId}/…` endpoint takes `Authorization: Bearer {memberPass}`. The Durable Object
  hashes the pass and looks it up (§7.3).
  - Create (E1) has no pass yet: it carries a Pro proof.
  - Preview and redeem (E9, E10) carry a token and a `joinProof`.
  - Store notifications (E27, E28) carry their own signatures.
  - Admin endpoints take `Authorization: Bearer {ADMIN_TOKEN}`.
- **Ids:** `{groupId}`, `{memberId}`, `{pinId}` and `{inviteId}`, in paths and in bodies, must be 22 base64url
  characters in canonical form (§2.1; `400 bad_request` otherwise). In member paths, `me` stands for the caller.
- **Response headers:** `Cache-Control: no-store` on every response. No CORS headers: the API is for the apps only.
- **Errors:** `{"error":"<code>","message":"<English sentence for logs>"}`. Apps choose their text from the code and
  never show `message`. `429` and `503` carry `Retry-After` (seconds).
- **Unknown JSON keys** are ignored. Wrong types are `400 bad_request`. The body is validated before the role
  check (§1.3): a malformed body answers `400` even from a role that may not do the action.

### 6.2 Error codes

| Status | Code | Meaning | App reaction |
| --- | --- | --- | --- |
| 400 | `bad_request` | Malformed body, id, query or field | Log; drop the request (a bug) |
| 400 | `bad_record` | An envelope has the wrong version, length or base64url | Log; drop that record |
| 401 | `unauthorized` | No pass, or a pass that matches no member and no recent removal | From E2: "no longer in the group" (§8.10). From any other endpoint: re-check with E2 first. |
| 402 | `pro_invalid` | The proof doesn't verify (signature, chain, bundle, product, environment, family sharing), or the store API answers 400, 404 or 410 for it | Paywall error text |
| 402 | `pro_inactive` | The proof verifies but the entitlement is not active (expired, refunded, revoked, pending), or the purchase's newer known state says so (§7.6) | Paywall error text |
| 403 | `forbidden` | The caller's role may not do this (§1.3) | Refresh roles; show the generic error |
| 404 | `not_found` | No such member, pin, invite, archive, offer, or report target | Treat a delete as done |
| 404 | `invite_not_found` | Unknown, used, cancelled or expired invite, or wrong `joinProof` (one answer for all) | "Invite no longer works" text |
| 404 | `group_not_found` | No such group (never created, or fully deleted) | From E2: "No longer available" (§8.10). Otherwise re-check with E2 first. |
| 405 | `method_not_allowed` | | Log |
| 409 | `group_exists` | `groupId` taken (by a different pass) | Make a new id and retry once |
| 409 | `member_exists` | `memberId` taken in this group, now or by a removed or departed member (SD24) | Make a new id and retry once |
| 409 | `pin_exists` | `pinId` taken by another member | Make a new id and retry once |
| 409 | `group_paused` | Sharing or pins while paused | Apply paused state (§8.10) |
| 409 | `owner_must_hand_over` | The owner tried to leave | Should not happen (UI hides it) |
| 409 | `not_allowed` | Role change or offer aimed at the owner or at yourself, or removing the owner | Log |
| 409 | `too_many_groups` | This purchase already owns 20 groups (SD9) | "You already own 20 groups…" text |
| 409 | `terms_outdated` | E1 or E10 sent a `termsVersion` below `TERMS_VERSION` | If the app knows a newer terms version than the one accepted, show S1 and retry once; otherwise show the "update the app" text (§8.14) |
| 410 | `removed` | This pass was removed from the group in the last 30 days | "Removed" alert (§8.10) |
| 410 | `group_deleted` | The owner deleted the group in the last 30 days | "Deleted" alert (§8.10) |
| 410 | `pin_deleted` | E23 for a `pinId` that was deleted (SD24) | Drop the queued pin and remove it from the map |
| 413 | `too_large` | Body, record or message too large | Log; drop (clients pack uploads below the limit, §4.3) |
| 415 | `unsupported_media_type` | Not JSON | Log |
| 422 | `bad_time` | A time is out of range (future, or too old) | Drop that item |
| 422 | `pin_expired` | An animal pin's expiry has already passed | Drop the queued pin |
| 426 | `upgrade_required` | `/ws` without `Upgrade: websocket` | Log |
| 429 | `rate_limited` | Too many requests (§6.8) | Wait, then retry (the core waits a fixed 60 s, §6.7) |
| 500 | `internal` | Also: a required binding is missing (§7.8) | Retry with backoff |
| 503 | `store_unavailable` | Apple or Google could not be asked, or the server has no credentials for it | "Store isn't available" text; retry later |

### 6.3 Shared objects

**GroupStatus.** Returned by E1, E2, E5, E10, E15, E17 and pushed on the WebSocket.

```json
{
  "groupId": "oKGio6SlpqeoqaqrrK2urw",
  "status": "active",
  "pausedReason": null,
  "pausedAt": null,
  "deleteAt": null,
  "pausedIntervals": [],
  "ownerId": "sLGys7S1tre4ubq7vL2-vw",
  "memberCount": 5,
  "profile": "<envelope: group profile>",
  "profileSeq": 1,
  "you": {"memberId": "sLGys7S1tre4ubq7vL2-vw", "role": "owner", "joinedAt": 1793613000},
  "transfer": null,
  "pro": {"platform": "apple", "productId": "pro.yearly", "expiresAt": 1825149600},
  "watchers": 2,
  "seq": 812,
  "serverTime": 1793613622
}
```

| Key | Type | Meaning |
| --- | --- | --- |
| `status` | `active` or `paused` | Deleted groups answer `410 group_deleted` instead |
| `pausedReason` | null or string | `pro_ended` (the store says not active: expired, billing retry after grace, on hold, paused); `pro_refunded` (refunded or revoked); `pro_unverified` (SD8) |
| `pausedAt`, `deleteAt` | null or time | `deleteAt = pausedAt + 2592000` (30 days) |
| `pausedIntervals` | array of `[pausedAt, resumedAt or null]` | Pauses that ended in the last 30 days, plus the current one. Clients drop queued points inside them (§8.5). |
| `transfer` | null or `{"to": memberId, "expiresAt": time}` | An open ownership offer |
| `pro` | null or object | Shown only to the owner. `expiresAt` is null for Lifetime. |
| `watchers` | integer | Distinct members **other than the caller** with a watching WebSocket (§6.6) |
| `seq` | integer | The group's newest change number |

**Member** (E11 and change items):

```json
{"memberId": "…", "role": "admin", "joinedAt": 1793613000, "profile": "<envelope>", "lastLiveAt": 1793613620}
```

`lastLiveAt` is the time of the member's newest live record, or null.

**ProProof** (E1, E5, E17):

```json
{"platform": "apple", "signedTransaction": "<the JWS from StoreKit 2 VerificationResult.jwsRepresentation>"}
{"platform": "google", "productId": "pro", "purchaseToken": "<Play purchase token>"}
```

**Change items** (E19, and the WebSocket `tick`). Each item has `kind` and `seq`:

```json
{"kind": "group",   "seq": 1,  "profile": "<envelope>"}
{"kind": "member",  "seq": 2,  "memberId": "…", "role": "owner", "joinedAt": 1793613000, "profile": "<envelope>"}
{"kind": "chunk",   "seq": 40, "memberId": "…", "start": 1793613600, "rec": "<envelope>"}
{"kind": "archive", "seq": 41, "memberId": "…", "month": "2026-09", "maxChunkSeq": 39, "archivedAt": 1793581200}
{"kind": "pin",     "seq": 42, "pinId": "…", "memberId": "…", "createdAt": 1793613900, "expiresAt": 1793657100, "rec": "<envelope>"}
{"kind": "removed", "seq": 50, "what": "pin",    "id": "<pinId>"}
{"kind": "removed", "seq": 51, "what": "trail",  "id": "<memberId>"}
{"kind": "removed", "seq": 52, "what": "member", "id": "<memberId>"}
```

| Item | What the client does |
| --- | --- |
| `removed` `trail` | Delete every chunk and archive of that member |
| `removed` `member` | Delete the member, their chunks, archives, pins and live position |
| `archive` | If, before this page, the client's cursor was at or above `maxChunkSeq` **and** that cursor was reached at a `serverTime` earlier than `archivedAt` (§8.8), it already holds every chunk the archive contains: it relabels its stored chunks of that member and month with the archive item's `seq` and downloads nothing. Otherwise it downloads E20 for that member and month and stores its chunks under the archive item's `seq`. |

`seq` values rise but have gaps (a replaced row takes a new number). Clients skip items whose `seq` is at or below
their cursor: a tick computed before a later HTTP page can arrive after it, and the client keeps no tombstones, so
an old pin or member item would bring back what a removal deleted.

In an `archive` item, `maxChunkSeq` is the largest of: the `seq` of every chunk row merged into that object in any
pass, and the `seq` the archive item had before this pass (if any). `archivedAt` is the server time of the pass's
final transaction. Both conditions are needed: a cursor can pass a row's `seq` after an archive pass already
deleted the row, and only a cursor reached before the pass proves the row was seen.

**LiveEntry:** `{"memberId": "…", "at": 1793613620, "rec": "<envelope>"}`.

### 6.4 Endpoints

| # | Method and path | Auth | Allowed |
| --- | --- | --- | --- |
| E1 | `POST /v1/groups` | Pro proof | anyone |
| E2 | `GET /v1/groups/{groupId}` | pass | member |
| E3 | `PUT /v1/groups/{groupId}/profile` | pass | owner, admin |
| E4 | `DELETE /v1/groups/{groupId}` | pass | owner |
| E5 | `PUT /v1/groups/{groupId}/pro` | pass | owner |
| E6 | `POST /v1/groups/{groupId}/invites` | pass | owner, admin |
| E7 | `GET /v1/groups/{groupId}/invites` | pass | owner, admin |
| E8 | `DELETE /v1/groups/{groupId}/invites/{inviteId}` | pass | owner, admin |
| E9 | `POST /v1/invites/preview` | token + joinProof | anyone with the link |
| E10 | `POST /v1/invites/redeem` | token + joinProof | anyone with the link |
| E11 | `GET /v1/groups/{groupId}/members` | pass | member |
| E12 | `PUT /v1/groups/{groupId}/members/me/profile` | pass | member |
| E13 | `PUT /v1/groups/{groupId}/members/{memberId}/role` | pass | owner |
| E14 | `DELETE /v1/groups/{groupId}/members/{memberId}` (`me` = leave) | pass | §1.3 |
| E15 | `POST /v1/groups/{groupId}/transfer` | pass | owner |
| E16 | `DELETE /v1/groups/{groupId}/transfer` | pass | owner (cancel) or the target (decline) |
| E17 | `POST /v1/groups/{groupId}/transfer/accept` | pass + Pro proof | the target |
| E18 | `POST /v1/groups/{groupId}/upload` | pass | member |
| E19 | `GET /v1/groups/{groupId}/changes?since={seq}&limit={n}` | pass | member |
| E20 | `GET /v1/groups/{groupId}/archive/{memberId}/{month}` | pass | member |
| E21 | `GET /v1/groups/{groupId}/live` | pass | member |
| E22 | `DELETE /v1/groups/{groupId}/trail/me` | pass | member |
| E23 | `POST /v1/groups/{groupId}/pins` | pass | member |
| E24 | `DELETE /v1/groups/{groupId}/pins/{pinId}` | pass | dropper, admin, owner |
| E25 | `POST /v1/groups/{groupId}/reports` | pass | member |
| E26 | `GET /v1/groups/{groupId}/ws` (WebSocket upgrade) | pass | member |
| E27 | `POST /v1/store/apple` | Apple signature | App Store Server Notifications V2 |
| E28 | `POST /v1/store/google?token={GOOGLE_RTDN_TOKEN}` | shared token | Pub/Sub push |
| E29 | `GET /v1/health` | none | `{"ok":true,"version":"<package version>"}` |
| A1–A6 | `/v1/admin/…` (§7.7) | `ADMIN_TOKEN` | the developer |
| W1–W6 | `GET /j/{token}`, `/.well-known/apple-app-site-association`, `/.well-known/assetlinks.json`, `/terms`, `/privacy`, `/` | none | browsers (§5.4, §5.5, §11.1) |

### 6.5 Endpoint details

**E1 Create a group**

Request:

```json
{
  "groupId": "oKGio6SlpqeoqaqrrK2urw",
  "memberId": "sLGys7S1tre4ubq7vL2-vw",
  "pass": "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8",
  "joinProof": "fPTRgHDXPibWSxTJe_wDy6cjiIwcmAob7icOtfhjTOI",
  "groupProfile": "<envelope>",
  "memberProfile": "<envelope>",
  "termsVersion": 1,
  "pro": {"platform": "apple", "signedTransaction": "<JWS>"}
}
```

- **201:** GroupStatus. The caller is the owner, `joinedAt = now`. The group profile becomes change `seq` 1 and the
  owner's member item `seq` 2.
- **Retry:** if the group exists and SHA-256(`pass`) is its owner's pass hash, answer 200 with the status.
- **Errors:** `400 bad_request`/`bad_record`, `402 pro_invalid`/`pro_inactive`, `409 group_exists` (also for the id
  of a deleted group), `409 too_many_groups`, `409 terms_outdated`, `429`, `503 store_unavailable`.
- **Order of work:**
  1. The Worker applies `RL_CREATE` (§6.8).
  2. Validate the body, including `termsVersion ≥ TERMS_VERSION` and, for Google, the purchase token's shape
     (§7.6), before any store call.
  3. Verify the proof and ask the store API (§7.6).
  4. Attach the group to the purchase's ProDO.
  5. Write the group in one `transactionSync`.

**E2 Group status:** 200 GroupStatus.

**E3 Rename the group:** `{"profile": "<envelope>"}`. 200 `{"seq": n}`. Adds a `group` change item. Allowed while
paused.

**E4 Delete the group:** 204. Only the owner. The server:

1. detaches the group from its ProDO
2. closes every socket with code 4404
3. in one transaction, deletes all rows, keeps only `grp.status = 'deleted'`, `deleted_at` (so requests answer
   `410 group_deleted`) and `pro_name` (so the 30-day purge can retry the ProDO detach), and inserts the prefix
   `g/{groupId}/` into `r2_deletes`; the `r2_delete` job then empties it (§7.5)
4. after 30 days, the `purge` job and `deleteAll()` (§7.4)

**E5 New Pro proof:** `{"pro": ProProof}`. 200 GroupStatus. Used:

- on renewal
- after "Restore purchases"
- when the owner switches plans (for example, monthly to lifetime)

The group moves to the new purchase's ProDO and leaves the old one. If the group was paused for a Pro reason and the
new entitlement is active, it resumes. The proof goes through the same verification and store check as E1 (§7.6);
an old proof can't undo a newer refund or expiry (`state_at`, §7.6), so it answers `402 pro_inactive`.

**E6 Create an invite:** body `{}`. 201:

```json
{"inviteId": "…", "token": "oKGio6SlpqeoqaqrrK2ur-Dh4uPk5ebn6Onq6-zt7u8", "createdAt": 1793613000, "expiresAt": 1794217800}
```

- `token` appears only in this response. The server stores SHA-256(secret).
- `expiresAt = createdAt + 604800`.
- Allowed while paused (SD18).
- Limits: 100 pending invites per group, 200 created per group per day.

**E7 Pending invites:** 200 `{"invites": [{"inviteId", "createdBy", "createdAt", "expiresAt"}]}`. Only pending,
unexpired invites, newest first. `createdBy` is a member id (the app shows that member's name).

**E8 Cancel an invite:** 204. Marks it cancelled. `404 not_found` if it isn't pending.

**E9 Preview an invite**

Request: `{"token": "…", "joinProof": "…"}`.

200:

```json
{"groupId": "…", "status": "active", "profile": "<envelope>", "memberCount": 5, "inviter": {"memberId": "…", "profile": "<envelope>"}, "expiresAt": 1794217800}
```

- `inviter` is null if that member has left (defensive: SD23 cancels the pending invites of a member who leaves or
  loses admin, so it doesn't arise in practice).
- Does not use up the invite.
- `404 invite_not_found` for every failure: unknown token, used, cancelled, expired, wrong proof, deleted group. A
  `token` or `joinProof` that isn't 43 base64url characters can't be routed (§7.2) and answers `400 bad_request`.
- The Worker applies `RL_INVITE` first (`429`).

**E10 Redeem an invite**

Request:

```json
{"token": "…", "joinProof": "…", "memberId": "…", "pass": "…", "profile": "<envelope>", "termsVersion": 1}
```

- **201:** GroupStatus for the new member: role `member`, `joinedAt = now`, plus a `member` change item.
- Atomic in one `transactionSync`: the invite must be pending, unexpired, with a matching proof hash. It is marked
  used, with `used_by` and `used_pass_hash`, and the member row is inserted.
- **Retry:** if the invite is used and `used_pass_hash` equals SHA-256(`pass`), and that member still exists,
  answer 200 with the same status.
- **Errors:** `400 bad_request` (also a malformed `token` or `joinProof`, as E9), `404 invite_not_found`,
  `409 member_exists` (the id is a current member's, or appears in `removals` as a member, SD24),
  `409 terms_outdated`, `429` (`RL_INVITE`).
- Works while paused.

**E11 Members:** 200 `{"members": [Member…]}`, ordered by `joinedAt`.

**E12 My profile:** `{"profile": "<envelope>"}`. 200 `{"seq": n}`.

**E13 Set role:** `{"role": "admin"}` or `{"role": "member"}`. 200 `{"seq": n}`.

- `403 forbidden` unless the caller is the owner.
- `409 not_allowed` if the target is the owner or the caller.
- `404 not_found` if there is no such member.
- Demoting an admin to member also sets `state = 'cancelled'` on every pending invite with `created_by` = that
  member, in the same transaction (SD23).

**E14 Remove a member, or leave (`me`):** 204.

Who may remove whom:

- The owner may remove anyone but themself.
- An admin may remove only members (`403` for an admin or the owner).
- `me`: any role but the owner leaves (`409 owner_must_hand_over`).
- Your own `memberId` instead of `me`: the same as leaving for an admin or member; the owner gets `409 not_allowed`
  (removing the owner).

Effects, in one transaction:

1. Delete the member's chunks, `archives` rows, pins, live row and member row.
2. Insert the prefix `g/{groupId}/t/{memberId}/` into `r2_deletes`, so the `r2_delete` job removes their archived
   trail even if R2 fails now (§7.5).
3. Add a `removed member` item.
4. Cancel an ownership offer made to them.
5. Set `state = 'cancelled'` on every pending invite with `created_by` = that member, whether they were removed
   or left (SD23).
6. Store their pass hash in `gone_passes`, with reason `removed` (leaving stores nothing).

Then close their sockets: code 4403 if removed, 1000 if they left.

**E15 Offer ownership:** `{"to": "<memberId>"}`. 200 GroupStatus with `transfer`.

- Replaces any earlier offer.
- Expires after 7 days (`expiresAt = now + 604800`).
- `409 not_allowed` if the target is the caller.
- Allowed while paused.

**E16 Cancel or decline the offer:** 204.

- `403` unless the caller is the owner or the target.
- `404 not_found` if there is no offer.

**E17 Accept ownership:** `{"pro": ProProof}`. 200 GroupStatus.

- `403` unless the caller is the target. `404` if there is no offer. `402` if the proof fails.
- Effects:
  - the caller becomes owner and the old owner becomes admin (two `member` items)
  - the group moves to the caller's ProDO
  - the offer is deleted
  - if the group was paused for a Pro reason, it resumes

**E18 Upload trail chunks and live position**

Request:

```json
{
  "chunks": [{"start": 1793613600, "rec": "<envelope>"}],
  "live": {"at": 1793613620, "rec": "<envelope>"}
}
```

- `chunks`: 0–50 items (clients pack per §4.3).
- `live` absent means no change (a heartbeat with no new fix, §8.6). `"live": null` means "I stopped sharing": the
  server deletes the live row and tells watchers.

200:

```json
{"accepted": [1793613600], "rejected": [{"start": 1793000000, "error": "before_join"}], "liveAccepted": true, "watchers": 2, "status": "active", "serverTime": 1793613622}
```

A chunk is accepted (and replaces any stored chunk for that member and window, with a new `seq`) only if all of
these hold, checked in this order:

- `start % 600 == 0` (otherwise `bad_record`: it can never be accepted)
- `start + 600 > you.joinedAt` (otherwise `before_join`)
- `start ≤ now + 600` (otherwise `bad_time`)
- the envelope is 32–12 288 bytes with version 1 (otherwise `bad_record`)
- the window does not lie wholly inside a pause in `pause_log` (otherwise `window_paused`)

What the client does with each rejection is in §4.3.

A live record is accepted only if all of these hold:

- `now − 3600 ≤ at ≤ now + 600` (the same clock allowance as chunks)
- the envelope is 53–128 bytes with version 1
- `at` is newer than the stored live record
- at least 2 s passed since this member's last accepted live record

A rejected live record makes `liveAccepted` false, not an error. So does a request with no `live` key.

While paused, the whole request answers `409 group_paused`, unless it has no chunks and `"live": null`.

**E19 Changes since a cursor**

Query:

- `since`: default 0.
- `limit`: default 500, at most 1000.

200 `{"items": [...], "cursor": 812, "more": false, "serverTime": 1793613622}`:

- Items are in ascending `seq`, at most `limit` items and about 2 MiB of JSON.
- `cursor` is the `seq` of the last item, or `since` when there are none.
- `more` is true when items remain.
- `serverTime` is when the page was read; the client keeps it with the cursor (§8.8).

The viewer's `joinedAt` filters what is sent:

- Chunks with `start + 600 ≤ joinedAt` are left out.
- Archives are left out when their whole month ends before `joinedAt`.

What is included:

- Pins: only unexpired ones, whatever their age (SD2).
- Members: current members only. Removed ones come as `removed` items.

If `since` is below the group's `minCursor` (removal records older than 400 days were pruned, §7.4), or above
`seq`, the answer is `{"reset": true, "cursor": 0}`. The client deletes its cached copy of the group and pulls from 0.

**E20 Archive month:** 200 `application/octet-stream`, the §4.9 format, holding only windows with
`start + 600 > joinedAt`. `404 not_found` if there is no archive or nothing is visible.

**E21 Live positions:** 200 `{"live": [LiveEntry…], "watchers": 2, "serverTime": t}`. Includes the caller's own
entry. Only entries with `at ≥ now − 86400`.

**E22 Clear my trail:** 204. In one transaction: deletes the caller's chunks and `archives` rows, inserts the
prefix `g/{groupId}/t/{memberId}/` into `r2_deletes` (§7.5), and adds a `removed trail` item. Allowed while paused.

**E23 Drop a pin**

Request: `{"pinId": "…", "createdAt": 1793613900, "expiresAt": 1793657100, "rec": "<envelope>"}`.

- **201:** `{"seq": n}`.
- **Retry:** if that `pinId` already exists from the same member, answer 200 with its `seq`.
- **Errors:**
  - `410 pin_deleted`: the `pinId` appears in `removals` as a pin (deleted by its dropper, an admin, the
    developer or expiry). The app drops the queued pin (SD24).
  - `409 pin_exists` (another member's id)
  - `409 group_paused`
  - `422 pin_expired` (`expiresAt ≤ now`)
  - `400 bad_record`: `expiresAt` must be null or exactly `createdAt + 43200`; the envelope must be 49–1024 bytes
  - `422 bad_time`: `createdAt` must be at most `now + 600` and, for pins that never expire, at least
    `now − 604800`
- **Limits:** 60 pins per member per hour; 2000 unexpired pins per group (`429`).

**E24 Delete a pin:** 204, and a `removed pin` item.

- `403` unless the caller is the dropper, an admin or the owner.
- `404 not_found` if there is no such pin.
- Allowed while paused.

**E25 Report**

Request:

```json
{"targetKind": "pin", "targetId": "<pinId>", "reason": "offensive", "comment": "", "content": {"type": "animal", "animal": "Rhino", "note": "…", "name": "Thandi"}}
```

- `targetKind`: `pin`, `member` or `group` (the group name; `targetId` is the `groupId`).
- `reason`: `offensive`, `harassment`, `spam`, `dangerous` or `other`.
- `comment`: cleaned text, up to 500 code points (§2.5). Apps send `""` when there is none; an absent comment is
  read as `""`.
- `content` is the reporter's decrypted copy (SD28: the reporter's claim, which the server can't check):
  - for a pin: `type`, `animal`, `note`, and `name` (the dropper's display name)
  - for a member: `name`
  - for the group: `name` (the group name)
- The server keeps only those keys and drops any others. Each must be a string within its §2.5 limits (`name` of a
  group 40 code points, of a member 30; `animal` 40; `note` 140; `type` at most 20 characters), otherwise
  `400 bad_request`. Positions are never sent (SD10). The server checks each text's code points and UTF-8 bytes
  but doesn't clean it again: the phone sends `GroupText.clean` output.
- **Target check.** The server looks the target up: a current member or pin, or a `removals` row for one already
  gone (or, for `group`, this group). A target that never existed answers `404 not_found`.
- **Facts.** With the report, the server stores what it knows itself: whether the target still exists, the member
  it belongs to (the pin's dropper, or the member), the pin's `createdAt` or the member's `joinedAt`, and the
  stored envelope's length (for a pin, member or group profile). §7.7 shows them to the developer.
- **202:** `{"reportId": "…"}`.
- **Errors:** `400 bad_request`, `403 forbidden` for a group-name report from the owner (§1.3: the owner renames
  instead), `404 not_found`, `429`.
- **Limit:** 20 reports per member per day. Allowed while paused.

**E27 Apple notifications:** body `{"signedPayload": "<JWS>"}`.

- Verified as in §7.6 ("App Store Server Notifications V2").
- 200 when it verifies, even when no group uses that purchase.
- 400 when it doesn't verify, so Apple retries.

**E28 Google notifications:**

- `404 not_found` when `GOOGLE_RTDN_TOKEN` is unset or shorter than 32 characters (the endpoint doesn't exist).
- Otherwise `token` must equal `GOOGLE_RTDN_TOKEN` (constant-time compare), or `403`.
- The body is a Pub/Sub push message. Answer 204, which acknowledges it.

### 6.6 WebSocket protocol

**Connect.**

- `GET wss://{domain}/v1/groups/{groupId}/ws` with `Upgrade: websocket` and `Authorization: Bearer {memberPass}`.
  Both URLSessionWebSocketTask (built from a URLRequest) and OkHttp can send the header.
- The Worker checks the `Upgrade` header (`426 upgrade_required` without it) and forwards the request to the
  group's DO.
- The DO checks the pass:
  - an HTTP error before the upgrade: 401, 410 or 404 as in §6.2
  - otherwise `ctx.acceptWebSocket(server, [memberId])`, with the attachment
    `{memberId, watching: false, cursor: 0, helloAt: 0, openedAt, w: -1, st: "", more: false}` (`openedAt` for
    the 10 s `hello` timeout; `w` the watcher count, `st` a fingerprint of the status and `more` the backlog flag
    last sent to this socket, §7.3)
- A member may have at most 4 sockets. A fifth closes the oldest with code 4409.

**Frames.**

- Text frames hold one JSON object each.
- The literal text `ping` is answered `pong` by
  `ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"))`, without waking the DO.
- Clients send `ping` every 30 s.
- Frames are at most 256 KiB from client to server and at most 512 KiB from server to client.
- Client messages count toward the 120 requests per member per minute (§6.8); `ping` doesn't. Over the limit the
  socket closes with 4429.

**Client to server:**

| Message | Meaning |
| --- | --- |
| `{"type":"hello","v":1,"watching":true,"cursor":812}` | Must be the first message, within 10 s (otherwise close 4408). Sets `watching` and the socket's change cursor. |
| `{"type":"watching","on":false}` | The map stopped or started showing this group (§8.7) |
| `{"type":"upload","id":17,"chunks":[…],"live":{…}}` | Exactly the E18 body plus a client counter `id`. `chunks` may be left out. |
| `{"type":"ack","cursor":830}` | The client has stored changes up to `cursor`. Used after the client caught up over HTTP. |

**Server to client:**

| Message | Meaning |
| --- | --- |
| `{"type":"welcome","v":1,"status":{GroupStatus},"live":[LiveEntry…]}` | The answer to `hello`: full status and every live entry from the last 24 h |
| `{"type":"uploaded","id":17,"accepted":[…],"rejected":[…],"liveAccepted":true}` | Sent at once for each `upload` |
| `{"type":"error","id":17,"error":"group_paused","message":"…"}` | An upload failed as a whole (`id` is absent for protocol errors) |
| `{"type":"tick","serverTime":t,"watchers":2,"live":[…],"changes":{"from":812,"items":[…],"cursor":830,"more":false},"status":{GroupStatus}}` | The one combined frame per tick (below) |

**Ticks: one combined frame per tick.**

- After any change (an upload, a live record, a pin, a member change, a status change, a watcher change), the DO
  schedules a flush 2 s later with `setTimeout`, if none is scheduled. Changes inside those 2 s go out in that one
  flush.
- At the flush, every socket that has something new gets one `tick`, holding only the keys that have news:
  - `live`: entries received since the last flush, including `{"memberId":…, "at":…, "rec":null}` for a member who
    stopped sharing
  - `changes`: exactly the E19 query for the recipient from the socket's cursor, with the recipient's `joinedAt`
    rules (chunks with `start + 600 ≤ joinedAt` and archives of months that end before `joinedAt` are left out).
    At most 200 items or 256 KiB; `from` is the cursor they follow. The DO then sets the socket's cursor to
    `changes.cursor`.
  - `watchers`: when the socket's count changed
  - `status`: when the recipient's status changed (it includes `you`, so a role change reaches that member)
- A socket whose backlog is more than one page gets `"more": true` and no further `changes` until it sends `ack`.
  Meanwhile the client pulls over HTTP (E19).
- If `changes.from` is greater than the client's own cursor, the client ignores those items and pulls over HTTP.
- The DO caches each flush's change queries by the pair (cursor, `joinedAt`), because sockets usually share one.
  Never by cursor alone: members who joined at different times see different chunks.

**Watching (the "someone is watching" signal).** A socket is watching when its last `hello` or `watching` said so.
`watchers`, for a recipient, is the number of distinct members other than the recipient who have a watching socket.
Senders use it to choose their pace (§8.6). HTTP uploads learn it from the E18 answer.

**Close codes:**

| Code | Meaning |
| --- | --- |
| 1000 | Normal close; the member left |
| 1001 | Going away |
| 4400 | Protocol error |
| 4401 | Pass no longer valid |
| 4403 | Removed from the group |
| 4404 | Group deleted |
| 4408 | No `hello` within 10 s |
| 4409 | Replaced by a newer socket |
| 4429 | Rate limited |

On 4401, 4403 or 4404 the client stops reconnecting and runs §8.10 (4401 checks with E2 first). Otherwise it
reconnects with backoff 1, 2, 4, 8, 16, 32, 60 s (plus or minus 20 % jitter) while the connect conditions in §8.7
still hold. How the app reports a refused upgrade is in §8.7.

### 6.7 Idempotency and retries

| Request | Safe retry |
| --- | --- |
| Create (E1) | Same `groupId` and `pass` gives 200. A create that got no answer is resent with the same `groupId`, `memberId` and `pass` by the next Create (§8.3), so a retry can't make a second group. |
| Redeem (E10) | Same token and `pass` gives 200 |
| Drop a pin (E23) | Same `pinId` gives 200, or `410 pin_deleted` if it was deleted meanwhile |
| Uploads (E18) | Replace by member and window |
| Deletes (E8, E14, E16, E22, E24) | Clients treat `404` as done |

Clients retry network failures and `5xx` with backoff 2, 4, 8 … 300 s. They never retry `4xx`, except `429`, the
id clashes in §6.2, and `409 terms_outdated` (once, after S1, §8.13). The core's transport returns only the status
and body (§8.2), so after a `429` the hub waits a fixed 60 s rather than `Retry-After`.

### 6.8 Rate and size limits

| What | Limit | Where |
| --- | --- | --- |
| E1 create | 3 per IP per minute (key `create:{ip}`) | Worker, Rate Limiting binding `RL_CREATE` (required, §7.8) |
| E9 preview / E10 redeem | 10 per IP per minute each (keys `preview:{ip}`, `redeem:{ip}`) | Worker, binding `RL_INVITE` (required) |
| Store calls for a junk Google token | A token with the wrong shape never reaches Google; a token Google refused is answered from memory for 10 min | ProDO (§7.6) |
| Authenticated requests | 120 per member per minute, WebSocket messages included (`ping` is not); over it HTTP answers `429` and a socket closes with 4429 | DO, in memory |
| Live records | 1 per member per 2 s (extras ignored) | DO |
| Invites | 100 pending and 200 created per group per day | DO, `counters` table |
| Pins | 60 per member per hour; 2000 unexpired per group | DO, `counters` table |
| Reports | 20 per member per day | DO, `counters` table |
| Sockets | 4 per member | DO |
| Bodies | 256 KiB (64 KiB for E1, E9, E10, E25) | Worker |
| Envelopes | group/member profile 39–512 B; chunk 32–12 288 B; live 53–128 B; pin 49–1024 B | DO |
| Chunks per upload | 50 | DO |
| Groups per Pro purchase | 20, not counting deleted groups (SD9, `MAX_GROUPS_PER_PURCHASE`) | ProDO |

The IP (`CF-Connecting-IP`) is used only as a rate-limit key. It is never stored or logged.

---

## 7. Server internals

### 7.1 Layout and tools

```
server/
  package.json  package-lock.json  tsconfig.json  wrangler.jsonc  vitest.config.ts  .dev.vars.example  README.md
  src/index.ts              Worker entry: routing (§7.2), body limits, RL_CREATE and RL_INVITE, error mapping
  src/group/GroupDO.ts      class GroupDO extends DurableObject: HTTP handlers, WebSocket handlers, alarm()
  src/group/schema.ts       the DDL in §7.3 and migrations by schema_version
  src/group/changes.ts      change feed and tick building
  src/group/archive.ts      R2 archiving and reading
  src/pro/ProDO.ts          class ProDO: one per purchase
  src/pro/apple.ts          JWS verification, App Store Server API, notification decoding
  src/pro/apple-root-g3.ts  Apple Root CA - G3 (DER, base64) pinned
  src/pro/google.ts         service-account token, subscriptionsv2 / productsv2, RTDN decoding
  src/pro/der.ts  src/pro/types.ts   DER reader for the certificate extensions; shared Pro types
  src/admin/AdminDO.ts      class AdminDO: reports
  src/web/landing.ts  src/web/wellknown.ts  src/web/pages.ts  src/web/html.ts   (/j, AASA, assetlinks, /terms, /privacy, /)
  src/lib/b64url.ts  config.ts  errors.ts  envelope.ts  hash.ts  ids.ts  jobs.ts  limits.ts  log.ts  time.ts
  src/types/*.d.ts          the few Node APIs used (no @types/node, §13.2)
  test/unit/*.test.ts  test/integration/*.test.ts  test/ws/*.test.ts
  test/helpers/{client,crypto,apple-test-chain,google,ws,examples,examples-reporter}.ts  test/types/*.d.ts
```

**Versions** (checked 2 November 2026; pin exact versions in `package.json`):

- Node 22 or newer
- TypeScript 6.0.x (the last JavaScript-based line; TypeScript 7 is not used in v1)
- vitest 4.1.x
- **`@cloudflare/vitest-plugin`**, not the deprecated `@cloudflare/vitest-pool-workers`: `defineWorkersConfig` is
  gone; use `cloudflareTest({wrangler: {configPath: "./wrangler.jsonc"}})` inside vitest's `defineConfig`.
  Tests import `env` and `exports` from `cloudflare:workers` (use `exports.default.fetch()`, not `SELF`), and
  `runInDurableObject`, `runDurableObjectAlarm` and `evictDurableObject` from `cloudflare:test`.
- wrangler: exactly the version that plugin release pins
- **Chosen** (2 November 2026): `@cloudflare/vitest-plugin` 1.4.4, the newest release at least 14 days old (1.4.5
  was 13 days 22 h old), which pins wrangler 4.151.1; vitest 4.1.12; TypeScript 6.0.3.
- The lockfile: Node 22's own npm 10.9.4 crashes building this tree from scratch (an arborist error on vite's
  optional peers), so `package-lock.json` was made with `npx npm@11.21.0 install --before=2026-10-19`; `npm ci` with
  npm 10 installs from it. `package.json` `overrides` pin undici 7.30.1 and zod 4.6.7 (both at least 14 days old)
  to clear `npm audit` advisories in miniflare and the plugin.
- Types come from `wrangler types`, which writes `worker-configuration.d.ts` (committed), plus
  `@cloudflare/vitest-plugin/types` in tsconfig.

**Scripts:**

| Script | Command |
| --- | --- |
| `test` | `vitest run --project unit --project integration` |
| `test:ws` | `vitest run --project ws --max-workers=1 --no-isolate` |
| `typecheck` | `tsc --noEmit` |
| `build` | `wrangler deploy --dry-run --outdir dist` |
| `dev` | `wrangler dev` |
| `types` | `wrangler types --strict-vars=false` |

Plain `wrangler types` types each variable as its literal default (for example `TEST_MODE: "0"`), which the code
can't compare against other values. The WebSocket tests run separately, without isolation: under per-file storage
isolation a Durable Object that holds open WebSockets doesn't answer RPC (`stub.method()`) until its sockets are
gone.

`wrangler.jsonc`:

```jsonc
{
  "$schema": "./node_modules/wrangler/config-schema.json",
  "name": "satmap-groups",
  "main": "src/index.ts",
  "compatibility_date": "2026-08-01",
  "compatibility_flags": ["nodejs_compat"],
  "observability": { "enabled": false },
  "durable_objects": { "bindings": [
    { "name": "GROUP", "class_name": "GroupDO" },
    { "name": "PRO", "class_name": "ProDO" },
    { "name": "ADMIN", "class_name": "AdminDO" }
  ] },
  "migrations": [{ "tag": "v1", "new_sqlite_classes": ["GroupDO", "ProDO", "AdminDO"] }],
  "r2_buckets": [{ "binding": "ARCHIVE", "bucket_name": "satmap-groups-archive" }],
  "ratelimits": [
    { "name": "RL_CREATE", "namespace_id": "1001", "simple": { "limit": 3, "period": 60 } },
    { "name": "RL_INVITE", "namespace_id": "1002", "simple": { "limit": 10, "period": 60 } }
  ],
  "vars": { /* every variable in §7.8 with its default */ }
}
```

Notes on the config:

- `2026-08-01` works with every tool, and is after 2026-04-07, so close frames are answered automatically.
- Use `migrations`, not the newer `exports` key. **[Unconfirmed]** which wrangler first supports `exports`.
- SQLite storage is fixed when a namespace is created, so all three classes are SQLite from the start.
- `observability` is off by default because invocation logs record URLs, which hold group ids and invite tokens. The
  owner may switch it on later with invocation logs off (§7.9).
- Rate limiting: wrangler's schema allows only `period` 10 or 60, and one limit per binding, hence two bindings.
  `namespace_id` is any positive integer string chosen in the config, unique per account; nothing is created in
  the dashboard. When a binding is missing, E1, E9 and E10 answer `500 internal` and log
  `{"t":"cfg","missing":"RL_CREATE"}`, except under `TEST_MODE=1`, where a missing binding means "no limit". The
  test runtime supports the bindings, and the tests exercise both for real (§14 U3, closed).

### 7.2 Worker routing

| Path | Handler |
| --- | --- |
| `/j/{token}`, `/.well-known/*`, `/terms`, `/privacy`, `/` | `src/web/*`, static, in the Worker |
| `POST /v1/groups` | Read the body, take `groupId`, then `env.GROUP.get(env.GROUP.idFromName(groupId), {locationHint: "afr"})` |
| `/v1/groups/{groupId}/…` (including `/ws`) | The same DO, `stub.fetch(request)` with the original request (headers and body) |
| `POST /v1/invites/preview`, `/redeem` | Decode `token` (43 chars → 32 bytes); its first 16 bytes are the `groupId`; that DO |
| `POST /v1/store/apple` | Verify the JWS in the Worker, take `originalTransactionId`, then `env.PRO.idFromName("apple:" + id)` → `notifyApple()` |
| `POST /v1/store/google` | Check the token, decode, then `env.PRO.idFromName("google:" + b64url(sha256(purchaseToken)))` → `notifyGoogle()` |
| `/v1/admin/*` | Check `ADMIN_TOKEN`, then the AdminDO (`idFromName("admin")`) or the group's DO (admin RPC methods) |
| `GET /v1/health` | Worker |
| anything else | `404 not_found` |

- The location hint `afr` has effect only when an object is created. It is best effort: Cloudflare does not
  currently create objects in Africa and falls back to a nearby region.
- There is no South African jurisdiction option. This is noted in the privacy notice (§11.1).

### 7.3 GroupDO

**State.** Each group is one DO named by its `groupId` text. A DO with no `grp` row has never been created; any
request but E1, E9 or E10 to it answers `404 group_not_found` (and E9/E10 answer `invite_not_found`). The tables are
created by E1; the constructor runs migrations (inside `ctx.blockConcurrencyWhile`) only when a group exists, so a
request to an unknown id never leaves a stored object behind.

**Schema** (`schema_version` 1):

```sql
CREATE TABLE IF NOT EXISTS grp (
  one INTEGER PRIMARY KEY CHECK (one = 1),
  group_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('active','paused','deleted')),
  paused_reason TEXT, paused_at INTEGER, delete_at INTEGER, deleted_at INTEGER,
  profile BLOB, profile_seq INTEGER NOT NULL DEFAULT 0,
  join_proof_hash BLOB,
  seq INTEGER NOT NULL DEFAULT 0,
  min_cursor INTEGER NOT NULL DEFAULT 0,
  pro_name TEXT, pro_platform TEXT, pro_product TEXT,
  pro_expires_at INTEGER,          -- NULL with pro_name set = lifetime
  schema_version INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS members (
  member_id TEXT PRIMARY KEY,
  pass_hash BLOB NOT NULL UNIQUE,
  role TEXT NOT NULL CHECK (role IN ('owner','admin','member')),
  joined_at INTEGER NOT NULL,
  profile BLOB NOT NULL,
  seq INTEGER NOT NULL,
  terms_version INTEGER NOT NULL,
  last_live_at INTEGER
);
CREATE INDEX IF NOT EXISTS members_seq ON members(seq);
CREATE TABLE IF NOT EXISTS gone_passes (pass_hash BLOB PRIMARY KEY, reason TEXT NOT NULL, at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS invites (
  invite_id TEXT PRIMARY KEY,
  secret_hash BLOB NOT NULL UNIQUE,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('pending','used','cancelled')),
  used_by TEXT, used_pass_hash BLOB, used_at INTEGER     -- used_at: when it left 'pending' (used or cancelled)
);
CREATE TABLE IF NOT EXISTS chunks (
  member_id TEXT NOT NULL, start INTEGER NOT NULL, rec BLOB NOT NULL,
  seq INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  PRIMARY KEY (member_id, start)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS chunks_seq ON chunks(seq);
CREATE INDEX IF NOT EXISTS chunks_start ON chunks(start);
CREATE TABLE IF NOT EXISTS archives (
  member_id TEXT NOT NULL, month TEXT NOT NULL, count INTEGER NOT NULL, bytes INTEGER NOT NULL,
  first_start INTEGER NOT NULL, last_start INTEGER NOT NULL, seq INTEGER NOT NULL,
  max_chunk_seq INTEGER NOT NULL, archived_at INTEGER NOT NULL,      -- §6.3 archive item
  PRIMARY KEY (member_id, month)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS archives_seq ON archives(seq);
CREATE TABLE IF NOT EXISTS pins (
  pin_id TEXT PRIMARY KEY, member_id TEXT NOT NULL, created_at INTEGER NOT NULL,
  expires_at INTEGER, rec BLOB NOT NULL, seq INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS pins_seq ON pins(seq);
CREATE INDEX IF NOT EXISTS pins_member ON pins(member_id);
CREATE INDEX IF NOT EXISTS pins_expiry ON pins(expires_at) WHERE expires_at IS NOT NULL;
CREATE TABLE IF NOT EXISTS removals (
  seq INTEGER PRIMARY KEY, what TEXT NOT NULL CHECK (what IN ('pin','member','trail')),
  id TEXT NOT NULL, at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS removals_id ON removals(what, id);   -- SD24 and report look-ups
CREATE TABLE IF NOT EXISTS live (member_id TEXT PRIMARY KEY, at INTEGER NOT NULL, rec BLOB NOT NULL) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS pause_log (paused_at INTEGER PRIMARY KEY, resumed_at INTEGER, reason TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS transfer (
  one INTEGER PRIMARY KEY CHECK (one = 1), to_member TEXT NOT NULL,
  offered_at INTEGER NOT NULL, expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS jobs (kind TEXT PRIMARY KEY, at INTEGER NOT NULL, tries INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS r2_deletes (prefix TEXT PRIMARY KEY, at INTEGER NOT NULL);   -- §7.5
CREATE TABLE IF NOT EXISTS counters (key TEXT PRIMARY KEY, window_start INTEGER NOT NULL, count INTEGER NOT NULL);
```

**Rules:**

- **Change numbers:** every write that clients must see takes `seq = grp.seq + 1` and stores it in its row, in the
  same `ctx.storage.transactionSync(() => …)` that writes the row. `BEGIN` is not allowed in DO SQLite.
- **Auth:**
  1. `pass_hash = SHA-256(base64url-decoded Bearer)`, then look it up in `members`.
  2. If not found, look in `gone_passes`: reason `removed` gives `410 removed`.
  3. Otherwise `401 unauthorized`.
  4. A deleted group (`status = 'deleted'`) answers `410 group_deleted` before auth.
- **Hash comparisons:** proof and secret hashes are compared with `crypto.subtle.timingSafeEqual`.
- **Time:** `now()` returns `this.clockOverride ?? Math.floor(Date.now()/1000)`. Tests set `clockOverride` through
  `runInDurableObject`. Outgoing store calls go through `this.httpFetch` (default `fetch`), which tests replace
  the same way. ProDO has the same two fields.
- **Live positions** are held in a memory map. They are written to the `live` table at most once per 60 s per
  member, and always when deleted, so an evicted DO comes back at most 60 s stale. `members.last_live_at` is
  updated at the same moments; when the live record is deleted it keeps the last persisted time (up to 60 s old).
- **Pause** (one transaction), then a broadcast of the status and live `rec: null` for everyone:
  - `status='paused'`, `paused_reason`, `paused_at=now`, `delete_at=now+2592000`
  - insert into `pause_log`
  - `jobs('purge', delete_at)`
  - delete all live rows and the memory map
- **Resume:** `status='active'`, clear the pause fields, set `resumed_at` in `pause_log`, delete the `purge` job,
  broadcast.
- **Socket attachments** (≤ 16 KiB): `{memberId, watching, cursor, helloAt, openedAt, w, st, more}` (§6.6). On
  wake from hibernation the DO rebuilds watcher counts from `ctx.getWebSockets()`.

### 7.4 Alarms

A DO has one alarm. `jobs` holds the next time for each kind. After every write that changes a job, and at the end
of `alarm()`, the DO sets the alarm to the earliest of `MIN(jobs.at)` and `MIN(pins.expires_at)`, in milliseconds.
A DO with no `alarm()` method cannot set an alarm, so GroupDO, ProDO and AdminDO all define one.

**Errors.** The platform retries a throwing `alarm()` only a few times, then drops it, which would silently stop a
deletion (R8). So `alarm()` runs each due job inside its own `try`/`catch` and never throws:

- A failed `purge`, `r2_delete`, `pro_check` or archive pass (`archive_more`) stays in `jobs` with `tries + 1` and
  `at` = now + 15 min after the first failure, then now + 1 h after each later one. It logs
  `{"t":"job","kind":…,"err":…}` without ids.
- `r2_delete`, the archive pass and `purge` run only inside `alarm()` (the platform never runs two `alarm()` calls
  of one object at once); requests only queue prefixes in `r2_deletes` and never write to R2.
- A job that succeeds resets `tries` to 0.
- `alarm()` always sets the next alarm before it returns, in a `finally`.

| Job | When | What it does |
| --- | --- | --- |
| pin expiry | `MIN(pins.expires_at)` | Delete expired pins; add `removed pin` items; broadcast |
| `pro_check` | `pro_expires_at` (subscriptions, and Sandbox or test purchases, SD20) | Ask the ProDO `check()`. **Active with an expiry after now + 60:** store it and reschedule at that expiry. **Active with an expiry at or before now + 60** (Google at the renewal instant still says ACTIVE with the old `expiryTime`): check again every 10 min, up to 6 re-checks; if the 7th check still says so, pause `pro_unverified`. **Inactive:** pause with its reason. **Unknown** (the store failed): pause `pro_unverified` (SD8) and set `pro_retry`. |
| `pro_retry` | every 30 min for 24 h after a `pro_unverified` pause, then every 6 h | `check()` again; active resumes |
| `transfer` | `transfer.expires_at` | Delete the expired offer; broadcast the status |
| `r2_delete` | at once after a row is added to `r2_deletes`, then as rescheduled | For each `r2_deletes` row: list the prefix and delete in batches of up to 1000 keys; remove the row only when a list of the prefix comes back empty |
| `purge` | `delete_at` (paused), or `deleted_at + 2592000` (deleted) | List and delete every R2 object under `g/{groupId}/` until a list comes back empty; only then detach from the ProDO, `ctx.storage.deleteAlarm()`, then `ctx.storage.deleteAll()`. An R2 error leaves SQLite untouched and the job rescheduled (above). |
| `daily` | once a day at a minute between 01:00 and 04:00 UTC fixed per group: 01:00 + (FNV-1a of the `groupId` text mod 180) minutes (the id is random, so the minutes spread evenly; a retried run finds the same minute without a column) | First the retention deletes, in SQLite: `removals` older than 400 days (set `min_cursor` to the largest deleted `seq`); `gone_passes` older than 30 days; used and cancelled invites 30 days after `used_at`, pending ones 30 days after `expires_at`; `pause_log` rows that ended more than 30 days ago; `live` rows older than 30 days (and clear `last_live_at` for them). Then the archive pass (§7.5); its R2 errors never delay the retention deletes (R8) and are retried through `archive_more` under the error rule above. For a lifetime entitlement, ask `check()` on the 1st of each month. |
| `archive_more` | 60 s after an archive pass that hit its batch limit | Continue the pass |

**ProDO and AdminDO alarms.** ProDO: `check()` 60 s after `expires_at` (a second safeguard beside each group's
`pro_check`), and self-deletion (`deleteAlarm()`, `deleteAll()`): each write or `detach()` that leaves it with no
groups sets it to 30 days later; an `attach()` in between cancels it. AdminDO: the daily report clean-up (§7.7).
Both use the same error rule.

### 7.5 R2 archive

- **Bucket binding:** `ARCHIVE`. Keys look like `g/{groupId}/t/{memberId}/{YYYY-MM}.bin` (§4.9 format). Nothing else
  goes in R2: reports live in the AdminDO.
- **Archive pass:** take chunks with `start < now − 2592000` (30 days), grouped by member and month, at most 50
  member-months per pass. A member whose prefix is in `r2_deletes` is skipped until that row is gone. For each
  member-month:
  1. Note `startSeq = grp.seq` and the `seq` of each row read.
  2. Read the existing object, if any. Merge the chunks in; a SQLite row replaces an archived record for the same
     window. Write the object back.
  3. Then, in one transaction (R2 calls let other requests run in between, so this re-checks):
     - If the member no longer exists, or `removals` has a `trail` or `member` row for them with `seq > startSeq`
       (they cleared their trail, left or were removed meanwhile): write no `archives` row, delete nothing else,
       and insert the member's prefix into `r2_deletes`, so the object just written is deleted too.
     - Otherwise: delete only the rows whose `seq` is still the one read in step 1 (a row replaced meanwhile by a
       late upload stays for the next pass), and upsert the `archives` row with a new `seq`, `max_chunk_seq` (§6.3)
       and `archived_at = now`.
- **Late uploads** for an archived month go into SQLite as usual and are merged into the month by a later pass.
- **Deleting** never calls R2 inside a request. Clear trail (E22), leave and remove (E14) insert the prefix
  `g/{groupId}/t/{memberId}/`, and E4 the prefix `g/{groupId}/`, into `r2_deletes` in the same transaction that
  deletes the rows; the `r2_delete` job (§7.4) empties them and keeps retrying until a list comes back empty. Purge
  deletes `g/{groupId}/` itself before `deleteAll()`.
- **Reading (E20):** read the object, keep windows with `start + 600 > viewer.joinedAt`, and return a new object.
  While the member's prefix is in `r2_deletes`, E20 for that member answers `404 not_found`.

### 7.6 Pro entitlements

**ProDO.** One per purchase, named:

- Apple: `apple:{originalTransactionId}`
- Google: `google:{b64url(SHA-256(purchaseToken))}`

Tables:

- `ent` (one row): `platform, product_id, kind ('subscription'|'lifetime'), environment, active, expires_at,
  grace_until, revoked_at, refunded (0/1, sticky), state_at, checked_at, store_state, transaction_id,
  purchase_token` (Google only)
  - `state_at` is the time of the newest information applied: a proof's or notification's `signedDate` (Apple, in
    seconds), an RTDN's `eventTimeMillis` (Google), or the time of a store API answer.
  - `expires_at` for a Sandbox or test purchase is at most its original purchase date + 1 209 600 (14 days,
    SD20), also for Lifetime.
- `groups(group_id PRIMARY KEY, attached_at)`
- `seen(notification_id PRIMARY KEY, at)` (kept 30 days)

RPC methods:

| Method | What it does |
| --- | --- |
| `attach(name, groupId, proof)` | Ask the store about the proof's purchase (`check`, below; a Google token reaches the ProDO only inside the proof), apply the answer to `ent` (ordering rule below), check the group count (SD9) and add the group. Return `{active, reason, expiresAt, productId, platform, name}`; the caller answers `402`, `409` or `503` when it isn't `ok`. |
| `detach(groupId)` | Remove the group; with no groups left, schedule self-deletion (§7.4) |
| `releaseGroups()` | Remove and return every group: they move to the purchase that replaced this one (Google `linkedPurchaseToken`) |
| `check(callerGroupId?)` | Ask the store API and store the answer (`state_at` = now). Every attached group but `callerGroupId` hears about a change. Returns `unknown: true` only when the store could not be asked. |
| `notifyApple(name, notification)`, `notifyGoogle(name, rtdn)` | Drop duplicates by notification id. Apply it (ordering rule below), or call `check()` (Google always: the API answer decides, never the message; Apple when the state can't be derived). Push to the groups. |

**Ordering rule** (so an old proof can't undo a refund, R7):

- `attach()`, `notifyApple()` and `notifyGoogle()` ignore any proof or notification whose time is at or before
  `state_at`; `ent` keeps the newer state.
- Refunded or revoked is sticky: only a newer `REFUND_REVERSED` notification or a store API answer clears it.
- E1, E5 and E17 ask the store (inside `attach()`) after the proof verifies, and use the API's answer (the proof
  only names the purchase). Under `TEST_MODE=1` with no API credentials, the verified proof is used as is.
  Xcode-environment purchases have no App Store Server API, so their verified proof is always used as is (reachable
  only with `APPLE_ALLOW_XCODE` under `TEST_MODE=1`). An Apple notification that arrives when the server has no API
  credentials, outside `TEST_MODE`, is logged (`{"t":"cfg",…}`) and changes nothing.
- **When state changes,** the ProDO calls `proChanged(name, state)` on each attached group's DO (by RPC). The group
  ignores it unless `name` is its current `pro_name`.

**Proofs are bearer credentials** (§14 R5). Whoever holds a signed transaction or purchase token can attach up to 20
groups to that purchase. Accepted for v1: there are no accounts to bind a purchase to.

**Apple JWS verification** (`apple.ts`; pure functions, the same checks as Apple's `app-store-server-library`
`SignedDataVerifier`). `verifyJws(jws)` checks the signature and chain without calling Apple:

1. The header has `alg` `ES256` and `x5c` with exactly 3 base64 DER certificates: leaf, intermediate, root.
2. The root must equal the pinned Apple Root CA - G3 byte for byte (SHA-256
   `63343ABFB89A6A03EBB57E9B3F5FA7BE7C4F5C756F3017B3A8C488C3653E9179`). Tests pass another root through the
   variable `APPLE_ROOT_OVERRIDE_B64`, which is honoured only when `TEST_MODE=1`.
3. Check the chain with `node:crypto` `X509Certificate`: `inter.ca === true`,
   `inter.checkIssued(root) && inter.verify(root.publicKey)`, then `leaf.checkIssued(inter) &&
   leaf.verify(inter.publicKey)`.
4. Parse `tbsCertificate.extensions` of the leaf and the intermediate with a small DER reader (SEQUENCE, the
   `[3]` explicit tag, OCTET STRING): the leaf must have an extension whose `extnID` is
   1.2.840.113635.100.6.11.1 (DER `060a2a864886f76364060b01`), the intermediate one whose `extnID` is
   1.2.840.113635.100.6.2.1 (`060a2a864886f76364060201`). Searching the raw bytes is not enough: the same bytes
   could sit in a name or a key.
5. Each certificate must be valid at the payload's `signedDate`, ±60 s.
6. Verify the ES256 signature over `header.payload`: import the leaf's SPKI into WebCrypto ECDSA P-256 / SHA-256.
   JWS signatures are already raw r‖s.

Any failure is `402 pro_invalid` (or `400` for E27). Then the payload checks depend on what was signed:

- **A transaction** (E1, E5, E17, and inside notifications) — `checkTransaction`:
  - `bundleId == APPLE_BUNDLE_ID`
  - `productId` in `PRO_PRODUCTS_APPLE`
  - `environment` is `Production`; or `Sandbox` while `ALLOW_TEST_PURCHASES=true`; or `Xcode` only when
    `APPLE_ALLOW_XCODE=true` and `TEST_MODE=1` (SD20)
  - `inAppOwnershipType == "PURCHASED"` (Family Sharing off)
  - `type` `Auto-Renewable Subscription` for `pro.monthly` and `pro.yearly`, `Non-Consumable` for `pro.lifetime`
  - any failure is `402 pro_invalid`
- **The active decision** (`decideTransaction`, separate, because notifications must accept expired and revoked
  transactions): for subscriptions, active means no `revocationDate` and `expiresDate` (ms) > now; for lifetime,
  no `revocationDate`; a Sandbox purchase also needs `originalPurchaseDate` + 14 days > now (SD20). E1, E5 and E17 answer
  `402 pro_inactive` when not active.
- **A notification** (E27; the outer `signedPayload`):
  - `data.bundleId == APPLE_BUNDLE_ID` and `data.environment` allowed as above
  - in `Production`, `data.appAppleId == APPLE_APP_APPLE_ID` (the app's numeric Apple ID; when it is unset,
    Production notifications fail with 400)
  - `data.signedTransactionInfo` passes `verifyJws` and `checkTransaction` (not `decideTransaction`)
  - `data.signedRenewalInfo`, when present, passes `verifyJws`, and its `environment` and
    `originalTransactionId` equal the transaction's (it has no bundle id or type)

**App Store Server API** (`APPLE_ISSUER_ID`, `APPLE_KEY_ID` and `APPLE_PRIVATE_KEY`; required: without them Apple
proofs answer `503 store_unavailable`, unless `TEST_MODE=1`):

- **Host:** `https://api.storekit.apple.com`, or `https://api.storekit-sandbox.apple.com` for Sandbox purchases.
  These are the hosts recommended since 2026-05-05.
- **Token:** a JWT with header `{alg:"ES256", kid, typ:"JWT"}` and claims
  `{iss, iat, exp: iat+1800, aud:"appstoreconnect-v1", bid}`, signed with the .p8 key through WebCrypto
  (pkcs8, ECDSA P-256), sent as `Authorization: Bearer`.
- **Subscriptions:** `GET /inApps/v1/subscriptions/{originalTransactionId}`. Each signed item in the answer goes
  through `verifyJws` and `checkTransaction`.

  | Status | Meaning | Result |
  | --- | --- | --- |
  | 1 | active | active; expiry from the signed transaction's `expiresDate` |
  | 4 | billing grace period | active; expiry from the renewal info's `gracePeriodExpiresDate` |
  | 2 | expired | inactive, `pro_ended` |
  | 3 | billing retry | inactive, `pro_ended` |
  | 5 | revoked | inactive, `pro_refunded` |

- **Lifetime:** `GET /inApps/v1/transactions/{transactionId}`. A `revocationDate` means inactive, `pro_refunded`.
- An API answer of 404 for the id means `402 pro_invalid`; 401, 429 and 5xx mean "unknown" (`503
  store_unavailable` at E1, E5, E17).

**App Store Server Notifications V2 (E27).**

- Verify as above. Drop duplicates by `notificationUUID`. `TEST` answers 200 and does nothing.
- Call `check()` (the credentials are required). Only under `TEST_MODE=1` without credentials, derive the state
  from the notification, subject to the ordering rule (`signedDate` against `state_at`):

  | Notification | Result |
  | --- | --- |
  | `SUBSCRIBED`, `DID_RENEW`, `REFUND_REVERSED`, `RENEWAL_EXTENDED`, `ONE_TIME_CHARGE` | active, with the transaction's `expiresDate` (none for lifetime) |
  | `DID_FAIL_TO_RENEW` with subtype `GRACE_PERIOD` | active until `gracePeriodExpiresDate` |
  | `DID_FAIL_TO_RENEW` with no subtype | inactive, `pro_ended` |
  | `EXPIRED` (any subtype) | inactive, `pro_ended` |
  | `GRACE_PERIOD_EXPIRED` | inactive, `pro_ended` |
  | `REFUND`, `REVOKE` | inactive, `pro_refunded` (sticky) |
  | anything else | no change |

**Google** (`GOOGLE_SERVICE_ACCOUNT_JSON`; required: without it Google proofs answer `503 store_unavailable`).

- **`ALLOW_UNVERIFIED_PRO=true`** is honoured only when `TEST_MODE=1` (for the local check, §12.6). Then any
  Google proof with a well-formed token counts as `productId` `pro`, yearly, active, expiring now + 31 536 000,
  without calling Google.
- **Token shape:** a purchase token must match `^[A-Za-z0-9._-]{20,1024}$`, or `402 pro_invalid` without any store
  call.
- **Negative cache:** the token's ProDO remembers in memory (not in storage) a refusal (`pro_invalid` or
  `pro_inactive`) for 10 minutes and answers it again without calling Google. A junk token leaves no stored ProDO
  (nothing is written unless the proof verifies).
- **Access token:**
  - Sign a JWT `{iss: client_email, scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token", iat, exp: iat+3600}` with RS256 (WebCrypto pkcs8
    RSASSA-PKCS1-v1_5).
  - `POST https://oauth2.googleapis.com/token` with
    `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=…`.
  - Cache the token in module memory until 60 s before `expires_in`.
- **API errors:** 400, 404 or 410 for a token mean `402 pro_invalid`; 401, 403, 429 and 5xx mean "unknown"
  (`503 store_unavailable` at E1, E5, E17).
- **Subscription** (`productId` `pro`):
  `GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{ANDROID_PACKAGE}/purchases/subscriptionsv2/tokens/{token}`.
  The v1 `purchases.subscriptions.get` no longer exists.
  - Every `lineItems[].productId` must equal the proof's `productId` (`pro`), or `402 pro_invalid`.
  - Active when `subscriptionState` is `SUBSCRIPTION_STATE_ACTIVE` or `SUBSCRIPTION_STATE_IN_GRACE_PERIOD`, or
    `SUBSCRIPTION_STATE_CANCELED` with the latest `lineItems[].expiryTime` in the future.
  - Expiry = the latest `lineItems[].expiryTime`.
  - In grace, also re-check every 6 h.
  - `SUBSCRIPTION_STATE_ON_HOLD`, `_PAUSED` and `_EXPIRED` mean inactive, `pro_ended`.
    `SUBSCRIPTION_STATE_PENDING` means `402 pro_inactive`. Every other value, including
    `SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED`, `SUBSCRIPTION_STATE_UNSPECIFIED` and values added later, means
    inactive (`pro_ended`; `402 pro_inactive` at E1, E5, E17).
  - `testPurchase` present means a test purchase (SD20: accepted only while `ALLOW_TEST_PURCHASES=true`, and capped
    at 14 days).
  - When `linkedPurchaseToken` names a ProDO that has groups, those groups move to the new ProDO.
- **Lifetime** (`productId` `pro_lifetime`): `GET …/purchases/productsv2/tokens/{token}`.
  - Some `productLineItem[].productId` must equal `pro_lifetime`, or `402 pro_invalid` (otherwise a cheaper
    one-time product's token could be sent as Lifetime).
  - Active when `purchaseStateContext.purchaseState` is `PURCHASED`; `CANCELLED` means inactive, `pro_refunded`;
    every other value means inactive.
  - A test purchase is treated as in SD20.
- **Acknowledging** is done by the app (`acknowledgePurchase`), so a server outage can't cause the automatic
  3-day refund. The server never acknowledges.
- **Real-time developer notifications (E28):**
  - Decode `message.data` (base64) to JSON. Drop duplicates by `message.messageId`.
  - `subscriptionNotification`, `oneTimeProductNotification` and `voidedPurchaseNotification`: route by
    `purchaseToken`, then `check()`. A voided purchase is not trusted on the notification's word: the API answer
    decides (a refunded one-time product reads `CANCELLED`, a revoked subscription `EXPIRED`).
  - `testNotification`: 204.
  - Pub/Sub's OIDC token is not checked in v1. The secret token in the URL (at least 32 characters, E28) is the
    check, and every state change is confirmed with the API.

### 7.7 Reports and the admin endpoints

**Reports.**

- E25 stores a report in the AdminDO (`idFromName("admin")`) table:

  ```
  reports(report_id PK, created_at, group_id, reporter_id, target_kind, target_id, reason, comment, content_json,
          target_exists (0/1), target_member_id, target_time, target_bytes,
          status ('open'|'resolved'), resolved_at, action, admin_note)
  ```

  The `target_*` columns are the server's own facts from E25: whether the target still existed, the member it
  belongs to (pin dropper or member; null for `group`), the pin's `createdAt` or the member's `joinedAt`, and the
  stored envelope's length.
- **`content` is the reporter's claim** (SD28). The server can't decrypt the stored record, so a member could type
  words "from" someone else. Before removing a member (A4) or deleting a group (A5) because of what a report says,
  the developer needs corroboration: reports from more than one member (`reporters` in A1), a matching
  `target_bytes` (the padded size class of the claimed text, §2.6), or the reporter's screenshot by e-mail. A
  single uncorroborated report leads at most to deleting the reported pin (A3), and the reporter can block.
- The daily alarm deletes resolved reports 180 days after they were resolved, and open reports older than 365
  days.
- The AdminDO POSTs `{"reportId", "createdAt", "reason", "targetKind"}` (no content) to `REPORT_WEBHOOK_URL`. The
  owner points it at an e-mail or chat relay. S13 promises a review within 24 hours, so either this webhook or a
  written daily A1 routine is a release blocker (§14 R1).
- **Deletion and access requests** by e-mail quote the support code `{groupId}.{memberId}` that S5 shows. The
  developer can delete that member's data in the group with A4 (the data is encrypted, so an access request can
  only be answered with what A6 and the server's metadata show).

**Admin endpoints** (`Authorization: Bearer {ADMIN_TOKEN}`, compared in constant time; every admin path answers
`404` when `ADMIN_TOKEN` is unset):

| # | Endpoint | Result |
| --- | --- | --- |
| A1 | `GET /v1/admin/reports?status=open\|resolved\|all&limit=100&before={createdAt}` | `{"reports": [{reportId, createdAt, groupId, reporterId, targetKind, targetId, reason, comment, content, facts: {targetExists, targetMemberId, targetTime, targetBytes}, reporters, status, resolvedAt, action, adminNote}]}`, newest first. `reporters` is the number of distinct reporters of the same group and target. |
| A2 | `POST /v1/admin/reports/{reportId}/resolve` `{"action": "none"\|"pin_deleted"\|"member_removed"\|"group_deleted", "note": "…"}` | 200 |
| A3 | `DELETE /v1/admin/groups/{groupId}/pins/{pinId}` | 204 (as E24, any pin) |
| A4 | `DELETE /v1/admin/groups/{groupId}/members/{memberId}` | 204 (as E14; `409 not_allowed` for the owner: delete the group instead) |
| A5 | `DELETE /v1/admin/groups/{groupId}` | 204 (as E4) |
| A6 | `GET /v1/admin/groups/{groupId}` | `{"status", "createdAt", "pausedAt", "memberCount", "databaseBytes", "pro": {platform, productId, expiresAt}}`. Metadata only. |

`server/README.md` documents these with `curl` examples, and the deploy steps (§13.4).

### 7.8 Configuration and secrets

**Variables** (in `wrangler.jsonc` `vars`; defaults shown):

| Name | Default | Use |
| --- | --- | --- |
| `PUBLIC_BASE_URL` | `https://example.invalid` | The domain (§5.1) |
| `CONTACT_EMAIL` | `groups@example.invalid` | Pages and landing footer |
| `APP_STORE_URL`, `PLAY_STORE_URL` | `""` | Landing page links |
| `APPLE_BUNDLE_ID` | `com.jacobusoettle.satmap` | JWS check |
| `APPLE_APP_IDS` | `TEAMID.com.jacobusoettle.satmap` | AASA |
| `ANDROID_PACKAGE` | `com.jacobusoettle.satmap` | assetlinks; Google API path |
| `ANDROID_CERT_SHA256` | `""` | assetlinks fingerprints, comma-separated |
| `PRO_PRODUCTS_APPLE` | `pro.monthly,pro.yearly,pro.lifetime` | |
| `PRO_PRODUCTS_GOOGLE` | `pro,pro_lifetime` | `pro` = subscription, `pro_lifetime` = one-time |
| `APPLE_APP_APPLE_ID` | `""` | The app's numeric Apple ID, checked on Production notifications (§7.6); a release blocker |
| `MAX_GROUPS_PER_PURCHASE` | `20` | SD9 |
| `TERMS_VERSION` | `1` | Lowest accepted `termsVersion` (`409 terms_outdated` below it) |
| `ALLOW_TEST_PURCHASES` | `true` | Accept App Store Sandbox and Google test purchases, capped at 14 days (SD20) |
| `APPLE_ALLOW_XCODE` | `false` | SD20; honoured only when `TEST_MODE=1` |
| `ALLOW_UNVERIFIED_PRO` | `false` | §7.6; honoured only when `TEST_MODE=1` |
| `TEST_MODE` | `0` | Enables the test-only variables, and lets missing rate-limit bindings and Apple API credentials pass |
| `APPLE_ROOT_OVERRIDE_B64` | `""` | Tests only; honoured only when `TEST_MODE=1` |

**Secrets** (`wrangler secret put` when deploying; `.dev.vars` locally, never committed; `.dev.vars.example` lists
them empty):

| Name | Use |
| --- | --- |
| `ADMIN_TOKEN` | Admin endpoints (32+ random characters) |
| `APPLE_ISSUER_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY` | App Store Server API (required for Apple purchases; without them Apple proofs answer `503 store_unavailable` unless `TEST_MODE=1`) |
| `GOOGLE_SERVICE_ACCOUNT_JSON` | Play Developer API (required for Google purchases) |
| `GOOGLE_RTDN_TOKEN` | Secret in the RTDN push URL, 32+ random characters (E28 answers 404 without it) |
| `REPORT_WEBHOOK_URL` | Report alert; this or a written daily A1 routine is a release blocker (§7.7) |

**Bindings:** `GROUP`, `PRO`, `ADMIN` (Durable Objects), `ARCHIVE` (R2), `RL_CREATE` and `RL_INVITE` (rate
limiting, required outside `TEST_MODE=1`, §7.1).

### 7.9 Logging without personal data

- One JSON line per request: `{"t":"req","route":"POST /v1/groups/:gid/upload","status":200,"ms":12}`, plus
  `"err":"<code>"` on errors.
- Store calls: `{"t":"store","platform":"apple","op":"status","status":200}`.
- Never log IPs, ids, tokens, passes, proofs, JWS, purchase tokens, user agents, bodies or record sizes per member.
- Exceptions are logged as `{"t":"exc","route":…,"name":…,"message":…}` with any id-shaped substring (22 or 43
  base64url characters) replaced by `…`.
- Cloudflare observability stays off (§7.1) until the owner chooses to enable it with invocation logs off.

### 7.10 Retention summary (server)

| Data | Kept until |
| --- | --- |
| Trail chunks and archives | Forever while the group exists, unless the member clears them, leaves or is removed |
| Pins | Animal: 12 h. Others: until deleted, or the dropper leaves or is removed. |
| Live positions | Replaced by the next one; deleted on stop or pause; not returned after 24 h; rows older than 30 days pruned |
| Member rows | Until the member leaves or is removed |
| `removals` (ids of removed pins, trails and members, with times) | 400 days, so offline phones can catch up (E19) |
| Archived trail objects of a member who cleared, left or was removed | Deleted at once; retried until R2 confirms (`r2_deletes`, §7.5) |
| `gone_passes` | 30 days |
| Invites | 30 days after use or cancellation (`used_at`); pending ones 30 days after expiry |
| Paused groups | Everything deleted 30 days after `pausedAt` unless resumed (R8) |
| Deleted groups | Content deleted at once; the deleted marker (status, `deleted_at`, `pro_name`) after 30 days |
| Reports | 180 days after being resolved; open reports at most 365 days |
| ProDO purchase records | While any group is attached; a ProDO with no groups deletes itself 30 days later (§7.4) |
| Report facts (`target_*`) | With the report |

---

## 8. Client behaviour (both apps)

### 8.1 Where the code lives

- **Core** holds all group logic, so it is tested on Linux and the JVM and is the same on both phones: wire
  formats, crypto, records, invite links, the word filter, API models, the local store, the sync engine
  (`GroupHub`) and the demo server.
- **The app layer** holds only what needs the platform:
  - the HTTP and WebSocket transport (URLSession / OkHttp)
  - the secret store (Keychain / Keystore)
  - the store purchases (StoreKit / Play Billing)
  - timers and lifecycle
  - the UI and map drawing
- **The app wrapper** is `GroupsModel`: an `ObservableObject` on iOS in `SatMap/Groups/GroupsModel.swift`, and
  `StateFlow`s on Android in `app/groups/GroupsModel.kt` (DESIGN rule D4: no public `MutableStateFlow`).
  `AppModel` owns one (`model.groups`), made in `AppModel.init`, because recording runs with no screen. All hub
  calls happen on the main thread; network calls are `async` / `suspend`.

### 8.2 Core API

The UI parts code against these names. Each core part first pushes all these types with compiling signatures
(bodies may `fatalError("todo")` / `TODO()`), so the UI parts can build while the bodies are written (§13.3).
Kotlin uses the same names, in package `com.jacobusoettle.satmap.core`.

```swift
// GroupWire.swift
enum Base64URL { static func encode(_ data: Data) -> String; static func decode(_ text: String) -> Data? }
enum GroupRandom { static func bytes(_ count: Int) -> Data }
enum MicroDegrees { static func from(_ degrees: Double) -> Int32; static func degrees(_ e6: Int32) -> Double }
enum GroupTextField { case groupName, displayName, animal, note, reportComment }
enum GroupText {
    static func clean(_ text: String, _ field: GroupTextField) -> String?   // §2.5; nil = invalid
    static func codePoints(_ text: String) -> Int
    static func initials(_ name: String) -> String                          // §9.5
}
enum GroupFormat { static func ago(_ seconds: Int64) -> String }           // §9.3 "Times"
enum GroupPolicy { /* the constants in §8.6, by those names */ }
enum LiveFix { static func position(lat: Double, lon: Double, accuracy: Double?, course: Double?, speed: Double?,
                                    heading: Double?, time: Double) -> LivePosition }   // §4.4 rounding

// GroupCrypto.swift
enum GroupCrypto {
    enum Failure: Error { case unsupportedVersion, unreadable, cipherFailed }
    static func subkey(groupKey: Data, groupId: Data, purpose: String) -> Data
    static func seal(_ plain: Data, key: Data, aad: String) throws -> Data
    static func seal(_ plain: Data, key: Data, aad: String, nonce: Data) throws -> Data  // tests only
    static func open(_ envelope: Data, key: Data, aad: String) throws -> Data
}
struct GroupKeys {
    let groupId: String                                   // 22-char text
    let group, member, pin, joinProof: Data               // group-wide (§3.3)
    init(groupKey: Data, groupId: Data)
    func trail(_ memberId: String) -> Data                // per member, derived on first use and cached
    func live(_ memberId: String) -> Data
}
enum GroupAAD {
    static func group(_ groupId: String) -> String
    static func member(_ groupId: String, _ memberId: String) -> String
    static func trail(_ groupId: String, _ memberId: String, start: Int64) -> String
    static func live(_ groupId: String, _ memberId: String, at: Int64) -> String
    static func pin(_ groupId: String, pinId: String, memberId: String, createdAt: Int64, expiresAt: Int64?) -> String
}

// GroupRecords.swift
struct TrailPoint: Equatable { var time: Int64; var lat: Int32; var lon: Int32; var startsSegment: Bool }
enum TrailChunk {
    static func windowStart(_ time: Int64) -> Int64
    static func encode(_ points: [TrailPoint], windowStart: Int64, compress: Bool) throws -> Data
    static func decode(_ plain: Data, windowStart: Int64) throws -> [TrailPoint]   // §4.2, exact inflate
}
struct LivePosition: Equatable {
    var time: Int64; var lat: Int32; var lon: Int32; var accuracy: Int?; var heading: Int?; var speed: Int?
    func encoded() -> Data
    static func decode(_ plain: Data) throws -> LivePosition
}
enum PinType: String, CaseIterable { case animal, shelter, water, danger, pothole, police }
struct PinRecord: Equatable {
    var type: String; var animal: String?; var note: String?; var lat: Int32; var lon: Int32
    var knownType: PinType? { PinType(rawValue: type) }
    func json() -> Data                                    // padded (§2.6)
    static func decode(_ json: Data) throws -> PinRecord
}
struct MemberProfile: Equatable { var name: String; var colour: Int; func json() -> Data; static func decode(_ json: Data) throws -> MemberProfile }
struct GroupProfile: Equatable { var name: String; func json() -> Data; static func decode(_ json: Data) throws -> GroupProfile }
enum Deflate {                                             // Deflate.swift (§4.2)
    static func raw(_ data: Data) -> Data?
    static func inflateExact(_ data: Data, size: Int) -> Data?
}

// InviteLink.swift
struct InviteLink: Equatable {
    let token: String; let groupId: Data; let groupKey: Data
    var groupIdText: String { get }
    func url(domain: String) -> String
    var schemeURL: String { get }
    enum Parsed: Equatable { case invite(InviteLink), missingKey, notInvite }
    static func parse(_ text: String, domain: String) -> Parsed
}

// WordFilter.swift
struct WordFilter { init(list: String); func allows(_ text: String) -> Bool; func masked(_ text: String) -> String }

// GroupAPI.swift: Codable models for every request, response and change item in §6, by the same names
// (GroupStatusDTO, MemberDTO, ChangeItem, ChangesPage, UploadRequest, UploadResult, LiveEntry, ProProof …),
// plus `struct GroupAPIError: Error { let status: Int; let code: String }`. Kotlin builds and reads the JSON by hand
// with kotlinx-serialization-json's JsonObject (no plugin), as SwiftJson does.

// GroupStore.swift: local SQLite (Swift: SQLite3 / CSQLite as MBTiles does; Kotlin: core SqlDriver,
// AndroidSqlDriver in the app, JdbcSqlDriver in tests). Schema in §8.3. Used only by GroupHub.
final class GroupStore { init(path: String) throws }

// GroupHub.swift: the sync engine
protocol GroupTransport: AnyObject {
    // One request to "/v1/…" under the base URL; pass = Bearer or nil. Throws GroupTransportError.offline when
    // there is no connection, or .failed for any other transport error. No headers reach the core: after a 429 the
    // hub waits a fixed 60 s (§6.7).
    func request(method: String, path: String, pass: String?, json: Data?) async throws -> (status: Int, body: Data)
}
enum GroupTransportError: Error { case offline, failed }
protocol GroupSocketFactory: AnyObject { func open(groupId: String, pass: String) -> GroupSocket }   // URL from the base URL
protocol GroupSocket: AnyObject { func send(_ text: String); func close(code: Int) }
protocol GroupSecretStore: AnyObject {
    func loadAll() throws -> [GroupSecret]   // throws when it can't read now: the hub deletes nothing (§8.3)
    func save(_ secret: GroupSecret) throws
    func delete(groupId: String)
}
struct GroupSecret: Codable, Equatable { let groupId: String; let memberId: String; let pass: String; let groupKey: String }
enum ProProof: Equatable { case apple(signedTransaction: String); case google(productId: String, purchaseToken: String) }

final class GroupHub {
    init(store: GroupStore, secrets: GroupSecretStore, transport: GroupTransport, sockets: GroupSocketFactory?,
         words: WordFilter, domain: String, clock: @escaping () -> Int64)   // sockets nil in demo mode; domain for links
    var onChange: ((GroupHubEvent) -> Void)?                // .groups, .group(id), .map, .alert(GroupAlert)
    var acceptedTermsVersion: Int                           // groupsTermsAccepted, sent by E1 and E10 (§8.13);
                                                            // default GroupPolicy.termsVersion (1)

    // Reading (synchronous, from memory)
    var groups: [GroupSummary] { get }                      // §9.3 S2 rows
    func detail(_ groupId: String) -> GroupDetail?          // §9.3 S5
    var shownGroupId: String? { get }
    func mapContent(now: Int64) -> GroupMapContent?         // §9.5, for the shown group, block list and filter applied
    func pinDetail(groupId: String, pinId: String) -> PinDetail?
    func memberDetail(groupId: String, memberId: String) -> MemberDetail?
    func needsConsent(_ groupId: String) -> Bool
    func nextTickDelay(foreground: Bool) -> Int?            // seconds until the app should call tick; nil = nothing to do

    // Lifecycle and pipeline
    func start() async                                      // launch: load secrets and store, restore groups (§8.3)
    func becameActive() async                               // foreground: status, pull, live, flush, socket
    func enteredBackground()                                // close the socket
    func connectivityChanged(online: Bool) async
    func setLocationAccess(authorised: Bool, precise: Bool) // from the recorder's authorisation; §8.5
    func refreshDetail(_ groupId: String) async             // S5 opened: E2, E11 (lastLiveAt), E7 for owner/admin,
                                                            // and E21 when the group isn't shown (or no sockets)
    func trackPoint(_ point: TrackPoint)                    // from AppModel.appendTrack (TrackLog's TrackPoint)
    func fix(lat: Double, lon: Double, accuracy: Double?, course: Double?, speed: Double?, heading: Double?, time: Double)
    func tick(foreground: Bool) async                       // uploads due per §8.6
    func socketDidOpen(groupId: String)                     // the three name a socket by its group only: report
    func socketDidReceive(groupId: String, text: String)    // events of the socket the hub opened last, never of
    func socketDidClose(groupId: String, code: Int)         // one it closed (§8.7)

    // Actions (throw GroupActionError, whose `message` is the §8.14 text to show)
    func createGroup(name: String, displayName: String, pro: ProProof) async throws -> String
    func preview(_ invite: InviteLink) async throws -> InvitePreview
    func join(_ invite: InviteLink, displayName: String) async throws -> String
    func setShown(_ groupId: String?)
    func recordConsent(_ groupId: String)
    func setSharing(_ on: Bool, groupId: String)
    func setTrails(_ mode: TrailsMode, groupId: String)     // .off, .day, .all
    func setDisplayName(_ name: String, colour: Int, groupId: String) async throws
    func renameGroup(_ name: String, groupId: String) async throws
    func createInvite(groupId: String) async throws -> MadeInvite      // link, expiresAt
    func invites(groupId: String) async throws -> [PendingInvite]
    func cancelInvite(_ inviteId: String, groupId: String) async throws
    func setRole(_ role: GroupRole, memberId: String, groupId: String) async throws
    func removeMember(_ memberId: String, groupId: String) async throws
    func offerOwnership(to memberId: String, groupId: String) async throws
    func cancelOwnershipOffer(groupId: String) async throws  // owner cancels or target declines
    func acceptOwnership(groupId: String, pro: ProProof) async throws
    func updatePro(_ pro: ProProof, productId: String, expiresAt: Int64?) async  // E5 where needed (§10.7);
                                                            // expiresAt nil for Lifetime, and always nil on Android
    func dropPin(groupId: String, type: PinType, animal: String?, note: String?, lat: Double, lon: Double) throws
    func deletePin(_ pinId: String, groupId: String)
    func report(groupId: String, target: ReportTarget, reason: ReportReason, comment: String) async throws
    func block(_ memberId: String, groupId: String)
    func unblock(_ memberId: String, groupId: String)
    func clearMyTrail(groupId: String)
    func leave(groupId: String) async throws
    func deleteGroup(groupId: String) async throws
}
// FakeGroupServer.swift: an in-memory GroupTransport that follows §6 (roles, invites, seq, changes, joinedAt,
// paused), used by GroupHubTests and by demo mode. GroupDemo.swift: the demo seed (§8.15).
```

**Kotlin specifics.** Where Swift has no direct Kotlin form, the Kotlin core uses:

- `class GroupResponse(val status: Int, val body: ByteArray)` for the transport's answer, and a sealed
  `GroupTransportError` with `Offline(cause)` and `Failed(cause)`. The Android OkHttp transport returns and throws
  exactly these.
- Small classes for Swift tuples: `GroupDemo.Demo(hub, server)`, `GroupStatusDTO.Pause`, `GroupArchive.Record`,
  `GroupStore.ChunkKey`; `GroupStore.monthRange` returns a `Pair`.
- `GroupHub(…, clock, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main))` as its last
  parameter: work that the Swift hub starts as a main-actor `Task` (the immediate `live: null` upload, the flush
  after `dropPin`, `deletePin` or `clearMyTrail`, a pull after a tick) runs there. Tests pass their own scope. A
  background job that fails is logged without content, never thrown into the scope (that would end the app).
  `GroupDemo.makeHub` takes the same parameter.
- `GroupStore(path, driver: SqlDriver = MBTiles.driver)`, throwing `GroupStore.StoreError.Open`, so
  `GroupStore(path)` still compiles. `last_insert_rowid()` is read in the same transaction as its insert: Android's
  WAL connection pool may answer a later read from another connection.

#### 8.2.1 Value types

These are the exact fields; both cores use these names and types, and UI parts read only these. Names are
already decrypted, cleaned and masked (§8.12); a profile that fails to open gives "Unknown member" or "Unnamed
group". " (you)" is added by the UI. Colours are `#RRGGBB` text. Times are Int64 Unix seconds. Coordinates are
degrees (`Double`). Optional fields are `?` in Swift and nullable in Kotlin. All are `Equatable` (Kotlin `data
class`es and `enum class`es / sealed interfaces).

```swift
enum GroupRole: String { case owner, admin, member }
enum TrailsMode: String { case off, day, all }
enum PausedReason: String { case proEnded = "pro_ended", proRefunded = "pro_refunded", proUnverified = "pro_unverified" }
enum ReportReason: String, CaseIterable { case offensive, harassment, spam, dangerous, other }
enum ReportTarget: Equatable { case pin(pinId: String), member(memberId: String), group }
enum MemberState: Equatable { case sharing(at: Int64), lastSeen(at: Int64), notSharing }   // rule in §8.9
struct GroupCoordinate: Equatable { let lat: Double; let lon: Double }

struct GroupSummary {            // S2 row
    let groupId: String; let name: String; let memberCount: Int
    let isSharing: Bool; let isPaused: Bool; let hasOfferToYou: Bool; let isShown: Bool
}
struct MemberRow {               // S5 member row
    let memberId: String; let name: String; let initials: String; let colourHex: String
    let role: GroupRole; let isYou: Bool; let isBlocked: Bool; let state: MemberState
}
struct GroupDetail {             // S5
    let groupId: String; let name: String; let memberCount: Int
    let yourMemberId: String; let yourRole: GroupRole; let joinedAt: Int64
    let isPaused: Bool; let pausedReason: PausedReason?; let deleteAt: Int64?
    let isShown: Bool; let isSharing: Bool; let hasConsent: Bool; let trails: TrailsMode
    let offerToYouFrom: String?                  // the owner's name when an offer to you is open
    let offerByYouTo: String?                    // the target's name when your offer is open
    let members: [MemberRow]                     // ordered by joinedAt
    let invites: [PendingInvite]?                // nil unless owner or admin; empty until E7 answers
    let blocked: [MemberRow]                     // S16; real names
    let canInvite: Bool; let canRename: Bool; let canHandOver: Bool; let canDeleteGroup: Bool; let canLeave: Bool
    let canReportGroupName: Bool                 // admins and members (§9.3 S5)
    let supportCode: String                      // "{groupId}.{memberId}"
}
struct MemberDetail {            // S12
    let row: MemberRow
    let live: GroupCoordinate?                   // newest live position, if any and not hidden
    let canMakeAdmin: Bool; let canRemoveAdmin: Bool; let canMakeOwner: Bool; let canRemove: Bool
    let canReport: Bool; let canBlock: Bool      // false for yourself
}
struct PinDetail {               // S11
    let pinId: String; let type: String; let knownType: PinType?
    let animal: String?; let note: String?
    let dropperId: String; let dropperName: String; let createdAt: Int64; let expiresAt: Int64?
    let position: GroupCoordinate
    let isYours: Bool; let canDelete: Bool; let canReport: Bool; let canBlock: Bool   // report, block: false if yours
}
struct InvitePreview {           // S8
    let groupId: String; let name: String; let inviterName: String?; let memberCount: Int
    let isPaused: Bool; let expiresAt: Int64
}
struct MadeInvite { let inviteId: String; let link: String; let expiresAt: Int64; let groupName: String }   // S6
struct PendingInvite {           // S5 invite row
    let inviteId: String; let createdBy: String; let createdByName: String
    let createdAt: Int64; let expiresAt: Int64; let link: String?    // link only if this phone made it
}
struct GroupAlert { enum Kind { case removed, deleted, gone }; let kind: Kind; let groupId: String; let groupName: String }
enum GroupHubEvent: Equatable { case groups, group(String), map, alert(GroupAlert) }

struct GroupMapContent {         // §9.5, for the shown group; blocked members and filters already applied
    let groupId: String
    let members: [MapMember]     // not you; not blocked; live within 24 h
    let trails: [MapTrail]       // per the Trails setting; already thinned by core (§9.5)
    let pins: [MapPin]           // unexpired; not from blocked members
}
struct MapMember {
    let memberId: String; let position: GroupCoordinate; let colourHex: String; let initials: String
    let headingDeg: Int?         // nil when unknown or stale
    let isStale: Bool; let a11yLabel: String     // "{name}, {state text}" (§9.6)
}
struct MapTrail {
    let memberId: String; let colourHex: String; let widthPt: Double   // 3, or 2 above 10 visible trails
    let segments: [[GroupCoordinate]]            // each a polyline of 2+ points, oldest first
}
struct MapPin {
    let pinId: String; let type: String; let knownType: PinType?; let position: GroupCoordinate
    let colourHex: String        // §9.4
    let label: String?           // animal pins: "{animal} · {ago}" at `now`; nil otherwise
    let a11yLabel: String        // "{title}, dropped by {name} {ago}" (§9.6)
}

struct GroupActionError: Error, Equatable {
    enum Kind: Equatable {
        case offline, rateLimited, server, storeUnavailable, proNotConfirmed, tooManyGroups, forbidden
        case inviteNotFound, alreadyMember(groupId: String), damagedLink, termsOutdated, groupPaused, notFound
        case invalidText(GroupTextField), wordFilter(GroupTextField)
    }
    let kind: Kind
    let message: String          // §8.14 (every kind, including invalidText and wordFilter)
}
```

Core builds the state texts (`MemberState` to "Sharing · {ago}" and so on) only for `a11yLabel`; screens build
their own from `MemberState` with the §9.3 texts. UI parts read these types; neither part changes the other's
files (§13.1).

### 8.3 Local storage and secrets

**Secrets** (`GroupSecret` as JSON) never go to backups or other devices.

- **iPhone:** one Keychain item per group:
  - `kSecClassGenericPassword`, `kSecAttrService` `com.jacobusoettle.satmap.groups`, `kSecAttrAccount` = `groupId`
  - `kSecAttrAccessible` = `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (readable in the background after the
    first unlock, never in backups or on other devices)
  - `kSecAttrSynchronizable` false
  - This is the app's first Keychain use (`SatMap/Groups/KeychainSecrets.swift`).
  - iOS keeps Keychain items when the app is deleted. A secret with no local group row, as after a reinstall,
    restores the membership: E2, then E19 from 0. Only an E2 answer of `401`, `404 group_not_found` or `410`
    deletes the secret (§8.10).
- **Android:** an Android Keystore key, alias `satmap.groups.v1`:
  - AES-256, `PURPOSE_ENCRYPT|PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, randomised
    encryption, no user authentication
  - It encrypts the JSON array of secrets into `noBackupFilesDir/groups/secrets.bin` as version byte `0x01`, 12-byte
    IV, then ciphertext and tag, written with `AtomicFile`
  - Robolectric has no AndroidKeyStore, so tests use a fake `GroupSecretStore`
  - Both rule files add `<exclude domain="root" path="no_backup/"/>` (in `data_extraction_rules.xml` under both
    `cloud-backup` and `device-transfer`, and in `backup_rules.xml`), although the platform already leaves
    `no_backup` out.

**Order:** create and join save the secret *before* sending the request, and delete it only after a definite
failure (a `4xx` that is not a retry case). A crash after the server said yes can't lose a membership. For an
existing membership, only an E2 answer deletes the secret: a `401` or `404` from any other endpoint first triggers
an E2 (§8.10), so a bug in one endpoint can't wipe memberships.

**Lost answers.** A create or join that got no answer leaves its secret pending (restored by E2 at the next start,
activation or reconnect).

- The next join with the same link reuses that member id and pass, which the server answers again (§6.7); a new
  pass would find the invite used.
- The next create, while the earlier one is still pending and no E2 has settled it, resends E1 with the same
  `groupId`, `memberId` and `pass`, with profiles sealed from the names typed now (if the server made the group, the
  first names stay). Once E2 has restored the group, a new create makes a new group.
- An E2 answer for a pending secret (success, or `401`, `404 group_not_found`, `410`) counts only while that exact
  secret is still pending and no group exists for it: a create or join retry that took it over decides instead.

**A failed read.** `start()` deletes store rows that have no secret. When `loadAll` throws (a Keychain or Keystore
error, such as a read before the first unlock), the hub deletes and loads nothing and reads again at the next
`becameActive()`, keeping any group made or joined meanwhile.

**Local store** (`GroupStore`), one SQLite file:

- iPhone: `AppPaths.root/Groups/groups.sqlite`. The `Groups` folder is excluded from backup
  (`isExcludedFromBackup`) and inherits `completeUntilFirstUserAuthentication` protection.
- Android: `noBackupFilesDir/groups/groups.sqlite`.

Other members' records stay encrypted at rest; they are decrypted in memory when needed.

```sql
CREATE TABLE groups (group_id TEXT PRIMARY KEY, member_id TEXT NOT NULL, role TEXT NOT NULL, joined_at INTEGER NOT NULL,
  status_json TEXT, cursor INTEGER NOT NULL DEFAULT 0, cursor_at INTEGER NOT NULL DEFAULT 0, sharing INTEGER NOT NULL DEFAULT 0,
  consent_at INTEGER, trails TEXT NOT NULL DEFAULT 'day', shown INTEGER NOT NULL DEFAULT 0, profile BLOB,
  profile_seq INTEGER NOT NULL DEFAULT 0,
  pro_sent TEXT);   -- cursor_at: serverTime of the page or tick that last moved cursor (§8.8);
                    -- pro_sent: Android purchaseToken last accepted by E1/E5/E17 for this group (§10.7)
CREATE TABLE members (group_id TEXT, member_id TEXT, role TEXT, joined_at INTEGER, profile BLOB, seq INTEGER,
  PRIMARY KEY (group_id, member_id));
CREATE TABLE chunks (group_id TEXT, member_id TEXT, start INTEGER, rec BLOB, seq INTEGER,
  PRIMARY KEY (group_id, member_id, start));
CREATE TABLE pins (group_id TEXT, pin_id TEXT, member_id TEXT, created_at INTEGER, expires_at INTEGER, rec BLOB, seq INTEGER,
  PRIMARY KEY (group_id, pin_id));
CREATE TABLE live (group_id TEXT, member_id TEXT, at INTEGER, rec BLOB, PRIMARY KEY (group_id, member_id));
CREATE TABLE own_points (group_id TEXT, start INTEGER, points BLOB NOT NULL, version INTEGER NOT NULL,
  sent_version INTEGER NOT NULL DEFAULT 0, bad_time_tries INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (group_id, start));   -- outbox: TrailChunk plaintext (encoding 0); answers handled per §4.3
CREATE TABLE ops (id INTEGER PRIMARY KEY AUTOINCREMENT, group_id TEXT NOT NULL, kind TEXT NOT NULL, body TEXT NOT NULL,
  created_at INTEGER NOT NULL);   -- queued pin create/delete, profile, clear trail, in order
CREATE TABLE blocked (group_id TEXT, member_id TEXT, PRIMARY KEY (group_id, member_id));
CREATE TABLE made_invites (group_id TEXT, invite_id TEXT, link TEXT, expires_at INTEGER, PRIMARY KEY (group_id, invite_id));
```

`made_invites` lets the phone that made an invite show its QR again. Rows are deleted at expiry, on cancel, or when
the invite no longer appears in E7.

**Preferences** (UserDefaults / Defaults; add them to DESIGN §5.7):

| Key | Meaning |
| --- | --- |
| `groupsTermsAccepted` | Integer terms version |
| `groupsDisplayName` | The last display name typed, to prefill |
| `proCache` | §10.6 |
| `groupsDemo`, `groupsBaseURL` | Launch arguments only, never written |

### 8.4 Lifecycle

| Moment | What happens |
| --- | --- |
| Launch | `GroupsModel` is made in `AppModel.init`, then `hub.start()`. Neither blocks the UI. |
| Becomes active (iOS `didBecomeActive`; Android `AppLifecycle.didBecomeActive`) | `hub.becameActive()`. For every group: E2 status; pull changes (§8.8); send queued ops and points. For the shown group: E21 live, then open the socket (§8.7). |
| Enters background | `hub.enteredBackground()`: the socket closes with 1000. Uploads go on at background pace only for groups with sharing on. |
| Back online (`Connectivity`, false → true) | `hub.connectivityChanged(online: true)`: send the outbox at once; reconnect the socket if wanted |
| Map tap on a member or pin | Opens S12 or S11 (§9.1) |
| S5 opens | `hub.refreshDetail(groupId)`: E2, E11 (for `lastLiveAt`), E7 for owners and admins, and E21 when the group isn't the shown one (or in demo mode), so a member who shares now reads "Sharing", not "Last seen" (§8.9) |
| Location authorisation or accuracy changes | `hub.setLocationAccess(authorised:precise:)` (iOS from the recorder's authorisation and `accuracyAuthorization`; Android from the location permission and whether fine location is granted) |

### 8.5 The sharing pipeline

- **Hooks.**
  - iOS `AppModel.appendTrack(_:)` calls `groups.trackPoint(point)`; `AppModel.locationUpdated` calls `groups.fix(…)`.
  - Android, the same functions. Do not reassign the recorder's callbacks.
  - In-app consent is checked in the hub, not in the recorder.
- **A group shares** only when all of these hold:
  - sharing is on for it
  - consent is recorded
  - its status is active
  - location is authorised and precise (`setLocationAccess`). iOS reduced accuracy, or Android coarse-only
    permission, means no sharing; the existing hint covers it.
- **Trail.** Each `trackPoint` is converted (§2.4) and added to `own_points` of every sharing group: window by
  time, `version + 1`.
- **Live.** The newest `fix` is kept in memory only. It is never queued: an old live position is useless. A fix
  whose accuracy is unknown or worse than `GroupPolicy.liveMaxAccuracy` (100 m) is never sent as live: the
  recorder also reports coarse fixes (reduced accuracy, a cell or Wi-Fi fix after waking), which could put a
  member kilometres off. The fix becomes a `LivePosition` through `LiveFix.position` (§4.4).
- **Pause.** Points recorded while a group is paused are not added. Before an upload, queued points inside the
  status's `pausedIntervals` are dropped.
- **Sharing switched off:** an immediate upload with `"live": null`. Points already queued still go, because they
  were recorded while sharing. The app then offers to clear the trail (§9.3 "Stop sharing" alert). If an upload
  is already on its way, the `live: null` goes at the next tick (5 s in the foreground, 60 s in the background).
  Offline, the hub keeps the stop pending in memory (not across a restart) and sends it at the next flush, also
  while paused.
- **Sharing switched on:** the next point has `startsSegment` set.
- Your own trail in a group is never drawn as a group trail: your cyan "Where I've been" track already shows it.

### 8.6 Upload pace

Constants in `GroupPolicy`:

| Name | Value |
| --- | --- |
| `uploadInterval` | 60 s |
| `liveWatchedForeground` | 5 s |
| `liveWatchedBackground` | 15 s |
| `liveMinMove` | 5 m |
| `liveHeartbeat` | 30 s |
| `staleAfter` | 600 s |
| `liveHiddenAfter` | 86 400 s |
| `animalPinLifetime` | 43 200 s |
| `maxChunksPerUpload` | 50 |
| `maxUploadBytes` | 196 608 (192 KiB of JSON, §4.3) |
| `liveMaxAccuracy` | 100 m |
| `socketPing` | 30 s |
| `lateWindowGrace` | 60 s (§4.3) |

| Situation for group G | Live position | Trail chunks | Channel |
| --- | --- | --- | --- |
| App in front, G shown, socket open, `watchers > 0` | Every 5 s when moved ≥ 5 m since the last sent, or 30 s passed | Every 60 s if changed | WebSocket `upload` |
| App in front, G shown, socket open, `watchers == 0` | With the chunk upload, every 60 s | Every 60 s if changed | WebSocket `upload` |
| Otherwise (background, or G not shown), last answer had `watchers > 0` | Every 15 s | Every 60 s if changed | HTTP E18 (live and chunks together) |
| Otherwise, `watchers == 0` | Every 60 s (a heartbeat, even when still) | Every 60 s if changed | HTTP E18 |
| No connection | Not queued | Kept in `own_points`; sent on reconnect, oldest first, packed per §4.3 | none |

- **Heartbeat.** When a live send is due, the newest fix is sent if its time (floor of the fix timestamp) is newer
  than the last `at` sent; otherwise the upload has no `live` key (the server would refuse the same `at` again)
  and still learns `watchers`. A still phone's dot therefore turns stale only if no new fix arrives for 10 min.
- The 60 s heartbeat is how a background sender learns that someone started watching. After at most 60 s it speeds
  up to 15 s.
- **One clock.** Wherever the live position goes in fixed slots (every row but the first), changed chunks wait for
  the next live slot and go in the same request, still at most once per 60 s. On two clocks, chunks falling due at
  the first point after a stop would go between two heartbeats from then on (two requests a minute, about 40 % more
  requests and background wake-ups). The first row keeps its own 60 s chunk timer.
- **Ticks.**
  - In the foreground the app calls `hub.tick(foreground: true)` every 5 s.
  - In the background, the hub returns `nextTickDelay` (15 or 60 s, or nil when no group shares and nothing is
    queued).
  - iOS: a timer, plus a check on every `onFix`. The app keeps running in the background because the recorder
    never stops location updates. Each upload is wrapped in `UIApplication.beginBackgroundTask`.
  - Android: a `WakeTimer` (AlarmManager `ELAPSED_REALTIME_WAKEUP`; a coroutine `delay` stalls in deep sleep),
    plus a check on every fix, while `LocationService` runs.
  - **[Unconfirmed]** that a location foreground service keeps network access in Doze. Demo mode can't show it
    (its server is in-process), so it is a manual device check (§12.6, §14 U9).

### 8.7 The WebSocket

- **When to connect:** only for the shown group, only while the app is in the foreground, and only while that group
  is not deleted or left. At most one socket per app.
- `watching` is true while the app is in the foreground with that group shown and active. It is false when paused:
  the socket stays open to hear about a resume.
- **On open:** send `hello` with the stored cursor, then apply `welcome`: status and live.
- Sheets over the map don't change `watching`.
- **Closing:** close the socket when the app goes to the background, when the shown group changes (then open one for
  the new group), when sharing and showing both stop, and on 4401, 4403 or 4404.
- **Events from the app.** `socketDidOpen`, `socketDidReceive` and `socketDidClose` name the socket only by its group.
  The app reports events only for the socket the hub opened last (`GroupSocketFactory.open`) and drops the late
  frames and close of a socket the hub already closed; otherwise a stale close would end the new socket's state and
  `uploaded` ids (which restart at 1) could match the wrong upload.
- **A refused upgrade** (an HTTP answer before the upgrade, §6.6) is reported as a close: `401` and
  `404 group_not_found` as 4401 (the hub checks with E2), `410 removed` as 4403, `410 group_deleted` as 4404, and
  anything else (or no answer) as 1006, which reconnects with backoff.

### 8.8 Sync

- **Each group keeps a cursor.** It pulls E19 from the cursor until `more` is false:
  - when the app becomes active
  - when a socket `tick` says `more`
  - when a tick's `from` is ahead of the cursor
  - after a `welcome` whose `status.seq` is above the cursor and that brought no changes
- Each page is applied in one store transaction, then the cursor is saved with `cursor_at` = the page's (or tick's)
  `serverTime`. Items with `seq` at or below the stored cursor are skipped (§6.3), and the cursor only moves forward.
- **`archive` items:** if the cursor and `cursor_at` saved before this page satisfy `cursor ≥ maxChunkSeq` and
  `cursor_at < archivedAt`, relabel the stored chunks of that member and month with the item's `seq` (no download,
  §6.3). Otherwise fetch E20 and store each window as a `chunk` with the archive item's `seq`. An archive download
  that fails is retried at the next pull; the cursor still moves on. Archive items still to fetch are kept in
  `ops`.
- **`reset`:** delete the group's members, chunks, pins and live rows (keep the secret, settings, block list,
  `own_points` and `ops`), set the cursor to 0, and pull again.

### 8.9 Applying and showing data

- **Chunks:**
  - Keep the copy with the highest `seq` for each member and window.
  - Other members' chunks with `start + 600 ≤` your own `joinedAt` are dropped whole (the server shouldn't send
    them; this guards against a server bug). Points earlier than your `joinedAt` are cut from the chunk that
    straddles it. The server can only filter whole chunks, so a modified app could see up to 10 minutes more
    (accepted).
  - Your own chunks from the server are stored but not drawn.
- **Records that fail to open** (§3.4) are dropped from view and logged, without content.
- **Pins** with `expiresAt ≤ now` disappear at once, even offline (the map refreshes pins every 60 s).
- **Members:** a profile that fails to open shows as "Unknown member" in grey `#8E8E93`.
- **Live:** keep the newest per member. "Stale" when older than 10 min; hidden after 24 h.
- **Member state** (`MemberState`, shown in S5, S12 and the map labels). Let L be the newest live entry the phone
  holds for the member (from `welcome`, E21 or ticks; never older than 24 h), and `lastLiveAt` the value from the
  last E11:
  - L exists and is at most 600 s old: `sharing(at: L.at)`, shown "Sharing · {ago}".
  - L exists and is older: `lastSeen(at: L.at)`, shown "Last seen {ago}".
  - No L, `lastLiveAt` not null: `lastSeen(at: lastLiveAt)`.
  - Otherwise: `notSharing`, shown "Not sharing".
  - A live `rec: null` (stopped sharing) removes L.
- **Blocked members:** no dot, trail or pins on the map; their row shows "Blocked member", initials "BM", grey
  `#8E8E93` (S16 lists them by their real names).
- **Received names, animal names and notes** pass through `WordFilter.masked` before display.

### 8.10 Clearing, leaving, removal, deletion, pause

| Event | What the app does |
| --- | --- |
| Clear my trail | Delete the group's `own_points` and your stored chunks at once; queue E22 (sent when online). No chunk uploads for the group until E22 succeeds (§4.3). |
| Delete a pin | Gone from the map at once; E24 queued (a `404` counts as done). A pin still queued is never sent, unless its E23 is already on its way: then an E24 follows that E23. If the E23 is answered `409 pin_exists`, nothing is resent under a new id and no E24 goes (the id is another member's pin); a core may also skip the E24 after another `4xx`, which stored nothing. |
| Leave | Needs a connection; offline shows the offline text. E14 `me`, then delete the group's rows and its secret. |
| Removed (`410 removed`, close 4403) | Delete the group's rows and secret; alert "Removed" (§9.3) |
| Deleted (`410 group_deleted`, close 4404) | Same; alert "Deleted" |
| Gone (`401`, `404 group_not_found`) | Only an **E2** answer counts. A `401` or `404 group_not_found` from any other endpoint triggers an E2 first, and nothing is deleted unless E2 answers the same. Then: same as Removed; alert "No longer available". |
| Paused | Show the paused section and banner (§9.3). Stop live and chunk uploads; don't record new points. Disable the sharing switch and drop-pin buttons. Deleting pins, clearing your trail, leaving, renaming, invites, ownership offers, reporting and blocking still work. The socket stays open, not watching. |
| Resumed | Sharing continues if it was on. The first point starts a new segment. |
| Ownership offered to you | The group's row and detail show the offer (§9.3 S5) |

### 8.11 Block list

- The block list is on the phone only (`blocked` table) and is never sent.
- Blocking from a pin blocks the pin's dropper.
- You can't block yourself.
- Unblock in S16.
- Blocking changes nothing for anyone else and is not told to anyone.
- **It is one-way (SD27).** The blocked person still sees your position, trail and pins while you share in that
  group. The Block alert says so, and while you share it offers "Block and stop sharing", which blocks and switches
  sharing off in that group (§9.3). To stop someone seeing you at all, an admin or the owner must remove them.
- There are no accounts, so a person who leaves and rejoins with a new invite has a new `memberId` and is not
  blocked (§14 R6).

### 8.12 Word filter

**The list.** The file is `SatMap/Resources/GroupWords.txt` (the Android app gets it as an asset, as with the other
resources). Format, one entry per line:

- `word`: matches a whole token
- `word*`: matches a token that starts with it
- `*word*`: matches anywhere in a token, and in the letters-only text
- `#` starts a comment; blank lines are ignored

Entries are written lower case.

**Parsing the list:** split on U+000A; strip spaces, tabs and U+000D at both ends; skip empty lines and lines
starting with `#`. `*word*` (longer than 2 characters) is a contains entry, `word*` (longer than 1) a prefix entry,
anything else an exact entry.

**Matching**, the same in both cores. Normalisation works **per original code point**, so every normalised code
point remembers which original code point it came from:

1. For each code point c of the text (index i): take NFKC of c alone (Swift
   `String(c).precomposedStringWithCompatibilityMapping`, Kotlin `Normalizer.normalize(…, NFKC)`), lower-case the
   result with the full Unicode mapping (Swift `lowercased()`, Kotlin `lowercase()`, which uses the root locale),
   then map each resulting code point `0→o 1→i 3→e 4→a 5→s 7→t @→a $→s`. Each output code point is tagged with i.
   One original can give several (fullwidth `Ｂ` gives `b`; `İ` gives `i` plus U+0307).
2. **Tokens** are the maximal runs of normalised code points that are letters: general category L*. Swift: the
   scalar's `properties.generalCategory` in the five letter categories. Kotlin: `Character.isLetter(codePoint)`. A
   token's span is from the tag of its first code point to the tag of its last.
3. A token **matches** an exact entry if equal, a prefix entry if it starts with it, a contains entry if it
   contains it.
4. The **letters-only text** is all tokens joined with nothing between them.
5. `allows(text)` is false if any token matches, or the letters-only text contains a contains entry.
6. `masked(text)` replaces the original code points of each matching token's span with `•••` (overlapping spans
   merge into one). If no token matched but the letters-only check did, the whole text becomes `•••`.

Vectors: `vectors.json` `wordFilter` (its own `list`, then `text`, `allows`, `masked` per case) extends the table
below.

**Where it applies.**

- Own input (group name, display name, animal, note, report comment) is checked on save. It is refused with
  "Please use different words." (field-specific texts in §9.3).
- Everything received is masked (§8.9).

**Content.** The starting list (English, Afrikaans and isiZulu profanity and slurs, including South African racial
slurs) is written by the Swift core part and reviewed by the owner before release. It is a release blocker
(§14). Core tests use their own small list:

```
badword
worse*
*nasty*
```

| Text | `allows` | `masked` |
| --- | --- | --- |
| `Badword` | false | `•••` |
| `b4dw0rd!` | false | `•••!` |
| `worsening` | false | `•••` |
| `supernastything` | false | `•••` |
| `n.a.s.t.y` | false | `•••` |
| `bad word` | true | |
| `Scunthorpe` | true | |
| `Kudu at the bridge` | true | |

### 8.13 Terms and consent

- **Terms.** Version 1. Accepted once per install (`groupsTermsAccepted = 1`):
  - on the first opening of Groups (S1 replaces the list)
  - or before a join if they weren't accepted yet (S8 shows S1 first)
  - "Agree and continue" also confirms the person is 18 or older or has a parent's or guardian's permission
    (POPIA section 35; the sentence is on S1).
  - Create and redeem send the accepted `termsVersion`. A later version in the app (`GroupConfig.termsVersion`)
    re-shows S1. On `409 terms_outdated`: if `GroupConfig.termsVersion` is above the accepted one, show S1 and
    retry once; otherwise the server needs terms this app doesn't have, so show "Please update SatMap to create or
    join groups." (§8.14).
  - The app keeps `GroupHub.acceptedTermsVersion` equal to `groupsTermsAccepted`. On `terms_outdated` the action
    throws `.termsOutdated` with that text and deletes the secret it saved; the app decides whether to show S1, set
    the property and retry.
- **Consent (S9)** is per group, before the first time sharing is switched on:
  - right after creating a group
  - right after joining
  - when the switch is first turned on
  - "Not now" leaves sharing off. The group can still be viewed, and pins dropped at a tapped spot.
  - If location permission is not yet granted, "Share my location" asks for it: iOS `requestWhenInUseAuthorization`
    if not determined, the denied alert otherwise; Android runs `PermissionFlow` for location.
  - If location is refused, sharing stays off.

### 8.14 Error texts (shared)

| Case | Text |
| --- | --- |
| Offline | "No connection. Try again when you're online." |
| `429` | "Too many tries. Wait a moment and try again." |
| `5xx`, timeout | "Something went wrong on our side. Try again in a minute." |
| `503 store_unavailable` | "The store can't be reached right now. Try again later." |
| `402 pro_invalid` / `pro_inactive` | "SatMap Pro couldn't be confirmed. Tap Restore purchases, then try again." |
| `409 too_many_groups` | "You already own 20 groups. Delete one to make a new group." |
| `403` | "You can't do that in this group." |
| `invite_not_found` | "This invite has already been used, was cancelled or has expired. Ask for a new one." |
| `409 terms_outdated` the app can't satisfy | "Please update SatMap to create or join groups." |
| `409 group_paused` (`.groupPaused`) | "This group is paused at the moment." |
| Another `404`, such as `not_found` (`.notFound`) | "This is no longer in the group." |
| An action on a group that turns out to be gone (`401`, `404 group_not_found`, `410`; `.notFound`) | "The group no longer exists, or your membership ended." (the Gone alert's message, §9.3) |
| Already a member (`.alreadyMember`) | "You're already in {group}.", or "You're already in this group." where the name isn't known |
| Damaged link (`.damagedLink`) | "This invite link is damaged. Ask for a new one." |
| `.invalidText(.groupName)` | "Group names are 1 to 40 characters, on one line." |
| `.invalidText(.displayName)` | "Names are 1 to 30 characters, on one line." |
| `.invalidText(.animal)` | "Type the animal, up to 40 characters." |
| `.invalidText(.note)` | "Notes are up to 140 characters and 4 lines." |
| `.invalidText(.reportComment)` | "Details are up to 500 characters and 4 lines." |
| `.wordFilter(.groupName)` / `(.displayName)` / others | "Please use different words for the group name." / "Please use different words for your name." / "Please use different words." (§9.3) |

### 8.15 Demo mode (for walkthroughs and screenshots)

**Switching it on:**

- iOS: the launch argument `-groupsDemo YES`.
- Android: `--es groupsDemo YES` (via `Defaults.setLaunchArguments`, as `zoomScript` is). `ScreenshotTest` launches
  MainActivity with an intent carrying that extra.

**What it changes** (the stub commits of P2 and P3 already return this seed from the read accessors, §13.2):

- The transport is core's `FakeGroupServer`, and the hub gets `sockets: nil` (no WebSocket; with `sockets` nil
  the hub pulls E19 and E21 for the shown group on each foreground tick instead).
- The secret store is in memory.
- The Pro store is a fake: not Pro at launch; any purchase succeeds at once and gives yearly Pro.
- Nothing else in the app changes.

**`GroupDemo.seed(server:, now:)`** (both cores) makes, relative to the anchor A = (−33.918292, 18.921204), the first
point of the walkthrough's walk:

1. **"Kruger trip"**, owned by you, display name "Sam" (colour 0), **not** shown on the map (`shown = false`, so
   shots 01–27 have no group on the map; step 36 switches it on), sharing on, consent recorded.
   - Members:
     - "Thandi": admin, colour 1, live at A + (−0.0012, +0.0010), heading 120
     - "Pieter": member, colour 2, A + (+0.0008, +0.0016), heading 270
     - "Lerato": member, colour 4, A + (−0.0004, −0.0015), heading 30
     - "Sipho": member, colour 9, A + (+0.0018, −0.0006), live 25 min old (stale)
   - Each has a 2-hour trail of 60 points ending at their live position: a straight line from 0.004° away, plus a
     sine wiggle of 0.0003°, so trails look natural and stay the same every run.
   - Pins, one of each type:
     - animal "Kudu", note "Three bulls", at A + (−0.0006, +0.0004), dropped by Thandi 2 h ago
     - water, at A + (+0.0010, −0.0010), by Pieter
     - shelter, at A + (+0.0014, +0.0008), by Lerato
     - danger, note "Loose gravel on the bend", at A + (−0.0015, +0.0002), by Thandi
     - pothole, at A + (−0.0002, +0.0018), by Sipho
     - police, at A + (+0.0004, −0.0018), by Pieter
   - One pending invite, made by you.
2. **"Drone team"**, owned by "Pieter", you are a member, paused (`pro_ended`), `deleteAt` = now + 27 days. Members
   "Pieter" (owner) and "Ayesha" (admin).
3. **"Weekend ride"**, not joined. `GroupDemo.inviteLink` is a fixed valid link to it: domain `example.invalid`,
   token from groupId `0x10…0x1f` and secret `0x20…0x2f`, key `0x30…0x4f`. Its owner is "Nomsa". The walkthrough
   pastes this link.
4. **"Braai club"**, not joined, owner "Bongani". `GroupDemo.inviteLink2`: groupId `0x60…0x6f`, secret
   `0x70…0x7f`, key `0x80…0x9f`. Used only by the link checks (§9.7 step 41, `InviteIntentTest`).

Times the steps above leave open (both cores the same): "Kruger trip" was made 45 days ago and you joined "Drone
team" 20 days ago, so S2 lists "Kruger trip, Drone team" (step 29); the Kruger pins were dropped between 50 min and
26 h ago. "Weekend ride" also has "Lindiwe" and "Johan" (3 members), "Braai club" also "Zanele". The fake server's
`liveSource` keeps the seeded members' live records at their seeded ages, so the long walkthrough doesn't turn every
dot stale before shot 36.

---

## 9. UI (the same on both apps)

Texts are exact. `{group}`, `{name}` and the like are filled in. Straight apostrophes, as in the rest of the app.
Times follow the phone's locale ("14:05" in South Africa); dates are "9 Nov" (with the year if it isn't this
year).

### 9.1 Map screen entry points

- **Groups button** (SD21): a `MapButton` with SF Symbol `person.3.fill` (Android: a matching `SatIcons.Person3`
  vector) and label "Groups" (the VoiceOver label; Android `contentDescription` "Groups", testTag `groupsButton`).
  - The first button of the right-hand `controls` column: under the compass's 44 pt/dp slot and 12 pt/dp above the
    terrain button (the mountain, a plane in drone mode), like every button there. Nothing is at the left edge.
  - It is shown whenever the column is (not while drawing an area).
  - It is `active` (yellow) while a group is shown on the map.
  - Tap: `model.sheet = .groups` (new `ActiveSheet` case `groups`).
- **Fitting the buttons** (the same rule on both apps): the column is now the compass slot and seven buttons, 478
  pt/dp tall. Over the lift (124 pt/dp, plus the 8 pt/dp bottom padding) it doesn't fit under the top panels on a
  small phone (iPhone SE, 375 × 667 pt; a 360 × 640 dp Android phone; both stack the coordinate bars), in
  landscape on any phone, or under tall panels (a download banner over a measure panel). Tighter spacing alone
  can't help in landscape: with no gaps at all the slots are 394 pt/dp, and a phone on its side has at most about
  420 pt/dp of safe height, before the lift and the top bars.
  - While the column fits the height it is offered (what the top panels leave), it is one column, exactly as above.
  - Otherwise the buttons form a grid of 50 pt/dp cells, 12 pt/dp apart both ways, with as many rows as fit (at least
    one). It is filled from the last button: My location stays at the bottom right, and each column is filled from
    the bottom up, right to left. The leftmost column holds the first buttons from its top, so the compass slot is at
    the top left (centred in its cell) and Groups under it. The grid sits where the column did, on the lift.
  - So the first 56 pt/dp of shortfall only moves the compass slot (empty unless the map is turned) to a second
    column beside Groups, and no button moves; in landscape the buttons are two or three rows of columns.
  - iOS: `controls` is a `Layout` (`MapButtonsLayout`, the grid from core's `MapButtonGrid`), offered the height the
    `VStack` leaves: the bottom `HStack` takes the room under the panels with `.frame(maxHeight: .infinity,
    alignment: .bottom)` in place of the `Spacer` above it. (Not `.layoutPriority(1)`: that offers the row the room
    less the panels' smallest sizes, so a hint that wraps could be cut to one line.) Android: `Controls` is a
    `Layout` (`mapButtonGrid`), measured with the height `MapOverlay`'s `Column` leaves.
- **Paused banner:** an existing `Hint` in the top stack, under the location hints, while the shown group is paused
  (id `groupPausedHint`).

  | `pausedReason` | Member | Owner |
  | --- | --- | --- |
  | `pro_ended` | "{group} is paused: the owner's SatMap Pro has ended." | "{group} is paused: your SatMap Pro has ended. Tap to renew." |
  | `pro_refunded` | "{group} is paused: the owner's SatMap Pro was refunded or cancelled." | "{group} is paused: your SatMap Pro was refunded or cancelled. Tap to renew." |
  | `pro_unverified` | "{group} is paused: the owner's SatMap Pro couldn't be confirmed." | "{group} is paused: your SatMap Pro couldn't be confirmed. Tap to fix." |

  - A tap opens S5 for that group. Its close button hides it until the next launch.
- **Drop pin:** while a group is shown and active, the tapped-spot overlay at the bottom gets a capsule button
  "Drop pin" (SF `mappin.and.ellipse`, id `dropPin`) above the coordinate row, beside the name capsule if there is
  one. A tap opens S10 at that spot.
- **Map taps** test, in this order, before the existing chain (KML point, aviation place, measure close,
  `model.mapTapped`):
  1. a member dot (22 pt/dp radius), which opens S12
  2. a group pin (22 pt/dp), which opens S11
- **Invite links** open S8 over anything.

### 9.2 Sheets

- **Routes** (one list for both apps): `List`, `Terms`, `Paywall`, `Create`, `Detail(gid)`, `Consent(gid)`,
  `Invite(gid)`, `JoinLink`, `Scan`, `Member(gid, mid)`, `Pin(gid, pid)`, `Report(gid, target)`, `EditName(gid)`,
  `Rename(gid)`, `Blocked(gid)`, `Handover(gid)`.
- **iOS:**
  - `ActiveSheet.groups` shows `GroupsSheet` (a `NavigationStack` over a `[GroupsRoute]` path, root S2 or S1).
  - S11 and S12 opened from the map use their own sheet, `model.groupItem` (presented with the
    `Color.clear.sheet(item:)` trick, like `pendingArea`), with its own `NavigationStack`: root `Pin` or `Member`,
    and `Report` pushed on it.
  - S8 uses its own sheet, `model.pendingInvite`, so it can open over anything.
- **Android:** the same three as `SheetHost`s:
  - `ActiveSheet.groups` with `RouteStack<GroupsRoute>` (a sealed interface with the routes above)
  - `model.groupItem`, with its own `RouteStack`: `[Pin(gid, pid) | Member(gid, mid)]`, then `Report`
  - `model.pendingInvite`
- **Flows between sheets:**
  - Create (S4) succeeds: the stack becomes `[List, Consent(gid)]`; when S9 finishes it is replaced by
    `[List, Detail(gid)]`.
  - Join (S8) succeeds: close `pendingInvite`, open `.groups` with `[List, Consent(gid)]`, then `[List,
    Detail(gid)]` as above. The joined group becomes the shown group.
  - Switching sharing on without consent (S5): push `Consent(gid)`; when it finishes, pop back to S5.
  - Paywall (S3) is pushed from S4's entry, S5's "Renew SatMap Pro" or "Accept"; on success it pops and the action
    continues.
- **House style:** Form/List sections with plain footers, "Done" or "Cancel" in the leading slot, `Label` rows,
  alerts driven by an optional message. Android uses `Form.kt`'s rows.

### 9.3 Screens and texts

**S1 Terms** (title "Groups"; leading "Done")

- Headline: "See your group on the map"
- Rows, each with an icon:
  - "Share your position, trail and pins with friends, family or your drone team." (`person.3.fill`)
  - "Only people you invite can join. Each invite works once, for 7 days." (`link`)
  - "Everything your group shares is encrypted on your phone. Our server can't read names, places or notes."
    (`lock.fill`)
  - "Don't post anything illegal, hateful or dangerous. You can report and block anyone." (`flag.fill`)
  - "Creating a group needs SatMap Pro. Joining is free." (`star.fill`)
- Text above the button: "By tapping Agree and continue you accept the terms of use and confirm that you're 18
  or older, or have a parent's or guardian's permission."
- Button "Agree and continue" (id `agreeTerms`).
- Link rows "Terms of use" (opens `{base}/terms`) and "Privacy notice" (`{base}/privacy`).
- Footer: "Questions or a problem? {contactEmail}"

**S2 Groups list** (title "Groups"; leading "Done")

- Section "Your groups" (only when there is at least one). One row per group (id `groupRow`), ordered by your
  `joinedAt`, oldest first:
  - title: the group name
  - subtitle:
    - "{n} members · Sharing", or "{n} members · Not sharing"
    - "Paused"
    - "Wants you as owner" when an offer to you is open
  - trailing `map.fill` icon when shown on the map
  - a tap opens S5
- With no groups, the first section's footer: "You're not in a group yet."
- Section: row "Create group" (`plus.circle`, id `createGroupRow`) and row "Join with an invite link" (`link`, id
  `joinLinkRow`). Footer: "To join a group, open the invite link someone in the group sent you, or scan their QR
  code with your camera."
- Section: link rows "Terms of use", "Privacy notice", and "Contact us" (opens `mailto:{contactEmail}`).

**S3 Paywall** (title "SatMap Pro"; leading "Cancel")

- Headline: "Create your own groups"
- Rows with icons:
  - "Create groups and invite as many people as you like" (`person.3.fill`)
  - "Members join free" (`gift.fill`)
  - "See everyone's position and trail, and share pins" (`map.fill`)
  - "Encrypted on your phone: we can't read it" (`lock.fill`)
- Section "Choose a plan", three choice rows. "Yearly" is selected at first. Ids `planYearly`, `planMonthly`,
  `planLifetime`.
  - "Yearly": detail "{price} a year", with a capsule "Save {n}%"
  - "Monthly": "{price} a month"
  - "Lifetime": "{price} once"
  - Prices come from the store (StoreKit `displayPrice`, Play `formattedPrice`).
  - n = floor((1 − yearly ÷ (12 × monthly)) × 100), hidden when it isn't above 0.
  - While prices load: "Loading prices…". If they fail: "The store can't be reached right now. Try again later."
- Primary button "Subscribe" (Yearly or Monthly) or "Buy" (Lifetime), id `buyPro`.
- Button "Restore purchases" (id `restorePurchases`).
- Footer:
  - "Subscriptions renew automatically until you cancel them in your {App Store / Google Play} settings, at least 24
    hours before the end of the period. If Pro ends, groups you own pause until you renew or hand them to a member
    who has Pro. Pro bought on an iPhone doesn't carry over to Android, or the other way round."
  - Then link rows "Terms of use" and "Privacy notice".
- Results:
  - Pending: "Your purchase is waiting for approval. Pro starts when it's approved."
  - Restore found nothing: "No SatMap Pro purchase was found for this {Apple ID / Google account}."
  - Success: the screen closes and the flow continues (S4 or the ownership accept).

**S4 Create group** (title "New group"; leading "Cancel")

- Section "Group name": text field, placeholder "e.g. Kruger trip" (id `groupName`).
- Section "Your name in the group": text field, placeholder "e.g. Sam" (id `displayName`), prefilled from
  `groupsDisplayName`. Footer: "Members see these names. Names are encrypted on your phone."
- Button "Create group" (id `createGroup`), disabled until both names are valid. While working: a spinner with
  "Creating…".
- Filter errors: "Please use different words for the group name." / "Please use different words for your name."
- Other errors from §8.14.
- On success: S9 for the new group, then S5.
- Reached from S2's "Create group". If not Pro, S3 comes first.

**S5 Group detail** (title: the group name, inline; leading "Back" in the list stack, "Done" if opened alone).
Sections in this order, each only when it applies:

1. **Paused** (warning style, no header). The texts are below this list. Owner buttons: "Renew SatMap Pro" and
   "Hand over ownership" (S14). "Renew SatMap Pro" sends E5 at once when the phone already has Pro (for example
   after a `pro_unverified` pause, or a renewal the server hasn't seen); otherwise it opens S3, then E5.
2. **Offer to you:** "{owner} wants to make you the owner of this group. You need SatMap Pro." Buttons "Accept" (id
   `acceptOwner`; E17 at once when the phone has Pro, otherwise S3 first, then E17) and "Decline".
3. **Offer by you:** "Waiting for {name} to accept ownership." Button "Cancel request".
4. **"On the map":**
   - switch "Show on the map" (id `showOnMap`)
   - switch "Share my location" (id `shareLocation`; disabled when paused; turning it on shows S9 first if
     consent is missing)
   - choice row "Trails" with values "Off", "Last 24 hours" and "Everything" (id `trailsMode`)
   - Footer:
     - Sharing on: "Members see where you are and the trail you leave, also while SatMap is in the background."
     - Off: "Members can't see where you are now. The trail you already shared stays visible until you clear it.
       You can still see the group and drop pins."
     - Paused: "Sharing is off while the group is paused."
   - Turning "Share my location" off shows the "Stop sharing" alert (below), which offers to clear the trail too.
5. **"Members ({n})":** one row per member (id `memberRow`):
   - colour dot with initials
   - name, with " (you)" for you; a blocked member shows "Blocked member"
   - subtitle: role ("Owner", "Admin" or nothing) joined with " · " to the state ("Sharing · {ago}", "Not
     sharing", "Last seen {ago}", by the rule in §8.9)
   - a tap opens S12
6. **"Invites"** (owner and admin):
   - row "Invite someone" (`person.badge.plus`, id `inviteSomeone`), which makes an invite (E6) and opens S6
   - one row per pending invite: "Invite from {name}", subtitle "Expires {date}"
     - a tap opens S6 again if this phone made it
     - a trailing "Cancel" asks "Cancel this invite?" with message "The link stops working." and buttons "Cancel
       invite" (destructive) and "Keep"
   - Footer: "Each invite link works once, for one person, and expires after 7 days."
7. **Untitled:**
   - "Drop a pin where I am" (`mappin.and.ellipse`), disabled without a location fix or when paused. Opens S10 at
     your position.
   - "Your name and colour" (S15)
   - "Rename group" (owner and admin; S15)
   - "Report group name" (admins and members; id `reportGroup`; S13 for the group name)
8. **"Clear my trail"** (destructive row).
9. **"Blocked ({n})"** (when any): row opens S16.
10. **End:**
    - Owner: "Hand over ownership" (S14) and "Delete group" (destructive).
    - Others: "Leave group" (destructive).
    - Row "Support code" with the value `{groupId}.{memberId}` (id `supportCode`); a tap copies it and shows the
      toast "Support code copied".
    - Footer: "Report a problem: {contactEmail}. Quote your support code when you write about your data."

Paused texts. `{date}` is `deleteAt`.

- **Member, `pro_ended`:** "This group is paused because the owner's SatMap Pro has ended. Nobody can share their
  location or drop pins until the owner renews Pro or hands the group to a member who has Pro. If it stays paused,
  everything shared in the group is deleted on {date}."
- **Owner:** the same, with "because your SatMap Pro has ended" and "until you renew Pro or hand the group to a
  member who has Pro".
- **`pro_refunded`:** "because the owner's SatMap Pro was refunded or cancelled" (owner: "your SatMap Pro").
- **`pro_unverified`:** "because the owner's SatMap Pro couldn't be confirmed" (owner: "your SatMap Pro").

**S6 Invite** (title "Invite"; leading "Done")

- The QR code, 240 pt/dp (id `inviteQR`), with the group name under it.
- Text: "Ask them to scan this code with their phone's camera, or send them the link."
- Buttons:
  - "Share link" (`square.and.arrow.up`, id `shareInvite`). Text shared: `Join "{group}" on SatMap: {link}`. iOS
    uses `SharePresenter`; Android adds a text share to `ShareHost`.
  - "Copy link" (`doc.on.doc`, id `copyInvite`). Shows the toast "Link copied". The link holds the group key, so:
    - iOS: `UIPasteboard.general.setItems([[UTType.plainText.identifier: link]], options: [.localOnly: true,
      .expirationDate: now + 600 s])` (no Universal Clipboard; gone after 10 minutes).
    - Android: `ClipData.newPlainText("Invite link", link)` with `description.extras` holding
      `ClipDescription.EXTRA_IS_SENSITIVE = true` (the string `"android.content.extra.IS_SENSITIVE"` below API 33),
      so the clipboard preview hides it.
- Footer: "This invite works once, for one person, and expires on {date} at {time}. Anyone who gets the link can
  use it, so send it only to the person you're inviting."
- If making it fails: "Couldn't make an invite. Check your connection and try again."

**S7 Join with an invite link** (title "Join a group"; leading "Back")

- Multi-line text field, placeholder "Paste the invite link" (id `inviteText`), with a "Paste" button.
- Button "Continue" (id `continueJoin`), enabled when the text parses as an invite. Opens S8.
- Button "Scan QR code" (`qrcode.viewfinder`, id `scanQR`). Opens S17.
- Footer: "Invite links start with https://{domain}/j/"
- Under the field while it doesn't parse:
  - `missingKey`: "This invite link is incomplete. Copy the whole link from the message, or scan the QR code."
  - `notInvite`, when the field isn't empty: "That isn't a SatMap invite link."

**S8 Join confirmation** (title "Join group"; leading "Cancel")

- If the terms aren't accepted, S1 shows first, in this sheet.
- While E9 runs: a spinner with "Checking the invite…"
- Then:
  - the group name (large, bold)
  - "Invited by {name}"
  - "{n} members"
  - when paused: "This group is paused at the moment."
- Text field "Your name in the group" (id `displayName`), prefilled.
- Text: "Members will see your name. If you share your location, they also see where you are and the trail you
  leave."
- Button "Join group" (id `joinGroup`). On success: S9, then S5 for the group, and it becomes the shown group
  (flow in §9.2).
- Errors:
  - `invite_not_found`: §8.14
  - already a member: "You're already in {group}." with button "Open group"
  - the group profile doesn't open with the link's key: "This invite link is damaged. Ask for a new one."
  - offline: §8.14

**S9 Share your location?** (title "Share your location?"; no leading button)

- Text: "SatMap collects location data to share your position and trail with {group}, even when the app is in the
  background." This is Google's prominent-disclosure sentence; iOS uses it too.
- Rows:
  - "Members see where you are while sharing is on."
  - "They see the trail you leave from now on, until you clear it."
  - "Your location is encrypted on your phone. We can't read it."
  - "Turn sharing off any time in the group."
- Buttons "Share my location" (id `consentShare`) and "Not now" (id `consentNotNow`).
- Footer: "Without sharing you can still see the group and drop pins."
- Location refused (iOS alert): "Location is off for SatMap. Turn it on in Settings › Privacy › Location Services
  to share." Android: the denied path of `PermissionFlow`, with the same text.

**S10 Drop pin** (title "Drop pin"; leading "Cancel")

- A 3 × 2 grid of the six types (§9.4), with icon and short label, ids `pinType-animal` … `pinType-police`. Nothing
  is selected at first.
- With Animal selected: text field "Which animal?", placeholder "e.g. Rhino" (id `pinAnimal`). Required.
- Text field "Note (optional)", placeholder "e.g. Two adults near the water hole" (id `pinNote`).
- A read-only row with the spot's coordinates (`Format.coordinate`).
- Footer:
  - Animal: "Animal sightings disappear after 12 hours. Everyone in {group} sees this pin."
  - Other types: "Everyone in {group} sees this pin until it's deleted."
- Button "Drop pin" (id `dropPinConfirm`), enabled once a type is chosen (and an animal typed for Animal). The pin
  shows on the map at once (queued if offline).
- Filter error: "Please use different words."

**S11 Pin detail** (title: the type label from §9.4, for example "Animal sighting"; leading "Done")

- Header: the pin icon, then the animal name (Animal) or the type label.
- The note, if any.
- "Dropped by {name} · {time} ({ago})"
- Animal pins: "Disappears at {time}"
- Coordinate row and height, as for a tapped spot (tap to copy).
- Buttons, each when allowed:
  - "Delete pin" (destructive; the dropper, admins and the owner). Asks "Delete this pin for everyone?" with
    buttons "Delete" and "Cancel".
  - "Report pin" (S13).
  - "Block {name}" (not on your own pins; the Block alert).

**S12 Member detail** (title: the member's name; leading "Done" or "Back")

- Header: colour dot with initials, name, role.
- State: "Sharing · last position {ago}", "Not sharing" or "Last seen {ago}".
- When there is a live position: its coordinate row, and a button "Show on map" that centres the map on it and
  closes the sheet.
- Owner, for a member: "Make admin". For an admin: "Remove admin".
- Owner, for anyone else: "Make owner" (S14).
- Owner, or an admin for a member: "Remove from group" (destructive). Asks "Remove {name}?" with message "{name}'s
  trail and pins in this group are deleted. They can only come back with a new invite." and button "Remove".
- Not for yourself: "Report {name}" (S13) and "Block {name}" (the Block alert) / "Unblock {name}".
- For yourself: "Your name and colour" (S15).

**S13 Report** (title "Report"; leading "Cancel")

- "Why are you reporting this {pin / name / group name}?" with five choice rows, ids `reportReason-<reason>`:
  - "Offensive or hateful" (`offensive`)
  - "Harassment or bullying" (`harassment`)
  - "Spam" (`spam`)
  - "Dangerous or illegal" (`dangerous`)
  - "Something else" (`other`)
- Text field "More details (optional)".
- Text: "We'll get a copy of this {pin's text / name / group name} to review, without any position. We review
  reports within 24 hours."
- Button "Send report" (id `sendReport`). Afterwards, for a pin or member, an alert "Thanks for reporting" with
  message "Do you also want to block {name}? You won't see them on this phone. They can still see you while you
  share." and buttons "Block" and "Not now". For the group name, the alert "Thanks for reporting" with "OK" only.

**S14 Hand over ownership**

- From S5, a member picker (title "Hand over to") lists the members; from S12 the member is already chosen.
- Then the alert "Make {name} the owner?" with message "{name} needs SatMap Pro to accept. You'll become an admin. If
  they don't accept within 7 days, the request ends." and buttons "Send request" and "Cancel".

**S15 Your name and colour / Rename group** (title "Your name and colour" or "Rename group"; leading "Cancel";
trailing "Save")

- One text field.
- "Your name and colour" also has a row of the 12 colour dots.
- Filter errors as in S4.

**S16 Blocked** (title "Blocked")

- Rows: the blocked member's real name, each with an "Unblock" button.
- Footer: "Blocked members are hidden on this phone only. They aren't told, and they can still see you while you
  share in this group."

**S17 Scan QR code** (title "Scan QR code"; leading "Cancel")

- The camera view with a square guide. Text: "Point the camera at the invite QR code."
- A found invite closes the scanner and opens S8.
- No permission: "Camera access is off for SatMap. Turn it on in Settings to scan a code." with an "Open Settings"
  button.

**Alerts**

| Alert | Title | Message | Buttons |
| --- | --- | --- | --- |
| Removed | "Removed from {group}" | "An admin removed you from this group. Its positions, trails and pins are gone from this phone." | OK |
| Deleted | "{group} was deleted" | "The owner deleted this group." | OK |
| Gone | "{group} is no longer available" | "The group no longer exists, or your membership ended." | OK |
| Clear trail | "Clear your trail?" | "Everyone in {group} stops seeing where you've been. Your own track in Where I've been stays. This can't be undone." | "Clear my trail" (destructive) |
| Leave | "Leave {group}?" | "Your trail and pins in this group are deleted. You can only come back with a new invite." | "Leave group" (destructive) |
| Delete group | "Delete {group}?" | "The group, its trails and pins are deleted for everyone. This can't be undone." | "Delete group" (destructive) |
| Block | "Block {name}?" | "You won't see {name}'s position, trail, pins or name on this phone. They won't be told, and they can still see you while you share in this group." | "Block" (destructive); while you share in that group also "Block and stop sharing" (destructive, id `blockStopSharing`: blocks, then switches sharing off in that group) |
| Stop sharing | "Sharing is off" | "Members can't see where you are now. The trail you already shared in {group} stays visible to them until you clear it." | "Clear my trail too" (destructive; clears at once, as "Clear my trail") and "Keep trail" |

Every alert above with a destructive button also has "Cancel", except "Stop sharing", whose "Keep trail" is the
cancel action (sharing is already off when it shows).

**Times** (`GroupFormat.ago`; s = whole seconds of age, which may be negative when clocks differ):

| Age | Text |
| --- | --- |
| s < 60 (also negative) | "just now" |
| s < 3600 | "{m} min ago", m = floor(s / 60) |
| s < 172 800 | "{h} h ago", h = floor(s / 3600) |
| otherwise | "{d} d ago", d = floor(s / 86 400) |

Vectors: `vectors.json` `ago`.

### 9.4 Pin types

| `type` | Label (titles) | Short label (grid) | iOS SF Symbol | Android (`SatIcons`, drawn to match) | Colour |
| --- | --- | --- | --- | --- | --- |
| `animal` | Animal sighting | Animal | `pawprint.fill` | `Paw` | `#2E7D32` |
| `shelter` | Shelter | Shelter | `house.fill` | `House` | `#8D6E63` |
| `water` | Water | Water | `drop.fill` | `Drop` | `#1E88E5` |
| `danger` | Danger | Danger | `exclamationmark.triangle.fill` | `Warning` | `#E53935` |
| `pothole` | Pothole | Pothole | `smallcircle.filled.circle` | `Pothole` | `#F57C00` |
| `police` | Police | Police | `shield.lefthalf.filled` | `Shield` | `#3949AB` |
| unknown | Pin | none | `mappin` | `Pin` | `#757575` |

### 9.5 Map drawing

**Member dots.**

- A 26 pt/dp circle in the member's colour, with a 2 pt white ring and white bold 11 pt initials.
- Heading: a white triangle, 8 pt wide and 6 pt deep, just outside the ring, pointing the heading's way. Left out
  when the heading is unknown.
- Stale (over 10 min): grey fill `#8E8E93`, with the ring in the member's colour, and no heading triangle.
- Hidden after 24 h.
- Not drawn for you: your own blue dot stays as it is.
- Initials (`GroupText.initials`), on code points:
  - If the name's first code point is pictographic (U+2600–U+27BF or U+1F000–U+1FAFF), the initials are that
    emoji: the code point, plus each following U+FE0E, U+FE0F, U+20E3, U+1F3FB–U+1F3FF or U+E0020–U+E007F, plus
    U+200D together with the code point after it, repeatedly; a regional indicator (U+1F1E6–U+1F1FF) takes the
    next one if it is also a regional indicator, and nothing more.
  - Otherwise: split the name into words on the §2.5 White_Space set and keep the words that contain a letter (L*)
    or decimal digit (Nd). The initials are the first such code point of the first kept word, plus that of the
    last kept word when there are two or more, each upper-cased with the full Unicode mapping (`ß` gives `SS`).
  - With no kept word: `?`.
  - Vectors: `vectors.json` `initials`.
- Drawing order: your dot, then member dots, then pins, then KML labels.
  - iOS: `MemberDotView` with zPriority just under the user dot's; collision mode `.circle`.
  - Android: a symbol layer pair `group-member-arrows` (`icon-rotate` = heading, `icon-rotation-alignment` map)
    under `group-members` (a per-member bitmap: app-drawn, because the style has no glyphs). Both allow overlap.

**Trails.**

- A solid line in the member's colour, 85 % opaque, 3 pt wide (2 pt when more than 10 members have visible
  trails).
- Segments as in §4.2.
- The "Trails" setting: Off, Last 24 hours (the default) or Everything.
- Thinning for big groups, done in core (`GroupMapContent.trails` arrives thinned, with `widthPt` set):
  `Simplify` (GeoMath) with a tolerance of 3 m for up to 10 members with visible trails, 10 m for 11–30, and 25 m
  above 30. At most the newest 4000 points per member after simplifying.
- iOS: one `GroupTrailOverlay` per member, a copy of `TrackOverlay` holding the colour, with a solid
  `GroupTrailRenderer`. Inserted above the KML blocks and below your track: `insertOverlay(_:below: trackOverlay)`.
- Android: one GeoJSON source `group-trails` holding a MultiLineString feature per member with a `colour`
  property; the line layer `group-trails` uses `line-color: ["get","colour"]` and `MapStyle.zoomScaled` widths.

**Pins.**

- A 30 pt/dp circle in the type's colour, with a 2 pt white ring and a white 16 pt glyph.
- Animal pins have a label under them, `MapPin.label` = "{animal} · {ago}" from core (white bold 12 pt on a 65 %
  black capsule), refreshed every minute.
- iOS: `GroupPinView`.
- Android: source `group-pins`, with a symbol layer `group-pins` (icon, allow-overlap true) and
  `group-pin-labels` (label bitmaps, allow-overlap false).

**Android layer order** (add to `MapIds`, `MapStyle.sources`, `overlayLayers` and DESIGN §3.8):

1. `group-trails`, after the KML fill and line layers and before `track`
2. `group-pins`, `group-pin-labels`, `group-member-arrows` and `group-members`, after the KML points and before
   `measure-markers`

The car map installs the sources too, but they stay empty (SD7). iOS CarPlay gets no group layers.

**Camera.** When a group becomes the shown group from S5 or S8, the map fits every live member position plus
yours, with 60 pt/dp padding and at least 300 m across. With no live positions, the camera doesn't move.

**Binding.**

- iOS: `MapController.bind()` subscribes to `model.groups.$mapRevision`. A new `GroupLayer` (like `AviationLayer`)
  adds, moves and removes annotations and overlays from `hub.mapContent(now:)`. Moving a member animates through
  the annotation's `@objc dynamic coordinate`.
- Android: `MapController.bind()` collects `model.groups.mapRevision` and updates the GeoJSON sources off the main
  thread.

### 9.6 Accessibility ids and test tags

- iOS `accessibilityIdentifier` values equal the Android testTags.
- Ids used in §9.3: `groupsButton` (Android only; iOS tests tap the label "Groups"), `dropPin`, `agreeTerms`,
  `groupRow`, `createGroupRow`, `joinLinkRow`, `planYearly`, `planMonthly`, `planLifetime`, `buyPro`,
  `restorePurchases`, `groupName`, `displayName`, `createGroup`, `acceptOwner`, `showOnMap`, `shareLocation`,
  `trailsMode`, `memberRow`, `inviteSomeone`, `inviteQR`, `shareInvite`, `copyInvite`, `inviteText`,
  `continueJoin`, `scanQR`, `joinGroup`, `consentShare`, `consentNotNow`, `pinType-<type>`, `pinAnimal`,
  `pinNote`, `dropPinConfirm`, `reportReason-<reason>`, `sendReport`, `reportGroup`, `supportCode`,
  `blockStopSharing`.
- Banners: `groupPausedHint`.
- Map annotations: accessibility label "{name}, {state}" for member dots, and "{title}, dropped by {name} {ago}"
  for pins, where {title} is the animal name for animal pins and the type label otherwise (both from core,
  `a11yLabel`). iOS member dot views get the identifier `groupMember` and pin views `groupPin`. Android has no
  views for them; the test-only accessor `MapController.groupItemScreenPoints()` returns each dot's and pin's
  screen point.

### 9.7 New screenshots and walkthrough steps

Both walkthroughs (`SatMapUITests/ScreenshotTests.swift`, `android/app/src/androidTest/.../ScreenshotTest.kt`)
launch in demo mode (§8.15) and append these steps after the existing ones. The earlier steps don't change, but
shots 01–27 are regenerated: they now show the (inactive) Groups button, and nothing else of groups, because the
demo's "Kruger trip" starts hidden.

The Android Robolectric tests also render each screen (`ScreenRendersGroupsTest`, same names, `IPHONE_SCREEN`).
Robolectric can't draw MapLibre (as `ScreenRendersMapTest` notes), so for the map shots 36, 38 and 40 it checks
only the overlays (the Groups button, the banner, the drop-pin capsule); the emulator walkthrough checks the map.

| # | Name | Steps |
| --- | --- | --- |
| 28 | `28-groups-terms` | Tap "Groups"; S1 shows |
| 29 | `29-groups-list` | Tap "Agree and continue"; S2 with "Kruger trip" and "Drone team" |
| 30 | `30-paywall` | Tap "Create group"; S3 (fake prices R79.99 / R399.99 / R1,199.99) |
| 31 | `31-create-group` | Tap "Subscribe" (the fake purchase succeeds); S4 with "Weekend braai" and "Sam" typed. Then tap "Cancel". |
| 32 | `32-group-detail` | Tap "Kruger trip"; S5 |
| 33 | `33-invite-qr` | Tap "Invite someone"; S6. Then tap "Done". |
| 34 | `34-join-group` | Back to S2; "Join with an invite link"; type `GroupDemo.inviteLink`; "Continue"; S8 |
| 35 | `35-share-consent` | Tap "Join group"; S9. Then tap "Share my location". |
| 36 | `36-group-map` | Open "Kruger trip", switch on "Show on the map", close the sheet; the map fits the group (dots, trails, six pins) |
| 37 | `37-drop-pin` | Tap an empty spot of the map: the first point of a 40 pt/dp grid, scanning outwards from the map's centre, that is at least 44 pt/dp from every member dot and group pin (iOS: the frames of the `groupMember` and `groupPin` elements; Android: `MapController.groupItemScreenPoints()`) and from the screen's overlays. Then tap "Drop pin"; choose Animal; type "Rhino"; S10 |
| 38 | `38-pin-detail` | Tap "Drop pin"; then tap the Kudu pin; S11. iOS finds the pin view by its accessibility label (it starts with "Kudu"). Android asks `MapController` for the pin's screen point through a test-only accessor, inside `scenario.onActivity`, then taps there. |
| 39 | `39-member-detail` | Groups → "Kruger trip" → member row "Thandi"; S12 |
| 40 | `40-group-paused` | Groups → "Drone team" → switch on "Show on the map", close; the map with the paused banner |
| 41 | (no screenshot) | Link check (§5.6), with the scheme form of `GroupDemo.inviteLink2` ("Braai club", not joined). iOS: `XCUIDevice.shared.system.open(URL(string: <that satmap:// link>)!)`; if Springboard asks 'Open in "SatMap"?', tap "Open" (through `XCUIApplication(bundleIdentifier: "com.apple.springboard")`); then `app.buttons["joinGroup"].waitForExistence(timeout: 10)` must be true. Android: covered by the instrumented `InviteIntentTest` (§5.6), which uses the same link. |

The fake prices come from the fake Pro store in demo mode, so screenshots are stable.

### 9.8 README

Add rows 28–40 to the iPhone screenshot table (iOS UI part) and the Android table (Android UI part), in the existing
style. Add a short "Groups" paragraph (iOS UI part).

### 9.9 Allowed differences between the apps (write each at the code site)

- Store names in texts: "App Store" / "Apple ID" versus "Google Play" / "Google account".
- Permission prompts and system share sheets.
- QR scanning: VisionKit/AVFoundation versus CameraX/ZXing.
- Android's location notification text, and its Stop button, which also switches sharing off in every group
  (§11.6).
- The Keychain versus Keystore storage details.
- The clipboard flags for "Copy link" (§9.3 S6) and the landing page's fallback link (`satmap://` on iPhone,
  `intent://` on Android, §5.4).
- Android's `proCache.expiresAt` is always null (Play Billing gives no expiry, §10.6).

---

## 10. Pro

### 10.1 Products

Prices are from the pricing research, including 15 % VAT, typed in by hand on both stores (not converted from USD).
No free trial and no introductory offer at launch.

| Plan | Apple product id (type) | Google product | Price |
| --- | --- | --- | --- |
| Monthly | `pro.monthly`: auto-renewable, subscription group "SatMap Pro", level 2 | Subscription `pro`, base plan `monthly` (P1M, auto-renewing) | R79.99 |
| Yearly | `pro.yearly`: auto-renewable, same group, level 1 | Subscription `pro`, base plan `yearly` (P1Y, auto-renewing) | R399.99 ("Save 58%") |
| Lifetime | `pro.lifetime`: non-consumable | One-time product `pro_lifetime` | R1,199.99 |

- Family Sharing is off for all three.
- The product ids live in one place per app: `GroupConfig.proProducts` (iOS) and `GroupConfig.proProducts`
  (Android).
- Rough USD equivalents are $4.84, $24.20 and $72.60. A later global base could be $4.99 / $24.99 / $69.99.
- If sign-ups are low, try a 3-day trial on Yearly only (a later decision).

### 10.2 Store setup (owner tasks, later)

**App Store Connect:**

- the subscription group and the three products, as above
- a notification URL `https://{domain}/v1/store/apple`, V2, for production and sandbox
- an In-App Purchase key for the App Store Server API secrets (required, §7.8), and the app's numeric Apple ID for
  `APPLE_APP_APPLE_ID`

**Play Console:**

- the subscription `pro` with its two base plans, and the one-time product `pro_lifetime`, all in ZAR
- real-time developer notifications to a Pub/Sub topic, with a push subscription to
  `https://{domain}/v1/store/google?token={GOOGLE_RTDN_TOKEN}`, with voided-purchase and one-time notifications on
- a service account with "View financial data" and "Manage orders" access, for `GOOGLE_SERVICE_ACCOUNT_JSON`

### 10.3 iPhone: StoreKit 2 (`SatMap/Groups/ProStore.swift`)

- An `ObservableObject` made at launch. Its listener runs `for await update in Transaction.updates`, started in a
  detached `Task` at launch. It handles verified transactions, calls `await transaction.finish()`, then
  `refresh()` and `groups.updatePro(proof, productId: best.productID, expiresAt: best.expirationDate)` (seconds;
  nil for Lifetime).
- `refresh()` reads `Transaction.currentEntitlements`, keeps only `.verified` Pro transactions, and picks the best:
  lifetime, else the subscription with the latest `expirationDate`. It sets `isPro` and writes `proCache`.
- `purchase(plan)`: `try await product.purchase()`. `appAccountToken` is not used. `purchase(options:)` is the API
  that works from iOS 16 (`purchase(confirmIn:)` needs 18.2).
  - `.success(.verified(t))`: finish, refresh, success.
  - `.pending`: the pending text.
  - `.userCancelled`: nothing.
  - Unverified results are ignored.
- `restore()`: `try await AppStore.sync()` (it may show an Apple ID prompt, so only on a tap), then `refresh()`.
- `proof()`: `.apple(signedTransaction: best.jwsRepresentation)`. Take the `jwsRepresentation` of the
  `VerificationResult` that held it.
- CI tests do not buy anything; demo mode fakes the store. **[Unconfirmed]** whether `xcodebuild` UI tests on CI
  pick up a scheme's `.storekit` file, so it isn't used.

### 10.4 Android: Play Billing 9.1.0 (`app/groups/ProStore.kt`)

- Add `com.android.billingclient:billing-ktx:9.1.0` to `libs.versions.toml`. Version 8 is the minimum for new
  updates now; minSdk 26 is fine.
- Code it behind an interface `BillingGateway`, so Robolectric tests use a fake.
- The client:
  `BillingClient.newBuilder(ctx).setListener(listener).enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).enableAutoServiceReconnection().build()`.
- Product details: `queryProductDetails` for `pro` (SUBS) and `pro_lifetime` (INAPP). The offer token comes from
  `subscriptionOfferDetails` with the matching `basePlanId`, or from `oneTimePurchaseOfferDetailsList`.
- `launchBillingFlow` with `ProductDetailsParams` (product and offer token).
- In `onPurchasesUpdated` and in `queryPurchasesAsync` (SUBS and INAPP, at start and on every resume):
  - PURCHASED and not acknowledged: `acknowledgePurchase`
  - PENDING: the pending text
  - `isSuspended`: not Pro
- "Restore purchases" runs `queryPurchasesAsync` again.
- `proof()`: `.google(productId, purchaseToken)` of the best purchase (lifetime first). `groups.updatePro(proof,
  productId: purchase.products.first, expiresAt: nil)` after every purchase result: `Purchase` carries no expiry
  time and no base-plan id, so Android never knows the subscription's expiry; the server finds it (§7.6).

### 10.5 Paywall

S3 (§9.3). The paywall opens from:

- "Create group" when not Pro
- "Renew SatMap Pro" (S5) when not Pro
- "Accept" an ownership offer when not Pro

When the phone already has Pro (`isPro`), "Renew SatMap Pro" sends E5 and "Accept" sends E17 directly, so nobody
is invited to buy twice.

### 10.6 Pro cache

`proCache` (preference) is JSON `{"productId": "pro.yearly", "expiresAt": 1825149600 or null, "checkedAt": t}`.
`expiresAt` is null for Lifetime, and always null on Android, where Play Billing gives no expiry (there the cache
means "Play said Pro at `checkedAt`").

- Written after every store result.
- Read at launch to show Pro state at once and offline.
- Never sent to the server.
- If the store says not Pro while online, the cache is cleared.

### 10.7 Sending the proof to the server

| When | Endpoint |
| --- | --- |
| Creating a group | E1 |
| Accepting ownership | E17 |
| At launch, and on every store update (`hub.updatePro(proof, productId:, expiresAt:)`), for each group the phone owns, when any rule below holds | E5 |

`updatePro` sends E5 for an owned group when any of these holds:

1. `status.pro` is null;
2. `status.pausedReason` is a Pro reason (`pro_ended`, `pro_refunded`, `pro_unverified`);
3. `productId` differs from `status.pro.productId`;
4. `expiresAt` is known and more than `status.pro.expiresAt + 60` (iPhone renewals the server hasn't seen);
5. for a Google proof (only Android makes them; both cores apply the rule): the proof's `purchaseToken` differs
   from `groups.pro_sent` for that group (a new purchase, an upgrade or a resubscription; renewals keep the token,
   and the server learns their expiry itself, §7.6).

After E1, E5 or E17 succeeds, `pro_sent` is set to the token sent (Android). A `402` from E5 changes nothing on the
phone; the server's status decides what is shown.

### 10.8 Lapse and handover

- The server pauses the group when the store says Pro ended (§7.6). It is immediate: at the expiry alarm, or on the
  notification.
- Every member sees S5's paused section and the map banner. The owner sees "Renew SatMap Pro" and "Hand over
  ownership".
- Renewing sends E5, which resumes.
- Handing over: the offer (E15) and the accept with the new owner's proof (E17) resume the group under the new
  owner's purchase.
- Pro does not cross platforms (R14). An owner who moves from iPhone to Android buys Pro again or hands over.

---

## 11. Privacy and compliance tasks

### 11.1 Privacy notice and terms (POPIA)

The Worker serves `/privacy` and `/terms` as simple HTML pages (`src/web/pages.ts`, same look as the landing page),
with `Cache-Control: public, max-age=3600` (as `/` and the well-known files; §6.1's `no-store` is for `/v1`).
The server part drafts them from these points, and the owner reviews them before release (a release blocker).
Both apps link to them (S1, S2, S3).

**Privacy notice points:**

1. **Responsible party:** the developer's name and `CONTACT_EMAIL`. The developer registers an Information Officer
   with the Information Regulator (owner task). There is no variable for the name: the draft says "Jacobus Oettle,
   the developer of SatMap", a constant in `src/web/pages.ts`, for the owner's review.
2. **What is processed:**
   - random ids, role, join time and a hash of the member pass
   - encrypted names, positions, trails and pins, which the developer cannot read
   - upload and live-update times and sizes
   - Pro purchase records: Apple transaction id or Google purchase token, product, expiry
   - reports a user sends: a decrypted copy of the name or text, the reason and comment, never positions, plus
     the server's own facts about the reported item (when it was made, by which member id, its size)
   - ids of removed pins, trails and members, kept 400 days so other phones can delete their copies
   - IP addresses, for security and rate limiting: not stored by us; Cloudflare processes them at its edge
3. **Purposes:** running groups, checking Pro, handling reports, security and abuse prevention.
4. **Lawful basis:** consent (location sharing, S9); performing the service the user asked for; legitimate
   interest (security).
5. **Who receives what:**
   - group members (they can decrypt the group's content)
   - Cloudflare (hosting, as processor)
   - Apple and Google (purchase checks)
   - No selling, no advertising, no tracking.
6. **Cross-border transfer:** stored on Cloudflare's servers outside South Africa (there is no South African data
   location option), under POPIA section 72(1)(a): Cloudflare is bound by its data processing addendum, which
   gives an adequate level of protection.
7. **Retention:** §7.10 in plain words, including the 30-day deletion after a pause. Also: "Deleting the app
   doesn't take you out of a group; your trail stays until you leave or clear it." and "On iPhone, reinstalling
   the app restores your groups from the phone's keychain."
8. **Rights:**
   - access and correction: change your name in the app, or write to the contact address
   - deletion: Clear my trail, Leave group, Delete group, or write to the contact address
   - objection; withdrawing consent: switch sharing off (and clear your trail)
   - complaint to the Information Regulator (https://inforegulator.org.za)
   - "We can't tell who you are from our data. When you write about your data, quote the support code shown at
     the bottom of the group's screen."
9. **Security:** end-to-end encryption; "the group key travels only inside the invite links you send; our server
   never receives it"; passes stored as hashes. Also say plainly that anyone who ever had an invite link to a
   group keeps its key (§3.5).
10. **Children:** Groups is not meant for people under 18 without a parent's or guardian's permission; S1 asks
    users to confirm this.
11. **Version and date** of the notice.

**Terms of use points:**

- Who may use groups.
- Acceptable use: nothing illegal, hateful, harassing, sexual, dangerous or spam. Follow the law and park rules,
  including any rules about sharing wildlife sightings.
- Reports and removal of content and members (by group admins and by the developer).
- Pro billing, renewal and the pause on lapse.
- No guarantee of position accuracy or availability: don't rely on groups in an emergency.
- Ending use.
- Contact.
- South African law.

### 11.2 iPhone purpose strings and Info.plist

- `NSLocationWhenInUseUsageDescription`: "SatMap shows where you are on the map and records the dotted track of
  where you have been, also while the app is in the background. If you turn on sharing in a group, your position
  and track are also shared with that group's members."
- New `NSCameraUsageDescription`: "SatMap uses the camera to scan a group's invite QR code."
- No "Always" location. The existing When In Use plus background location mode is enough, with the blue
  indicator.
- New keys: `CFBundleURLTypes` (§5.6) and `SatMapGroupsDomain` (§5.1).
- `build.yml`'s Info.plist check adds `NSCameraUsageDescription`, `CFBundleURLTypes` and `SatMapGroupsDomain`.

### 11.3 iPhone `PrivacyInfo.xcprivacy` and App Store privacy label

Add `NSPrivacyCollectedDataTypes` entries, each linked to the user, not used for tracking, purpose
`NSPrivacyCollectedDataTypePurposeAppFunctionality`:

- `NSPrivacyCollectedDataTypePreciseLocation`
- `NSPrivacyCollectedDataTypeName` (display names)
- `NSPrivacyCollectedDataTypeOtherUserContent` (group names, pins, notes, reports)
- `NSPrivacyCollectedDataTypeUserID` (member id)
- `NSPrivacyCollectedDataTypePurchaseHistory`

The App Store Connect privacy answers match: Location (precise), Contact Info (name), User Content (other),
Identifiers (user ID) and Purchases. All "linked to you", none "used to track you".

**[Unconfirmed]** whether end-to-end encrypted content counts as "collected" under Apple's definition. Declaring it
is the safe default; the owner may narrow it after review.

### 11.4 Google Play Data safety answers

| Data type | Collected | Shared | Optional | Purpose |
| --- | --- | --- | --- | --- |
| Location: precise location | Yes | No (transfers to other users the user chose are not "sharing") | Yes | App functionality |
| Personal info: name (display name) | Yes | No | Yes | App functionality |
| Personal info: user IDs (member id) | Yes | No | Yes | App functionality |
| Financial info: purchase history | Yes | No | Yes | App functionality |
| App activity: other user-generated content (pins, notes, reports) | Yes | No | Yes | App functionality |

- Data is encrypted in transit: yes.
- Users can ask for deletion: yes (in the app, and through the contact address).
- **[Unconfirmed]** whether end-to-end encrypted data is exempt. Declared to be safe.

### 11.5 Play declarations

- **Foreground service type `location`:** update the declaration to say the service records the track and, when
  the user turns it on, shares the position with the user's group.
- No `ACCESS_BACKGROUND_LOCATION` (unchanged).
- **Prominent disclosure:** S9's first sentence, shown before sharing starts.
- **New permission `CAMERA`:** in the manifest with `<uses-feature android:name="android.hardware.camera"
  android:required="false"/>`, asked at first scan.
- **App content:** the user-generated content questionnaire (in-app reporting, blocking, moderation), target
  audience not children, and Data safety as in §11.4.

### 11.6 Android location notification

Today the notification's title is "SatMap" and its content text "Recording your track" (`LocationService.kt`). While
any group shares:

- the content text stays "Recording your track" (the collapsed view)
- a `NotificationCompat.BigTextStyle` holds two lines, "Recording your track" and then:
  - one group: "Sharing your location with {group}."
  - several groups: "Sharing your location with {n} groups."

The title stays "SatMap". Its Stop action stops recording (as today) and switches sharing off in every group. This
is an Android-only difference (§9.9).

### 11.7 Store user-generated content rules (Apple guideline 1.2, Google UGC policy)

| Requirement | How it is met |
| --- | --- |
| Filter objectionable material | §8.12 |
| Report content | S11, S12, S13, S5 "Report group name", E25 (members, pins and the group name) |
| Act on reports within 24 hours | Admin endpoints A1–A5 (§7.7); `REPORT_WEBHOOK_URL` or a written daily A1 routine (release blocker, §14 R1); reports carry server facts so action rests on corroboration (SD28) |
| Block abusive users | §8.11 |
| Published contact information | S1, S2, S5, the landing page and `/privacy` |
| Terms accepted before use | S1 |

### 11.8 Other compliance items

- **Export compliance:** encryption comes from the operating systems (CryptoKit, `javax.crypto`) with standard
  AES-GCM. `ITSAppUsesNonExemptEncryption` stays `false`, pending the owner's answers to the App Store Connect
  questionnaire (owner task).
- **Age ratings:** update both questionnaires for user-generated content and location sharing (owner task).
- **Account deletion** (Apple 5.1.1(v)): there are no accounts. Leave group and Delete group remove a person's
  group data, which covers the rule's intent.

---

## 12. Test plan and CI

### 12.1 Server (`server/test`)

**Unit tests:**

- base64url, including `mustFail.badBase64url`; canonical ids (`mustFail.nonCanonicalIds` answer 400)
- envelope validation per kind (§6.8)
- token split
- the three hash vectors
- the AAD builders and the test client's AES-GCM and HKDF against all `records` vectors
- the Apple JWS verifier, with a test chain: root, intermediate and leaf, carrying the two Apple OIDs, valid
  2026–2046. The chain is made once by `server/test/fixtures/make_apple_test_chain.py` (Python `cryptography`)
  and committed as PEM, with a leaf key to sign test JWS. Cases:
  - a valid JWS
  - a wrong root
  - a missing OID; an OID whose bytes appear only inside a name (must fail)
  - an intermediate that is not a CA (must fail)
  - a forged intermediate and a forged leaf: the right issuer name, signed by another key (must fail)
  - a bad signature
  - a wrong bundle id or product
  - Family Sharing
  - expired, revoked, Xcode environment (on and off, and on without `TEST_MODE`)
  - Sandbox with `ALLOW_TEST_PURCHASES` false (fails) and true (active only for 14 days after
    `originalPurchaseDate`)
  - notifications: another bundle id, a Production notification with the wrong or unset `appAppleId`, a renewal
    info whose `originalTransactionId` differs; an `EXPIRED` and a `REFUND` notification carrying an expired or
    revoked transaction must verify
- Google decision tables, with `httpFetch` replaced: a `pro_lifetime` proof whose `productLineItem` names another
  product (`pro_invalid`); `SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED`, `_UNSPECIFIED` and an unknown state
  (inactive); API 404 and 410 (`pro_invalid`); a malformed token never calls the API; a refused token is answered
  from memory within 10 minutes; `ALLOW_UNVERIFIED_PRO` ignored without `TEST_MODE`
- the notification decision tables (§7.6), and the ordering rule: `REFUND`, then E5 with the pre-refund JWS
  answers `402 pro_inactive`; `EXPIRED` followed by an older-signed `DID_RENEW` leaves the group paused
- the landing page (headers, CSP hash, token check)
- AASA and assetlinks from variables

**Integration tests** (`exports.default.fetch`, `runInDurableObject` for the clock, `runDurableObjectAlarm`):

- create, with a retry
- invites: create, list, cancel, single use, 7-day expiry, wrong proof, preview doesn't use up; a removed admin's,
  a demoted admin's and a departed admin's pending invites answer `404 invite_not_found` at E9 and E10
- redeem: atomic and idempotent; two redeems at once, only one wins
- the role table: every cell of §1.3
- remove: R2 objects gone, 410 for 30 days then 401; a new joiner reusing the removed `memberId` gets
  `409 member_exists`
- leave; the owner can't leave
- ownership: offer, accept with Pro, decline, expiry
- upload rules: every rejection code
- changes:
  - paging
  - `joinedAt` filtering, with a chunk before, straddling and after
  - removals
  - reset below `minCursor`
- archive pass:
  - rows move to R2 and E20 returns them, filtered; the `archive` item carries `maxChunkSeq` and `archivedAt`
  - a late upload is merged; a row replaced during the pass stays for the next pass
  - E22 interleaved with a pass (a gated R2 stub holds the pass between its R2 write and its transaction):
    afterwards there is no `archives` row and no object
- R2 failures: a clear-trail whose R2 delete throws keeps its `r2_deletes` row and completes on a later alarm; a
  purge whose R2 delete throws twice still completes, and SQLite is untouched until it does
- pins: 12 h expiry by alarm; rate limit; an E23 retry for a deleted `pinId` answers `410 pin_deleted`
- clear trail
- pause on store inactive (no uploads, live cleared, banner status); resume on E5 and on transfer
- `pro_check` with an ACTIVE answer whose expiry is already past re-checks every 10 min, 6 times, then pauses
  `pro_unverified`
- `terms_outdated` on E1 and E10; reports: unknown target `404`, the stored facts, `reporters` in A1, a group-name
  report, reports while paused
- 30-day purge: `deleteAll`, then 404
- delete group: 410 then 404
- reports and admin endpoints, and `ADMIN_TOKEN` unset gives 404; E28 with `GOOGLE_RTDN_TOKEN` unset or short
  gives 404
- logging: a spy on `console.log` sees no id-shaped strings

**WebSocket tests** (`test:ws`, separate project, `--no-isolate` (§7.1); RPC to a DO with open sockets runs here):

- hello and welcome
- `ping` gets `pong`
- an upload gets `uploaded`, and the others get one `tick`, combined within 2 s
- `watchers` counts across two members
- `more` and `ack`
- a late joiner gets no tick items for windows before its `joinedAt`, even when another member uploads old
  windows while both sockets share a cursor
- close codes 4403 and 4404 on removal and deletion
- hibernation: `evictDurableObject(stub, {webSockets: "hibernate"})`, then a message still works and watcher counts
  are rebuilt
- the fifth socket closes the oldest

### 12.2 Cores

Each core has the same tests: Swift in `Tests/SatMapCoreTests/Group*Tests.swift`, Kotlin in
`android/core/src/test/.../Group*Tests.kt`, ported test for test.

| Test file | What it checks |
| --- | --- |
| `GroupCryptoTests` | Every `vectors.json` subkey (from `subkeyInfo`), record and `mustFail` case |
| `GroupRecordsTests` | Chunk encode and decode, both encodings, limits and rejections, the three `mustFail.chunkPlainHex` chunks; live; `liveRounding`; JSON records: `jsonWriters` exact bytes (padded), every record decodes; coordinates; varints; zigzag; deflate round trip |
| `InviteLinkTests` | `invite.url` and `schemeUrl` round trip; every `inviteParse` case |
| `WordFilterTests` | The §8.12 table and every `wordFilter` case |
| `GroupTextTests` | Every `groupText`, `initials` and `ago` case |
| `GroupStoreTests` | Apply pages (dedupe by `seq`, items at or below the cursor skipped, `joinedAt` drop and trim, removals, reset); archive relabel only when `cursor ≥ maxChunkSeq` and `cursor_at < archivedAt`; outbox versions; ops order; block list |
| `GroupHubTests` | Against `FakeGroupServer`: create, invite, join, both see each other; upload pace per §8.6 (an injected clock); heartbeat without a new fix has no `live` key; fixes worse than 100 m or with location not precise are not sent; packing (50 chunks or 192 KiB); rejected chunks per §4.3 (`before_join`, `window_paused`, `bad_record` deleted; `bad_time` retried once after E2); no chunk upload while E22 is queued; offline queue then flush; paused drops points and stops live; removed, deleted and gone alerts wipe data; a `401` from E18 wipes nothing unless E2 agrees; clear trail; an expired animal pin hides offline; `410 pin_deleted` drops the queued pin; consent gating; a blocked member's data is hidden; member state per §8.9; `terms_outdated` both ways; `updatePro` decisions (§10.7): each of the five rules, Android `pro_sent`, and nothing sent when none holds; lost answers (a flaky transport that holds or loses them): create retried with the same id and pass, join retried with the same pass, an E2 answer for a secret a retry took over, a rename racing a colour fix, a pin deleted while its E23 is on its way; acknowledged windows kept for the grace and a late point joining its whole window; live and chunks in one upload; unreadable secrets delete nothing |
| `GroupContractTests` | Every `api-examples.json` example (§13.1) through `GroupAPI` and `SocketMessage`: the core builds the request the server accepted (method, path, Bearer, body as the server reads it) and reads every answer back to the same JSON, or to its status and §6.2 code; the file covers E1–E26, every §6.2 code and every server frame |

`build.yml` runs `swift test` on macOS. Locally it can run on Linux with
`/opt/swift/swift-6.4.1-RELEASE-ubuntu24.04/usr/bin/swift test`. `android.yml` runs `:core:test`.

### 12.3 Apps

**Android** (Robolectric, locally and in `android.yml`):

- `ScreenRendersGroupsTest`: renders 28–40 (for 36, 38 and 40 only the overlays, §9.7)
- `GroupsModelTest`: state mapping
- `MainActivityTest` additions: the https and satmap intent filters resolve to MainActivity; `handleIncoming` with
  a link, and with `satmap://join/<token>` plus the `groupKey` extra, opens the join sheet; a replay does nothing
- `GroupHttpTest`: OkHttp transport and socket against MockWebServer (headers, status mapping, offline)
- `ProStoreTest`: with the fake `BillingGateway`; `updatePro` gets `expiresAt` nil
- `LocationServiceTest`: the notification title, content text and big-text lines (§11.6)
- `MapScreenTest`, `MapScreenGroupsTest`: the Groups button at the top of the right-hand column (columns side by side
  in a small landscape window, §9.1) and the banner

**Instrumented** (emulator, `android-screenshots.yml`): the walkthrough steps in §9.7, plus `InviteIntentTest`
(§5.6).

**iPhone:**

- `build.yml` compiles the app and checks the plist keys.
- The walkthrough (`screenshots.yml`) covers §9.7.
- `MapButtonGridTests` (core): the map buttons' column, or grid where it doesn't fit (§9.1), the same cases as
  Android's `MapScreenPiecesTest`.
- The link check is walkthrough step 41, inside `ScreenshotTests` (§9.7), not a shell step: a shell step can't see
  accessibility ids, and `simctl openurl` would launch the app without its demo arguments.
- There is no iOS unit-test target: the logic is in core.

### 12.4 New workflow `.github/workflows/server.yml`

```yaml
name: Server
on:
  workflow_dispatch:
  push:
    branches: ["**"]
    paths: ["server/**", "Tests/SatMapCoreTests/Fixtures/groups/**", ".github/workflows/server.yml"]
  pull_request:
    paths: ["server/**", "Tests/SatMapCoreTests/Fixtures/groups/**", ".github/workflows/server.yml"]
permissions:
  contents: read
jobs:
  test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: server
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-node@v5
        with:
          node-version: 22
          cache: npm
          cache-dependency-path: server/package-lock.json
      - run: npm ci
      - run: npm run typecheck
      - run: npm test
      - run: npm run test:ws
      - run: npm run build          # wrangler deploy --dry-run: checks that the Worker bundles; deploys nothing
```

### 12.5 Changes to existing workflows

- `build.yml`: the Info.plist keys (§11.2). Its paths already cover `Tests/` and `SatMap/`.
- `screenshots.yml`: no change. The test passes its own launch arguments, and the link check runs inside it.
- `android-screenshots.yml`: no new step; `InviteIntentTest` runs with the other instrumented tests.
- `android.yml`: no change (its paths cover `android/**`, `SatMap/Resources/**` and the fixtures).

### 12.6 Local end-to-end check (manual, after the merge)

1. `cd server && npx wrangler dev` (with `.dev.vars` setting `TEST_MODE=1` and `ALLOW_UNVERIFIED_PRO=true`).
2. Run the Android debug app on an emulator with `--es groupsBaseURL http://10.0.2.2:8787 --es fakePro YES`.
   `fakePro` exists only in debug builds and only outside demo mode: the Pro store then reports Pro at once and
   its proof is `.google(productId: "pro", purchaseToken: "local-test-" + 20 random base64url characters)`, which
   the server accepts as yearly Pro under `ALLOW_UNVERIFIED_PRO` (§7.6).
3. Create a group, invite, join from a second emulator, share, drop pins, and remove.
4. **Doze (U9), on a real Android phone:** share in a group, put the app in the background with the screen off,
   run `adb shell dumpsys deviceidle force-idle`, and watch the other phone (or the `wrangler dev` log) for uploads
   every 15 or 60 s for 10 minutes. Then `adb shell dumpsys deviceidle unforce`.

This is listed in `server/README.md`. It is not part of CI.

---

## 13. Work breakdown

### 13.1 Parts and file ownership

Only the owner of a file edits it. A part that needs a change in another part's file asks through the integration
step (§13.3), not by editing.

| Part | Owns |
| --- | --- |
| **P1 Server** | `server/**`; `.github/workflows/server.yml`; `Tests/SatMapCoreTests/Fixtures/groups/api-examples.json` (at least one request and response example per endpoint and per error code an app reacts to, written by the integration tests, for the cores' contract tests). Format: `{"examples": [{"id": "E1-created", "method": "POST", "path": "/v1/groups", "request": {…} or null, "status": 201, "response": {…} or null}]}`; ids, passes and envelopes are the vector values where they fit. A raw body that isn't JSON (too large, wrong type) is recorded as `request` null; a binary answer (E20) as `response` null. WebSocket frames are recorded as method `WS`, status 101, `request` the client message and `response` the server frame (`E26-welcome`, `-uploaded`, `-tick`, `-error`, `-ack`), recorded last in a second group with the fixed id `EBESExQVFhcYGRobHB0eHw`. E27 isn't recorded: Apple's signed payload carries a fresh ECDSA signature on every run. |
| **P2 Swift core** | New files in `SatMap/Core/`: `GroupWire.swift`, `GroupCrypto.swift`, `GroupRecords.swift`, `Deflate.swift` (`raw` and `inflateExact`, §4.2), `InviteLink.swift`, `WordFilter.swift`, `GroupAPI.swift`, `GroupStore.swift`, `GroupHub.swift`, `FakeGroupServer.swift`, `GroupDemo.swift`. Also `Tests/SatMapCoreTests/Group*Tests.swift`, `InviteLinkTests.swift`, `WordFilterTests.swift`; `Tests/Shims/COpenSSL/shim.h`; `SatMap/Resources/GroupWords.txt`. No edits to existing core files. |
| **P3 Kotlin core** | The same file names as `.kt` in `android/core/src/main/kotlin/com/jacobusoettle/satmap/core/` and their tests in `android/core/src/test/...`; `android/core/build.gradle.kts` if a dependency is needed (none expected). No edits to existing core files. |
| **P4 iPhone UI** | New `SatMap/Groups/` (`GroupsModel`, `GroupHTTP`, `GroupSocket`, `KeychainSecrets`, `ProStore`, `QRCode`, `QRScanner`), `SatMap/UI/Groups/*.swift` (every §9.3 screen), `SatMap/Map/GroupLayer.swift`. Edits to `App/SatMapApp.swift`, `App/AppModel.swift`, `App/Config.swift` (`GroupConfig`), `UI/MapScreen.swift`, `Map/MapController.swift`, `Info.plist`, `SatMap.entitlements`, `PrivacyInfo.xcprivacy`, `SatMap.xcodeproj/project.pbxproj` (only the `GROUPS_DOMAIN` setting), `SatMapUITests/ScreenshotTests.swift`, `.github/workflows/build.yml`, `.github/workflows/screenshots.yml`, and README's iPhone table and Groups paragraph. |
| **P5 Android UI** | New `app/groups/*`, `app/ui/groups/*`, `app/map/GroupLayers.kt`, `android/docs/spec/groups.md` (the Android mapping, in the style of the other specs). Edits to `MainActivity.kt`, `app/AppModel.kt`, `app/Config.kt`, `ui/MapScreen.kt`, `map/MapController.kt`, `map/MapStyle.kt`, `location/LocationService.kt`, `ui/ShareSheet.kt` (text share), `AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `android/DESIGN.md` (new decisions D23+ and the §3.8, §4.2, §5.6, §5.7 and §8 rows), Android tests, `ScreenshotTest.kt`, `.github/workflows/android-screenshots.yml`, and README's Android table. |
| **Spec** | `docs/groups/SPEC.md`, `docs/groups/vectors.py`, `Tests/SatMapCoreTests/Fixtures/groups/vectors.json`: read-only for all parts |

Notes:

- `android/PORTING.md` gets a short "Group formats" section pointing to this spec. That is P3's job.
- P2 and P3 don't touch `TileCrypto`. Group crypto is new code that copies its pattern.

### 13.2 Rules for working in parallel

- **Core API first.** P2 and P3 each push, as their first commit, every type and signature in §8.2 and §8.2.1,
  compiling, with stub bodies. In that commit the hub's read accessors (`groups`, `detail`, `mapContent`,
  `pinDetail`, `memberDetail`) return a fixed, hand-written plaintext snapshot equal to the §8.15 seed (no crypto,
  store or server needed), and actions return success. P4 and P5 build and take screenshots against those stubs
  from day one; the real `FakeGroupServer` and `GroupDemo` replace the snapshot in step 3 (§13.3).
- **Interop.** The cores agree byte for byte through `vectors.json`. The server and cores agree through §6 and,
  once P1 lands it, `api-examples.json`.
- **Texts.** UI parts copy texts from §9.3 exactly. A text that turns out to be missing is added to this spec, then
  used by both UI parts.
- **No new dependencies** beyond those named here:
  - Android: billing-ktx 9.1.0, ZXing core 3.5.4, CameraX 1.6.2
  - iOS: system frameworks only
  - server: wrangler, `@cloudflare/vitest-plugin`, vitest, TypeScript

### 13.3 Order of integration

1. **Done:** this spec and the vectors.
2. **In parallel:** P1, P2 and P3 to green tests. P4 and P5 build screens, map drawing and the transports against
   the core stubs, using demo mode.
3. **Merge P2 and P3** (both vector suites green, `GroupHubTests` green). P4 and P5 switch from stubs to the real
   core and finish the walkthroughs (§9.7).
4. **Merge P1** (server CI green, `api-examples.json` committed). P2 and P3 add contract tests that parse every
   example (`GroupContractTests` in both cores, §12.2).
5. **Merge P4 and P5** (CI green, screenshots committed). Run the manual end-to-end check (§12.6).
6. **Release blockers** (§14) cleared by the owner, then deployment (§13.4).

### 13.4 Deployment (owner, later; documented in `server/README.md`)

1. Choose the domain. Set `GROUPS_DOMAIN` (iOS), `groupsDomain` (Android) and `PUBLIC_BASE_URL` (server).
2. In the Cloudflare account (Workers Paid plan): create the R2 bucket and the route or custom domain. Keep the two
   rate-limit bindings in `wrangler.jsonc`, changing their `namespace_id`s only if they clash with another Worker
   on the account. Then `npx wrangler deploy`.
3. `wrangler secret put` each secret (§7.8): `ADMIN_TOKEN`, the three `APPLE_*` API secrets,
   `GOOGLE_SERVICE_ACCOUNT_JSON`, `GOOGLE_RTDN_TOKEN` (32+ random characters) and `REPORT_WEBHOOK_URL`. Set
   `APPLE_APP_IDS` (Team ID), `APPLE_APP_APPLE_ID` and `ANDROID_CERT_SHA256`. Check `TEST_MODE` is `0`.
4. Check `https://{domain}/.well-known/apple-app-site-association` and `assetlinks.json`, then
   `adb shell pm verify-app-links --re-verify com.jacobusoettle.satmap`.
5. Store setup (§10.2), the privacy notice and terms review, the word list review, and the contact address.

---

## 14. Open risks and unconfirmed items

| # | Item | Default chosen | How to close it |
| --- | --- | --- | --- |
| U1 | The URL fragment may not survive into the app (universal links, App Links) | Incomplete-link text; landing page button through `satmap://` (iPhone) or `intent://` (Android); paste and scan paths | The CI checks (§5.6) cover only the app's own handling of a link that has the fragment. Whether a tap in WhatsApp, Mail or Chrome keeps it is a real-device check after deployment, on both phones |
| U2 | Associated domains may not work on sideloaded free-Apple-ID builds | Custom scheme fallback and paste/scan | Test on a sideloaded build |
| U3 | The test runtime may not support the Rate Limiting bindings | Tests run with `TEST_MODE=1`, where a missing binding means no limit; production requires both (§7.1) | **Closed:** the bindings work in the test runtime, and the tests exercise both |
| U4 | Which wrangler version first supports `exports` | Use `migrations` | None needed |
| U5 | The newest vitest-plugin and wrangler are under 14 days old | Newest release at least 14 days old | **Closed:** plugin 1.4.4 with wrangler 4.151.1 (§7.1) |
| U6 | Whether CI UI tests pick up a `.storekit` file | Not used; demo mode fakes Pro | None needed |
| U7 | Whether E2E-encrypted data counts as "collected" (Apple and Google forms) | Declared | Owner's legal review |
| U8 | Whether Apple adds SA VAT to its commission (affects net revenue only) | Assumed not | Owner's accountant |
| U9 | Whether a location foreground service keeps network access in Doze | Assumed yes | Manual real-phone check (§12.6 step 4); demo mode can't show it |
| U10 | Durable Object storage limits on the Free plan (10 GB or 1 GB) and whether storage billing is live | Workers Paid plan assumed | Owner checks the account |
| U11 | Fail-closed pause when a store check can't be completed at expiry (SD8) could pause a group during an Apple or Google outage | Pause with "couldn't be confirmed", re-check every 30 min, resume automatically | Owner may relax it to a 6-hour grace later |
| U12 | A `welcome` holding every live entry passes the 512 KiB server frame cap (§6.6) at roughly 3,500 live sharers in one group, and the server then drops it (the client waits for a welcome that never comes). Groups are uncapped (R6). | Accepted for v1: far beyond expected group sizes | Page the welcome's live entries, or send the status alone and let the client fetch E21 |
| U13 | `alarm()` serialising per object | `r2_delete`, the archive pass and `purge` assume the platform never runs two `alarm()` calls of one object at once; the races around them were tested only in the vitest Workers pool | Holds by the Durable Objects model; watch for it after deployment |
| R1 | Release blockers | | Contact address; domain; word list review (known false positives: `nigga*` and `nigger*` also block "niggard" and "niggardly"; `*kaffir*` matches across words in the letters-only text, as in "Kaf firewood"); privacy notice and terms review; store products; Information Officer registration; export compliance answers; age ratings; the App Store Server API key and `APPLE_APP_APPLE_ID`; `GOOGLE_RTDN_TOKEN`; `REPORT_WEBHOOK_URL` or a written daily A1 routine (S13 promises review within 24 hours) |
| R2 | An invite link is a bearer secret: anyone who gets it can join once. It also carries K: anyone who ever held any invite link for the group (used, cancelled or expired) keeps K for good and, with a copy of the server's data, could read the group; an operator who changed the landing page could read K from links opened in a browser; on iPhone another app registering `satmap://` could catch it (§3.5) | Short life (7 days), single use, owners and admins can cancel, the share text warns; the landing page clears the key from history, Android's fallback names the package, the clipboard copy is local and expires | Accepted (owner decisions R2, R3, R4, R12) |
| R3 | Removed members keep data they already downloaded, and the key (no rotation) | Server access ends at once; their pending invites are cancelled (SD23) | Accepted (R4) |
| R4 | Some parks have rules about sharing wildlife sightings, rhino especially (information only; the owner's decision R10 stands) | The terms ask users to follow park rules; pins are padded so their size doesn't name the animal (SD25) | The owner may want to check the parks' current rules |
| R5 | Pro proofs are bearer credentials: whoever holds a signed transaction or purchase token (shared by a friend, or leaked) can attach up to 20 groups to that purchase and use up the buyer's quota without the buyer seeing it | The 20-group cap (SD9); test purchases capped at 14 days (SD20); refunds can't be undone by an old proof (§7.6) | Accepted for v1. Binding proofs to an install (`appAccountToken` / `obfuscatedAccountId`) would break Restore on a new phone without accounts; revisit if abuse shows up |
| R6 | Blocking is one-way and only on this phone: the blocked person still sees you while you share, and someone who rejoins with a new invite is not blocked (no accounts) | The Block alert and S16 say so; "Block and stop sharing"; admins and the owner can remove members (SD27) | Accepted (R1, R11) |
| R7 | Reports can be made up: the server can't check the reporter's decrypted copy | The server stores its own facts and the reporter count; the developer acts on corroborated reports only (SD28, §7.7) | Accepted; inherent to end-to-end encryption |

---

## Review notes

The security/compliance review and the implementability review (2 November 2026) were applied, except where a line
below says otherwise. Each line names the review point and why the spec differs from it.

- Security 4 (archive race): the pass re-checks in its final transaction instead of running inside
  `blockConcurrencyWhile`, so the DO keeps answering during R2 calls; it also deletes only unchanged rows and skips
  members with a pending R2 delete.
- Security 8(a): voided purchases are confirmed with the Play API (the service account is required anyway) rather
  than by checking Pub/Sub's OIDC token, which stays out of v1.
- Security 8(c): instead of one required `RL_PUBLIC`, two required bindings (`RL_CREATE` 3/min, `RL_INVITE`
  10/min), because the binding allows only 10 s or 60 s periods (implementability 5).
- Security 9(b): "Report group name" is shown to admins and members, not to the owner, who can simply rename it.
- Security 11: the "Stop sharing" alert is itself the clear-trail confirmation, rather than chaining the Clear
  trail alert after it.
- Security 13: the variable is `ALLOW_TEST_PURCHASES` and also covers Google test purchases; the 14 days count from
  the original purchase date so sandbox renewals can't extend them. Binding proofs with `appAccountToken` is not
  done (it would break Restore on a new phone without accounts); the risk is §14 R5.
- Security 15 (age): the 18+ / guardian confirmation is a sentence that "Agree and continue" accepts, not a
  separate control: the same affirmative act, one tap fewer.
- Security 18 (nonces): per-member trail and live keys were chosen over a rewritten bound; this changed the
  `trail` and `live` subkeys and the four trail and live envelopes in §3.6.
- Implementability 2: Android's extra E5 rule compares only the purchase token, not the `orderId`, which changes on
  every renewal and would send an E5 per renewal per group for nothing (the server tracks renewals itself).
- Implementability 10: the keyless link patterns also require the token to end after 43 characters, so a
  44-character token is `notInvite`, not `missingKey`.
- Implementability 11: the Android `adb shell am start` step is dropped rather than re-quoted: `am start -d`
  builds the same intent as the in-process `InviteIntentTest`, which asserts the result.
- Implementability 12: U9 (Doze) is a manual real-phone check rather than an emulator test against a stub, since
  emulator Doze says little about real phones.
- Implementability 17: after `terms_outdated` the app re-shows S1 and retries only when it has newer terms than
  the user accepted; otherwise it asks for an app update, because re-accepting the same terms can't satisfy a newer
  server version.
- Implementability 18: `maxChunkSeq` alone is not safe (a cursor can pass a row's `seq` after an archive pass
  already deleted it, for example after an interrupted pull), so the item also carries `archivedAt`, the client
  keeps `cursor_at`, and `maxChunkSeq` includes the archive's previous `seq`.
- Implementability 21: a heartbeat still sends the newest fix when it is newer than the last `at` sent and omits
  `live` only otherwise; always omitting it would turn a member who stands still grey after 10 minutes.
- Implementability 25 (step 37): instead of one fixed point, a deterministic scan for an empty point, because the
  camera fit makes any fixed screen point unreliable.

Integration of the server and both cores (2 November 2026) filled these gaps; the text above now says what the code
does:

- Server choices the spec left open: the E18 code for an off-grid start (`bad_record`), `400` for a malformed invite
  token, `403` for an owner's group-name report, removing yourself by `memberId`, `used_at` for cancellations, the
  daily minute and order, the ProDO RPC shape, the deleted marker keeping `pro_name`, body validation before roles,
  WebSocket messages in the 120/min limit, tables made at E1, and the `api-examples.json` shapes for frames and
  binary answers.
- Both cores now: skip change items at or below the cursor (no tombstones, §6.3); keep acknowledged windows for a
  60 s grace and sweep them each upload pass (§4.3); send chunks in the live slot (§8.6); resend a lost create with
  the same id and pass and guard restore against a retry that took the secret over (§8.3); let a rename land after
  a colour fix (§4.6); delete nothing when the secrets can't be read (§8.3); wait a fixed 60 s after `429` (the
  transport carries no headers); and share the error texts in §8.14 that §9.3 lacked.
- App layers: report socket events only for the socket the hub opened last, and map a refused upgrade to a close
  code (§8.7).

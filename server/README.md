# Spoor groups server

The Cloudflare Worker behind Spoor groups: one Durable Object per group (SQLite storage, hibernating WebSockets),
one per Pro purchase, and one for reports, with old trail chunks archived to R2. Everything a group shares is
encrypted on the phones; the server stores and forwards ciphertext and sees only ids, times, roles and sizes.

The specification is [docs/groups/SPEC.md](../docs/groups/SPEC.md) (§6 API, §7 internals). Nothing is deployed from
this repository: the domain and the secrets are configuration, set by the owner when deploying (below).

## Layout

| Path | What |
| --- | --- |
| `src/index.ts` | Worker entry: routing (§7.2), body limits, `RL_CREATE` and `RL_INVITE`, store notification checks, admin endpoints, one log line per request |
| `src/routes.ts` | The API's routes, shared by the Worker and GroupDO |
| `src/group/GroupDO.ts` | One per group: every `/v1/groups/{groupId}/…` endpoint, invite preview and redeem, live positions, WebSocket ticks, alarms, the archive pass, admin RPC |
| `src/group/schema.ts`, `changes.ts`, `archive.ts` | The SQLite schema and migrations, the change feed, the R2 archive object |
| `src/pro/ProDO.ts` | One per purchase (`apple:{originalTransactionId}`, `google:{b64url(sha256(token))}`): entitlement state, the ordering rule, attached groups (at most `MAX_GROUPS_PER_PURCHASE`) |
| `src/pro/apple.ts`, `apple-root-g3.ts`, `der.ts` | App Store JWS verification against the pinned Apple Root CA - G3, the App Store Server API, notifications |
| `src/pro/google.ts` | Service-account tokens, `subscriptionsv2` / `productsv2`, real-time developer notifications |
| `src/admin/AdminDO.ts` | Reports, the report webhook, the report clean-up |
| `src/web/*` | The invite landing page, the well-known files, `/privacy`, `/terms` and `/` |
| `src/lib/*` | base64url, ids, envelopes, errors, limits, hashing, logging, configuration, alarm jobs |
| `test/unit`, `test/integration`, `test/ws` | The three test projects (below) |
| `test/fixtures/make_apple_test_chain.py` | Makes the Apple-like test certificate chain and two fixed signed transactions in `test/fixtures/apple/` |

## Commands

Node 22 or newer. From `server/`:

| Command | What |
| --- | --- |
| `npm ci` | Install the pinned dependencies |
| `npm run typecheck` | `tsc --noEmit` over the Worker and the tests |
| `npm test` | Unit and integration tests (offline, in the Workers runtime through `@cloudflare/vitest-plugin`) |
| `npm run test:ws` | The WebSocket tests, on one worker without per-file storage isolation |
| `npm run build` | `wrangler deploy --dry-run --outdir dist`: checks that the Worker bundles; deploys nothing |
| `npm run dev` | `wrangler dev` on <http://localhost:8787> |
| `npm run types` | Regenerates `worker-configuration.d.ts` from `wrangler.jsonc` (run it after changing the config; the file is committed) |

CI runs the same steps (`.github/workflows/server.yml`).

## Tests

- **Unit** (`test/unit`): base64url and ids against `vectors.json` (`badBase64url`, `nonCanonicalIds`), the token
  split, the three server hashes, envelope sizes, the test client's AES-GCM and HKDF against every record vector,
  the Apple JWS verifier (wrong root, missing OIDs, an OID only in a name, a non-CA intermediate, bad signatures,
  bundle, product, Family Sharing, Sandbox, Xcode), the App Store Server API answers, the Google decision tables
  and negative cache, notification decisions, the landing page (headers and CSP hash), the well-known files.
- **Integration** (`test/integration`): every endpoint and error code through `exports.default.fetch`, with
  `runInDurableObject` for the clock (`clockOverride`), the store calls (`httpFetch`), the environment
  (`envOverride`), the server's randomness (`random`) and R2 (`bucketOverride`), and `runDurableObjectAlarm` for the
  alarms. Nothing calls Apple or Google: store answers come from the fake `httpFetch` and the test certificates.
- **WebSocket** (`test/ws`): hello, ticks, watchers, more and ack, close codes, hibernation, the socket limit.

### api-examples.json

`test/integration/api-examples.test.ts` walks every endpoint with the vector ids, passes and envelopes, fixed clocks
and fixed signed transactions, and a reporter (`test/helpers/examples-reporter.ts`) writes
`Tests/SatMapCoreTests/Fixtures/groups/api-examples.json` after a run in which every recording test passed. The
cores' contract tests parse it. Reruns write the same file, so after changing an answer run `npm test` and commit
the file with the change. WebSocket frames are recorded with method `WS` and status `101`; the archive download
(binary) has `response: null`.

### The Apple test chain

`python3 test/fixtures/make_apple_test_chain.py` (Python `cryptography`) writes a root, intermediates and leaves
valid 2026–2046 carrying the two Apple OIDs, the leaf key, and `example-jws.json`. The tests trust the test root
through `APPLE_ROOT_OVERRIDE_B64`, which the Worker honours only under `TEST_MODE=1`. After regenerating, rerun
`npm test` and commit the fixtures and `api-examples.json` (its signed transactions change). It also writes
`forged_inter.pem` and `forged_leaf.pem`, which copy the chain's names but are signed by an unrelated key, so the
tests reach the certificate signature checks; `--forged-only` rewrites just those two from the committed chain and
leaves everything else as it is.

### Notes on the test runtime

- The Rate Limiting bindings work in the test runtime (§14 U3): `RL_CREATE` and `RL_INVITE` are tested for real.
  A missing binding is tested by calling the Worker with a changed environment.
- Under per-file storage isolation (the integration project), a Durable Object that holds open WebSockets answers
  RPC calls only after its sockets are gone. The WebSocket tests therefore run in their own project (`test:ws`),
  where RPC with open sockets is tested, and the examples test records its WebSocket frames last.

### Dependencies

Exact versions, each published at least 14 days before they were chosen (`npm view <package> time`):
`@cloudflare/vitest-plugin` 1.4.4 (the newest release that old on 2 November 2026), the `wrangler` it pins
(4.151.1), `vitest` 4.1.12 and `typescript` 6.0.3. `overrides` lift two transitive packages to patched releases that
are also at least 14 days old (`undici` 7.30.1 and `zod` 4.6.7; `npm audit` is clean). The lock file was resolved with
`npx npm@11.21.0 install --before=2026-10-19`, which works around an npm 10 resolver crash on optional peer
dependencies; `npm ci` with Node 22's own npm installs from it. There is no `@types/node`: the few Node APIs used
are declared in `src/types` and `test/types`.

## Configuration (SPEC §7.8)

Variables are in `wrangler.jsonc` (`vars`) with their defaults:

| Variable | Default | Use |
| --- | --- | --- |
| `PUBLIC_BASE_URL` | `https://example.invalid` | The domain |
| `CONTACT_EMAIL` | `groups@example.invalid` | Pages and the landing page footer |
| `APP_STORE_URL`, `PLAY_STORE_URL` | `""` | Landing page links (left out when empty) |
| `APPLE_BUNDLE_ID` | `com.jacobusoettle.satmap` | JWS checks |
| `APPLE_APP_IDS` | `TEAMID.com.jacobusoettle.satmap` | apple-app-site-association, comma-separated |
| `ANDROID_PACKAGE` | `com.jacobusoettle.satmap` | assetlinks.json; Play API path; the landing page's `intent://` link |
| `ANDROID_CERT_SHA256` | `""` | assetlinks.json fingerprints, comma-separated |
| `PRO_PRODUCTS_APPLE` | `pro.monthly,pro.yearly,pro.lifetime` | Accepted Apple products |
| `PRO_PRODUCTS_GOOGLE` | `pro,pro_lifetime` | `pro` = subscription, `pro_lifetime` = one-time |
| `APPLE_APP_APPLE_ID` | `""` | The app's numeric Apple ID, checked on Production notifications |
| `MAX_GROUPS_PER_PURCHASE` | `20` | SD9 |
| `TERMS_VERSION` | `1` | Lowest accepted `termsVersion` |
| `ALLOW_TEST_PURCHASES` | `true` | Sandbox and Google test purchases, capped at 14 days (SD20) |
| `APPLE_ALLOW_XCODE` | `false` | Xcode purchases; only under `TEST_MODE=1` |
| `ALLOW_UNVERIFIED_PRO` | `false` | Google proofs without asking Google; only under `TEST_MODE=1` |
| `TEST_MODE` | `0` | Enables the test-only variables; missing rate-limit bindings and Apple API credentials then pass |
| `APPLE_ROOT_OVERRIDE_B64` | `""` | Tests only; only under `TEST_MODE=1` |

Secrets (`npx wrangler secret put NAME`; locally in `.dev.vars`, see `.dev.vars.example`): `ADMIN_TOKEN`,
`APPLE_ISSUER_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY`, `GOOGLE_SERVICE_ACCOUNT_JSON`, `GOOGLE_RTDN_TOKEN`,
`REPORT_WEBHOOK_URL`.

Bindings: `GROUP`, `PRO`, `ADMIN` (Durable Objects, all SQLite), `ARCHIVE` (R2 bucket `satmap-groups-archive`),
`RL_CREATE` (3 per IP per minute) and `RL_INVITE` (10 per IP per minute), both required outside `TEST_MODE=1`.

## Deployment (owner; SPEC §13.4)

1. **Choose the domain.** Set `GROUPS_DOMAIN` (iPhone build setting), `groupsDomain` (`android/gradle.properties`)
   and `PUBLIC_BASE_URL` in `wrangler.jsonc` to the same domain.
2. **Cloudflare account** (Workers Paid plan, for Durable Objects storage):
   - `npx wrangler r2 bucket create satmap-groups-archive`
   - add the route or custom domain for the Worker (`"routes"` in `wrangler.jsonc`, or the dashboard)
   - keep the two rate-limit bindings in `wrangler.jsonc`; change their `namespace_id`s only if they clash with
     another Worker on the account
   - `npx wrangler deploy`
3. **Secrets and variables:**
   ```sh
   npx wrangler secret put ADMIN_TOKEN                 # 32+ random characters: openssl rand -base64 33
   npx wrangler secret put APPLE_ISSUER_ID
   npx wrangler secret put APPLE_KEY_ID
   npx wrangler secret put APPLE_PRIVATE_KEY < AuthKey_XXXXXXXXXX.p8
   npx wrangler secret put GOOGLE_SERVICE_ACCOUNT_JSON < service-account.json
   npx wrangler secret put GOOGLE_RTDN_TOKEN           # 32+ random characters
   npx wrangler secret put REPORT_WEBHOOK_URL
   ```
   Set `APPLE_APP_IDS` (your Team ID), `APPLE_APP_APPLE_ID` and `ANDROID_CERT_SHA256` (Play Console → Release →
   Setup → App signing; add the debug fingerprint for local tests) in `wrangler.jsonc`, check that `TEST_MODE` is
   `"0"`, and deploy again.
4. **Check the app links:** open `https://{domain}/.well-known/apple-app-site-association` and
   `https://{domain}/.well-known/assetlinks.json`, then run
   `adb shell pm verify-app-links --re-verify com.jacobusoettle.satmap`.
5. **Store setup** (SPEC §10.2):
   - App Store Connect: the subscription group "Spoor Pro" with `pro.monthly` and `pro.yearly`, the non-consumable
     `pro.lifetime`; App Store Server Notifications V2 to `https://{domain}/v1/store/apple` for production and
     sandbox; an In-App Purchase key for the three `APPLE_*` secrets.
   - Play Console: the subscription `pro` (base plans `monthly`, `yearly`) and the one-time product `pro_lifetime`;
     real-time developer notifications to a Pub/Sub topic with a push subscription to
     `https://{domain}/v1/store/google?token={GOOGLE_RTDN_TOKEN}` (voided-purchase and one-time notifications on);
     a service account with "View financial data" and "Manage orders".
   - Then the privacy notice and terms review (`/privacy`, `/terms`, drafted in `src/web/pages.ts`), the word list
     review, and the contact address (`CONTACT_EMAIL`). These are release blockers (SPEC §14 R1).

Observability stays off in `wrangler.jsonc`: invocation logs would record URLs, which hold group ids and invite
tokens. If you switch it on, keep invocation logs off. The Worker's own logs are one JSON line per request with the
route template, status, duration and error code, and never ids, tokens, passes, proofs or IP addresses (§7.9).

## Admin endpoints (SPEC §7.7)

Every admin path answers `404` while `ADMIN_TOKEN` is unset.

```sh
BASE=https://example.invalid
AUTH="Authorization: Bearer $ADMIN_TOKEN"

# A1: reports, newest first (status=open|resolved|all, limit, before={createdAt}); "reporters" counts distinct
# reporters of the same target, and "facts" are the server's own (target size, maker, time).
curl -s -H "$AUTH" "$BASE/v1/admin/reports?status=open&limit=100"

# A2: resolve a report (action: none | pin_deleted | member_removed | group_deleted)
curl -s -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"action":"pin_deleted","note":"two reporters"}' "$BASE/v1/admin/reports/{reportId}/resolve"

# A3: delete a pin; A4: remove a member (not the owner: delete the group instead); A5: delete a group
curl -s -X DELETE -H "$AUTH" "$BASE/v1/admin/groups/{groupId}/pins/{pinId}"
curl -s -X DELETE -H "$AUTH" "$BASE/v1/admin/groups/{groupId}/members/{memberId}"
curl -s -X DELETE -H "$AUTH" "$BASE/v1/admin/groups/{groupId}"

# A6: a group's metadata (status, createdAt, pausedAt, memberCount, databaseBytes, pro)
curl -s -H "$AUTH" "$BASE/v1/admin/groups/{groupId}"
```

A report's `content` is the reporter's own decrypted copy, which the server can't check (SD28). Act on a report
only when it is corroborated: reports from more than one member (`reporters`), a `targetBytes` that matches the
padded size of the claimed text, or the reporter's screenshot by e-mail. A single uncorroborated report leads at
most to deleting the reported pin (A3). Deletion and access requests by e-mail quote the support code
`{groupId}.{memberId}` shown in the app; A4 deletes that member's data in the group.

`REPORT_WEBHOOK_URL` receives `{"reportId","createdAt","reason","targetKind"}` (no content) for every report,
retried hourly for a day. Without it, a written daily A1 routine is needed: the app promises a review within 24
hours.

## Local end-to-end check (manual; SPEC §12.6)

1. `cp .dev.vars.example .dev.vars`, uncomment `TEST_MODE=1` and `ALLOW_UNVERIFIED_PRO=true`, then
   `npx wrangler dev`.
2. Run the Android debug app on an emulator with `--es groupsBaseURL http://10.0.2.2:8787 --es fakePro YES`: its Pro
   proof is a well-formed Google token that the server accepts as yearly Pro under `ALLOW_UNVERIFIED_PRO`.
3. Create a group, invite, join from a second emulator, share, drop pins, and remove.
4. **Doze, on a real Android phone:** share in a group, put the app in the background with the screen off, run
   `adb shell dumpsys deviceidle force-idle`, and watch the other phone (or the `wrangler dev` log) for uploads every
   15 or 60 s for 10 minutes. Then `adb shell dumpsys deviceidle unforce`.

## Retention (SPEC §7.10)

| Data | Kept until |
| --- | --- |
| Trail chunks and archives | Forever while the group exists, unless the member clears them, leaves or is removed |
| Pins | Animal: 12 h. Others: until deleted, or the dropper leaves or is removed |
| Live positions | Replaced by the next one; deleted on stop or pause; not returned after 24 h; rows older than 30 days pruned |
| `removals` | 400 days (E19 answers `reset` below the pruned cursor) |
| Archived trail of a member who cleared, left or was removed | Deleted at once; retried until R2 confirms (`r2_deletes`) |
| `gone_passes` | 30 days |
| Invites | 30 days after use, cancellation or expiry |
| Paused groups | Everything deleted 30 days after the pause unless resumed |
| Deleted groups | Content deleted at once; the deleted marker after 30 days |
| Reports | 180 days after being resolved; open reports at most 365 days |
| ProDO purchase records | While any group is attached; deleted 30 days after the last group left |

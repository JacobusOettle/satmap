// E1 create (with retry, every error), E2 status, body and content-type limits, RL_CREATE (SPEC §6.5, §6.8).

import { env } from "cloudflare:workers";
import { createExecutionContext } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import worker from "../../src/index";
import { appleNotification, appleProof, signedTransaction } from "../helpers/apple-test-chain";
import {
  BASE,
  GROUP_PROFILE,
  MEMBER_PROFILE,
  T0,
  api,
  createGroup,
  inPro,
  newMember,
  randomId,
  randomSecret,
  setClock,
  setProClock,
} from "../helpers/client";
import { b64urlEncode, envelopeOf, vectors } from "../helpers/crypto";
import { fakeGoogle, purchaseToken, subscription } from "../helpers/google";
import { googleProName } from "../../src/pro/ProDO";

function createBody(over: Record<string, unknown> = {}) {
  const owner = newMember();
  return {
    groupId: randomId(),
    memberId: owner.memberId,
    pass: owner.pass,
    joinProof: randomSecret(),
    groupProfile: GROUP_PROFILE,
    memberProfile: MEMBER_PROFILE,
    termsVersion: 1,
    ...over,
  };
}

async function createWith(over: Record<string, unknown>, now = T0) {
  const body = createBody(over);
  await setClock(body.groupId as string, now);
  return api("POST", "/v1/groups", { body });
}

async function appleBody(now: number, tx: Record<string, unknown> = {}) {
  const jws = await signedTransaction(now, tx);
  const otid = JSON.parse(atob(jws.split(".")[1]!.replace(/-/g, "+").replace(/_/g, "/"))).originalTransactionId as string;
  await setProClock(`apple:${otid}`, now);
  return { pro: appleProof(jws), proName: `apple:${otid}` };
}

describe("E1 create", () => {
  it("creates the group: the caller is the owner, the profile is seq 1 and the owner seq 2", async () => {
    const g = await createGroup();
    expect(g.status).toEqual({
      groupId: g.groupId,
      status: "active",
      pausedReason: null,
      pausedAt: null,
      deleteAt: null,
      pausedIntervals: [],
      ownerId: g.owner.memberId,
      memberCount: 1,
      profile: GROUP_PROFILE,
      profileSeq: 1,
      you: { memberId: g.owner.memberId, role: "owner", joinedAt: T0 },
      transfer: null,
      pro: { platform: "apple", productId: "pro.yearly", expiresAt: T0 + 365 * 86400 },
      watchers: 0,
      seq: 2,
      serverTime: T0,
    });
    const changes = await api("GET", `/v1/groups/${g.groupId}/changes`, { pass: g.owner.pass });
    expect(changes.body.items).toEqual([
      { kind: "group", seq: 1, profile: GROUP_PROFILE },
      { kind: "member", seq: 2, memberId: g.owner.memberId, role: "owner", joinedAt: T0, profile: MEMBER_PROFILE },
    ]);
  });

  it("answers a retry with the same pass 200 and the same status, and another pass 409 group_exists", async () => {
    const owner = newMember();
    const pro = await appleBody(T0);
    const body = createBody({ memberId: owner.memberId, pass: owner.pass, pro: pro.pro });
    await setClock(body.groupId as string, T0);
    const first = await api("POST", "/v1/groups", { body });
    expect(first.status).toBe(201);
    const retry = await api("POST", "/v1/groups", { body });
    expect(retry.status).toBe(200);
    expect(retry.body).toEqual(first.body);
    const other = await api("POST", "/v1/groups", { body: { ...body, pass: randomSecret() } });
    expect(other.status).toBe(409);
    expect(other.body.error).toBe("group_exists");
  });

  it("validates ids, secrets, envelopes and termsVersion before any store call", async () => {
    const pro = (await appleBody(T0)).pro;
    const cases: Array<[Record<string, unknown>, number, string]> = [
      [{ groupId: vectors.mustFail.nonCanonicalIds[0] }, 400, "bad_request"],
      [{ memberId: vectors.mustFail.nonCanonicalIds[1] }, 400, "bad_request"],
      [{ memberId: "short" }, 400, "bad_request"],
      [{ pass: vectors.inputs.groupId }, 400, "bad_request"],
      [{ joinProof: undefined }, 400, "bad_request"],
      [{ groupProfile: b64urlEncode(new Uint8Array(40)) }, 400, "bad_record"],
      [{ groupProfile: 7 }, 400, "bad_request"],
      [{ memberProfile: b64urlEncode(new Uint8Array([1, 2, 3])) }, 400, "bad_record"],
      [{ memberProfile: `${MEMBER_PROFILE}=` }, 400, "bad_record"],
      [{ termsVersion: "1" }, 400, "bad_request"],
      [{ termsVersion: 0 }, 409, "terms_outdated"],
      [{ pro: undefined }, 400, "bad_request"],
      [{ pro: { platform: "windows" } }, 400, "bad_request"],
      [{ pro: { platform: "apple" } }, 400, "bad_request"],
      [{ pro: { platform: "google", productId: "pro" } }, 400, "bad_request"],
    ];
    for (const [over, status, code] of cases) {
      const res = await createWith({ pro, ...over });
      expect(res.status, JSON.stringify(over)).toBe(status);
      expect(res.body.error, JSON.stringify(over)).toBe(code);
    }
  });

  it("refuses proofs that don't verify (402 pro_invalid) and inactive entitlements (402 pro_inactive)", async () => {
    const invalid = [
      { tx: { bundleId: "com.example.other" } },
      { tx: { productId: "pro.weekly" } },
      { tx: { inAppOwnershipType: "FAMILY_SHARED" } },
      { tx: { environment: "Xcode" } },
    ];
    for (const c of invalid) {
      const res = await createWith((await appleBody(T0, c.tx)) as Record<string, unknown>);
      expect(res.status, JSON.stringify(c)).toBe(402);
      expect(res.body.error).toBe("pro_invalid");
    }
    const junk = await createWith({ pro: appleProof("not.a.jws") });
    expect(junk.body.error).toBe("pro_invalid");
    for (const tx of [{ expiresDate: (T0 - 1) * 1000 }, { revocationDate: (T0 - 1) * 1000 }]) {
      const res = await createWith((await appleBody(T0, tx)) as Record<string, unknown>);
      expect(res.status, JSON.stringify(tx)).toBe(402);
      expect(res.body.error).toBe("pro_inactive");
    }
  });

  it("accepts a Sandbox purchase, and Lifetime with no expiry", async () => {
    const sandbox = await createWith((await appleBody(T0, { environment: "Sandbox", originalPurchaseDate: (T0 - 86400) * 1000 })) as Record<string, unknown>);
    expect(sandbox.status).toBe(201);
    expect(sandbox.body.pro.expiresAt).toBe(T0 - 86400 + 14 * 86400);
    const life = await createWith((await appleBody(T0, { productId: "pro.lifetime" })) as Record<string, unknown>);
    expect(life.status).toBe(201);
    expect(life.body.pro).toEqual({ platform: "apple", productId: "pro.lifetime", expiresAt: null });
  });

  it("refuses a malformed Google token or product without calling Google", async () => {
    for (const pro of [
      { platform: "google", productId: "pro", purchaseToken: "short" },
      { platform: "google", productId: "pro", purchaseToken: "has spaces in it, twenty+ chars" },
      { platform: "google", productId: "coins", purchaseToken: purchaseToken() },
    ]) {
      const name = await googleProName(pro.purchaseToken);
      const calls: string[] = [];
      await inPro(name, (p) => {
        p.httpFetch = async (url) => {
          calls.push(url);
          return new Response("{}", { status: 500 });
        };
      });
      const res = await createWith({ pro });
      expect(res.status).toBe(402);
      expect(res.body.error).toBe("pro_invalid");
      expect(calls).toEqual([]);
    }
  });

  it("answers 503 store_unavailable for Google without a service account, with Retry-After", async () => {
    const res = await createWith({ pro: { platform: "google", productId: "pro", purchaseToken: purchaseToken() } });
    expect(res.status).toBe(503);
    expect(res.body.error).toBe("store_unavailable");
    expect(res.headers.get("Retry-After")).not.toBeNull();
  });

  it("creates with a Google subscription confirmed by the Play API", async () => {
    const google = await fakeGoogle();
    const token = purchaseToken();
    google.purchases.set(token, [200, subscription(T0)]);
    const name = await googleProName(token);
    await inPro(name, (p) => {
      p.httpFetch = google.fetch;
      p.clockOverride = T0;
      p.envOverride = { GOOGLE_SERVICE_ACCOUNT_JSON: google.serviceAccountJson };
    });
    const res = await createWith({ pro: { platform: "google", productId: "pro", purchaseToken: token } });
    expect(res.status).toBe(201);
    expect(res.body.pro).toEqual({ platform: "google", productId: "pro", expiresAt: T0 + 30 * 86400 });
  });

  it("asks the App Store Server API when its credentials are set, and uses its answer", async () => {
    const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign"])) as CryptoKeyPair;
    const der = new Uint8Array((await crypto.subtle.exportKey("pkcs8", pair.privateKey)) as ArrayBuffer);
    const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...der))}\n-----END PRIVATE KEY-----`;
    const creds = { APPLE_ISSUER_ID: "issuer", APPLE_KEY_ID: "KEY", APPLE_PRIVATE_KEY: pem };
    for (const [http, status, expected] of [
      [200, 201, null],
      [404, 402, "pro_invalid"],
      [500, 503, "store_unavailable"],
    ] as const) {
      const { pro, proName } = await appleBody(T0);
      const tx = JSON.parse(atob(pro.signedTransaction.split(".")[1]!.replace(/-/g, "+").replace(/_/g, "/")));
      const signedTransactionInfo = await signedTransaction(T0, { ...tx, expiresDate: (T0 + 99) * 1000 });
      const urls: string[] = [];
      await inPro(proName, (p) => {
        p.envOverride = creds;
        p.httpFetch = async (url) => {
          urls.push(url);
          return new Response(
            JSON.stringify({
              data: [{ lastTransactions: [{ originalTransactionId: tx.originalTransactionId, status: 1, signedTransactionInfo }] }],
            }),
            { status: http },
          );
        };
      });
      const res = await createWith({ pro });
      expect(res.status).toBe(status);
      expect(urls).toEqual([`https://api.storekit.apple.com/inApps/v1/subscriptions/${tx.originalTransactionId}`]);
      if (expected === null) expect(res.body.pro.expiresAt).toBe(T0 + 99);
      else expect(res.body.error).toBe(expected);
    }
  });

  it("caps one purchase at MAX_GROUPS_PER_PURCHASE groups that are not deleted (SD9)", async () => {
    const { pro, proName } = await appleBody(T0);
    await inPro(proName, (p) => {
      p.envOverride = { MAX_GROUPS_PER_PURCHASE: "2" };
    });
    const owner = newMember();
    const made: string[] = [];
    for (let i = 0; i < 2; i++) {
      const res = await createWith({ pro, memberId: owner.memberId, pass: owner.pass });
      expect(res.status).toBe(201);
      made.push(res.body.groupId);
    }
    const third = await createWith({ pro });
    expect(third.status).toBe(409);
    expect(third.body.error).toBe("too_many_groups");
    expect((await api("DELETE", `/v1/groups/${made[0]}`, { pass: owner.pass })).status).toBe(204);
    expect((await createWith({ pro })).status).toBe(201);
  });

  it("answers 415 for a body that isn't JSON, 400 for malformed JSON and 413 above 64 KiB", async () => {
    const text = await api("POST", "/v1/groups", { rawBody: "{}", headers: { "Content-Type": "text/plain" } });
    expect(text.status).toBe(415);
    expect(text.body.error).toBe("unsupported_media_type");
    const bad = await api("POST", "/v1/groups", { rawBody: "{", headers: { "Content-Type": "application/json" } });
    expect(bad.status).toBe(400);
    const big = await api("POST", "/v1/groups", { body: { ...createBody(), filler: "x".repeat(65 * 1024) } });
    expect(big.status).toBe(413);
    expect(big.body.error).toBe("too_large");
  });

  it("applies RL_CREATE: 3 per IP per minute", async () => {
    const ip = "192.0.2.77";
    const statuses: number[] = [];
    for (let i = 0; i < 4; i++) statuses.push((await api("POST", "/v1/groups", { body: createBody({ termsVersion: 0 }), ip })).status);
    expect(statuses.slice(0, 3)).toEqual([400, 400, 400]);
    expect(statuses[3]).toBe(429);
    const limited = await api("POST", "/v1/groups", { body: createBody(), ip });
    expect(limited.body.error).toBe("rate_limited");
    expect(limited.headers.get("Retry-After")).toBe("60");
  });

  it("answers 500 when a required rate-limit binding is missing outside TEST_MODE", async () => {
    const res = await worker.fetch(
      new Request(`${BASE}/v1/groups`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(createBody()) }),
      { ...env, TEST_MODE: "0", RL_CREATE: undefined } as unknown as typeof env,
    );
    expect(res.status).toBe(500);
    expect(await res.json()).toMatchObject({ error: "internal" });
    void createExecutionContext;
  });
});

describe("E2 status", () => {
  it("answers members with GroupStatus and others with 401 or 404", async () => {
    const g = await createGroup();
    const ok = await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass });
    expect(ok.status).toBe(200);
    expect(ok.body.you.role).toBe("owner");
    expect(ok.headers.get("Cache-Control")).toBe("no-store");
    expect(ok.headers.get("Access-Control-Allow-Origin")).toBeNull();
    expect((await api("GET", `/v1/groups/${g.groupId}`)).status).toBe(401);
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: randomSecret() })).body.error).toBe("unauthorized");
    expect((await api("GET", `/v1/groups/${g.groupId}`, { headers: { Authorization: "Basic abc" } })).status).toBe(401);
    const missing = await api("GET", `/v1/groups/${randomId()}`, { pass: g.owner.pass });
    expect(missing.status).toBe(404);
    expect(missing.body.error).toBe("group_not_found");
    expect((await api("GET", `/v1/groups/${vectors.mustFail.nonCanonicalIds[0]}`, { pass: g.owner.pass })).status).toBe(400);
  });

  it("shows pro only to the owner", async () => {
    const g = await createGroup();
    const { token } = (await api("POST", `/v1/groups/${g.groupId}/invites`, { pass: g.owner.pass, body: {} })).body;
    const m = newMember();
    const joined = await api("POST", "/v1/invites/redeem", {
      body: { token, joinProof: g.joinProof, memberId: m.memberId, pass: m.pass, profile: envelopeOf("memberProfile"), termsVersion: 1 },
    });
    expect(joined.body.pro).toBeNull();
    expect(joined.body.you.role).toBe("member");
  });

  it("is limited to 120 authenticated requests per member per minute", async () => {
    const g = await createGroup();
    let limited: Awaited<ReturnType<typeof api>> | null = null;
    for (let i = 0; i < 125 && limited === null; i++) {
      const res = await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass });
      if (res.status === 429) limited = res;
    }
    expect(limited).not.toBeNull();
    expect(limited!.body.error).toBe("rate_limited");
    expect(Number(limited!.headers.get("Retry-After"))).toBeGreaterThan(0);
  });
});

describe("store notification endpoint E27", () => {
  it("answers 400 for a notification that doesn't verify and 200 for a TEST notification", async () => {
    const bad = await api("POST", "/v1/store/apple", { body: { signedPayload: "a.b.c" } });
    expect(bad.status).toBe(400);
    const test = await api("POST", "/v1/store/apple", { body: { signedPayload: await appleNotification(T0, "TEST") } });
    expect(test.status).toBe(200);
  });
});

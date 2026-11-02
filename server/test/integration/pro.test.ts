// Pro entitlements end to end: pause and resume (R7, R8), the ordering rule, pro_check and pro_retry (SD8), store
// notifications (E27, E28), linked purchases and ProDO self-deletion (SPEC §7.4, §7.6, §12.1).

import { env } from "cloudflare:workers";
import { runDurableObjectAlarm } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import worker from "../../src/index";
import { googleProName } from "../../src/pro/ProDO";
import { appleNotification, appleTransaction, signedTransaction } from "../helpers/apple-test-chain";
import {
  BASE,
  GROUP_PROFILE,
  MEMBER_PROFILE,
  T0,
  addMember,
  advance,
  api,
  appleProofAt,
  createGroup,
  inGroup,
  inPro,
  makeInvite,
  newMember,
  proStub,
  randomId,
  randomSecret,
  redeem,
  rows,
  runAlarm,
  setClock,
  setProClock,
  type TestGroup,
} from "../helpers/client";
import { envelopeOf } from "../helpers/crypto";
import { fakeGoogle, purchaseToken, rfc3339, subscription, type FakeGoogle } from "../helpers/google";

const RTDN = "test-rtdn-token-0123456789abcdefghijklmnop";

async function notify(signedPayload: string) {
  return api("POST", "/v1/store/apple", { body: { signedPayload } });
}

async function status(g: TestGroup) {
  return (await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).body;
}

async function appleP8(): Promise<Record<string, string>> {
  const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign"])) as CryptoKeyPair;
  const der = new Uint8Array((await crypto.subtle.exportKey("pkcs8", pair.privateKey)) as ArrayBuffer);
  return {
    APPLE_ISSUER_ID: "issuer",
    APPLE_KEY_ID: "KEY",
    APPLE_PRIVATE_KEY: `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...der))}\n-----END PRIVATE KEY-----`,
  };
}

/** A group whose owner bought Google Pro (`pro`), confirmed by a fake Play API. */
async function googleGroup(now: number, google: FakeGoogle, token = purchaseToken(), expiry = now + 30 * 86400) {
  google.purchases.set(token, [200, subscription(now, { lineItems: [{ productId: "pro", expiryTime: rfc3339(expiry) }] })]);
  const proName = await googleProName(token);
  await inPro(proName, (p) => {
    p.httpFetch = google.fetch;
    p.clockOverride = now;
    p.envOverride = { GOOGLE_SERVICE_ACCOUNT_JSON: google.serviceAccountJson };
  });
  const owner = newMember();
  const groupId = randomId();
  const joinProof = randomSecret();
  await setClock(groupId, now);
  const res = await api("POST", "/v1/groups", {
    body: {
      groupId,
      memberId: owner.memberId,
      pass: owner.pass,
      joinProof,
      groupProfile: GROUP_PROFILE,
      memberProfile: MEMBER_PROFILE,
      termsVersion: 1,
      pro: { platform: "google", productId: "pro", purchaseToken: token },
    },
  });
  expect(res.status).toBe(201);
  const g: TestGroup = { groupId, owner, joinProof, otid: "", proName, now, status: res.body };
  return { g, token };
}

/**
 * Points `proName`'s ProDO at the fake Play API and holds its first purchases call until `release()`; `asked`
 * resolves when that call starts. The held call answers `answer` when given, otherwise from the fake.
 */
async function holdFirstPurchaseCall(proName: string, google: FakeGoogle, now: number, answer?: [number, unknown]) {
  let release!: () => void;
  const gate = new Promise<void>((r) => (release = r));
  let started!: () => void;
  const asked = new Promise<void>((r) => (started = r));
  let first = true;
  await inPro(proName, (p) => {
    p.clockOverride = now;
    p.envOverride = { GOOGLE_SERVICE_ACCOUNT_JSON: google.serviceAccountJson };
    p.httpFetch = async (input, init) => {
      if (first && input.includes("/purchases/")) {
        first = false;
        started();
        await gate;
        if (answer !== undefined) return new Response(JSON.stringify(answer[1]), { status: answer[0] });
      }
      return google.fetch(input, init);
    };
  });
  return { asked, release: () => release() };
}

const googlePro = (token: string) => ({ platform: "google", productId: "pro", purchaseToken: token });

describe("pause when the store says Pro ended (R7)", () => {
  it("pauses on a REFUND notification: no uploads, no new pins, live cleared; the rest still works", async () => {
    const g = await createGroup();
    const member = await addMember(g);
    const pinId = randomId();
    await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: member.pass, body: { pinId, createdAt: g.now, expiresAt: null, rec: envelopeOf("pinWater") } });
    await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: member.pass, body: { live: { at: g.now, rec: envelopeOf("live") } } });

    const refund = await appleNotification(g.now + 10, "REFUND", {
      transaction: appleTransaction(g.now, { originalTransactionId: g.otid, revocationDate: (g.now + 5) * 1000 }),
    });
    expect((await notify(refund)).status).toBe(200);

    const s = await status(g);
    expect(s).toMatchObject({
      status: "paused",
      pausedReason: "pro_refunded",
      pausedAt: g.now,
      deleteAt: g.now + 30 * 86400,
      pausedIntervals: [[g.now, null]],
    });
    expect((await api("GET", `/v1/groups/${g.groupId}/live`, { pass: member.pass })).body.live).toEqual([]);
    const up = await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: member.pass, body: { live: { at: g.now + 3, rec: envelopeOf("live") } } });
    expect(up.body.error).toBe("group_paused");
    const pin = await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: member.pass, body: { pinId: randomId(), createdAt: g.now, expiresAt: null, rec: envelopeOf("pinWater") } });
    expect(pin.body.error).toBe("group_paused");
    // Still allowed while paused (§8.10, SD18).
    const inv = await makeInvite(g);
    expect((await api("POST", "/v1/invites/preview", { body: { token: inv.token, joinProof: g.joinProof } })).body.status).toBe("paused");
    expect((await redeem(g, inv.token)).status).toBe(201);
    expect((await api("PUT", `/v1/groups/${g.groupId}/profile`, { pass: g.owner.pass, body: { profile: GROUP_PROFILE } })).status).toBe(200);
    expect((await api("DELETE", `/v1/groups/${g.groupId}/pins/${pinId}`, { pass: member.pass })).status).toBe(204);
    expect((await api("DELETE", `/v1/groups/${g.groupId}/trail/me`, { pass: member.pass })).status).toBe(204);
    expect((await api("POST", `/v1/groups/${g.groupId}/reports`, { pass: member.pass, body: { targetKind: "group", targetId: g.groupId, reason: "spam" } })).status).toBe(202);
    expect((await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: member.memberId } })).status).toBe(200);
    // The purge is set for 30 days after the pause.
    expect(await rows(g.groupId, "SELECT at FROM jobs WHERE kind = 'purge'")).toEqual([{ at: g.now + 30 * 86400 }]);
  });

  it("can't be undone by the pre-refund proof (402 pro_inactive), but a new purchase resumes it", async () => {
    const g = await createGroup();
    const original = await signedTransaction(g.now, { originalTransactionId: g.otid });
    await notify(await appleNotification(g.now + 10, "REFUND", { transaction: appleTransaction(g.now, { originalTransactionId: g.otid, revocationDate: (g.now + 5) * 1000 }) }));
    const old = await api("PUT", `/v1/groups/${g.groupId}/pro`, { pass: g.owner.pass, body: { pro: { platform: "apple", signedTransaction: original } } });
    expect(old.status).toBe(402);
    expect(old.body.error).toBe("pro_inactive");
    // A newer proof of the same purchase can't clear a refund either (sticky).
    const newer = await signedTransaction(g.now + 20, { originalTransactionId: g.otid });
    expect((await api("PUT", `/v1/groups/${g.groupId}/pro`, { pass: g.owner.pass, body: { pro: { platform: "apple", signedTransaction: newer } } })).status).toBe(402);

    await advance(g, 100);
    const fresh = await appleProofAt(g.now, { productId: "pro.lifetime" });
    const res = await api("PUT", `/v1/groups/${g.groupId}/pro`, { pass: g.owner.pass, body: { pro: fresh.pro } });
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      status: "active",
      pausedReason: null,
      pausedAt: null,
      deleteAt: null,
      pausedIntervals: [[g.now - 100, g.now]],
      pro: { platform: "apple", productId: "pro.lifetime", expiresAt: null },
    });
    expect(await proStub(g.proName).groupCount()).toBe(0);
    expect(await proStub(fresh.proName).groupCount()).toBe(1);
    expect(await rows(g.groupId, "SELECT * FROM jobs WHERE kind = 'purge'")).toEqual([]);
  });

  it("stays paused when an older-signed DID_RENEW follows EXPIRED; a newer one resumes", async () => {
    const g = await createGroup();
    const tx = appleTransaction(g.now, { originalTransactionId: g.otid, expiresDate: (g.now + 5000) * 1000 });
    await notify(await appleNotification(g.now + 100, "EXPIRED", { subtype: "VOLUNTARY", transaction: tx }));
    expect((await status(g)).pausedReason).toBe("pro_ended");
    await notify(await appleNotification(g.now + 50, "DID_RENEW", { transaction: tx }));
    expect((await status(g)).status).toBe("paused");
    await notify(await appleNotification(g.now + 200, "DID_RENEW", { transaction: tx }));
    expect((await status(g)).status).toBe("active");
  });

  it("drops a repeated notification by notificationUUID", async () => {
    const g = await createGroup();
    const tx = appleTransaction(g.now, { originalTransactionId: g.otid });
    const uuid = crypto.randomUUID();
    await notify(await appleNotification(g.now + 100, "EXPIRED", { transaction: tx, uuid }));
    await notify(await appleNotification(g.now + 200, "DID_RENEW", { transaction: tx }));
    expect((await status(g)).status).toBe("active");
    await notify(await appleNotification(g.now + 300, "EXPIRED", { transaction: tx, uuid }));
    expect((await status(g)).status).toBe("active");
  });

  it("resumes when ownership passes to a member who has Pro", async () => {
    const g = await createGroup();
    const member = await addMember(g);
    await notify(await appleNotification(g.now + 10, "EXPIRED", { transaction: appleTransaction(g.now, { originalTransactionId: g.otid }) }));
    expect((await status(g)).status).toBe("paused");
    await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: member.memberId } });
    const { pro } = await appleProofAt(g.now);
    const res = await api("POST", `/v1/groups/${g.groupId}/transfer/accept`, { pass: member.pass, body: { pro } });
    expect(res.status).toBe(200);
    expect(res.body.status).toBe("active");
  });

  it("deletes everything 30 days after the pause unless it resumed (R8)", async () => {
    const g = await createGroup();
    await env.ARCHIVE.put(`g/${g.groupId}/t/${g.owner.memberId}/2030-01.bin`, new Uint8Array([1]));
    await notify(await appleNotification(g.now + 10, "EXPIRED", { transaction: appleTransaction(g.now, { originalTransactionId: g.otid }) }));
    await advance(g, 30 * 86400);
    await runAlarm(g.groupId);
    const gone = await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass });
    expect(gone.status).toBe(404);
    expect(gone.body.error).toBe("group_not_found");
    expect((await env.ARCHIVE.list({ prefix: `g/${g.groupId}/` })).objects).toEqual([]);
    expect(await proStub(g.proName).groupCount()).toBe(0);
    expect(await inGroup(g.groupId, (_d, state) => state.storage.sql.exec("SELECT name FROM sqlite_master WHERE type = 'table'").toArray())).toEqual([]);
  });
});

describe("pro_check at expiry (SPEC §7.4)", () => {
  it("re-checks a stale ACTIVE answer every 10 min, 6 times, then pauses pro_unverified; pro_retry resumes", async () => {
    const google = await fakeGoogle();
    const now = T0;
    const { g, token } = await googleGroup(now, google, purchaseToken(), now + 3600);
    expect(await rows(g.groupId, "SELECT at FROM jobs WHERE kind = 'pro_check'")).toEqual([{ at: now + 3600 }]);
    await advance(g, 3600);
    for (let i = 0; i < 6; i++) {
      expect(await runAlarm(g.groupId)).toBe(true);
      expect((await status(g)).status, `check ${i}`).toBe("active");
      expect(await rows(g.groupId, "SELECT at FROM jobs WHERE kind = 'pro_check'")).toEqual([{ at: g.now + 600 }]);
      await advance(g, 600);
    }
    await runAlarm(g.groupId);
    const paused = await status(g);
    expect(paused).toMatchObject({ status: "paused", pausedReason: "pro_unverified" });
    expect(await rows(g.groupId, "SELECT at FROM jobs WHERE kind = 'pro_retry'")).toEqual([{ at: g.now + 1800 }]);
    // The renewal shows up: the next retry resumes.
    const renewed = g.now + 30 * 86400;
    google.purchases.set(token, [200, subscription(g.now, { lineItems: [{ productId: "pro", expiryTime: rfc3339(renewed) }] })]);
    await advance(g, 1800);
    await runAlarm(g.groupId);
    expect(await status(g)).toMatchObject({ status: "active", pro: { platform: "google", productId: "pro", expiresAt: renewed } });
    expect(await rows(g.groupId, "SELECT at FROM jobs WHERE kind = 'pro_check'")).toEqual([{ at: renewed }]);
  });

  it("pauses pro_unverified when the store can't be asked, retrying every 30 min for 24 h, then every 6 h", async () => {
    const google = await fakeGoogle();
    const { g, token } = await googleGroup(T0, google, purchaseToken(), T0 + 600);
    google.purchases.set(token, [503, {}]);
    await advance(g, 600);
    await runAlarm(g.groupId);
    expect(await status(g)).toMatchObject({ status: "paused", pausedReason: "pro_unverified" });
    await advance(g, 1800);
    await runAlarm(g.groupId);
    expect(await rows(g.groupId, "SELECT at FROM jobs WHERE kind = 'pro_retry'")).toEqual([{ at: g.now + 1800 }]);
    await advance(g, 86400);
    await runAlarm(g.groupId);
    expect(await rows(g.groupId, "SELECT at FROM jobs WHERE kind = 'pro_retry'")).toEqual([{ at: g.now + 6 * 3600 }]);
  });

  it("pauses with the store's reason when the subscription ended, and moves the expiry on a renewal", async () => {
    const google = await fakeGoogle();
    const { g, token } = await googleGroup(T0, google, purchaseToken(), T0 + 600);
    google.purchases.set(token, [200, subscription(T0, { lineItems: [{ productId: "pro", expiryTime: rfc3339(T0 + 99999) }] })]);
    await advance(g, 600);
    await runAlarm(g.groupId);
    expect((await status(g)).pro.expiresAt).toBe(T0 + 99999);
    google.purchases.set(token, [200, subscription(T0, { subscriptionState: "SUBSCRIPTION_STATE_EXPIRED" })]);
    await advance(g, 99999);
    await runAlarm(g.groupId);
    expect(await status(g)).toMatchObject({ status: "paused", pausedReason: "pro_ended" });
  });

  it("asks the App Store Server API at expiry when its credentials are set", async () => {
    const creds = await appleP8();
    const g = await createGroup({ tx: { expiresDate: (T0 + 600) * 1000 } });
    const expired = await signedTransaction(T0, { originalTransactionId: g.otid, expiresDate: (T0 + 600) * 1000 });
    await inPro(g.proName, (p) => {
      p.envOverride = creds;
      p.httpFetch = async () =>
        Response.json({ data: [{ lastTransactions: [{ originalTransactionId: g.otid, status: 2, signedTransactionInfo: expired }] }] });
    });
    await advance(g, 600);
    await runAlarm(g.groupId);
    expect(await status(g)).toMatchObject({ status: "paused", pausedReason: "pro_ended" });
  });

  it("checks a Lifetime purchase on the 1st of each month", async () => {
    const creds = await appleP8();
    const start = Date.UTC(2030, 2, 30, 12) / 1000;
    const g = await createGroup({ now: start, tx: { productId: "pro.lifetime" } });
    const revoked = await signedTransaction(start, { originalTransactionId: g.otid, productId: "pro.lifetime", revocationDate: (start + 10) * 1000 });
    let calls = 0;
    await inPro(g.proName, (p) => {
      p.envOverride = creds;
      p.httpFetch = async () => {
        calls += 1;
        return Response.json({ signedTransactionInfo: revoked });
      };
    });
    await advance(g, 86400); // 31 March: the daily job runs, no check
    await runAlarm(g.groupId);
    expect(calls).toBe(0);
    await advance(g, 86400); // 1 April
    await runAlarm(g.groupId);
    expect(calls).toBe(1);
    expect(await status(g)).toMatchObject({ status: "paused", pausedReason: "pro_refunded" });
  });

  it("keeps the pro_check of a purchase E5 attached while the old purchase's check waited for the store", async () => {
    const google = await fakeGoogle();
    const { g, token } = await googleGroup(T0, google, purchaseToken(), T0 + 600);
    google.purchases.set(token, [503, {}]);
    const held = await holdFirstPurchaseCall(g.proName, google, T0);
    await advance(g, 600);
    const alarm = runAlarm(g.groupId);
    await held.asked;
    const fresh = await appleProofAt(g.now);
    const res = await api("PUT", `/v1/groups/${g.groupId}/pro`, { pass: g.owner.pass, body: { pro: fresh.pro } });
    expect(res.status).toBe(200);
    const expiresAt = res.body.pro.expiresAt as number;
    expect(expiresAt).toBeGreaterThan(g.now);
    held.release();
    await alarm;
    // The old purchase's "unknown" answer neither pauses the group nor drops the new purchase's check.
    expect(await status(g)).toMatchObject({ status: "active", pro: { platform: "apple", expiresAt } });
    expect(await rows(g.groupId, "SELECT kind, at FROM jobs WHERE kind IN ('pro_check', 'pro_retry')")).toEqual([{ kind: "pro_check", at: expiresAt }]);
    expect(await proStub(g.proName).groupCount()).toBe(0);
    expect(await proStub(fresh.proName).groupCount()).toBe(1);
  });

  it("drops a stale 'unknown' answer when E5 proved the same purchase renewed meanwhile", async () => {
    const google = await fakeGoogle();
    const { g, token } = await googleGroup(T0, google, purchaseToken(), T0 + 600);
    const held = await holdFirstPurchaseCall(g.proName, google, T0, [503, {}]);
    await advance(g, 600);
    const alarm = runAlarm(g.groupId);
    await held.asked;
    const renewed = g.now + 30 * 86400;
    google.purchases.set(token, [200, subscription(g.now, { lineItems: [{ productId: "pro", expiryTime: rfc3339(renewed) }] })]);
    const res = await api("PUT", `/v1/groups/${g.groupId}/pro`, { pass: g.owner.pass, body: { pro: googlePro(token) } });
    expect(res.status).toBe(200);
    expect(res.body.pro.expiresAt).toBe(renewed);
    held.release();
    await alarm;
    expect(await status(g)).toMatchObject({ status: "active", pausedReason: null, pro: { platform: "google", expiresAt: renewed } });
    expect(await rows(g.groupId, "SELECT kind, at FROM jobs WHERE kind IN ('pro_check', 'pro_retry')")).toEqual([{ kind: "pro_check", at: renewed }]);
  });
});

describe("requests that lose a race during their store call (SD9, §1.3)", () => {
  it("E1: the create that lost the groupId leaves its purchase without the group", async () => {
    const google = await fakeGoogle();
    const token = purchaseToken();
    google.purchases.set(token, [200, subscription(T0)]);
    const proName = await googleProName(token);
    const held = await holdFirstPurchaseCall(proName, google, T0);
    const groupId = randomId();
    await setClock(groupId, T0);
    const owner = newMember();
    const losing = api("POST", "/v1/groups", {
      body: {
        groupId,
        memberId: owner.memberId,
        pass: owner.pass,
        joinProof: randomSecret(),
        groupProfile: GROUP_PROFILE,
        memberProfile: MEMBER_PROFILE,
        termsVersion: 1,
        pro: googlePro(token),
      },
    });
    await held.asked;
    const winner = await createGroup({ groupId });
    held.release();
    const res = await losing;
    expect(res.status).toBe(409);
    expect(res.body.error).toBe("group_exists");
    expect(await proStub(proName).groupCount()).toBe(0);
    expect(await proStub(winner.proName).groupCount()).toBe(1);
  });

  it("E5: a purchase sent while the group was deleted is detached again", async () => {
    const google = await fakeGoogle();
    const token = purchaseToken();
    google.purchases.set(token, [200, subscription(T0)]);
    const g = await createGroup();
    const proName = await googleProName(token);
    const held = await holdFirstPurchaseCall(proName, google, g.now);
    const sending = api("PUT", `/v1/groups/${g.groupId}/pro`, { pass: g.owner.pass, body: { pro: googlePro(token) } });
    await held.asked;
    expect((await api("DELETE", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).status).toBe(204);
    held.release();
    const res = await sending;
    expect(res.status).toBe(410);
    expect(res.body.error).toBe("group_deleted");
    expect(await proStub(proName).groupCount()).toBe(0);
    expect(await proStub(g.proName).groupCount()).toBe(0);
  });

  it("E17: a purchase sent while the group was deleted is detached again", async () => {
    const google = await fakeGoogle();
    const token = purchaseToken();
    google.purchases.set(token, [200, subscription(T0)]);
    const g = await createGroup();
    const m = await addMember(g);
    expect((await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: m.memberId } })).status).toBe(200);
    const proName = await googleProName(token);
    const held = await holdFirstPurchaseCall(proName, google, g.now);
    const accepting = api("POST", `/v1/groups/${g.groupId}/transfer/accept`, { pass: m.pass, body: { pro: googlePro(token) } });
    await held.asked;
    expect((await api("DELETE", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).status).toBe(204);
    held.release();
    const res = await accepting;
    expect(res.status).toBe(410);
    expect(res.body.error).toBe("group_deleted");
    expect(await proStub(proName).groupCount()).toBe(0);
  });

  it("E5 from an owner who handed the group over (E17) meanwhile is refused and leaves the new owner's purchase", async () => {
    const google = await fakeGoogle();
    const token = purchaseToken();
    google.purchases.set(token, [200, subscription(T0)]);
    const g = await createGroup();
    const m = await addMember(g);
    expect((await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: m.memberId } })).status).toBe(200);
    const proName = await googleProName(token);
    const held = await holdFirstPurchaseCall(proName, google, g.now);
    const sending = api("PUT", `/v1/groups/${g.groupId}/pro`, { pass: g.owner.pass, body: { pro: googlePro(token) } });
    await held.asked;
    const theirs = await appleProofAt(g.now);
    const accepted = await api("POST", `/v1/groups/${g.groupId}/transfer/accept`, { pass: m.pass, body: { pro: theirs.pro } });
    expect(accepted.status).toBe(200);
    held.release();
    const res = await sending;
    expect(res.status).toBe(403);
    expect(res.body.error).toBe("forbidden");
    expect(await rows(g.groupId, "SELECT pro_name FROM grp")).toEqual([{ pro_name: theirs.proName }]);
    expect(await rows(g.groupId, "SELECT role FROM members WHERE member_id = ?", g.owner.memberId)).toEqual([{ role: "admin" }]);
    expect(await rows(g.groupId, "SELECT role FROM members WHERE member_id = ?", m.memberId)).toEqual([{ role: "owner" }]);
    expect(await proStub(theirs.proName).groupCount()).toBe(1);
    expect(await proStub(proName).groupCount()).toBe(0);
    expect(await proStub(g.proName).groupCount()).toBe(0);
  });
});

describe("Google: E28 notifications and linked purchases", () => {
  const rtdn = (data: unknown, messageId = crypto.randomUUID()) => ({
    message: { data: btoa(JSON.stringify(data)), messageId, publishTime: "2030-01-01T00:00:00Z" },
    subscription: "projects/p/subscriptions/s",
  });

  it("is 404 without a token of 32+ characters, 403 with a wrong token, 204 with the right one", async () => {
    const send = (token: string, e: Record<string, unknown> = {}) =>
      worker.fetch(
        new Request(`${BASE}/v1/store/google?token=${token}`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(rtdn({ testNotification: { version: "1.0" } })),
        }),
        { ...env, ...e } as typeof env,
      );
    expect((await send(RTDN, { GOOGLE_RTDN_TOKEN: "" })).status).toBe(404);
    expect((await send("a".repeat(31), { GOOGLE_RTDN_TOKEN: "a".repeat(31) })).status).toBe(404);
    // Any method: without its secret the endpoint doesn't exist, so no 405 either.
    const get = (e: Record<string, unknown>) => worker.fetch(new Request(`${BASE}/v1/store/google?token=${RTDN}`), { ...env, ...e } as typeof env);
    expect((await get({ GOOGLE_RTDN_TOKEN: "" })).status).toBe(404);
    expect((await get({ GOOGLE_RTDN_TOKEN: "a".repeat(31) })).status).toBe(404);
    expect((await get({})).status).toBe(405);
    expect((await send("wrong-token-wrong-token-wrong-token")).status).toBe(403);
    expect((await send(RTDN)).status).toBe(204);
    const bad = await api("POST", `/v1/store/google?token=${RTDN}`, { body: { hello: 1 } });
    expect(bad.status).toBe(400);
  });

  it("re-checks the purchase with the Play API on a notification, ignoring repeats", async () => {
    const google = await fakeGoogle();
    const { g, token } = await googleGroup(T0, google);
    google.purchases.set(token, [200, subscription(T0, { subscriptionState: "SUBSCRIPTION_STATE_ON_HOLD" })]);
    const body = rtdn({ eventTimeMillis: String(T0 * 1000), subscriptionNotification: { notificationType: 5, purchaseToken: token, subscriptionId: "pro" } }, "msg-1");
    expect((await api("POST", `/v1/store/google?token=${RTDN}`, { body })).status).toBe(204);
    expect(await status(g)).toMatchObject({ status: "paused", pausedReason: "pro_ended" });
    const calls = google.calls.length;
    expect((await api("POST", `/v1/store/google?token=${RTDN}`, { body })).status).toBe(204);
    expect(google.calls.length).toBe(calls);
    // A voided one-time purchase is confirmed with the API, never trusted on the message's word.
    const voided = rtdn({ voidedPurchaseNotification: { purchaseToken: purchaseToken(), orderId: "GPA.1", productType: 2 } });
    expect((await api("POST", `/v1/store/google?token=${RTDN}`, { body: voided })).status).toBe(204);
  });

  it("moves every group of a replaced purchase to the new one (linkedPurchaseToken)", async () => {
    const google = await fakeGoogle();
    const { g: first, token: oldToken } = await googleGroup(T0, google);
    const { g: second } = await googleGroup(T0, google, oldToken);
    const newToken = purchaseToken();
    google.purchases.set(newToken, [200, subscription(T0, { linkedPurchaseToken: oldToken })]);
    const newName = await googleProName(newToken);
    await inPro(newName, (p) => {
      p.httpFetch = google.fetch;
      p.clockOverride = T0;
      p.envOverride = { GOOGLE_SERVICE_ACCOUNT_JSON: google.serviceAccountJson };
    });
    const res = await api("PUT", `/v1/groups/${first.groupId}/pro`, { pass: first.owner.pass, body: { pro: { platform: "google", productId: "pro", purchaseToken: newToken } } });
    expect(res.status).toBe(200);
    expect(await proStub(newName).groupCount()).toBe(2);
    expect(await proStub(first.proName).groupCount()).toBe(0);
    expect(await rows(second.groupId, "SELECT pro_name FROM grp")).toEqual([{ pro_name: newName }]);
  });
});

describe("App Store notifications with API credentials", () => {
  it("call check() and use the API's answer, not the notification's", async () => {
    const creds = await appleP8();
    const g = await createGroup();
    let calls = 0;
    const active = await signedTransaction(T0, { originalTransactionId: g.otid });
    await inPro(g.proName, (p) => {
      p.envOverride = creds;
      p.httpFetch = async () => {
        calls += 1;
        return Response.json({ data: [{ lastTransactions: [{ originalTransactionId: g.otid, status: 1, signedTransactionInfo: active }] }] });
      };
    });
    await notify(await appleNotification(T0 + 10, "EXPIRED", { transaction: appleTransaction(T0, { originalTransactionId: g.otid }) }));
    expect(calls).toBe(1);
    expect((await status(g)).status).toBe("active");
  });

  it("are ignored for a purchase no group ever used", async () => {
    const otid = "9999999999";
    const res = await notify(await appleNotification(T0, "DID_RENEW", { transaction: appleTransaction(T0, { originalTransactionId: otid }) }));
    expect(res.status).toBe(200);
    expect(await inPro(`apple:${otid}`, (_p, state) => state.storage.sql.exec("SELECT name FROM sqlite_master WHERE type = 'table'").toArray())).toEqual([]);
  });
});

describe("ProDO alarms", () => {
  it("checks 60 s after the expiry as a second safeguard and tells the groups", async () => {
    const g = await createGroup({ tx: { expiresDate: (T0 + 600) * 1000 } });
    const alarm = await inPro(g.proName, (_p, state) => state.storage.getAlarm());
    expect(alarm).toBe((T0 + 660) * 1000);
    await setProClock(g.proName, T0 + 660);
    await runDurableObjectAlarm(proStub(g.proName));
    // TEST_MODE without credentials: the stored state, re-decided, has ended.
    expect(await status(g)).toMatchObject({ status: "paused", pausedReason: "pro_ended" });
  });

  it("deletes itself 30 days after its last group left; an attach in between cancels that", async () => {
    const g = await createGroup();
    await api("DELETE", `/v1/groups/${g.groupId}`, { pass: g.owner.pass });
    const job = await inPro(g.proName, (_p, state) => state.storage.sql.exec("SELECT at FROM jobs WHERE kind = 'self_delete'").toArray());
    expect(job).toEqual([{ at: T0 + 30 * 86400 }]);
    await setProClock(g.proName, T0 + 30 * 86400);
    await runDurableObjectAlarm(proStub(g.proName));
    expect(await inPro(g.proName, (_p, state) => state.storage.sql.exec("SELECT name FROM sqlite_master WHERE type = 'table'").toArray())).toEqual([]);
    expect(await proStub(g.proName).current()).toBeNull();
  });
});

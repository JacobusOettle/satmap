// Google Play decision tables, the API client with httpFetch replaced, the negative cache and RTDN decoding
// (SPEC §7.6, §12.1).

import { describe, expect, it } from "vitest";
import { readConfig } from "../../src/lib/config";
import {
  clearGoogleTokenCache,
  decideProduct,
  decideSubscription,
  decodeRtdn,
  googleStoreCheck,
  isPurchaseToken,
  parseServiceAccount,
} from "../../src/pro/google";
import { googleProName } from "../../src/pro/ProDO";
import { inPro, randomId } from "../helpers/client";
import { fakeGoogle, product, purchaseToken, rfc3339, subscription } from "../helpers/google";

const NOW = 1_900_000_000;
const S = { packageName: "com.jacobusoettle.satmap", allowTestPurchases: true };

describe("subscriptionsv2 decisions", () => {
  const decide = (body: Record<string, unknown>, s = S) => decideSubscription(body, "pro", s, NOW);

  it("is active in ACTIVE and IN_GRACE_PERIOD, with the latest line item expiry", () => {
    const body = subscription(NOW, {
      lineItems: [
        { productId: "pro", expiryTime: rfc3339(NOW + 100) },
        { productId: "pro", expiryTime: rfc3339(NOW + 500) },
      ],
    });
    expect(decide(body)).toMatchObject({ type: "state", state: { active: true, expiresAt: NOW + 500, kind: "subscription" } });
    const grace = decide(subscription(NOW, { subscriptionState: "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" }));
    expect(grace).toMatchObject({ type: "state", state: { active: true, inGrace: true } });
  });

  it("is active when CANCELED only until the expiry", () => {
    expect(decide(subscription(NOW, { subscriptionState: "SUBSCRIPTION_STATE_CANCELED" }))).toMatchObject({ state: { active: true } });
    const past = subscription(NOW, {
      subscriptionState: "SUBSCRIPTION_STATE_CANCELED",
      lineItems: [{ productId: "pro", expiryTime: rfc3339(NOW - 1) }],
    });
    expect(decide(past)).toMatchObject({ state: { active: false, reason: "pro_ended" } });
  });

  it("is inactive (pro_ended) for ON_HOLD, PAUSED, EXPIRED, PENDING and every other state", () => {
    for (const state of [
      "SUBSCRIPTION_STATE_ON_HOLD",
      "SUBSCRIPTION_STATE_PAUSED",
      "SUBSCRIPTION_STATE_EXPIRED",
      "SUBSCRIPTION_STATE_PENDING",
      "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED",
      "SUBSCRIPTION_STATE_UNSPECIFIED",
      "SUBSCRIPTION_STATE_SOMETHING_NEW",
    ]) {
      expect(decide(subscription(NOW, { subscriptionState: state })), state).toMatchObject({ type: "state", state: { active: false, reason: "pro_ended" } });
    }
    const { subscriptionState: _ignored, ...missing } = subscription(NOW);
    expect(decide(missing)).toMatchObject({ state: { active: false } });
  });

  it("is invalid when a line item names another product", () => {
    const body = subscription(NOW, { lineItems: [{ productId: "pro" }, { productId: "coins", expiryTime: rfc3339(NOW + 10) }] });
    expect(decide(body)).toEqual({ type: "invalid" });
    expect(decide(subscription(NOW, { lineItems: [] }))).toEqual({ type: "invalid" });
  });

  it("caps test purchases at 14 days after startTime, and refuses them without ALLOW_TEST_PURCHASES", () => {
    const start = NOW - 10 * 86400;
    const body = subscription(NOW, { testPurchase: {}, startTime: rfc3339(start) });
    expect(decide(body)).toMatchObject({ state: { active: true, expiresAt: start + 14 * 86400, environment: "Test" } });
    const old = subscription(NOW, { testPurchase: {}, startTime: rfc3339(NOW - 15 * 86400) });
    expect(decide(old)).toMatchObject({ state: { active: false } });
    expect(decide(body, { ...S, allowTestPurchases: false })).toEqual({ type: "invalid" });
  });

  it("reports linkedPurchaseToken", () => {
    const linked = purchaseToken();
    expect(decide(subscription(NOW, { linkedPurchaseToken: linked }))).toMatchObject({ state: { linkedPurchaseToken: linked } });
  });
});

describe("productsv2 decisions", () => {
  const decide = (body: Record<string, unknown>, s = S) => decideProduct(body, "pro_lifetime", s, NOW);

  it("is active when PURCHASED, with no expiry", () => {
    expect(decide(product(NOW))).toMatchObject({ type: "state", state: { active: true, expiresAt: null, kind: "lifetime" } });
  });

  it("is refunded when CANCELLED and inactive for anything else", () => {
    expect(decide(product(NOW, { purchaseStateContext: { purchaseState: "CANCELLED" } }))).toMatchObject({ state: { active: false, reason: "pro_refunded" } });
    expect(decide(product(NOW, { purchaseStateContext: { purchaseState: "PENDING" } }))).toMatchObject({ state: { active: false, reason: "pro_ended" } });
    expect(decide(product(NOW, { purchaseStateContext: undefined }))).toMatchObject({ state: { active: false } });
  });

  it("is invalid when no line item is pro_lifetime (a cheaper one-time product's token)", () => {
    expect(decide(product(NOW, { productLineItem: [{ productId: "tip_jar" }] }))).toEqual({ type: "invalid" });
  });

  it("caps test purchases at 14 days after purchaseCompletionTime", () => {
    const done = NOW - 86400;
    expect(decide(product(NOW, { testPurchaseContext: { fopType: "TEST" }, purchaseCompletionTime: rfc3339(done) }))).toMatchObject({
      state: { active: true, expiresAt: done + 14 * 86400 },
    });
    expect(decide(product(NOW, { testPurchaseContext: {} }), { ...S, allowTestPurchases: false })).toEqual({ type: "invalid" });
  });
});

describe("purchase token shape", () => {
  it("accepts 20–1024 characters of [A-Za-z0-9._-]", () => {
    expect(isPurchaseToken("a".repeat(20))).toBe(true);
    expect(isPurchaseToken("a".repeat(1024))).toBe(true);
    expect(isPurchaseToken("a".repeat(19))).toBe(false);
    expect(isPurchaseToken("a".repeat(1025))).toBe(false);
    expect(isPurchaseToken(`${"a".repeat(20)} `)).toBe(false);
    expect(isPurchaseToken(`${"a".repeat(20)}/`)).toBe(false);
  });
});

describe("Play Developer API client", () => {
  it("gets an access token once, then asks subscriptionsv2 or productsv2", async () => {
    clearGoogleTokenCache();
    const google = await fakeGoogle();
    const sa = parseServiceAccount(google.serviceAccountJson)!;
    const sub = purchaseToken();
    const life = purchaseToken();
    google.purchases.set(sub, [200, subscription(NOW)]);
    google.purchases.set(life, [200, product(NOW)]);
    expect(await googleStoreCheck(sub, "pro", S, sa, google.fetch, NOW)).toMatchObject({ type: "state", state: { active: true } });
    expect(await googleStoreCheck(life, "pro_lifetime", S, sa, google.fetch, NOW)).toMatchObject({ type: "state", state: { active: true } });
    expect(google.tokenRequests).toBe(1);
    expect(google.calls).toContain(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.jacobusoettle.satmap/purchases/subscriptionsv2/tokens/${sub}`,
    );
    expect(google.calls).toContain(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.jacobusoettle.satmap/purchases/productsv2/tokens/${life}`,
    );
  });

  it("maps 400, 404 and 410 to invalid, and 401, 403, 429, 5xx and network errors to unknown", async () => {
    clearGoogleTokenCache();
    const google = await fakeGoogle();
    const sa = parseServiceAccount(google.serviceAccountJson)!;
    for (const [status, type] of [
      [400, "invalid"],
      [404, "invalid"],
      [410, "invalid"],
      [401, "unknown"],
      [403, "unknown"],
      [429, "unknown"],
      [500, "unknown"],
      [503, "unknown"],
    ] as const) {
      const token = purchaseToken();
      google.purchases.set(token, [status, {}]);
      expect(await googleStoreCheck(token, "pro", S, sa, google.fetch, NOW), String(status)).toEqual({ type });
    }
    const down = await googleStoreCheck(purchaseToken(), "pro", S, sa, async () => { throw new Error("offline"); }, NOW + 7200);
    expect(down).toEqual({ type: "unknown" });
  });

  it("parses a service account JSON", () => {
    expect(parseServiceAccount("")).toBeNull();
    expect(parseServiceAccount("{")).toBeNull();
    expect(parseServiceAccount(JSON.stringify({ client_email: "a" }))).toBeNull();
    expect(parseServiceAccount(JSON.stringify({ client_email: "a", private_key: "k" }))).toMatchObject({ clientEmail: "a" });
  });
});

describe("ProDO negative cache", () => {
  it("answers a refused token from memory for 10 minutes, and writes nothing", async () => {
    const google = await fakeGoogle();
    const token = purchaseToken();
    const name = await googleProName(token);
    google.purchases.set(token, [404, {}]);
    const attach = () =>
      inPro(name, async (pro) => {
        pro.httpFetch = google.fetch;
        pro.envOverride = { GOOGLE_SERVICE_ACCOUNT_JSON: google.serviceAccountJson };
        return pro.attach(name, randomId(), { platform: "google", productId: "pro", purchaseToken: token });
      });
    clearGoogleTokenCache();
    expect(await attach()).toEqual({ ok: false, error: "pro_invalid" });
    const calls = google.calls.length;
    expect(await attach()).toEqual({ ok: false, error: "pro_invalid" });
    expect(google.calls.length).toBe(calls);
    // A junk token leaves no stored object.
    expect(await inPro(name, (_p, state) => state.storage.sql.exec("SELECT name FROM sqlite_master WHERE type = 'table'").toArray())).toEqual([]);
    // After 10 minutes the store is asked again.
    await inPro(name, (pro) => {
      pro.clockOverride = Math.floor(Date.now() / 1000) + 601;
    });
    expect(await attach()).toEqual({ ok: false, error: "pro_invalid" });
    expect(google.calls.length).toBeGreaterThan(calls);
  });

  it("remembers an inactive answer too", async () => {
    const google = await fakeGoogle();
    const token = purchaseToken();
    const name = await googleProName(token);
    google.purchases.set(token, [200, subscription(NOW, { subscriptionState: "SUBSCRIPTION_STATE_EXPIRED" })]);
    const attach = () =>
      inPro(name, async (pro) => {
        pro.httpFetch = google.fetch;
        pro.clockOverride = NOW;
        pro.envOverride = { GOOGLE_SERVICE_ACCOUNT_JSON: google.serviceAccountJson };
        return pro.attach(name, randomId(), { platform: "google", productId: "pro", purchaseToken: token });
      });
    expect(await attach()).toEqual({ ok: false, error: "pro_inactive" });
    const calls = google.calls.length;
    expect(await attach()).toEqual({ ok: false, error: "pro_inactive" });
    expect(google.calls.length).toBe(calls);
  });

  it("ignores ALLOW_UNVERIFIED_PRO without TEST_MODE", async () => {
    expect(readConfig({ TEST_MODE: "0", ALLOW_UNVERIFIED_PRO: "true" }).allowUnverifiedPro).toBe(false);
    expect(readConfig({ TEST_MODE: "1", ALLOW_UNVERIFIED_PRO: "true" }).allowUnverifiedPro).toBe(true);
    const token = purchaseToken();
    const name = await googleProName(token);
    const result = await inPro(name, async (pro) => {
      pro.envOverride = { TEST_MODE: "0", ALLOW_UNVERIFIED_PRO: "true" };
      return pro.attach(name, randomId(), { platform: "google", productId: "pro", purchaseToken: token });
    });
    expect(result).toEqual({ ok: false, error: "store_unavailable" });
    const unverified = await inPro(name, async (pro) => {
      pro.envOverride = { TEST_MODE: "1", ALLOW_UNVERIFIED_PRO: "true" };
      pro.clockOverride = NOW;
      return pro.attach(name, randomId(), { platform: "google", productId: "pro", purchaseToken: token });
    });
    expect(unverified).toMatchObject({ ok: true, state: { active: true, productId: "pro", expiresAt: NOW + 31_536_000 } });
  });
});

describe("RTDN decoding", () => {
  const push = (data: unknown, messageId = "m-1") => ({
    message: { data: btoa(JSON.stringify(data)), messageId, publishTime: "2026-11-02T10:00:00Z" },
    subscription: "projects/p/subscriptions/s",
  });

  it("decodes the three purchase notifications and the test notification", () => {
    const token = purchaseToken();
    expect(decodeRtdn(push({ version: "1.0", packageName: "p", eventTimeMillis: "1793613600000", subscriptionNotification: { notificationType: 2, purchaseToken: token, subscriptionId: "pro" } }))).toEqual({
      messageId: "m-1",
      kind: "subscription",
      purchaseToken: token,
      productId: "pro",
      eventTimeMillis: 1793613600000,
    });
    expect(decodeRtdn(push({ oneTimeProductNotification: { notificationType: 1, purchaseToken: token, sku: "pro_lifetime" } }))).toMatchObject({
      kind: "oneTime",
      productId: "pro_lifetime",
    });
    expect(decodeRtdn(push({ voidedPurchaseNotification: { purchaseToken: token, orderId: "GPA.1", productType: 2, refundType: 1 } }))).toMatchObject({
      kind: "voided",
      productId: "pro_lifetime",
    });
    expect(decodeRtdn(push({ testNotification: { version: "1.0" } }))).toMatchObject({ kind: "test", purchaseToken: null });
  });

  it("is null for something that isn't a Pub/Sub push, and 'other' for unreadable data", () => {
    expect(decodeRtdn({})).toBeNull();
    expect(decodeRtdn({ message: {} })).toBeNull();
    expect(decodeRtdn({ message: { messageId: "x", data: "!!!" } })).toMatchObject({ kind: "other" });
    expect(decodeRtdn({ message: { messageId: "x", data: btoa("not json") } })).toMatchObject({ kind: "other" });
  });
});

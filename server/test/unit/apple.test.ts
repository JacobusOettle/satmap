// The Apple JWS verifier and the transaction and notification checks (SPEC §7.6, §12.1), with the test chain.

import { describe, expect, it } from "vitest";
import {
  appleApiToken,
  appleSettings,
  appleStoreCheck,
  checkTransaction,
  decideTransaction,
  deriveFromNotification,
  verifyJws,
  verifyNotification,
  type AppleSettings,
} from "../../src/pro/apple";
import { APPLE_ROOT_G3_B64, APPLE_ROOT_G3_SHA256_HEX } from "../../src/pro/apple-root-g3";
import { b64Decode, b64urlDecode } from "../../src/lib/b64url";
import { readConfig } from "../../src/lib/config";
import { hex, sha256 } from "../../src/lib/hash";
import { CERTS, appleNotification, appleTransaction, rootDer, signJws } from "../helpers/apple-test-chain";

const NOW = 1_900_000_000;

function settings(overrides: Partial<AppleSettings> = {}): AppleSettings {
  return {
    bundleId: "com.jacobusoettle.satmap",
    products: ["pro.monthly", "pro.yearly", "pro.lifetime"],
    allowTestPurchases: true,
    allowXcode: false,
    appAppleId: "1234567890",
    rootDer: rootDer(),
    ...overrides,
  };
}

async function failure(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
    return "ok";
  } catch (err) {
    return (err as Error).message;
  }
}

describe("pinned root", () => {
  it("is Apple Root CA - G3", async () => {
    expect(hex(await sha256(b64Decode(APPLE_ROOT_G3_B64)!))).toBe(APPLE_ROOT_G3_SHA256_HEX);
    expect(APPLE_ROOT_G3_SHA256_HEX.toUpperCase()).toBe("63343ABFB89A6A03EBB57E9B3F5FA7BE7C4F5C756F3017B3A8C488C3653E9179");
  });

  it("is replaced only under TEST_MODE=1", () => {
    const test = appleSettings(readConfig({ TEST_MODE: "1", APPLE_ROOT_OVERRIDE_B64: CERTS.root }));
    expect(test.rootDer).toEqual(rootDer());
    const prod = appleSettings(readConfig({ TEST_MODE: "0", APPLE_ROOT_OVERRIDE_B64: CERTS.root }));
    expect(prod.rootDer).toEqual(b64Decode(APPLE_ROOT_G3_B64));
  });
});

describe("verifyJws", () => {
  it("accepts a valid JWS and returns its payload", async () => {
    const payload = await verifyJws(await signJws(appleTransaction(NOW)), rootDer());
    expect(payload.bundleId).toBe("com.jacobusoettle.satmap");
  });

  it("refuses a chain that ends in another root", async () => {
    const jws = await signJws(appleTransaction(NOW), { x5c: [CERTS.leaf, CERTS.inter, CERTS.otherRoot] });
    expect(await failure(verifyJws(jws, rootDer()))).toBe("root");
    // A JWS with the real chain is refused by a verifier pinned to the real Apple root.
    expect(await failure(verifyJws(await signJws(appleTransaction(NOW)), b64Decode(APPLE_ROOT_G3_B64)!))).toBe("root");
  });

  it("refuses a leaf without the Apple leaf OID", async () => {
    const jws = await signJws(appleTransaction(NOW), { x5c: [CERTS.leafNoOid, CERTS.inter, CERTS.root] });
    expect(await failure(verifyJws(jws, rootDer()))).toBe("leaf OID");
  });

  it("refuses a leaf whose OID bytes appear only inside its subject name", async () => {
    const der = b64Decode(CERTS.leafOidInName)!;
    expect(hex(der)).toContain("060a2a864886f76364060b01"); // the bytes are there, but not as an extension
    const jws = await signJws(appleTransaction(NOW), { x5c: [CERTS.leafOidInName, CERTS.inter, CERTS.root] });
    expect(await failure(verifyJws(jws, rootDer()))).toBe("leaf OID");
  });

  it("refuses an intermediate without the Apple intermediate OID", async () => {
    const jws = await signJws(appleTransaction(NOW), { x5c: [CERTS.leafUnderNoOid, CERTS.interNoOid, CERTS.root] });
    expect(await failure(verifyJws(jws, rootDer()))).toBe("intermediate OID");
  });

  it("refuses an intermediate that is not a CA", async () => {
    const jws = await signJws(appleTransaction(NOW), { x5c: [CERTS.leafUnderNotCa, CERTS.interNotCa, CERTS.root] });
    expect(await failure(verifyJws(jws, rootDer()))).toBe("intermediate not a CA");
  });

  it("refuses a leaf that the intermediate didn't issue", async () => {
    const jws = await signJws(appleTransaction(NOW), { x5c: [CERTS.leafUnderNotCa, CERTS.inter, CERTS.root] });
    expect(await failure(verifyJws(jws, rootDer()))).toBe("leaf");
  });

  it("refuses a bad signature", async () => {
    const jws = await signJws(appleTransaction(NOW), { corruptSignature: true });
    expect(await failure(verifyJws(jws, rootDer()))).toBe("signature");
    const [h, p] = (await signJws(appleTransaction(NOW))).split(".");
    const other = (await signJws(appleTransaction(NOW, { productId: "pro.monthly" }))).split(".")[2];
    expect(await failure(verifyJws(`${h}.${p}.${other}`, rootDer()))).toBe("signature");
  });

  it("refuses another alg, a wrong x5c and malformed text", async () => {
    expect(await failure(verifyJws(await signJws(appleTransaction(NOW), { alg: "RS256" }), rootDer()))).toBe("alg");
    expect(await failure(verifyJws(await signJws(appleTransaction(NOW), { x5c: [CERTS.leaf, CERTS.inter] }), rootDer()))).toBe("x5c");
    expect(await failure(verifyJws("a.b", rootDer()))).toBe("not a JWS");
    expect(await failure(verifyJws("!!.!!.!!", rootDer()))).toBe("bad base64url");
  });

  it("refuses a payload signed when the certificates were not valid (±60 s)", async () => {
    const late = Date.UTC(2047, 0, 2) / 1000;
    expect(await failure(verifyJws(await signJws(appleTransaction(late)), rootDer()))).toBe("certificate not valid at signedDate");
    const early = Date.UTC(2026, 0, 1) / 1000 - 61;
    expect(await failure(verifyJws(await signJws(appleTransaction(early)), rootDer()))).toBe("certificate not valid at signedDate");
    const edge = Date.UTC(2026, 0, 1) / 1000 - 59;
    expect(await failure(verifyJws(await signJws(appleTransaction(edge)), rootDer()))).toBe("ok");
  });
});

describe("checkTransaction", () => {
  const check = async (overrides: Record<string, unknown>, s = settings()) =>
    failure(Promise.resolve().then(async () => checkTransaction(await verifyJws(await signJws(appleTransaction(NOW, overrides)), s.rootDer), s)));

  it("accepts the three products with their types", async () => {
    expect(await check({})).toBe("ok");
    expect(await check({ productId: "pro.monthly" })).toBe("ok");
    expect(await check({ productId: "pro.lifetime" })).toBe("ok");
  });

  it("refuses a wrong bundle id, product or type", async () => {
    expect(await check({ bundleId: "com.example.other" })).toBe("bundleId");
    expect(await check({ productId: "pro.weekly" })).toBe("productId");
    expect(await check({ productId: "pro.lifetime", type: "Auto-Renewable Subscription" })).toBe("type");
    expect(await check({ type: "Non-Consumable" })).toBe("type");
  });

  it("refuses Family Sharing", async () => {
    expect(await check({ inAppOwnershipType: "FAMILY_SHARED" })).toBe("inAppOwnershipType");
  });

  it("accepts Sandbox only while ALLOW_TEST_PURCHASES is true", async () => {
    expect(await check({ environment: "Sandbox" })).toBe("ok");
    expect(await check({ environment: "Sandbox" }, settings({ allowTestPurchases: false }))).toBe("environment");
  });

  it("accepts Xcode only with APPLE_ALLOW_XCODE under TEST_MODE", async () => {
    expect(await check({ environment: "Xcode" })).toBe("environment");
    const on = appleSettings(readConfig({ TEST_MODE: "1", APPLE_ALLOW_XCODE: "true", APPLE_ROOT_OVERRIDE_B64: CERTS.root }));
    expect(await check({ environment: "Xcode" }, on)).toBe("ok");
    const noTestMode = appleSettings(readConfig({ TEST_MODE: "0", APPLE_ALLOW_XCODE: "true" }));
    expect(noTestMode.allowXcode).toBe(false);
    expect(await check({ environment: "Xcode" }, { ...noTestMode, rootDer: rootDer() })).toBe("environment");
  });
});

describe("decideTransaction", () => {
  const decide = async (overrides: Record<string, unknown>, now = NOW) =>
    decideTransaction(checkTransaction(await verifyJws(await signJws(appleTransaction(NOW, overrides)), rootDer()), settings()), now);

  it("is active for an unexpired subscription, with its expiry", async () => {
    const state = await decide({ expiresDate: (NOW + 1000) * 1000 });
    expect(state).toMatchObject({ active: true, reason: null, expiresAt: NOW + 1000, productId: "pro.yearly", kind: "subscription" });
  });

  it("is inactive for an expired subscription", async () => {
    expect(await decide({ expiresDate: (NOW - 1) * 1000 })).toMatchObject({ active: false, reason: "pro_ended" });
  });

  it("is inactive and refunded for a revoked transaction", async () => {
    expect(await decide({ revocationDate: (NOW - 10) * 1000 })).toMatchObject({ active: false, reason: "pro_refunded" });
    expect(await decide({ productId: "pro.lifetime", revocationDate: NOW * 1000 })).toMatchObject({ active: false, reason: "pro_refunded" });
  });

  it("is active with no expiry for Lifetime", async () => {
    expect(await decide({ productId: "pro.lifetime" })).toMatchObject({ active: true, expiresAt: null, kind: "lifetime" });
  });

  it("caps Sandbox purchases at 14 days after the original purchase, also for Lifetime (SD20)", async () => {
    const original = NOW - 13 * 86400;
    const sub = await decide({ environment: "Sandbox", originalPurchaseDate: original * 1000, expiresDate: (NOW + 86400 * 30) * 1000 });
    expect(sub).toMatchObject({ active: true, expiresAt: original + 14 * 86400 });
    const life = await decide({ environment: "Sandbox", productId: "pro.lifetime", originalPurchaseDate: original * 1000 });
    expect(life).toMatchObject({ active: true, expiresAt: original + 14 * 86400 });
    const old = await decide({ environment: "Sandbox", productId: "pro.lifetime", originalPurchaseDate: (NOW - 15 * 86400) * 1000 });
    expect(old).toMatchObject({ active: false, reason: "pro_ended" });
  });
});

describe("notifications", () => {
  const s = settings();
  const tx = (overrides: Record<string, unknown> = {}) => appleTransaction(NOW, overrides);

  it("verifies a notification and its transaction and renewal info", async () => {
    const t = tx();
    const n = await verifyNotification(
      await appleNotification(NOW, "DID_RENEW", {
        transaction: t,
        renewal: { originalTransactionId: t.originalTransactionId, environment: "Production", signedDate: NOW * 1000 },
      }),
      s,
    );
    expect(n.notificationType).toBe("DID_RENEW");
    expect(n.transaction?.originalTransactionId).toBe(t.originalTransactionId);
  });

  it("refuses another bundle id", async () => {
    expect(await failure(verifyNotification(await appleNotification(NOW, "DID_RENEW", { transaction: tx(), bundleId: "x.y" }), s))).toBe(
      "notification bundleId",
    );
    const otherTx = tx({ bundleId: "x.y" });
    expect(await failure(verifyNotification(await appleNotification(NOW, "DID_RENEW", { transaction: otherTx }), s))).toBe("bundleId");
  });

  it("refuses a Production notification with a wrong or unset appAppleId", async () => {
    expect(await failure(verifyNotification(await appleNotification(NOW, "DID_RENEW", { transaction: tx(), appAppleId: 42 }), s))).toBe(
      "notification appAppleId",
    );
    expect(await failure(verifyNotification(await appleNotification(NOW, "DID_RENEW", { transaction: tx(), appAppleId: null }), s))).toBe(
      "notification appAppleId",
    );
    const unset = settings({ appAppleId: "" });
    expect(await failure(verifyNotification(await appleNotification(NOW, "DID_RENEW", { transaction: tx() }), unset))).toBe(
      "notification appAppleId",
    );
    // Sandbox notifications carry no check of appAppleId.
    const sandbox = tx({ environment: "Sandbox" });
    expect(await failure(verifyNotification(await appleNotification(NOW, "DID_RENEW", { transaction: sandbox, appAppleId: null }), unset))).toBe("ok");
  });

  it("refuses a renewal info for another original transaction or environment", async () => {
    const t = tx();
    const other = await appleNotification(NOW, "DID_RENEW", {
      transaction: t,
      renewal: { originalTransactionId: "999", environment: "Production", signedDate: NOW * 1000 },
    });
    expect(await failure(verifyNotification(other, s))).toBe("renewal transaction");
    const env = await appleNotification(NOW, "DID_RENEW", {
      transaction: t,
      renewal: { originalTransactionId: t.originalTransactionId, environment: "Sandbox", signedDate: NOW * 1000 },
    });
    expect(await failure(verifyNotification(env, s))).toBe("renewal environment");
  });

  it("accepts EXPIRED and REFUND notifications that carry expired or revoked transactions", async () => {
    const expired = tx({ expiresDate: (NOW - 86400) * 1000 });
    expect(await failure(verifyNotification(await appleNotification(NOW, "EXPIRED", { subtype: "VOLUNTARY", transaction: expired }), s))).toBe("ok");
    const revoked = tx({ revocationDate: (NOW - 10) * 1000 });
    expect(await failure(verifyNotification(await appleNotification(NOW, "REFUND", { transaction: revoked }), s))).toBe("ok");
  });

  it("refuses a notification whose JWS doesn't verify", async () => {
    const good = await appleNotification(NOW, "DID_RENEW", { transaction: tx() });
    const [h, p] = good.split(".");
    expect(await failure(verifyNotification(`${h}.${p}.${"A".repeat(86)}`, s))).toBe("signature");
  });

  it("derives the state of each notification type (TEST_MODE without credentials)", async () => {
    const t = tx({ expiresDate: (NOW + 1000) * 1000 });
    const derive = async (type: string, subtype?: string, renewal?: Record<string, unknown>) =>
      deriveFromNotification(
        await verifyNotification(await appleNotification(NOW, type, { subtype, transaction: t, renewal }), s),
      );
    for (const type of ["SUBSCRIBED", "DID_RENEW", "REFUND_REVERSED", "RENEWAL_EXTENDED", "ONE_TIME_CHARGE"]) {
      expect(await derive(type), type).toMatchObject({ active: true, expiresAt: NOW + 1000 });
    }
    const grace = await derive("DID_FAIL_TO_RENEW", "GRACE_PERIOD", {
      originalTransactionId: t.originalTransactionId,
      environment: "Production",
      gracePeriodExpiresDate: (NOW + 5000) * 1000,
      signedDate: NOW * 1000,
    });
    expect(grace).toMatchObject({ active: true, expiresAt: NOW + 5000 });
    expect(await derive("DID_FAIL_TO_RENEW")).toMatchObject({ active: false, reason: "pro_ended" });
    expect(await derive("EXPIRED", "VOLUNTARY")).toMatchObject({ active: false, reason: "pro_ended" });
    expect(await derive("GRACE_PERIOD_EXPIRED")).toMatchObject({ active: false, reason: "pro_ended" });
    expect(await derive("REFUND")).toMatchObject({ active: false, reason: "pro_refunded" });
    expect(await derive("REVOKE")).toMatchObject({ active: false, reason: "pro_refunded" });
    expect(await derive("PRICE_INCREASE")).toBeNull();
    expect(await derive("DID_CHANGE_RENEWAL_PREF")).toBeNull();
  });
});

describe("App Store Server API", () => {
  async function p8(): Promise<{ pem: string; publicKey: CryptoKey }> {
    const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"])) as CryptoKeyPair;
    const der = new Uint8Array((await crypto.subtle.exportKey("pkcs8", pair.privateKey)) as ArrayBuffer);
    const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...der))}\n-----END PRIVATE KEY-----`;
    return { pem, publicKey: pair.publicKey };
  }

  it("signs the bearer token as ES256 with the right claims", async () => {
    const { pem, publicKey } = await p8();
    const token = await appleApiToken({ issuerId: "issuer", keyId: "KEY123", privateKey: pem }, "com.jacobusoettle.satmap", NOW);
    const [h, c, sig] = token.split(".") as [string, string, string];
    const decode = (part: string) => JSON.parse(new TextDecoder().decode(b64urlDecode(part)!));
    expect(decode(h)).toEqual({ alg: "ES256", kid: "KEY123", typ: "JWT" });
    expect(decode(c)).toEqual({ iss: "issuer", iat: NOW, exp: NOW + 1800, aud: "appstoreconnect-v1", bid: "com.jacobusoettle.satmap" });
    const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, publicKey, b64urlDecode(sig)!, new TextEncoder().encode(`${h}.${c}`));
    expect(ok).toBe(true);
  });

  it("maps the subscription status table and HTTP failures", async () => {
    const { pem } = await p8();
    const creds = { issuerId: "i", keyId: "k", privateKey: pem };
    const t = appleTransaction(NOW, { expiresDate: (NOW + 3000) * 1000 });
    const ids = { originalTransactionId: t.originalTransactionId as string, transactionId: t.transactionId as string, kind: "subscription" as const, environment: "Production" };
    const answer = async (status: number, renewal?: Record<string, unknown>, http = 200, txOverride?: Record<string, unknown>) => {
      const urls: string[] = [];
      const body = {
        data: [
          {
            subscriptionGroupIdentifier: "1",
            lastTransactions: [
              {
                originalTransactionId: ids.originalTransactionId,
                status,
                signedTransactionInfo: await signJws(txOverride ?? t),
                ...(renewal ? { signedRenewalInfo: await signJws(renewal) } : {}),
              },
            ],
          },
        ],
      };
      const result = await appleStoreCheck(ids, settings(), creds, async (url) => {
        urls.push(url);
        return new Response(JSON.stringify(body), { status: http });
      }, NOW);
      return { result, urls };
    };
    const one = await answer(1);
    expect(one.urls[0]).toBe(`https://api.storekit.apple.com/inApps/v1/subscriptions/${ids.originalTransactionId}`);
    expect(one.result).toMatchObject({ type: "state", state: { active: true, expiresAt: NOW + 3000 } });
    const grace = await answer(4, { originalTransactionId: ids.originalTransactionId, environment: "Production", gracePeriodExpiresDate: (NOW + 9000) * 1000, signedDate: NOW * 1000 });
    expect(grace.result).toMatchObject({ type: "state", state: { active: true, expiresAt: NOW + 9000 } });
    expect((await answer(2)).result).toMatchObject({ type: "state", state: { active: false, reason: "pro_ended" } });
    expect((await answer(3)).result).toMatchObject({ type: "state", state: { active: false, reason: "pro_ended" } });
    expect((await answer(5)).result).toMatchObject({ type: "state", state: { active: false, reason: "pro_refunded" } });
    expect((await answer(1, undefined, 404)).result).toEqual({ type: "invalid" });
    for (const http of [401, 429, 500, 503]) expect((await answer(1, undefined, http)).result).toEqual({ type: "unknown" });
    expect((await answer(1, undefined, 200, { ...t, bundleId: "x.y" })).result).toEqual({ type: "invalid" });
  });

  it("uses the sandbox host for Sandbox purchases and the transaction endpoint for Lifetime", async () => {
    const { pem } = await p8();
    const creds = { issuerId: "i", keyId: "k", privateKey: pem };
    const t = appleTransaction(NOW, { productId: "pro.lifetime", environment: "Sandbox" });
    const urls: string[] = [];
    const respond = (revoked: boolean) => async (url: string) => {
      urls.push(url);
      const info = await signJws(revoked ? { ...t, revocationDate: NOW * 1000 } : t);
      return new Response(JSON.stringify({ signedTransactionInfo: info }), { status: 200 });
    };
    const ids = { originalTransactionId: t.originalTransactionId as string, transactionId: t.transactionId as string, kind: "lifetime" as const, environment: "Sandbox" };
    const ok = await appleStoreCheck(ids, settings(), creds, respond(false), NOW);
    expect(urls[0]).toBe(`https://api.storekit-sandbox.apple.com/inApps/v1/transactions/${ids.transactionId}`);
    expect(ok).toMatchObject({ type: "state", state: { active: true, kind: "lifetime" } });
    const refunded = await appleStoreCheck(ids, settings(), creds, respond(true), NOW);
    expect(refunded).toMatchObject({ type: "state", state: { active: false, reason: "pro_refunded" } });
    const down = await appleStoreCheck(ids, settings(), creds, async () => { throw new Error("network"); }, NOW);
    expect(down).toEqual({ type: "unknown" });
  });
});

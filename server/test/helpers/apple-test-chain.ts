// Signs App Store–style JWS with the committed test chain (test/fixtures/make_apple_test_chain.py). The Worker
// trusts the test root through APPLE_ROOT_OVERRIDE_B64 (vitest.config.ts), honoured only under TEST_MODE=1.

import forgedInterPem from "../fixtures/apple/forged_inter.pem?raw";
import forgedLeafPem from "../fixtures/apple/forged_leaf.pem?raw";
import interPem from "../fixtures/apple/inter.pem?raw";
import interNoOidPem from "../fixtures/apple/inter_no_oid.pem?raw";
import interNotCaPem from "../fixtures/apple/inter_not_ca.pem?raw";
import leafKeyPem from "../fixtures/apple/leaf.key?raw";
import leafPem from "../fixtures/apple/leaf.pem?raw";
import leafNoOidPem from "../fixtures/apple/leaf_no_oid.pem?raw";
import leafOidInNamePem from "../fixtures/apple/leaf_oid_in_name.pem?raw";
import leafUnderNoOidPem from "../fixtures/apple/leaf_under_no_oid.pem?raw";
import leafUnderNotCaPem from "../fixtures/apple/leaf_under_not_ca.pem?raw";
import otherRootPem from "../fixtures/apple/other_root.pem?raw";
import rootPem from "../fixtures/apple/root.pem?raw";
import { b64Decode, b64urlEncode } from "../../src/lib/b64url";

function pemB64(pem: string): string {
  return pem.replace(/-----(BEGIN|END)[^-]+-----/g, "").replace(/\s+/g, "");
}

export const CERTS = {
  root: pemB64(rootPem),
  otherRoot: pemB64(otherRootPem),
  inter: pemB64(interPem),
  interNotCa: pemB64(interNotCaPem),
  interNoOid: pemB64(interNoOidPem),
  leaf: pemB64(leafPem),
  leafNoOid: pemB64(leafNoOidPem),
  leafOidInName: pemB64(leafOidInNamePem),
  leafUnderNotCa: pemB64(leafUnderNotCaPem),
  leafUnderNoOid: pemB64(leafUnderNoOidPem),
  forgedInter: pemB64(forgedInterPem),
  forgedLeaf: pemB64(forgedLeafPem),
};

export const DEFAULT_X5C = [CERTS.leaf, CERTS.inter, CERTS.root];

export function rootDer(): Uint8Array {
  return b64Decode(CERTS.root)!;
}

let leafKey: Promise<CryptoKey> | null = null;

function signingKey(): Promise<CryptoKey> {
  leafKey ??= crypto.subtle.importKey("pkcs8", b64Decode(pemB64(leafKeyPem))!, { name: "ECDSA", namedCurve: "P-256" }, false, [
    "sign",
  ]);
  return leafKey;
}

const encoder = new TextEncoder();

export async function signJws(
  payload: Record<string, unknown>,
  opts: { x5c?: string[]; alg?: string; corruptSignature?: boolean } = {},
): Promise<string> {
  const header = b64urlEncode(encoder.encode(JSON.stringify({ alg: opts.alg ?? "ES256", x5c: opts.x5c ?? DEFAULT_X5C })));
  const body = b64urlEncode(encoder.encode(JSON.stringify(payload)));
  const sig = new Uint8Array(
    await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, await signingKey(), encoder.encode(`${header}.${body}`)),
  );
  if (opts.corruptSignature) sig[10] = (sig[10]! ^ 0xff) & 0xff;
  return `${header}.${body}.${b64urlEncode(sig)}`;
}

let txCounter = 1000;

export function newTransactionId(): string {
  txCounter += 1;
  return `${2000000000000000 + Math.floor(Math.random() * 1e9) * 1000 + txCounter}`;
}

/** A transaction payload with test defaults: yearly, Production, purchased, active for a year from `now`. */
export function appleTransaction(now: number, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const id = (overrides.originalTransactionId as string | undefined) ?? newTransactionId();
  const productId = (overrides.productId as string | undefined) ?? "pro.yearly";
  const lifetime = productId === "pro.lifetime";
  const base: Record<string, unknown> = {
    transactionId: id,
    originalTransactionId: id,
    bundleId: "com.koonap.spoor",
    productId,
    purchaseDate: (now - 3600) * 1000,
    originalPurchaseDate: (now - 3600) * 1000,
    quantity: 1,
    type: lifetime ? "Non-Consumable" : "Auto-Renewable Subscription",
    inAppOwnershipType: "PURCHASED",
    signedDate: now * 1000,
    environment: "Production",
    storefront: "ZAF",
  };
  if (!lifetime) base.expiresDate = (now + 365 * 86400) * 1000;
  return { ...base, ...overrides, originalTransactionId: id, transactionId: overrides.transactionId ?? id };
}

export async function signedTransaction(now: number, overrides: Record<string, unknown> = {}, x5c?: string[]): Promise<string> {
  return signJws(appleTransaction(now, overrides), { x5c });
}

export function appleProof(signedTransactionJws: string) {
  return { platform: "apple", signedTransaction: signedTransactionJws };
}

/** An App Store Server Notification V2 signedPayload. */
export async function appleNotification(
  now: number,
  type: string,
  opts: {
    subtype?: string;
    transaction?: Record<string, unknown>;
    renewal?: Record<string, unknown>;
    environment?: string;
    bundleId?: string;
    appAppleId?: number | null;
    uuid?: string;
    signedDate?: number;
  } = {},
): Promise<string> {
  const environment = opts.environment ?? (opts.transaction?.environment as string | undefined) ?? "Production";
  const data: Record<string, unknown> = {
    bundleId: opts.bundleId ?? "com.koonap.spoor",
    environment,
  };
  if (opts.appAppleId !== null) data.appAppleId = opts.appAppleId ?? 1234567890;
  if (opts.transaction !== undefined) data.signedTransactionInfo = await signJws(opts.transaction);
  if (opts.renewal !== undefined) data.signedRenewalInfo = await signJws(opts.renewal);
  const payload: Record<string, unknown> = {
    notificationType: type,
    notificationUUID: opts.uuid ?? crypto.randomUUID(),
    data,
    version: "2.0",
    signedDate: opts.signedDate ?? now * 1000,
  };
  if (opts.subtype !== undefined) payload.subtype = opts.subtype;
  return signJws(payload);
}

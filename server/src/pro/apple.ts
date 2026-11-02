// Apple: JWS verification (the checks of Apple's app-store-server-library SignedDataVerifier, without calling
// Apple), transaction and notification checks, the active decision, and the App Store Server API (SPEC §7.6).

import { X509Certificate } from "node:crypto";
import { b64Decode, b64urlDecode, b64urlEncode } from "../lib/b64url";
import type { Config } from "../lib/config";
import { bytesEqual } from "../lib/hash";
import { TEST_PURCHASE_LIFETIME } from "../lib/limits";
import { logLine } from "../lib/log";
import { APPLE_ROOT_G3_B64 } from "./apple-root-g3";
import { parseCertificate } from "./der";
import type { HttpFetch, ProKind, StoreAnswer, StoreState } from "./types";

export class AppleVerifyError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AppleVerifyError";
  }
}

/** extnID 1.2.840.113635.100.6.11.1 (Apple leaf) and 1.2.840.113635.100.6.2.1 (Apple WWDR intermediate). */
const LEAF_OID = new Uint8Array([0x06, 0x0a, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x63, 0x64, 0x06, 0x0b, 0x01]);
const INTERMEDIATE_OID = new Uint8Array([0x06, 0x0a, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x63, 0x64, 0x06, 0x02, 0x01]);
const CLOCK_SKEW = 60;

export interface AppleSettings {
  bundleId: string;
  products: string[];
  allowTestPurchases: boolean;
  allowXcode: boolean;
  appAppleId: string;
  rootDer: Uint8Array;
}

export function appleSettings(cfg: Config): AppleSettings {
  const root = cfg.appleRootOverrideB64 !== "" ? b64Decode(cfg.appleRootOverrideB64) : null;
  return {
    bundleId: cfg.appleBundleId,
    products: cfg.proProductsApple,
    allowTestPurchases: cfg.allowTestPurchases,
    allowXcode: cfg.appleAllowXcode,
    appAppleId: cfg.appleAppAppleId,
    rootDer: root ?? b64Decode(APPLE_ROOT_G3_B64)!,
  };
}

const decoder = new TextDecoder();
const encoder = new TextEncoder();

function decodeJsonPart(part: string): Record<string, unknown> {
  const bytes = b64urlDecode(part);
  if (bytes === null) throw new AppleVerifyError("bad base64url");
  let value: unknown;
  try {
    value = JSON.parse(decoder.decode(bytes));
  } catch {
    throw new AppleVerifyError("bad JSON");
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new AppleVerifyError("not an object");
  return value as Record<string, unknown>;
}

function hasExtension(ids: Uint8Array[], oid: Uint8Array): boolean {
  return ids.some((id) => bytesEqual(id, oid));
}

/**
 * Checks a JWS's signature and certificate chain (SPEC §7.6 steps 1–6) and returns its payload. Throws
 * AppleVerifyError on any failure.
 */
export async function verifyJws(jws: string, rootDer: Uint8Array): Promise<Record<string, unknown>> {
  if (typeof jws !== "string" || jws.length > 60_000) throw new AppleVerifyError("not a JWS");
  const parts = jws.split(".");
  if (parts.length !== 3) throw new AppleVerifyError("not a JWS");
  const [headerPart, payloadPart, signaturePart] = parts as [string, string, string];

  // 1. Header
  const header = decodeJsonPart(headerPart);
  if (header.alg !== "ES256") throw new AppleVerifyError("alg");
  const x5c = header.x5c;
  if (!Array.isArray(x5c) || x5c.length !== 3 || !x5c.every((c) => typeof c === "string")) {
    throw new AppleVerifyError("x5c");
  }
  const ders = (x5c as string[]).map((c) => b64Decode(c));
  if (ders.some((d) => d === null)) throw new AppleVerifyError("x5c base64");
  const [leafDer, interDer, rootCandidate] = ders as [Uint8Array, Uint8Array, Uint8Array];

  // 2. The root must be the pinned root, byte for byte.
  if (!bytesEqual(rootCandidate, rootDer)) throw new AppleVerifyError("root");

  // 3. The chain.
  let leaf: X509Certificate;
  let inter: X509Certificate;
  let root: X509Certificate;
  try {
    leaf = new X509Certificate(leafDer);
    inter = new X509Certificate(interDer);
    root = new X509Certificate(rootCandidate);
  } catch {
    throw new AppleVerifyError("certificate");
  }
  if (inter.ca !== true) throw new AppleVerifyError("intermediate not a CA");
  if (!(inter.checkIssued(root) && inter.verify(root.publicKey))) throw new AppleVerifyError("intermediate");
  if (!(leaf.checkIssued(inter) && leaf.verify(inter.publicKey))) throw new AppleVerifyError("leaf");

  // 4. The Apple extensions, found by walking tbsCertificate.extensions.
  let leafFields: ReturnType<typeof parseCertificate>;
  let interFields: ReturnType<typeof parseCertificate>;
  let rootFields: ReturnType<typeof parseCertificate>;
  try {
    leafFields = parseCertificate(leafDer);
    interFields = parseCertificate(interDer);
    rootFields = parseCertificate(rootCandidate);
  } catch {
    throw new AppleVerifyError("certificate DER");
  }
  if (!hasExtension(leafFields.extensionIds, LEAF_OID)) throw new AppleVerifyError("leaf OID");
  if (!hasExtension(interFields.extensionIds, INTERMEDIATE_OID)) throw new AppleVerifyError("intermediate OID");

  // 5. Valid at the payload's signedDate.
  const payload = decodeJsonPart(payloadPart);
  const signedDate = payload.signedDate;
  if (typeof signedDate !== "number" || !Number.isFinite(signedDate)) throw new AppleVerifyError("signedDate");
  const at = signedDate / 1000;
  for (const fields of [leafFields, interFields, rootFields]) {
    if (at < fields.notBefore - CLOCK_SKEW || at > fields.notAfter + CLOCK_SKEW) {
      throw new AppleVerifyError("certificate not valid at signedDate");
    }
  }

  // 6. The ES256 signature (raw r‖s) over header.payload.
  const signature = b64urlDecode(signaturePart);
  if (signature === null || signature.byteLength !== 64) throw new AppleVerifyError("signature");
  let key: CryptoKey;
  try {
    key = await crypto.subtle.importKey("spki", leafFields.spki, { name: "ECDSA", namedCurve: "P-256" }, false, [
      "verify",
    ]);
  } catch {
    throw new AppleVerifyError("leaf key");
  }
  const ok = await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    signature,
    encoder.encode(`${headerPart}.${payloadPart}`),
  );
  if (!ok) throw new AppleVerifyError("signature");
  return payload;
}

export type AppleEnvironment = "Production" | "Sandbox" | "Xcode";

export interface AppleTransaction {
  originalTransactionId: string;
  transactionId: string;
  productId: string;
  environment: AppleEnvironment;
  kind: ProKind;
  /** Milliseconds, as Apple sends them. */
  signedDate: number;
  expiresDate: number | null;
  revocationDate: number | null;
  originalPurchaseDate: number;
}

/** Product ids that name Lifetime (non-consumable); the others are auto-renewable subscriptions (§10.1). */
export function appleKind(productId: string): ProKind {
  return productId.endsWith("lifetime") ? "lifetime" : "subscription";
}

const TRANSACTION_ID = /^[0-9A-Za-z._-]{1,64}$/;

function environmentAllowed(env: unknown, s: AppleSettings): env is AppleEnvironment {
  return env === "Production" || (env === "Sandbox" && s.allowTestPurchases) || (env === "Xcode" && s.allowXcode);
}

function optionalMs(value: unknown, what: string): number | null {
  if (value === undefined || value === null) return null;
  if (typeof value !== "number" || !Number.isFinite(value)) throw new AppleVerifyError(what);
  return value;
}

/** The transaction payload checks (bundle, product, environment, ownership, type). Throws AppleVerifyError. */
export function checkTransaction(payload: Record<string, unknown>, s: AppleSettings): AppleTransaction {
  if (payload.bundleId !== s.bundleId) throw new AppleVerifyError("bundleId");
  const productId = payload.productId;
  if (typeof productId !== "string" || !s.products.includes(productId)) throw new AppleVerifyError("productId");
  if (!environmentAllowed(payload.environment, s)) throw new AppleVerifyError("environment");
  if (payload.inAppOwnershipType !== "PURCHASED") throw new AppleVerifyError("inAppOwnershipType");
  const kind = appleKind(productId);
  const expectedType = kind === "lifetime" ? "Non-Consumable" : "Auto-Renewable Subscription";
  if (payload.type !== expectedType) throw new AppleVerifyError("type");
  const originalTransactionId = payload.originalTransactionId;
  const transactionId = payload.transactionId;
  if (typeof originalTransactionId !== "string" || !TRANSACTION_ID.test(originalTransactionId)) {
    throw new AppleVerifyError("originalTransactionId");
  }
  if (typeof transactionId !== "string" || !TRANSACTION_ID.test(transactionId)) {
    throw new AppleVerifyError("transactionId");
  }
  const signedDate = optionalMs(payload.signedDate, "signedDate");
  const originalPurchaseDate = optionalMs(payload.originalPurchaseDate, "originalPurchaseDate");
  if (signedDate === null || originalPurchaseDate === null) throw new AppleVerifyError("dates");
  const expiresDate = optionalMs(payload.expiresDate, "expiresDate");
  if (kind === "subscription" && expiresDate === null) throw new AppleVerifyError("expiresDate");
  return {
    originalTransactionId,
    transactionId,
    productId,
    environment: payload.environment,
    kind,
    signedDate,
    expiresDate,
    revocationDate: optionalMs(payload.revocationDate, "revocationDate"),
    originalPurchaseDate,
  };
}

/** The 14-day end of a Sandbox or Xcode purchase (SD20), or null for Production. Seconds. */
export function testCap(tx: AppleTransaction): number | null {
  return tx.environment === "Production" ? null : Math.floor(tx.originalPurchaseDate / 1000) + TEST_PURCHASE_LIFETIME;
}

function capped(expiresAt: number | null, cap: number | null): number | null {
  if (cap === null) return expiresAt;
  return expiresAt === null ? cap : Math.min(expiresAt, cap);
}

function stateFrom(
  tx: AppleTransaction,
  active: boolean,
  reason: StoreState["reason"],
  expiresAt: number | null,
  storeState: string,
): StoreState {
  return {
    active,
    reason: active ? null : reason,
    expiresAt: capped(expiresAt, testCap(tx)),
    productId: tx.productId,
    kind: tx.kind,
    environment: tx.environment,
    storeState,
    transactionId: tx.transactionId,
  };
}

/**
 * The active decision for a transaction (E1, E5, E17 when the proof is used as is). Separate from checkTransaction
 * because notifications must accept expired and revoked transactions.
 */
export function decideTransaction(tx: AppleTransaction, now: number): StoreState {
  const expiresAt = tx.expiresDate === null ? null : Math.floor(tx.expiresDate / 1000);
  const cap = testCap(tx);
  let active = tx.revocationDate === null;
  if (tx.kind === "subscription") active &&= tx.expiresDate !== null && tx.expiresDate > now * 1000;
  if (cap !== null) active &&= cap > now;
  const reason = tx.revocationDate !== null ? "pro_refunded" : "pro_ended";
  return stateFrom(tx, active, reason, tx.kind === "lifetime" ? null : expiresAt, "proof");
}

export interface AppleRenewalInfo {
  originalTransactionId: string;
  environment: string;
  gracePeriodExpiresDate: number | null;
}

export async function verifyRenewalInfo(jws: string, tx: AppleTransaction, s: AppleSettings): Promise<AppleRenewalInfo> {
  const payload = await verifyJws(jws, s.rootDer);
  if (payload.environment !== tx.environment) throw new AppleVerifyError("renewal environment");
  if (payload.originalTransactionId !== tx.originalTransactionId) throw new AppleVerifyError("renewal transaction");
  return {
    originalTransactionId: tx.originalTransactionId,
    environment: tx.environment,
    gracePeriodExpiresDate: optionalMs(payload.gracePeriodExpiresDate, "gracePeriodExpiresDate"),
  };
}

export interface AppleNotification {
  notificationType: string;
  subtype: string | null;
  notificationUUID: string;
  /** Milliseconds. */
  signedDate: number;
  environment: string;
  transaction: AppleTransaction | null;
  renewal: AppleRenewalInfo | null;
}

/** App Store Server Notifications V2: the outer signedPayload and everything inside (SPEC §7.6). */
export async function verifyNotification(signedPayload: string, s: AppleSettings): Promise<AppleNotification> {
  const payload = await verifyJws(signedPayload, s.rootDer);
  const type = payload.notificationType;
  const uuid = payload.notificationUUID;
  if (typeof type !== "string" || typeof uuid !== "string" || uuid.length === 0 || uuid.length > 64) {
    throw new AppleVerifyError("notification fields");
  }
  const subtype = typeof payload.subtype === "string" ? payload.subtype : null;
  const data = (payload.data ?? payload.summary) as Record<string, unknown> | undefined;
  if (typeof data !== "object" || data === null) throw new AppleVerifyError("notification data");
  if (data.bundleId !== s.bundleId) throw new AppleVerifyError("notification bundleId");
  if (!environmentAllowed(data.environment, s)) throw new AppleVerifyError("notification environment");
  if (data.environment === "Production" && (s.appAppleId === "" || String(data.appAppleId) !== s.appAppleId)) {
    throw new AppleVerifyError("notification appAppleId");
  }
  let transaction: AppleTransaction | null = null;
  let renewal: AppleRenewalInfo | null = null;
  if (typeof data.signedTransactionInfo === "string") {
    transaction = checkTransaction(await verifyJws(data.signedTransactionInfo, s.rootDer), s);
    if (transaction.environment !== data.environment) throw new AppleVerifyError("transaction environment");
    if (typeof data.signedRenewalInfo === "string") {
      renewal = await verifyRenewalInfo(data.signedRenewalInfo, transaction, s);
    }
  } else if (data.signedTransactionInfo !== undefined) {
    throw new AppleVerifyError("signedTransactionInfo");
  }
  return {
    notificationType: type,
    subtype,
    notificationUUID: uuid,
    signedDate: payload.signedDate as number,
    environment: data.environment as string,
    transaction,
    renewal,
  };
}

/**
 * The state a notification implies, used only under TEST_MODE=1 without App Store Server API credentials (SPEC §7.6
 * table). Null means "no change".
 */
export function deriveFromNotification(n: AppleNotification): StoreState | null {
  const tx = n.transaction;
  if (tx === null) return null;
  const expiresAt = tx.kind === "lifetime" || tx.expiresDate === null ? null : Math.floor(tx.expiresDate / 1000);
  switch (n.notificationType) {
    case "SUBSCRIBED":
    case "DID_RENEW":
    case "REFUND_REVERSED":
    case "RENEWAL_EXTENDED":
    case "ONE_TIME_CHARGE":
      return stateFrom(tx, true, null, expiresAt, n.notificationType);
    case "DID_FAIL_TO_RENEW":
      if (n.subtype === "GRACE_PERIOD") {
        const grace = n.renewal?.gracePeriodExpiresDate ?? null;
        return stateFrom(tx, true, null, grace === null ? expiresAt : Math.floor(grace / 1000), n.notificationType);
      }
      return n.subtype === null ? stateFrom(tx, false, "pro_ended", expiresAt, n.notificationType) : null;
    case "EXPIRED":
    case "GRACE_PERIOD_EXPIRED":
      return stateFrom(tx, false, "pro_ended", expiresAt, n.notificationType);
    case "REFUND":
    case "REVOKE":
      return stateFrom(tx, false, "pro_refunded", expiresAt, n.notificationType);
    default:
      return null;
  }
}

// App Store Server API

export interface AppleCredentials {
  issuerId: string;
  keyId: string;
  privateKey: string;
}

export function pemBody(pem: string): Uint8Array | null {
  const body = pem.replace(/-----(BEGIN|END)[^-]+-----/g, "").replace(/\s+/g, "");
  return b64Decode(body);
}

const keyCache = new Map<string, CryptoKey>();

async function signingKey(pem: string): Promise<CryptoKey> {
  const cached = keyCache.get(pem);
  if (cached) return cached;
  const der = pemBody(pem);
  if (der === null) throw new Error("APPLE_PRIVATE_KEY is not a PEM key");
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  keyCache.set(pem, key);
  return key;
}

function jsonPart(value: unknown): string {
  return b64urlEncode(encoder.encode(JSON.stringify(value)));
}

/** The App Store Server API bearer token: ES256 JWT signed with the In-App Purchase key (.p8). */
export async function appleApiToken(creds: AppleCredentials, bundleId: string, now: number): Promise<string> {
  const head = jsonPart({ alg: "ES256", kid: creds.keyId, typ: "JWT" });
  const claims = jsonPart({ iss: creds.issuerId, iat: now, exp: now + 1800, aud: "appstoreconnect-v1", bid: bundleId });
  const key = await signingKey(creds.privateKey);
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, encoder.encode(`${head}.${claims}`));
  return `${head}.${claims}.${b64urlEncode(new Uint8Array(sig))}`;
}

export function appleApiHost(environment: string): string {
  return environment === "Sandbox" ? "https://api.storekit-sandbox.apple.com" : "https://api.storekit.apple.com";
}

async function apiGet(
  path: string,
  op: string,
  environment: string,
  creds: AppleCredentials,
  s: AppleSettings,
  httpFetch: HttpFetch,
  now: number,
): Promise<{ status: number; body: Record<string, unknown> | null }> {
  let response: Response;
  try {
    const token = await appleApiToken(creds, s.bundleId, now);
    response = await httpFetch(`${appleApiHost(environment)}${path}`, {
      headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
    });
  } catch {
    logLine({ t: "store", platform: "apple", op, status: 0 });
    return { status: 0, body: null };
  }
  logLine({ t: "store", platform: "apple", op, status: response.status });
  if (response.status !== 200) return { status: response.status, body: null };
  try {
    const body = (await response.json()) as unknown;
    return { status: 200, body: typeof body === "object" && body !== null ? (body as Record<string, unknown>) : null };
  } catch {
    return { status: 200, body: null };
  }
}

/**
 * Verifies a signed item from an API answer. A signature or chain failure means the answer can't be trusted now
 * ("unknown", retried later); a verified transaction that fails the payload checks is not a Pro purchase of this app.
 */
async function verifiedApiItem(
  transactionJws: string,
  renewalJws: string | undefined,
  s: AppleSettings,
): Promise<{ type: "ok"; tx: AppleTransaction; renewal: AppleRenewalInfo | null } | { type: "invalid" | "unknown" }> {
  let payload: Record<string, unknown>;
  try {
    payload = await verifyJws(transactionJws, s.rootDer);
  } catch {
    return { type: "unknown" };
  }
  let tx: AppleTransaction;
  try {
    tx = checkTransaction(payload, s);
  } catch {
    return { type: "invalid" };
  }
  let renewal: AppleRenewalInfo | null = null;
  if (renewalJws !== undefined) {
    try {
      renewal = await verifyRenewalInfo(renewalJws, tx, s);
    } catch {
      return { type: "unknown" };
    }
  }
  return { type: "ok", tx, renewal };
}

function failureAnswer(status: number): StoreAnswer {
  return status === 400 || status === 404 ? { type: "invalid" } : { type: "unknown" };
}

/**
 * Asks the App Store Server API about a purchase and decides from its signed answer: subscription status for
 * subscriptions, the transaction for Lifetime. Each signed item goes through verifyJws and checkTransaction.
 */
export async function appleStoreCheck(
  ids: { originalTransactionId: string; transactionId: string; kind: ProKind; environment: string },
  s: AppleSettings,
  creds: AppleCredentials,
  httpFetch: HttpFetch,
  now: number,
): Promise<StoreAnswer> {
  if (ids.kind === "lifetime") {
    const path = `/inApps/v1/transactions/${encodeURIComponent(ids.transactionId)}`;
    const { status, body } = await apiGet(path, "transaction", ids.environment, creds, s, httpFetch, now);
    if (status !== 200) return failureAnswer(status);
    if (body === null || typeof body.signedTransactionInfo !== "string") return { type: "unknown" };
    const signed = await verifiedApiItem(body.signedTransactionInfo, undefined, s);
    if (signed.type !== "ok") return signed;
    const tx = signed.tx;
    if (tx.originalTransactionId !== ids.originalTransactionId) return { type: "invalid" };
    const cap = testCap(tx);
    const active = tx.revocationDate === null && (cap === null || cap > now);
    return {
      type: "state",
      state: stateFrom(tx, active, tx.revocationDate !== null ? "pro_refunded" : "pro_ended", null, "transaction"),
    };
  }

  const path = `/inApps/v1/subscriptions/${encodeURIComponent(ids.originalTransactionId)}`;
  const { status, body } = await apiGet(path, "status", ids.environment, creds, s, httpFetch, now);
  if (status !== 200) return failureAnswer(status);
  const groups = body?.data;
  if (!Array.isArray(groups)) return { type: "unknown" };
  let item: Record<string, unknown> | undefined;
  for (const group of groups as Array<Record<string, unknown>>) {
    const last = group?.lastTransactions;
    if (!Array.isArray(last)) continue;
    item ??= (last as Array<Record<string, unknown>>).find((t) => t?.originalTransactionId === ids.originalTransactionId);
  }
  if (item === undefined || typeof item.signedTransactionInfo !== "string" || typeof item.status !== "number") {
    return { type: "invalid" };
  }
  const signed = await verifiedApiItem(
    item.signedTransactionInfo,
    typeof item.signedRenewalInfo === "string" ? item.signedRenewalInfo : undefined,
    s,
  );
  if (signed.type !== "ok") return signed;
  const { tx, renewal } = signed;
  const expiresAt = tx.expiresDate === null ? null : Math.floor(tx.expiresDate / 1000);
  const cap = testCap(tx);
  const withinCap = cap === null || cap > now;
  const storeState = `status ${item.status}`;
  switch (item.status) {
    case 1:
      return { type: "state", state: stateFrom(tx, withinCap, "pro_ended", expiresAt, storeState) };
    case 4: {
      const grace = renewal?.gracePeriodExpiresDate;
      const until = grace === null || grace === undefined ? expiresAt : Math.floor(grace / 1000);
      return { type: "state", state: stateFrom(tx, withinCap, "pro_ended", until, storeState) };
    }
    case 5:
      return { type: "state", state: stateFrom(tx, false, "pro_refunded", expiresAt, storeState) };
    default: // 2 expired, 3 billing retry, and any status added later
      return { type: "state", state: stateFrom(tx, false, "pro_ended", expiresAt, storeState) };
  }
}

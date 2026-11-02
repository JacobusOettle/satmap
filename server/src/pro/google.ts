// Google Play: service-account access token, Play Developer API purchase checks (subscriptionsv2, productsv2), the
// decision tables, and real-time developer notification decoding (SPEC §7.6).

import { b64Decode, b64urlEncode } from "../lib/b64url";
import type { Config } from "../lib/config";
import { TEST_PURCHASE_LIFETIME } from "../lib/limits";
import { logLine } from "../lib/log";
import { pemBody } from "./apple";
import type { HttpFetch, StoreAnswer, StoreState } from "./types";

export const PURCHASE_TOKEN = /^[A-Za-z0-9._-]{20,1024}$/;
export const LIFETIME_PRODUCT = "pro_lifetime";
const SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";

export function isPurchaseToken(value: unknown): value is string {
  return typeof value === "string" && PURCHASE_TOKEN.test(value);
}

export function googleKind(productId: string): "subscription" | "lifetime" {
  return productId === LIFETIME_PRODUCT ? "lifetime" : "subscription";
}

export interface GoogleSettings {
  packageName: string;
  allowTestPurchases: boolean;
}

export function googleSettings(cfg: Config): GoogleSettings {
  return { packageName: cfg.androidPackage, allowTestPurchases: cfg.allowTestPurchases };
}

export interface ServiceAccount {
  clientEmail: string;
  privateKey: string;
  tokenUri: string;
}

export function parseServiceAccount(json: string): ServiceAccount | null {
  if (json === "") return null;
  try {
    const value = JSON.parse(json) as Record<string, unknown>;
    if (typeof value.client_email !== "string" || typeof value.private_key !== "string") return null;
    return {
      clientEmail: value.client_email,
      privateKey: value.private_key,
      tokenUri: typeof value.token_uri === "string" ? value.token_uri : TOKEN_URL,
    };
  } catch {
    return null;
  }
}

const encoder = new TextEncoder();

function jsonPart(value: unknown): string {
  return b64urlEncode(encoder.encode(JSON.stringify(value)));
}

/** Access tokens cached in module memory until 60 s before they expire. */
const tokenCache = new Map<string, { token: string; until: number }>();

export function clearGoogleTokenCache(): void {
  tokenCache.clear();
}

/** An OAuth access token for the Play Developer API, or null when Google could not be asked. */
export async function googleAccessToken(sa: ServiceAccount, httpFetch: HttpFetch, now: number): Promise<string | null> {
  const cached = tokenCache.get(sa.clientEmail);
  if (cached && cached.until > now) return cached.token;
  let response: Response;
  try {
    const der = pemBody(sa.privateKey);
    if (der === null) throw new Error("bad key");
    const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, [
      "sign",
    ]);
    const unsigned = `${jsonPart({ alg: "RS256", typ: "JWT" })}.${jsonPart({
      iss: sa.clientEmail,
      scope: SCOPE,
      aud: TOKEN_URL,
      iat: now,
      exp: now + 3600,
    })}`;
    const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(unsigned));
    const assertion = `${unsigned}.${b64urlEncode(new Uint8Array(sig))}`;
    response = await httpFetch(sa.tokenUri, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${assertion}`,
    });
  } catch {
    logLine({ t: "store", platform: "google", op: "token", status: 0 });
    return null;
  }
  logLine({ t: "store", platform: "google", op: "token", status: response.status });
  if (response.status !== 200) return null;
  try {
    const body = (await response.json()) as { access_token?: unknown; expires_in?: unknown };
    if (typeof body.access_token !== "string") return null;
    const lifetime = typeof body.expires_in === "number" ? body.expires_in : 3600;
    tokenCache.set(sa.clientEmail, { token: body.access_token, until: now + lifetime - 60 });
    return body.access_token;
  } catch {
    return null;
  }
}

function seconds(rfc3339: unknown): number | null {
  if (typeof rfc3339 !== "string") return null;
  const ms = Date.parse(rfc3339);
  return Number.isFinite(ms) ? Math.floor(ms / 1000) : null;
}

const ACTIVE_STATES = new Set(["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"]);

/** The decision table for a `purchases.subscriptionsv2` answer (productId `pro`). */
export function decideSubscription(
  body: Record<string, unknown>,
  productId: string,
  s: GoogleSettings,
  now: number,
): StoreAnswer {
  const items = body.lineItems;
  if (!Array.isArray(items) || items.length === 0) return { type: "invalid" };
  let expiry: number | null = null;
  for (const item of items as Array<Record<string, unknown>>) {
    if (item?.productId !== productId) return { type: "invalid" };
    const t = seconds(item.expiryTime);
    if (t !== null && (expiry === null || t > expiry)) expiry = t;
  }
  const test = body.testPurchase !== undefined && body.testPurchase !== null;
  if (test && !s.allowTestPurchases) return { type: "invalid" };
  const start = seconds(body.startTime);
  const cap = test ? (start ?? now) + TEST_PURCHASE_LIFETIME : null;
  const stateText = typeof body.subscriptionState === "string" ? body.subscriptionState : "SUBSCRIPTION_STATE_UNSPECIFIED";
  let active =
    ACTIVE_STATES.has(stateText) ||
    (stateText === "SUBSCRIPTION_STATE_CANCELED" && expiry !== null && expiry > now);
  if (cap !== null) active &&= cap > now;
  const expiresAt = cap === null ? expiry : expiry === null ? cap : Math.min(expiry, cap);
  const state: StoreState = {
    active,
    reason: active ? null : "pro_ended",
    expiresAt,
    productId,
    kind: "subscription",
    environment: test ? "Test" : "Production",
    storeState: stateText,
    inGrace: stateText === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
  };
  if (typeof body.linkedPurchaseToken === "string" && PURCHASE_TOKEN.test(body.linkedPurchaseToken)) {
    state.linkedPurchaseToken = body.linkedPurchaseToken;
  }
  return { type: "state", state };
}

/** The decision table for a `purchases.productsv2` answer (productId `pro_lifetime`). */
export function decideProduct(body: Record<string, unknown>, productId: string, s: GoogleSettings, now: number): StoreAnswer {
  const items = body.productLineItem;
  if (!Array.isArray(items) || !(items as Array<Record<string, unknown>>).some((i) => i?.productId === productId)) {
    return { type: "invalid" };
  }
  const test = body.testPurchaseContext !== undefined && body.testPurchaseContext !== null;
  if (test && !s.allowTestPurchases) return { type: "invalid" };
  const context = body.purchaseStateContext as Record<string, unknown> | undefined;
  const purchaseState = typeof context?.purchaseState === "string" ? context.purchaseState : "UNSPECIFIED";
  const cap = test ? (seconds(body.purchaseCompletionTime) ?? now) + TEST_PURCHASE_LIFETIME : null;
  let active = purchaseState === "PURCHASED";
  if (cap !== null) active &&= cap > now;
  return {
    type: "state",
    state: {
      active,
      reason: active ? null : purchaseState === "CANCELLED" ? "pro_refunded" : "pro_ended",
      expiresAt: cap,
      productId,
      kind: "lifetime",
      environment: test ? "Test" : "Production",
      storeState: purchaseState,
    },
  };
}

/** Asks the Play Developer API about a purchase token and decides. */
export async function googleStoreCheck(
  purchaseToken: string,
  productId: string,
  s: GoogleSettings,
  sa: ServiceAccount,
  httpFetch: HttpFetch,
  now: number,
): Promise<StoreAnswer> {
  const accessToken = await googleAccessToken(sa, httpFetch, now);
  if (accessToken === null) return { type: "unknown" };
  const lifetime = googleKind(productId) === "lifetime";
  const op = lifetime ? "productsv2" : "subscriptionsv2";
  const url = `${API}/${encodeURIComponent(s.packageName)}/purchases/${op}/tokens/${encodeURIComponent(purchaseToken)}`;
  let response: Response;
  try {
    response = await httpFetch(url, { headers: { Authorization: `Bearer ${accessToken}`, Accept: "application/json" } });
  } catch {
    logLine({ t: "store", platform: "google", op, status: 0 });
    return { type: "unknown" };
  }
  logLine({ t: "store", platform: "google", op, status: response.status });
  if (response.status === 400 || response.status === 404 || response.status === 410) return { type: "invalid" };
  if (response.status !== 200) return { type: "unknown" };
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    return { type: "unknown" };
  }
  if (typeof body !== "object" || body === null) return { type: "unknown" };
  const record = body as Record<string, unknown>;
  return lifetime ? decideProduct(record, productId, s, now) : decideSubscription(record, productId, s, now);
}

/** The state ALLOW_UNVERIFIED_PRO gives any well-formed token (TEST_MODE=1 only, for the local check §12.6). */
export function unverifiedState(now: number): StoreState {
  return {
    active: true,
    reason: null,
    expiresAt: now + 31_536_000,
    productId: "pro",
    kind: "subscription",
    environment: "Unverified",
    storeState: "ALLOW_UNVERIFIED_PRO",
  };
}

export type RtdnKind = "subscription" | "oneTime" | "voided" | "test" | "other";

export interface Rtdn {
  messageId: string;
  kind: RtdnKind;
  purchaseToken: string | null;
  /** Product id for one-time notifications, "pro" for subscriptions. */
  productId: string | null;
  eventTimeMillis: number | null;
}

/** Decodes a Pub/Sub push body. Null when it isn't a Pub/Sub message. */
export function decodeRtdn(body: unknown): Rtdn | null {
  if (typeof body !== "object" || body === null) return null;
  const message = (body as Record<string, unknown>).message as Record<string, unknown> | undefined;
  if (typeof message !== "object" || message === null) return null;
  const messageId = message.messageId ?? message.message_id;
  if (typeof messageId !== "string" || messageId === "" || messageId.length > 200) return null;
  const empty: Rtdn = { messageId, kind: "other", purchaseToken: null, productId: null, eventTimeMillis: null };
  if (typeof message.data !== "string") return empty;
  const bytes = b64Decode(message.data);
  if (bytes === null) return empty;
  let data: Record<string, unknown>;
  try {
    data = JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;
  } catch {
    return empty;
  }
  if (typeof data !== "object" || data === null) return empty;
  const eventTime = Number(data.eventTimeMillis);
  const out: Rtdn = { ...empty, eventTimeMillis: Number.isFinite(eventTime) ? eventTime : null };
  const sub = data.subscriptionNotification as Record<string, unknown> | undefined;
  const oneTime = data.oneTimeProductNotification as Record<string, unknown> | undefined;
  const voided = data.voidedPurchaseNotification as Record<string, unknown> | undefined;
  if (sub && typeof sub === "object") {
    out.kind = "subscription";
    out.purchaseToken = isPurchaseToken(sub.purchaseToken) ? sub.purchaseToken : null;
    out.productId = "pro";
  } else if (oneTime && typeof oneTime === "object") {
    out.kind = "oneTime";
    out.purchaseToken = isPurchaseToken(oneTime.purchaseToken) ? oneTime.purchaseToken : null;
    out.productId = typeof oneTime.sku === "string" ? oneTime.sku : null;
  } else if (voided && typeof voided === "object") {
    out.kind = "voided";
    out.purchaseToken = isPurchaseToken(voided.purchaseToken) ? voided.purchaseToken : null;
    // productType 1 = subscription, 2 = one-time product.
    out.productId = voided.productType === 2 ? LIFETIME_PRODUCT : "pro";
  } else if (data.testNotification !== undefined) {
    out.kind = "test";
  }
  return out;
}

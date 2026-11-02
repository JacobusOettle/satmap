// Types shared by the Pro code (SPEC §7.6).

export type Platform = "apple" | "google";
export type ProKind = "subscription" | "lifetime";
export type ProReason = "pro_ended" | "pro_refunded";
export type PausedReason = ProReason | "pro_unverified";

/** What a store (API answer, verified proof or notification) says about a purchase. */
export interface StoreState {
  active: boolean;
  /** Why it is not active; null when active. */
  reason: ProReason | null;
  /** Unix seconds; null for Lifetime. Capped at 14 days after the original purchase for test purchases (SD20). */
  expiresAt: number | null;
  productId: string;
  kind: ProKind;
  /** Apple `environment`, or "Production" / "Test" for Google. */
  environment: string;
  /** The store's own state text, kept for the developer (Apple status number or Google state). */
  storeState: string;
  /** Google: in the billing grace period, re-checked every 6 h. */
  inGrace?: boolean;
  transactionId?: string;
  /** Google: the purchase token this one replaced. */
  linkedPurchaseToken?: string;
}

export type StoreAnswer =
  | { type: "state"; state: StoreState }
  /** The store doesn't know the purchase, or it is not a Pro purchase of this app (402 pro_invalid). */
  | { type: "invalid" }
  /** The store could not be asked (503 store_unavailable). */
  | { type: "unknown" };

/** A purchase's state as the groups see it (`proChanged`, SPEC §7.6). */
export interface ProState {
  name: string;
  platform: Platform;
  productId: string;
  active: boolean;
  reason: ProReason | null;
  expiresAt: number | null;
}

export type HttpFetch = (input: string, init?: RequestInit) => Promise<Response>;

export function isProReason(value: unknown): value is PausedReason {
  return value === "pro_ended" || value === "pro_refunded" || value === "pro_unverified";
}

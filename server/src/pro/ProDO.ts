// ProDO: one Durable Object per purchase (SPEC §7.6), named `apple:{originalTransactionId}` or
// `google:{b64url(SHA-256(purchaseToken))}`. It keeps the entitlement's newest known state, the groups attached to
// the purchase (at most MAX_GROUPS_PER_PURCHASE, SD9), and tells those groups when the state changes.
//
// Nothing is written unless a proof verifies, so a junk token leaves no stored object. A ProDO with no groups
// deletes itself 30 days later.

import { DurableObject } from "cloudflare:workers";
import type { AppEnv } from "../env";
import { b64urlEncode } from "../lib/b64url";
import { readConfig, type Config } from "../lib/config";
import { sha256 } from "../lib/hash";
import { Jobs, JOBS_DDL, runDueJobs } from "../lib/jobs";
import { DAY, HOUR } from "../lib/limits";
import { errorText, logLine } from "../lib/log";
import { systemNow } from "../lib/time";
import {
  appleSettings,
  appleStoreCheck,
  decideTransaction,
  deriveFromNotification,
  type AppleNotification,
  type AppleTransaction,
} from "./apple";
import { googleSettings, googleStoreCheck, parseServiceAccount, unverifiedState, type Rtdn } from "./google";
import type { HttpFetch, Platform, ProKind, ProReason, ProState, StoreAnswer, StoreState } from "./types";

/** A proof after its signature and payload checks (Apple) or shape check (Google). */
export type ProofInput =
  | { platform: "apple"; tx: AppleTransaction }
  | { platform: "google"; productId: string; purchaseToken: string };

export type AttachError = "pro_invalid" | "pro_inactive" | "store_unavailable" | "too_many_groups";
export type AttachResult = { ok: true; state: ProState } | { ok: false; error: AttachError };
export type CheckResult = { unknown: true } | { unknown: false; state: ProState };

const SCHEMA = `
CREATE TABLE IF NOT EXISTS ent (
  one INTEGER PRIMARY KEY CHECK (one = 1),
  name TEXT NOT NULL,
  platform TEXT NOT NULL CHECK (platform IN ('apple','google')),
  product_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('subscription','lifetime')),
  environment TEXT NOT NULL,
  active INTEGER NOT NULL,
  expires_at INTEGER,
  grace_until INTEGER,
  revoked_at INTEGER,
  refunded INTEGER NOT NULL DEFAULT 0,
  state_at INTEGER NOT NULL,
  checked_at INTEGER,
  store_state TEXT,
  transaction_id TEXT,
  purchase_token TEXT
);
CREATE TABLE IF NOT EXISTS groups (group_id TEXT PRIMARY KEY, attached_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS seen (notification_id TEXT PRIMARY KEY, at INTEGER NOT NULL);
${JOBS_DDL}
`;

type EntRow = {
  name: string;
  platform: Platform;
  product_id: string;
  kind: ProKind;
  environment: string;
  active: number;
  expires_at: number | null;
  grace_until: number | null;
  refunded: number;
  state_at: number;
  transaction_id: string | null;
  purchase_token: string | null;
}

type Source = "api" | "proof" | "notification";

const SELF_DELETE_AFTER = 30 * DAY;
const SEEN_RETENTION = 30 * DAY;
const REFUSAL_MEMORY_MS = 10 * 60 * 1000;
const GRACE_RECHECK = 6 * HOUR;

export async function googleProName(purchaseToken: string): Promise<string> {
  return `google:${b64urlEncode(await sha256(purchaseToken))}`;
}

export function appleProName(originalTransactionId: string): string {
  return `apple:${originalTransactionId}`;
}

export class ProDO extends DurableObject<AppEnv> {
  /** Tests set these through runInDurableObject (SPEC §7.3). */
  clockOverride: number | null = null;
  httpFetch: HttpFetch = (input, init) => fetch(input, init);
  envOverride: Record<string, string> | null = null;

  /** Google's negative cache: a refusal answered again from memory for 10 minutes (never stored). */
  private refusal: { error: "pro_invalid" | "pro_inactive"; until: number } | null = null;
  private hasSchema: boolean;

  constructor(ctx: DurableObjectState, env: AppEnv) {
    super(ctx, env);
    this.hasSchema =
      ctx.storage.sql.exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'ent'").toArray().length > 0;
  }

  get conf(): Config {
    return readConfig({ ...this.env, ...this.envOverride });
  }

  now(): number {
    return this.clockOverride ?? systemNow();
  }

  private nowMs(): number {
    return this.clockOverride !== null ? this.clockOverride * 1000 : Date.now();
  }

  private get sql(): SqlStorage {
    return this.ctx.storage.sql;
  }

  private get jobs(): Jobs {
    return new Jobs(this.sql);
  }

  private ensureSchema(): void {
    if (this.hasSchema) return;
    this.sql.exec(SCHEMA);
    this.hasSchema = true;
  }

  private readEnt(): EntRow | null {
    if (!this.hasSchema) return null;
    return this.sql.exec<EntRow>("SELECT * FROM ent WHERE one = 1").toArray()[0] ?? null;
  }

  private groupIds(): string[] {
    if (!this.hasSchema) return [];
    return this.sql.exec<{ group_id: string }>("SELECT group_id FROM groups ORDER BY attached_at").toArray().map((r) => r.group_id);
  }

  private stateOf(ent: EntRow): ProState {
    const active = ent.active === 1;
    const reason: ProReason | null = active ? null : ent.refunded === 1 ? "pro_refunded" : "pro_ended";
    return { name: ent.name, platform: ent.platform, productId: ent.product_id, active, reason, expiresAt: ent.expires_at };
  }

  // RPC

  /**
   * Applies a verified proof (after asking the store, SPEC §7.6 ordering rule), checks the group count (SD9) and adds
   * the group. The caller answers 402/409/503 when `ok` is false.
   */
  async attach(name: string, groupId: string, proof: ProofInput): Promise<AttachResult> {
    const now = this.now();
    const cfg = this.conf;
    if (proof.platform === "google" && this.refusal !== null && this.refusal.until > this.nowMs()) {
      return { ok: false, error: this.refusal.error };
    }
    const before = this.readEnt();
    let applied: boolean;
    if (proof.platform === "apple") {
      const tx = proof.tx;
      if (cfg.apple !== null && tx.environment !== "Xcode") {
        const answer = await appleStoreCheck(tx, appleSettings(cfg), cfg.apple, this.httpFetch, now);
        if (answer.type === "unknown") return { ok: false, error: "store_unavailable" };
        if (answer.type === "invalid") return { ok: false, error: "pro_invalid" };
        applied = this.applyState(name, "apple", answer.state, "api", now, now, {});
      } else if (cfg.testMode || tx.environment === "Xcode") {
        // TEST_MODE without API credentials: the verified proof is used as is. Xcode purchases have no API.
        applied = this.applyState(name, "apple", decideTransaction(tx, now), "proof", Math.floor(tx.signedDate / 1000), now, {});
      } else {
        logLine({ t: "cfg", missing: "APPLE_ISSUER_ID" });
        return { ok: false, error: "store_unavailable" };
      }
    } else {
      const answer = await this.askGoogle(proof.purchaseToken, proof.productId, now, cfg);
      if (answer.type === "unknown") return { ok: false, error: "store_unavailable" };
      if (answer.type === "invalid") {
        this.refusal = { error: "pro_invalid", until: this.nowMs() + REFUSAL_MEMORY_MS };
        return { ok: false, error: "pro_invalid" };
      }
      applied = this.applyState(name, "google", answer.state, "api", now, now, { purchaseToken: proof.purchaseToken });
      if (answer.state.linkedPurchaseToken !== undefined) await this.adoptLinked(name, answer.state.linkedPurchaseToken);
    }

    const ent = this.readEnt();
    if (ent === null) {
      // Only reachable when an old proof was ignored with nothing stored, which can't happen; be safe.
      return { ok: false, error: "pro_inactive" };
    }
    const state = this.stateOf(ent);
    if (applied && before !== null) await this.pushIfChanged(this.stateOf(before), state, groupId);
    if (!state.active) {
      if (proof.platform === "google") this.refusal = { error: "pro_inactive", until: this.nowMs() + REFUSAL_MEMORY_MS };
      this.scheduleJobs(now);
      return { ok: false, error: "pro_inactive" };
    }
    const others = this.sql
      .exec<{ n: number }>("SELECT COUNT(*) AS n FROM groups WHERE group_id != ?", groupId)
      .one().n;
    if (others >= cfg.maxGroupsPerPurchase) {
      this.scheduleJobs(now);
      return { ok: false, error: "too_many_groups" };
    }
    this.sql.exec("INSERT OR IGNORE INTO groups (group_id, attached_at) VALUES (?, ?)", groupId, now);
    this.refusal = null;
    this.scheduleJobs(now);
    return { ok: true, state };
  }

  async detach(groupId: string): Promise<void> {
    if (!this.hasSchema) return;
    this.sql.exec("DELETE FROM groups WHERE group_id = ?", groupId);
    this.scheduleJobs(this.now());
  }

  /** Removes and returns every group (they move to the purchase that replaced this one, Google linkedPurchaseToken). */
  async releaseGroups(): Promise<string[]> {
    const ids = this.groupIds();
    if (ids.length === 0) return [];
    this.sql.exec("DELETE FROM groups");
    this.scheduleJobs(this.now());
    return ids;
  }

  async groupCount(): Promise<number> {
    return this.groupIds().length;
  }

  /** The stored state, without asking the store (tests and the admin view). */
  async current(): Promise<ProState | null> {
    const ent = this.readEnt();
    return ent === null ? null : this.stateOf(ent);
  }

  /**
   * Asks the store API and stores the answer (`state_at` = now). Every attached group but `callerGroupId` hears
   * about a change. Returns `unknown: true` only when the store could not be asked.
   */
  async check(callerGroupId?: string): Promise<CheckResult> {
    const ent = this.readEnt();
    if (ent === null) return { unknown: true };
    const now = this.now();
    const before = this.stateOf(ent);
    const answer = await this.askStored(ent, now);
    if (answer.type === "unknown") return { unknown: true };
    const state: StoreState =
      answer.type === "state"
        ? answer.state
        : {
            active: false,
            reason: "pro_ended",
            expiresAt: ent.expires_at,
            productId: ent.product_id,
            kind: ent.kind,
            environment: ent.environment,
            storeState: "unknown to the store",
          };
    this.applyState(ent.name, ent.platform, state, "api", now, now, { purchaseToken: ent.purchase_token ?? undefined });
    if (state.linkedPurchaseToken !== undefined) await this.adoptLinked(ent.name, state.linkedPurchaseToken);
    const after = this.stateOf(this.readEnt()!);
    await this.pushIfChanged(before, after, callerGroupId);
    this.scheduleJobs(now);
    return { unknown: false, state: after };
  }

  /** An App Store Server Notification V2 for this purchase (already verified by the Worker). */
  async notifyApple(name: string, n: AppleNotification): Promise<void> {
    const ent = this.readEnt();
    if (ent === null || n.notificationType === "TEST") return; // no group ever used this purchase
    const now = this.now();
    if (!this.markSeen(n.notificationUUID, now)) return;
    const cfg = this.conf;
    if (cfg.apple !== null) {
      await this.check();
      return;
    }
    if (!cfg.testMode) {
      logLine({ t: "cfg", missing: "APPLE_ISSUER_ID" });
      return;
    }
    const derived = deriveFromNotification(n);
    if (derived === null) return;
    const before = this.stateOf(ent);
    const applied = this.applyState(name, "apple", derived, "notification", Math.floor(n.signedDate / 1000), now, {
      refundReversed: n.notificationType === "REFUND_REVERSED",
    });
    if (applied) await this.pushIfChanged(before, this.stateOf(this.readEnt()!));
    this.scheduleJobs(now);
  }

  /** A Google real-time developer notification for this purchase token. The API answer decides, never the message. */
  async notifyGoogle(name: string, rtdn: Rtdn): Promise<void> {
    const ent = this.readEnt();
    const now = this.now();
    if (ent === null) {
      // A new subscription token that replaces one whose groups we hold (upgrade, resubscription).
      if (rtdn.kind === "subscription" && rtdn.purchaseToken !== null) await this.adoptFromNotification(name, rtdn.purchaseToken, now);
      return;
    }
    if (!this.markSeen(rtdn.messageId, now)) return;
    await this.check();
  }

  // Internals

  private markSeen(id: string, now: number): boolean {
    const fresh = this.sql
      .exec("INSERT INTO seen (notification_id, at) VALUES (?, ?) ON CONFLICT DO NOTHING RETURNING notification_id", id, now)
      .toArray().length;
    if (fresh > 0) this.jobs.setEarliest("seen_prune", now + DAY);
    return fresh > 0;
  }

  private async askGoogle(purchaseToken: string, productId: string, now: number, cfg: Config): Promise<StoreAnswer> {
    if (cfg.allowUnverifiedPro) return { type: "state", state: unverifiedState(now) };
    const sa = parseServiceAccount(cfg.googleServiceAccountJson);
    if (sa === null) {
      logLine({ t: "cfg", missing: "GOOGLE_SERVICE_ACCOUNT_JSON" });
      return { type: "unknown" };
    }
    return googleStoreCheck(purchaseToken, productId, googleSettings(cfg), sa, this.httpFetch, now);
  }

  /** The store's answer for the stored purchase; under TEST_MODE without credentials, the stored state re-decided. */
  private async askStored(ent: EntRow, now: number): Promise<StoreAnswer> {
    const cfg = this.conf;
    if (ent.platform === "apple") {
      if (cfg.apple !== null && ent.environment !== "Xcode") {
        return appleStoreCheck(
          {
            originalTransactionId: ent.name.slice("apple:".length),
            transactionId: ent.transaction_id ?? ent.name.slice("apple:".length),
            kind: ent.kind,
            environment: ent.environment,
          },
          appleSettings(cfg),
          cfg.apple,
          this.httpFetch,
          now,
        );
      }
      if (cfg.testMode || ent.environment === "Xcode") return { type: "state", state: this.redecided(ent, now) };
      logLine({ t: "cfg", missing: "APPLE_ISSUER_ID" });
      return { type: "unknown" };
    }
    if (cfg.allowUnverifiedPro) return { type: "state", state: this.redecided(ent, now) };
    if (ent.purchase_token === null) return { type: "unknown" };
    return this.askGoogle(ent.purchase_token, ent.product_id, now, cfg);
  }

  /** The stored state at `now`: an expiry that passed ends it. */
  private redecided(ent: EntRow, now: number): StoreState {
    const active = ent.active === 1 && ent.refunded === 0 && (ent.expires_at === null || ent.expires_at > now);
    return {
      active,
      reason: active ? null : ent.refunded === 1 ? "pro_refunded" : "pro_ended",
      expiresAt: ent.expires_at,
      productId: ent.product_id,
      kind: ent.kind,
      environment: ent.environment,
      storeState: "stored",
    };
  }

  /**
   * Writes a state into `ent` under the ordering rule: a proof or notification at or before `state_at` is ignored, and
   * refunded is sticky (only a store API answer or a newer REFUND_REVERSED clears it). Returns whether it was applied.
   */
  private applyState(
    name: string,
    platform: Platform,
    state: StoreState,
    source: Source,
    at: number,
    now: number,
    extra: { purchaseToken?: string; refundReversed?: boolean },
  ): boolean {
    const ent = this.readEnt();
    if (ent !== null && source !== "api") {
      if (at <= ent.state_at) return false;
      if (ent.refunded === 1 && !(source === "notification" && extra.refundReversed === true)) return false;
    }
    this.ensureSchema();
    const refunded = state.reason === "pro_refunded" ? 1 : 0;
    this.sql.exec(
      `INSERT INTO ent (one, name, platform, product_id, kind, environment, active, expires_at, grace_until, revoked_at,
         refunded, state_at, checked_at, store_state, transaction_id, purchase_token)
       VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT (one) DO UPDATE SET product_id = excluded.product_id, kind = excluded.kind,
         environment = excluded.environment, active = excluded.active, expires_at = excluded.expires_at,
         grace_until = excluded.grace_until, revoked_at = COALESCE(excluded.revoked_at, ent.revoked_at),
         refunded = excluded.refunded, state_at = excluded.state_at,
         checked_at = COALESCE(excluded.checked_at, ent.checked_at), store_state = excluded.store_state,
         transaction_id = COALESCE(excluded.transaction_id, ent.transaction_id),
         purchase_token = COALESCE(excluded.purchase_token, ent.purchase_token)`,
      name,
      platform,
      state.productId,
      state.kind,
      state.environment,
      state.active ? 1 : 0,
      state.expiresAt,
      state.inGrace === true ? state.expiresAt : null,
      refunded === 1 ? now : null,
      refunded,
      Math.max(at, ent?.state_at ?? 0),
      source === "api" ? now : null,
      state.storeState,
      state.transactionId ?? null,
      extra.purchaseToken ?? null,
    );
    return true;
  }

  private async pushIfChanged(before: ProState, after: ProState, exceptGroupId?: string): Promise<void> {
    if (
      before.active === after.active &&
      before.reason === after.reason &&
      before.expiresAt === after.expiresAt &&
      before.productId === after.productId
    ) {
      return;
    }
    await this.push(after, exceptGroupId);
  }

  private async push(state: ProState, exceptGroupId?: string): Promise<void> {
    for (const groupId of this.groupIds()) {
      if (groupId === exceptGroupId) continue;
      try {
        await this.env.GROUP.get(this.env.GROUP.idFromName(groupId)).proChanged(state.name, state);
      } catch (err) {
        logLine({ t: "pro", op: "proChanged", err: errorText(err) });
      }
    }
  }

  /** Moves the groups of the purchase `linkedToken` replaced into this one (SPEC §7.6, linkedPurchaseToken). */
  private async adoptLinked(name: string, linkedToken: string): Promise<void> {
    const oldName = await googleProName(linkedToken);
    if (oldName === name) return;
    const old = this.env.PRO.get(this.env.PRO.idFromName(oldName));
    const moved = await old.releaseGroups();
    if (moved.length === 0) return;
    const now = this.now();
    for (const groupId of moved) {
      this.sql.exec("INSERT OR IGNORE INTO groups (group_id, attached_at) VALUES (?, ?)", groupId, now);
    }
    this.scheduleJobs(now);
    const state = this.stateOf(this.readEnt()!);
    for (const groupId of moved) {
      try {
        await this.env.GROUP.get(this.env.GROUP.idFromName(groupId)).proMoved(oldName, name, state);
      } catch (err) {
        logLine({ t: "pro", op: "proMoved", err: errorText(err) });
      }
    }
  }

  private async adoptFromNotification(name: string, purchaseToken: string, now: number): Promise<void> {
    const cfg = this.conf;
    const sa = parseServiceAccount(cfg.googleServiceAccountJson);
    if (sa === null) return;
    const answer = await googleStoreCheck(purchaseToken, "pro", googleSettings(cfg), sa, this.httpFetch, now);
    if (answer.type !== "state" || answer.state.linkedPurchaseToken === undefined) return;
    const old = this.env.PRO.get(this.env.PRO.idFromName(await googleProName(answer.state.linkedPurchaseToken)));
    if ((await old.groupCount()) === 0) return;
    this.applyState(name, "google", answer.state, "api", now, now, { purchaseToken });
    await this.adoptLinked(name, answer.state.linkedPurchaseToken);
  }

  /** `check` 60 s after expiry (6 h in a Google grace period), self-deletion 30 days after the last group left. */
  private scheduleJobs(now: number): void {
    if (!this.hasSchema) return;
    const jobs = this.jobs;
    const ent = this.readEnt();
    const groups = this.groupIds().length;
    if (ent !== null && groups > 0 && ent.expires_at !== null) {
      let at = ent.expires_at + 60;
      if (ent.grace_until !== null) at = Math.min(at, now + GRACE_RECHECK);
      if (ent.active === 0 && ent.expires_at + 60 <= now) jobs.delete("check");
      else jobs.set("check", Math.max(at, now));
    } else {
      jobs.delete("check");
    }
    if (groups === 0) {
      if (jobs.get("self_delete") === null) jobs.set("self_delete", now + SELF_DELETE_AFTER);
    } else {
      jobs.delete("self_delete");
    }
    this.setAlarm();
  }

  private setAlarm(): void {
    const at = this.jobs.earliest();
    if (at === null) void this.ctx.storage.deleteAlarm();
    else void this.ctx.storage.setAlarm(at * 1000);
  }

  override async alarm(): Promise<void> {
    if (!this.hasSchema) return;
    const now = this.now();
    let deleted = false;
    try {
      await runDueJobs(this.jobs, now, async (job) => {
        switch (job.kind) {
          case "check": {
            const result = await this.check();
            if (result.unknown) throw new Error("store unavailable");
            // check() rescheduled the job; a still-due job (expiry unchanged) moves to the next safeguard.
            const next = this.jobs.get("check");
            if (next !== null && next.at <= now) this.jobs.done("check", now + HOUR);
            else if (next !== null) this.jobs.done("check", next.at);
            break;
          }
          case "seen_prune": {
            this.sql.exec("DELETE FROM seen WHERE at < ?", now - SEEN_RETENTION);
            const left = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM seen").one().n;
            this.jobs.done("seen_prune", left > 0 ? now + DAY : null);
            break;
          }
          case "self_delete": {
            if (this.groupIds().length > 0) {
              this.jobs.done("self_delete", null);
              break;
            }
            await this.ctx.storage.deleteAlarm();
            await this.ctx.storage.deleteAll();
            this.hasSchema = false;
            deleted = true;
            break;
          }
          default:
            this.jobs.done(job.kind, null);
        }
      }, () => deleted);
    } finally {
      if (!deleted && this.hasSchema) this.setAlarm();
    }
  }
}

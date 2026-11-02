// GroupDO: one Durable Object per group, named by its `groupId` text (SPEC §7.3). It holds the group's SQLite
// tables, answers every /v1/groups/{groupId}/… request and the invite preview and redeem, keeps live positions in
// memory, pushes ticks to hibernating WebSockets (§6.6), and runs the alarm jobs (§7.4) and the R2 archive (§7.5).

import { DurableObject } from "cloudflare:workers";
import type { NewReport, AdminResult, ReportReason, ReportTargetKind } from "../admin/types";
import type { AppEnv } from "../env";
import { b64urlDecode, b64urlEncode } from "../lib/b64url";
import { readConfig, type Config } from "../lib/config";
import { decodeEnvelope, parseEnvelope } from "../lib/envelope";
import { ApiError, apiErrorResponse, errorResponse, json, noContent } from "../lib/errors";
import { bytesEqual, concatBytes, randomBytes, sha256 } from "../lib/hash";
import { decode32, parse32, parseId, splitToken } from "../lib/ids";
import { Jobs, runDueJobs, type Job } from "../lib/jobs";
import {
  ANIMAL_PIN_LIFETIME,
  ARCHIVE_AGE,
  ARCHIVE_BATCH,
  CHANGES_DEFAULT_LIMIT,
  CHANGES_MAX_BYTES,
  CHANGES_MAX_LIMIT,
  CLOCK_AHEAD,
  DAY,
  DELETED_RETENTION,
  GONE_PASS_RETENTION,
  HOUR,
  INVITE_LIFETIME,
  INVITE_RETENTION,
  LIVE_MAX_AGE,
  LIVE_MIN_INTERVAL_MS,
  LIVE_PERSIST_INTERVAL_MS,
  LIVE_ROW_RETENTION,
  LIVE_VISIBLE,
  MAX_CHUNKS_PER_UPLOAD,
  MAX_INVITES_PER_DAY,
  MAX_PENDING_INVITES,
  MAX_PINS_PER_MEMBER_HOUR,
  MAX_REPORTS_PER_MEMBER_DAY,
  MAX_REQUESTS_PER_MEMBER_MINUTE,
  MAX_UNEXPIRED_PINS,
  MINUTE,
  PAUSE_LOG_RETENTION,
  PAUSE_RETENTION,
  PIN_MAX_AGE,
  R2_DELETE_BATCH,
  REMOVALS_RETENTION,
  REPORT_TEXT,
  TICK_DELAY_MS,
  TICK_MAX_BYTES,
  TICK_MAX_ITEMS,
  TRANSFER_LIFETIME,
  WINDOW,
  WS_HELLO_TIMEOUT_MS,
  WS_IN_LIMIT,
  WS_MAX_SOCKETS,
  WS_OUT_LIMIT,
} from "../lib/limits";
import { errorText, logException, logLine } from "../lib/log";
import { dayOfMonth, dayStart, isMonth, monthRange, systemNow } from "../lib/time";
import { AppleVerifyError, appleSettings, checkTransaction, verifyJws } from "../pro/apple";
import { isPurchaseToken } from "../pro/google";
import { appleProName, googleProName, type ProofInput } from "../pro/ProDO";
import type { HttpFetch, PausedReason, ProState } from "../pro/types";
import { isProReason } from "../pro/types";
import { matchRoute } from "../routes";
import { archiveKey, decodeArchive, encodeArchive, groupPrefix, memberPrefix, type ArchivedChunk } from "./archive";
import { readChanges, type ChangesPage } from "./changes";
import { createSchema, migrate, SCHEMA_VERSION } from "./schema";

type Role = "owner" | "admin" | "member";

interface Caller {
  memberId: string;
  role: Role;
  joinedAt: number;
}

type GrpRow = {
  group_id: string;
  created_at: number;
  status: "active" | "paused" | "deleted";
  paused_reason: PausedReason | null;
  paused_at: number | null;
  delete_at: number | null;
  deleted_at: number | null;
  profile: ArrayBuffer | null;
  profile_seq: number;
  join_proof_hash: ArrayBuffer | null;
  seq: number;
  min_cursor: number;
  pro_name: string | null;
  pro_platform: string | null;
  pro_product: string | null;
  pro_expires_at: number | null;
}

type MemberRow = {
  member_id: string;
  role: Role;
  joined_at: number;
  profile: ArrayBuffer;
  seq: number;
  last_live_at: number | null;
}

/** A socket's serialized attachment (≤ 16 KiB). `w`, `st` and `more` remember what the last tick sent. */
interface Attachment {
  memberId: string;
  watching: boolean;
  cursor: number;
  helloAt: number;
  openedAt: number;
  /** Watcher count last sent, or -1. */
  w: number;
  /** Fingerprint of the status last sent. */
  st: string;
  /** The last tick's changes had `more`; no further changes until `ack`. */
  more: boolean;
}

interface LiveEntry {
  at: number;
  rec: Uint8Array;
  persistedAtMs: number;
  dirty: boolean;
}

interface PendingLive {
  memberId: string;
  at: number;
  rec: string | null;
}

interface UploadResult {
  accepted: number[];
  rejected: Array<{ start: number; error: string }>;
  liveAccepted: boolean;
}

type ProofBody = { platform: "apple"; signedTransaction: string } | { platform: "google"; productId: string; purchaseToken: string };

const REPORT_REASONS: ReadonlySet<string> = new Set(["offensive", "harassment", "spam", "dangerous", "other"]);
const PRO_RECHECK_KEY = "pro_recheck";
const PRO_RECHECK_LIMIT = 6;
const PRO_RECHECK_DELAY = 10 * MINUTE;
const textEncoder = new TextEncoder();

function blob(bytes: Uint8Array): ArrayBuffer {
  return bytes.slice().buffer;
}

function b64(value: ArrayBuffer | null): string {
  return value === null ? "" : b64urlEncode(new Uint8Array(value));
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function safeInt(value: unknown, what: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value)) throw new ApiError("bad_request", `${what} must be an integer.`);
  return value;
}

/** Length checks of SPEC §2.5 for report texts: code points and UTF-8 bytes. */
function textWithin(value: string, [maxCodePoints, maxBytes]: readonly [number, number]): boolean {
  return [...value].length <= maxCodePoints && textEncoder.encode(value).byteLength <= maxBytes;
}

/** FNV-1a of a text, for the status fingerprint kept in a socket attachment. */
function fingerprint(text: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(36) + ":" + text.length.toString(36);
}

export class GroupDO extends DurableObject<AppEnv> {
  /** Test hooks (SPEC §7.3): the clock, outgoing store calls, the environment, randomness and R2. */
  clockOverride: number | null = null;
  httpFetch: HttpFetch = (input, init) => fetch(input, init);
  envOverride: Record<string, string> | null = null;
  random: (count: number) => Uint8Array = randomBytes;
  bucketOverride: R2Bucket | null = null;
  helloTimeoutMs = WS_HELLO_TIMEOUT_MS;
  tickDelayMs = TICK_DELAY_MS;

  private hasSchema = false;
  private readonly live = new Map<string, LiveEntry>();
  private readonly liveAcceptedMs = new Map<string, number>();
  private readonly rate = new Map<string, { start: number; count: number }>();
  private pendingLive: PendingLive[] = [];
  private flushTimer: ReturnType<typeof setTimeout> | null = null;
  private persistTimer: ReturnType<typeof setTimeout> | null = null;
  private helloTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(ctx: DurableObjectState, env: AppEnv) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    void ctx.blockConcurrencyWhile(async () => {
      this.hasSchema = migrate(ctx.storage.sql);
      if (this.hasSchema) this.loadLive();
    });
  }

  // ───────────────────────────── basics

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

  private get bucket(): R2Bucket {
    return this.bucketOverride ?? this.env.ARCHIVE;
  }

  private grp(): GrpRow | null {
    if (!this.hasSchema) return null;
    return this.sql.exec<GrpRow>("SELECT * FROM grp WHERE one = 1").toArray()[0] ?? null;
  }

  private nextSeq(): number {
    return this.sql.exec<{ seq: number }>("UPDATE grp SET seq = seq + 1 WHERE one = 1 RETURNING seq").one().seq;
  }

  private member(memberId: string): MemberRow | null {
    return (
      this.sql
        .exec<MemberRow>(
          "SELECT member_id, role, joined_at, profile, seq, last_live_at FROM members WHERE member_id = ?",
          memberId,
        )
        .toArray()[0] ?? null
    );
  }

  private memberCount(): number {
    return this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM members").one().n;
  }

  private ownerId(): string {
    return this.sql.exec<{ member_id: string }>("SELECT member_id FROM members WHERE role = 'owner'").toArray()[0]?.member_id ?? "";
  }

  private proStub(name: string) {
    return this.env.PRO.get(this.env.PRO.idFromName(name));
  }

  private removedAs(what: "pin" | "member" | "trail", id: string): boolean {
    return this.sql.exec("SELECT 1 FROM removals WHERE what = ? AND id = ? LIMIT 1", what, id).toArray().length > 0;
  }

  /** The alarm goes to the earliest job or pin expiry (SPEC §7.4). */
  private setNextAlarm(): void {
    if (!this.hasSchema) return;
    const job = this.jobs.earliest();
    const pin = this.sql.exec<{ at: number | null }>("SELECT MIN(expires_at) AS at FROM pins").one().at;
    const at = job === null ? pin : pin === null ? job : Math.min(job, pin);
    if (at === null) void this.ctx.storage.deleteAlarm();
    else void this.ctx.storage.setAlarm(at * 1000);
  }

  /** A fixed-window counter in the `counters` table. Throws 429 when `max` is reached. */
  private countOrLimit(key: string, windowLength: number, max: number, now: number): void {
    const start = now - (now % windowLength);
    const row = this.sql.exec<{ window_start: number; count: number }>(
      "SELECT window_start, count FROM counters WHERE key = ?",
      key,
    ).toArray()[0];
    const count = row !== undefined && row.window_start === start ? row.count : 0;
    if (count >= max) throw new ApiError("rate_limited", undefined, start + windowLength - now);
    this.sql.exec(
      "INSERT INTO counters (key, window_start, count) VALUES (?, ?, ?) ON CONFLICT (key) DO UPDATE SET window_start = excluded.window_start, count = excluded.count",
      key,
      start,
      count + 1,
    );
  }

  /** 120 authenticated requests per member per minute, in memory (SPEC §6.8). */
  private rateLimit(memberId: string): boolean {
    const nowMs = Date.now();
    const entry = this.rate.get(memberId);
    if (entry === undefined || nowMs - entry.start >= 60_000) {
      this.rate.set(memberId, { start: nowMs, count: 1 });
      return true;
    }
    entry.count += 1;
    return entry.count <= MAX_REQUESTS_PER_MEMBER_MINUTE;
  }

  private rateRetryAfter(memberId: string): number {
    const entry = this.rate.get(memberId);
    return entry === undefined ? 60 : Math.max(1, Math.ceil((entry.start + 60_000 - Date.now()) / 1000));
  }

  // ───────────────────────────── HTTP

  override async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const match = matchRoute(request.method, url.pathname);
    if (match.type !== "route") {
      return errorResponse(match.type === "method_not_allowed" ? "method_not_allowed" : "not_found");
    }
    try {
      return await this.dispatch(match.route.id, match.params, request, url);
    } catch (err) {
      if (err instanceof ApiError) return apiErrorResponse(err);
      logException(`GroupDO ${match.route.id}`, err);
      return errorResponse("internal");
    }
  }

  private async dispatch(id: string, params: Record<string, string>, request: Request, url: URL): Promise<Response> {
    switch (id) {
      case "E1":
        return this.createGroup(await this.body(request));
      case "E9":
        return this.previewInvite(await this.body(request));
      case "E10":
        return this.redeemInvite(await this.body(request));
    }
    const caller = await this.auth(request);
    switch (id) {
      case "E2":
        return json(this.status(caller));
      case "E3":
        return this.setGroupProfile(caller, await this.body(request));
      case "E4":
        return this.deleteGroup(caller);
      case "E5":
        return this.newProof(caller, await this.body(request));
      case "E6":
        return this.createInvite(caller);
      case "E7":
        return this.listInvites(caller);
      case "E8":
        return this.cancelInvite(caller, parseId(params.iid, "inviteId"));
      case "E11":
        return this.listMembers();
      case "E12":
        return this.setMemberProfile(caller, await this.body(request));
      case "E13":
        return this.setRole(caller, this.memberParam(params.mid, caller), await this.body(request));
      case "E14":
        return this.removeMember(caller, params.mid === "me" ? "me" : parseId(params.mid, "memberId"));
      case "E15":
        return this.offerOwnership(caller, await this.body(request));
      case "E16":
        return this.cancelOffer(caller);
      case "E17":
        return this.acceptOwnership(caller, await this.body(request));
      case "E18": {
        const result = this.upload(caller, await this.body(request));
        const grp = this.grp()!;
        return json({ ...result, watchers: this.watchers(caller.memberId), status: grp.status, serverTime: this.now() });
      }
      case "E19":
        return this.changes(caller, url);
      case "E20":
        return this.archive(caller, parseId(params.mid, "memberId"), params.month ?? "");
      case "E21":
        return this.liveList(caller);
      case "E22":
        return this.clearTrail(caller);
      case "E23":
        return this.dropPin(caller, await this.body(request));
      case "E24":
        return this.deletePin(caller, parseId(params.pid, "pinId"));
      case "E25":
        return this.report(caller, await this.body(request));
      case "E26":
        return this.openSocket(request, caller);
    }
    return errorResponse("not_found");
  }

  private memberParam(value: string | undefined, caller: Caller): string {
    return value === "me" ? caller.memberId : parseId(value, "memberId");
  }

  private async body(request: Request): Promise<Record<string, unknown>> {
    const text = await request.text();
    if (text.trim() === "") return {};
    let value: unknown;
    try {
      value = JSON.parse(text);
    } catch {
      throw new ApiError("bad_request", "The body is not JSON.");
    }
    if (!isObject(value)) throw new ApiError("bad_request", "The body must be a JSON object.");
    return value;
  }

  /** The Bearer pass → the caller (SPEC §7.3 Auth). A deleted group answers 410 before auth. */
  private async auth(request: Request): Promise<Caller> {
    const grp = this.grp();
    if (grp === null) throw new ApiError("group_not_found");
    if (grp.status === "deleted") throw new ApiError("group_deleted");
    const header = request.headers.get("Authorization") ?? "";
    const m = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(header);
    const pass = m ? decode32(m[1]) : null;
    if (pass === null) throw new ApiError("unauthorized");
    const hash = await sha256(pass);
    const row = this.sql
      .exec<{ member_id: string; role: Role; joined_at: number }>(
        "SELECT member_id, role, joined_at FROM members WHERE pass_hash = ?",
        blob(hash),
      )
      .toArray()[0];
    if (row === undefined) {
      const gone = this.sql
        .exec<{ reason: string; at: number }>("SELECT reason, at FROM gone_passes WHERE pass_hash = ?", blob(hash))
        .toArray()[0];
      if (gone !== undefined && gone.reason === "removed" && gone.at >= this.now() - GONE_PASS_RETENTION) {
        throw new ApiError("removed");
      }
      throw new ApiError("unauthorized");
    }
    if (!this.rateLimit(row.member_id)) throw new ApiError("rate_limited", undefined, this.rateRetryAfter(row.member_id));
    return { memberId: row.member_id, role: row.role, joinedAt: row.joined_at };
  }

  // ───────────────────────────── status

  private statusShared(now: number) {
    const grp = this.grp()!;
    const pausedIntervals = this.sql
      .exec<{ paused_at: number; resumed_at: number | null }>(
        "SELECT paused_at, resumed_at FROM pause_log WHERE resumed_at IS NULL OR resumed_at >= ? ORDER BY paused_at",
        now - PAUSE_LOG_RETENTION,
      )
      .toArray()
      .map((r) => [r.paused_at, r.resumed_at] as [number, number | null]);
    const transferRow = this.sql
      .exec<{ to_member: string; expires_at: number }>("SELECT to_member, expires_at FROM transfer WHERE one = 1")
      .toArray()[0];
    return {
      grp,
      pausedIntervals,
      ownerId: this.ownerId(),
      memberCount: this.memberCount(),
      transfer:
        transferRow !== undefined && transferRow.expires_at > now
          ? { to: transferRow.to_member, expiresAt: transferRow.expires_at }
          : null,
      watchingMembers: this.watchingMembers(),
    };
  }

  private statusFrom(shared: ReturnType<GroupDO["statusShared"]>, caller: Caller, now: number) {
    const { grp } = shared;
    let watchers = shared.watchingMembers.size;
    if (shared.watchingMembers.has(caller.memberId)) watchers -= 1;
    return {
      groupId: grp.group_id,
      status: grp.status,
      pausedReason: grp.paused_reason,
      pausedAt: grp.paused_at,
      deleteAt: grp.delete_at,
      pausedIntervals: shared.pausedIntervals,
      ownerId: shared.ownerId,
      memberCount: shared.memberCount,
      profile: b64(grp.profile),
      profileSeq: grp.profile_seq,
      you: { memberId: caller.memberId, role: caller.role, joinedAt: caller.joinedAt },
      transfer: shared.transfer,
      pro:
        caller.role === "owner" && grp.pro_name !== null
          ? { platform: grp.pro_platform, productId: grp.pro_product, expiresAt: grp.pro_expires_at }
          : null,
      watchers,
      seq: grp.seq,
      serverTime: now,
    };
  }

  /** GroupStatus (SPEC §6.3) for `caller`. */
  private status(caller: Caller) {
    const now = this.now();
    return this.statusFrom(this.statusShared(now), caller, now);
  }

  private callerFor(memberId: string): Caller | null {
    const row = this.member(memberId);
    return row === null ? null : { memberId, role: row.role, joinedAt: row.joined_at };
  }

  // ───────────────────────────── Pro proofs

  private parseProof(value: unknown): ProofBody {
    if (!isObject(value)) throw new ApiError("bad_request", "pro must be an object.");
    if (value.platform === "apple") {
      if (typeof value.signedTransaction !== "string") throw new ApiError("bad_request", "signedTransaction must be a string.");
      return { platform: "apple", signedTransaction: value.signedTransaction };
    }
    if (value.platform === "google") {
      if (typeof value.productId !== "string" || typeof value.purchaseToken !== "string") {
        throw new ApiError("bad_request", "productId and purchaseToken must be strings.");
      }
      return { platform: "google", productId: value.productId, purchaseToken: value.purchaseToken };
    }
    throw new ApiError("bad_request", "Unknown platform.");
  }

  /** Checks the shape of a Google proof before any store call (402 pro_invalid). */
  private precheckProof(proof: ProofBody): void {
    if (proof.platform !== "google") return;
    if (!this.conf.proProductsGoogle.includes(proof.productId) || !isPurchaseToken(proof.purchaseToken)) {
      throw new ApiError("pro_invalid");
    }
  }

  /** Verifies a proof offline (Apple JWS chain and payload; Google shape) and names its ProDO. */
  private async verifyProof(proof: ProofBody): Promise<{ name: string; input: ProofInput }> {
    this.precheckProof(proof);
    if (proof.platform === "apple") {
      const settings = appleSettings(this.conf);
      try {
        const tx = checkTransaction(await verifyJws(proof.signedTransaction, settings.rootDer), settings);
        return { name: appleProName(tx.originalTransactionId), input: { platform: "apple", tx } };
      } catch (err) {
        if (err instanceof AppleVerifyError) throw new ApiError("pro_invalid");
        throw err;
      }
    }
    return {
      name: await googleProName(proof.purchaseToken),
      input: { platform: "google", productId: proof.productId, purchaseToken: proof.purchaseToken },
    };
  }

  /** Verify, ask the store and attach `groupId` to the purchase's ProDO (SPEC §6.5 E1 steps 3–4). */
  private async attachProof(groupId: string, proof: ProofBody): Promise<{ name: string; state: ProState }> {
    const verified = await this.verifyProof(proof);
    const result = await this.proStub(verified.name).attach(verified.name, groupId, verified.input);
    if (!result.ok) throw new ApiError(result.error);
    return { name: verified.name, state: result.state };
  }

  private async detachFrom(name: string | null, groupId: string): Promise<void> {
    if (name === null) return;
    try {
      await this.proStub(name).detach(groupId);
    } catch (err) {
      logLine({ t: "pro", op: "detach", err: errorText(err) });
    }
  }

  /** Writes a purchase's state into `grp` and pauses or resumes; call inside transactionSync. */
  private applyProStateTx(name: string, state: ProState, now: number): void {
    this.sql.exec(
      "UPDATE grp SET pro_name = ?, pro_platform = ?, pro_product = ?, pro_expires_at = ? WHERE one = 1",
      name,
      state.platform,
      state.productId,
      state.expiresAt,
    );
    const grp = this.grp()!;
    const jobs = this.jobs;
    if (state.active) {
      if (grp.status === "paused" && isProReason(grp.paused_reason)) this.resumeTx(now);
      this.sql.exec("DELETE FROM counters WHERE key = ?", PRO_RECHECK_KEY);
      jobs.delete("pro_retry");
      if (state.expiresAt !== null) jobs.set("pro_check", Math.max(state.expiresAt, now));
      else jobs.delete("pro_check");
    } else {
      const reason = state.reason ?? "pro_ended";
      if (grp.status === "active") this.pauseTx(reason, now);
      else if (isProReason(grp.paused_reason)) this.sql.exec("UPDATE grp SET paused_reason = ? WHERE one = 1", reason);
      jobs.delete("pro_check");
      jobs.delete("pro_retry");
    }
  }

  /** RPC from ProDO: the purchase `name`'s state changed. Ignored unless it is this group's current purchase. */
  async proChanged(name: string, state: ProState): Promise<void> {
    const grp = this.grp();
    if (grp === null || grp.status === "deleted" || grp.pro_name !== name) return;
    const now = this.now();
    const wasActive = grp.status === "active";
    this.ctx.storage.transactionSync(() => this.applyProStateTx(name, state, now));
    this.afterStatusChange(wasActive);
  }

  /** RPC from ProDO: the group's purchase was replaced by `newName` (Google linkedPurchaseToken). */
  async proMoved(oldName: string, newName: string, state: ProState): Promise<void> {
    const grp = this.grp();
    if (grp === null || grp.status === "deleted" || grp.pro_name !== oldName) return;
    const now = this.now();
    const wasActive = grp.status === "active";
    this.ctx.storage.transactionSync(() => this.applyProStateTx(newName, state, now));
    this.afterStatusChange(wasActive);
  }

  // ───────────────────────────── pause and resume (SPEC §7.3)

  private pauseTx(reason: PausedReason, now: number): void {
    const deleteAt = now + PAUSE_RETENTION;
    this.sql.exec(
      "UPDATE grp SET status = 'paused', paused_reason = ?, paused_at = ?, delete_at = ? WHERE one = 1",
      reason,
      now,
      deleteAt,
    );
    this.sql.exec(
      "INSERT INTO pause_log (paused_at, resumed_at, reason) VALUES (?, NULL, ?) ON CONFLICT (paused_at) DO UPDATE SET resumed_at = NULL, reason = excluded.reason",
      now,
      reason,
    );
    this.jobs.set("purge", deleteAt);
    this.sql.exec("DELETE FROM live");
    for (const memberId of this.live.keys()) this.pendingLive.push({ memberId, at: now, rec: null });
    this.live.clear();
  }

  private resumeTx(now: number): void {
    this.sql.exec(
      "UPDATE grp SET status = 'active', paused_reason = NULL, paused_at = NULL, delete_at = NULL WHERE one = 1",
    );
    this.sql.exec("UPDATE pause_log SET resumed_at = ? WHERE resumed_at IS NULL", now);
    this.jobs.delete("purge");
    this.jobs.delete("pro_retry");
  }

  /** After any status change: next alarm and a tick. */
  private afterStatusChange(_wasActive?: boolean): void {
    this.setNextAlarm();
    this.scheduleFlush();
  }

  // ───────────────────────────── E1 create

  private async createGroup(body: Record<string, unknown>): Promise<Response> {
    const cfg = this.conf;
    const groupId = parseId(body.groupId, "groupId");
    const memberId = parseId(body.memberId, "memberId");
    const pass = parse32(body.pass, "pass");
    const joinProof = parse32(body.joinProof, "joinProof");
    const groupProfile = parseEnvelope(body.groupProfile, "groupProfile", "groupProfile");
    const memberProfile = parseEnvelope(body.memberProfile, "memberProfile", "memberProfile");
    const termsVersion = safeInt(body.termsVersion, "termsVersion");
    const proof = this.parseProof(body.pro);
    if (termsVersion < cfg.termsVersion) throw new ApiError("terms_outdated");
    this.precheckProof(proof);
    const passHash = await sha256(pass);

    const retry = this.createRetry(passHash);
    if (retry !== null) return retry;

    const pro = await this.attachProof(groupId, proof);

    // The store call let other requests run; check again.
    const again = this.createRetry(passHash);
    if (again !== null) return again;

    const now = this.now();
    const joinProofHash = await sha256(joinProof);
    if (!this.hasSchema) {
      createSchema(this.sql);
      this.hasSchema = true;
    }
    const daily = dayStart(now) + DAY + HOUR + Math.floor(Math.random() * 3 * HOUR);
    this.ctx.storage.transactionSync(() => {
      this.sql.exec(
        `INSERT INTO grp (one, group_id, created_at, status, profile, profile_seq, join_proof_hash, seq, min_cursor,
           pro_name, pro_platform, pro_product, pro_expires_at, schema_version)
         VALUES (1, ?, ?, 'active', ?, 1, ?, 2, 0, ?, ?, ?, ?, ?)`,
        groupId,
        now,
        blob(groupProfile),
        blob(joinProofHash),
        pro.name,
        pro.state.platform,
        pro.state.productId,
        pro.state.expiresAt,
        SCHEMA_VERSION,
      );
      this.sql.exec(
        "INSERT INTO members (member_id, pass_hash, role, joined_at, profile, seq, terms_version) VALUES (?, ?, 'owner', ?, ?, 2, ?)",
        memberId,
        blob(passHash),
        now,
        blob(memberProfile),
        termsVersion,
      );
      this.jobs.set("daily", daily);
      if (pro.state.expiresAt !== null) this.jobs.set("pro_check", Math.max(pro.state.expiresAt, now));
    });
    this.setNextAlarm();
    return json(this.status({ memberId, role: "owner", joinedAt: now }), 201);
  }

  /** E1 retry: 200 with the status when the group exists and `pass` is its owner's; 409 when the id is taken. */
  private createRetry(passHash: Uint8Array): Response | null {
    const grp = this.grp();
    if (grp === null) return null;
    if (grp.status !== "deleted") {
      const owner = this.sql
        .exec<{ member_id: string; role: Role; joined_at: number }>(
          "SELECT member_id, role, joined_at FROM members WHERE pass_hash = ? AND role = 'owner'",
          blob(passHash),
        )
        .toArray()[0];
      if (owner !== undefined) {
        return json(this.status({ memberId: owner.member_id, role: owner.role, joinedAt: owner.joined_at }), 200);
      }
    }
    throw new ApiError("group_exists");
  }

  // ───────────────────────────── E3, E4, E5

  private requireRole(caller: Caller, ...roles: Role[]): void {
    if (!roles.includes(caller.role)) throw new ApiError("forbidden");
  }

  private setGroupProfile(caller: Caller, body: Record<string, unknown>): Response {
    this.requireRole(caller, "owner", "admin");
    const profile = parseEnvelope(body.profile, "groupProfile", "profile");
    const seq = this.ctx.storage.transactionSync(() => {
      const seq = this.nextSeq();
      this.sql.exec("UPDATE grp SET profile = ?, profile_seq = ? WHERE one = 1", blob(profile), seq);
      return seq;
    });
    this.scheduleFlush();
    return json({ seq });
  }

  private async deleteGroup(caller: Caller): Promise<Response> {
    this.requireRole(caller, "owner");
    await this.deleteGroupNow();
    return noContent();
  }

  /** E4 and A5 (SPEC §6.5 E4). */
  private async deleteGroupNow(): Promise<void> {
    const grp = this.grp()!;
    await this.detachFrom(grp.pro_name, grp.group_id);
    for (const ws of this.ctx.getWebSockets()) this.closeSocket(ws, 4404, "group deleted");
    const now = this.now();
    this.ctx.storage.transactionSync(() => {
      for (const table of ["members", "gone_passes", "invites", "chunks", "archives", "pins", "removals", "live", "pause_log", "transfer", "jobs", "counters"]) {
        this.sql.exec(`DELETE FROM ${table}`);
      }
      this.sql.exec(
        `UPDATE grp SET status = 'deleted', deleted_at = ?, paused_reason = NULL, paused_at = NULL, delete_at = NULL,
           profile = NULL, profile_seq = 0, join_proof_hash = NULL, pro_platform = NULL, pro_product = NULL,
           pro_expires_at = NULL WHERE one = 1`,
        now,
      );
      this.sql.exec("INSERT OR REPLACE INTO r2_deletes (prefix, at) VALUES (?, ?)", groupPrefix(grp.group_id), now);
      this.jobs.set("r2_delete", now);
      this.jobs.set("purge", now + DELETED_RETENTION);
    });
    this.live.clear();
    this.pendingLive = [];
    this.setNextAlarm();
  }

  private async newProof(caller: Caller, body: Record<string, unknown>): Promise<Response> {
    this.requireRole(caller, "owner");
    const proof = this.parseProof(body.pro);
    const grp = this.grp()!;
    const pro = await this.attachProof(grp.group_id, proof);
    const now = this.now();
    const current = this.grp()!;
    if (current.status === "deleted") throw new ApiError("group_deleted");
    const oldName = current.pro_name;
    this.ctx.storage.transactionSync(() => this.applyProStateTx(pro.name, pro.state, now));
    if (oldName !== null && oldName !== pro.name) await this.detachFrom(oldName, grp.group_id);
    this.afterStatusChange();
    const me = this.callerFor(caller.memberId) ?? caller;
    return json(this.status(me));
  }

  // ───────────────────────────── invites (E6–E10)

  private async createInvite(caller: Caller): Promise<Response> {
    this.requireRole(caller, "owner", "admin");
    const grp = this.grp()!;
    const inviteId = b64urlEncode(this.random(16));
    const secret = this.random(16);
    // groupId is canonical (checked at E1), so it decodes to exactly 16 bytes; the token routes by them.
    const token = b64urlEncode(concatBytes(b64urlDecode(grp.group_id)!, secret));
    const secretHash = await sha256(secret);
    const now = this.now();
    const expiresAt = now + INVITE_LIFETIME;
    this.ctx.storage.transactionSync(() => {
      const pending = this.sql
        .exec<{ n: number }>("SELECT COUNT(*) AS n FROM invites WHERE state = 'pending' AND expires_at > ?", now)
        .one().n;
      if (pending >= MAX_PENDING_INVITES) throw new ApiError("rate_limited", "Too many pending invites.", HOUR);
      this.countOrLimit("invites:day", DAY, MAX_INVITES_PER_DAY, now);
      this.sql.exec(
        "INSERT INTO invites (invite_id, secret_hash, created_by, created_at, expires_at, state) VALUES (?, ?, ?, ?, ?, 'pending')",
        inviteId,
        blob(secretHash),
        caller.memberId,
        now,
        expiresAt,
      );
    });
    return json({ inviteId, token, createdAt: now, expiresAt }, 201);
  }

  private listInvites(caller: Caller): Response {
    this.requireRole(caller, "owner", "admin");
    const now = this.now();
    const invites = this.sql
      .exec<{ invite_id: string; created_by: string; created_at: number; expires_at: number }>(
        "SELECT invite_id, created_by, created_at, expires_at FROM invites WHERE state = 'pending' AND expires_at > ? ORDER BY created_at DESC, invite_id",
        now,
      )
      .toArray()
      .map((r) => ({ inviteId: r.invite_id, createdBy: r.created_by, createdAt: r.created_at, expiresAt: r.expires_at }));
    return json({ invites });
  }

  private cancelInvite(caller: Caller, inviteId: string): Response {
    this.requireRole(caller, "owner", "admin");
    const now = this.now();
    const changed = this.sql
      .exec(
        "UPDATE invites SET state = 'cancelled', used_at = ? WHERE invite_id = ? AND state = 'pending' AND expires_at > ? RETURNING invite_id",
        now,
        inviteId,
        now,
      )
      .toArray().length;
    if (changed === 0) throw new ApiError("not_found");
    return noContent();
  }

  /** Finds a pending, unexpired invite whose secret and the group's join proof match; null otherwise. */
  private async findInvite(token: unknown, joinProof: unknown) {
    const split = splitToken(token);
    const proof = decode32(joinProof);
    if (split === null || proof === null) throw new ApiError("bad_request", "Malformed token or joinProof.");
    const grp = this.grp();
    if (grp === null || grp.status === "deleted" || grp.join_proof_hash === null) return null;
    if (split.groupId !== grp.group_id) return null;
    const [secretHash, proofHash] = await Promise.all([sha256(split.secret), sha256(proof)]);
    const invite = this.sql
      .exec<{
        invite_id: string;
        secret_hash: ArrayBuffer;
        created_by: string;
        expires_at: number;
        state: string;
        used_pass_hash: ArrayBuffer | null;
        used_by: string | null;
      }>(
        "SELECT invite_id, secret_hash, created_by, expires_at, state, used_pass_hash, used_by FROM invites WHERE secret_hash = ?",
        blob(secretHash),
      )
      .toArray()[0];
    if (invite === undefined || !bytesEqual(new Uint8Array(invite.secret_hash), secretHash)) return null;
    if (!bytesEqual(new Uint8Array(grp.join_proof_hash), proofHash)) return null;
    return { grp, invite };
  }

  private async previewInvite(body: Record<string, unknown>): Promise<Response> {
    const found = await this.findInvite(body.token, body.joinProof);
    const now = this.now();
    if (found === null || found.invite.state !== "pending" || found.invite.expires_at <= now) {
      throw new ApiError("invite_not_found");
    }
    const { grp, invite } = found;
    const inviter = this.member(invite.created_by);
    return json({
      groupId: grp.group_id,
      status: grp.status,
      profile: b64(grp.profile),
      memberCount: this.memberCount(),
      inviter: inviter === null ? null : { memberId: inviter.member_id, profile: b64(inviter.profile) },
      expiresAt: invite.expires_at,
    });
  }

  private async redeemInvite(body: Record<string, unknown>): Promise<Response> {
    const cfg = this.conf;
    if (splitToken(body.token) === null || decode32(body.joinProof) === null) {
      throw new ApiError("bad_request", "Malformed token or joinProof.");
    }
    const memberId = parseId(body.memberId, "memberId");
    const pass = parse32(body.pass, "pass");
    const profile = parseEnvelope(body.profile, "memberProfile", "profile");
    const termsVersion = safeInt(body.termsVersion, "termsVersion");
    if (termsVersion < cfg.termsVersion) throw new ApiError("terms_outdated");
    const passHash = await sha256(pass);
    const found = await this.findInvite(body.token, body.joinProof);
    if (found === null) throw new ApiError("invite_not_found");
    const now = this.now();
    const { invite } = found;

    if (invite.state === "used") {
      // Retry: same pass, and that member still exists.
      if (invite.used_pass_hash !== null && bytesEqual(new Uint8Array(invite.used_pass_hash), passHash) && invite.used_by !== null) {
        const caller = this.callerFor(invite.used_by);
        if (caller !== null) return json(this.status(caller), 200);
      }
      throw new ApiError("invite_not_found");
    }

    this.ctx.storage.transactionSync(() => {
      const current = this.sql
        .exec<{ state: string; expires_at: number }>("SELECT state, expires_at FROM invites WHERE invite_id = ?", invite.invite_id)
        .toArray()[0];
      if (current === undefined || current.state !== "pending" || current.expires_at <= now) {
        throw new ApiError("invite_not_found");
      }
      if (this.member(memberId) !== null || this.removedAs("member", memberId)) throw new ApiError("member_exists");
      if (this.sql.exec("SELECT 1 FROM members WHERE pass_hash = ?", blob(passHash)).toArray().length > 0) {
        throw new ApiError("member_exists");
      }
      const seq = this.nextSeq();
      this.sql.exec(
        "INSERT INTO members (member_id, pass_hash, role, joined_at, profile, seq, terms_version) VALUES (?, ?, 'member', ?, ?, ?, ?)",
        memberId,
        blob(passHash),
        now,
        blob(profile),
        seq,
        termsVersion,
      );
      this.sql.exec(
        "UPDATE invites SET state = 'used', used_by = ?, used_pass_hash = ?, used_at = ? WHERE invite_id = ?",
        memberId,
        blob(passHash),
        now,
        invite.invite_id,
      );
    });
    this.scheduleFlush();
    return json(this.status({ memberId, role: "member", joinedAt: now }), 201);
  }

  // ───────────────────────────── members (E11–E17)

  private liveAtOf(memberId: string, stored: number | null): number | null {
    const entry = this.live.get(memberId);
    if (entry !== undefined && (stored === null || entry.at > stored)) return entry.at;
    return stored;
  }

  private listMembers(): Response {
    const members = this.sql
      .exec<MemberRow>("SELECT member_id, role, joined_at, profile, seq, last_live_at FROM members ORDER BY joined_at, member_id")
      .toArray()
      .map((m) => ({
        memberId: m.member_id,
        role: m.role,
        joinedAt: m.joined_at,
        profile: b64(m.profile),
        lastLiveAt: this.liveAtOf(m.member_id, m.last_live_at),
      }));
    return json({ members });
  }

  private setMemberProfile(caller: Caller, body: Record<string, unknown>): Response {
    const profile = parseEnvelope(body.profile, "memberProfile", "profile");
    const seq = this.ctx.storage.transactionSync(() => {
      const seq = this.nextSeq();
      this.sql.exec("UPDATE members SET profile = ?, seq = ? WHERE member_id = ?", blob(profile), seq, caller.memberId);
      return seq;
    });
    this.scheduleFlush();
    return json({ seq });
  }

  private setRole(caller: Caller, targetId: string, body: Record<string, unknown>): Response {
    const role = body.role;
    if (role !== "admin" && role !== "member") throw new ApiError("bad_request", "role must be admin or member.");
    this.requireRole(caller, "owner");
    if (targetId === caller.memberId) throw new ApiError("not_allowed");
    const target = this.member(targetId);
    if (target === null) throw new ApiError("not_found");
    if (target.role === "owner") throw new ApiError("not_allowed");
    if (target.role === role) return json({ seq: target.seq });
    const now = this.now();
    const seq = this.ctx.storage.transactionSync(() => {
      const seq = this.nextSeq();
      this.sql.exec("UPDATE members SET role = ?, seq = ? WHERE member_id = ?", role, seq, targetId);
      // SD23: an invite dies with its maker's admin role.
      if (role === "member") this.cancelInvitesBy(targetId, now);
      return seq;
    });
    this.scheduleFlush();
    return json({ seq });
  }

  private cancelInvitesBy(memberId: string, now: number): void {
    this.sql.exec(
      "UPDATE invites SET state = 'cancelled', used_at = ? WHERE created_by = ? AND state = 'pending'",
      now,
      memberId,
    );
  }

  private removeMember(caller: Caller, target: string): Response {
    if (target === "me" || target === caller.memberId) {
      if (caller.role === "owner") throw new ApiError(target === "me" ? "owner_must_hand_over" : "not_allowed");
      this.deleteMemberNow(caller.memberId, false);
      return noContent();
    }
    if (caller.role === "member") throw new ApiError("forbidden");
    const row = this.member(target);
    if (row === null) throw new ApiError("not_found");
    if (row.role === "owner") throw new ApiError(caller.role === "owner" ? "not_allowed" : "forbidden");
    if (caller.role === "admin" && row.role !== "member") throw new ApiError("forbidden");
    this.deleteMemberNow(target, true);
    return noContent();
  }

  /** E14 effects (leave or removal) and A4, in one transaction; then the member's sockets close. */
  private deleteMemberNow(memberId: string, removed: boolean): void {
    const grp = this.grp()!;
    const now = this.now();
    this.ctx.storage.transactionSync(() => {
      const row = this.sql
        .exec<{ pass_hash: ArrayBuffer }>("SELECT pass_hash FROM members WHERE member_id = ?", memberId)
        .toArray()[0];
      if (row === undefined) return;
      this.sql.exec("DELETE FROM chunks WHERE member_id = ?", memberId);
      this.sql.exec("DELETE FROM archives WHERE member_id = ?", memberId);
      this.sql.exec("DELETE FROM pins WHERE member_id = ?", memberId);
      this.sql.exec("DELETE FROM live WHERE member_id = ?", memberId);
      this.sql.exec("DELETE FROM members WHERE member_id = ?", memberId);
      this.sql.exec("INSERT OR REPLACE INTO r2_deletes (prefix, at) VALUES (?, ?)", memberPrefix(grp.group_id, memberId), now);
      this.jobs.set("r2_delete", now);
      const seq = this.nextSeq();
      this.sql.exec("INSERT INTO removals (seq, what, id, at) VALUES (?, 'member', ?, ?)", seq, memberId, now);
      if (this.sql.exec("DELETE FROM transfer WHERE to_member = ? RETURNING one", memberId).toArray().length > 0) {
        this.jobs.delete("transfer");
      }
      this.cancelInvitesBy(memberId, now);
      if (removed) {
        this.sql.exec("INSERT OR REPLACE INTO gone_passes (pass_hash, reason, at) VALUES (?, 'removed', ?)", row.pass_hash, now);
      }
    });
    this.live.delete(memberId);
    this.liveAcceptedMs.delete(memberId);
    this.rate.delete(memberId);
    for (const ws of this.ctx.getWebSockets(memberId)) {
      this.closeSocket(ws, removed ? 4403 : 1000, removed ? "removed" : "left");
    }
    this.setNextAlarm();
    this.scheduleFlush();
  }

  private offerOwnership(caller: Caller, body: Record<string, unknown>): Response {
    const to = parseId(body.to, "to");
    this.requireRole(caller, "owner");
    if (to === caller.memberId) throw new ApiError("not_allowed");
    if (this.member(to) === null) throw new ApiError("not_found");
    const now = this.now();
    const expiresAt = now + TRANSFER_LIFETIME;
    this.ctx.storage.transactionSync(() => {
      this.sql.exec(
        "INSERT OR REPLACE INTO transfer (one, to_member, offered_at, expires_at) VALUES (1, ?, ?, ?)",
        to,
        now,
        expiresAt,
      );
      this.jobs.set("transfer", expiresAt);
    });
    this.afterStatusChange();
    return json(this.status(caller));
  }

  private openOffer(now: number): { to_member: string; expires_at: number } | null {
    const row = this.sql
      .exec<{ to_member: string; expires_at: number }>("SELECT to_member, expires_at FROM transfer WHERE one = 1")
      .toArray()[0];
    return row !== undefined && row.expires_at > now ? row : null;
  }

  private cancelOffer(caller: Caller): Response {
    const offer = this.openOffer(this.now());
    if (offer === null) throw new ApiError("not_found");
    if (caller.role !== "owner" && caller.memberId !== offer.to_member) throw new ApiError("forbidden");
    this.ctx.storage.transactionSync(() => {
      this.sql.exec("DELETE FROM transfer");
      this.jobs.delete("transfer");
    });
    this.afterStatusChange();
    return noContent();
  }

  private async acceptOwnership(caller: Caller, body: Record<string, unknown>): Promise<Response> {
    const proof = this.parseProof(body.pro);
    const offer = this.openOffer(this.now());
    if (offer === null) throw new ApiError("not_found");
    if (offer.to_member !== caller.memberId) throw new ApiError("forbidden");
    const grp = this.grp()!;
    const pro = await this.attachProof(grp.group_id, proof);
    const now = this.now();
    const current = this.grp()!;
    if (current.status === "deleted") throw new ApiError("group_deleted");
    const still = this.openOffer(now);
    if (still === null || still.to_member !== caller.memberId || this.member(caller.memberId) === null) {
      // The offer went away while the store was asked; leave the new purchase as it was.
      if (current.pro_name !== pro.name) await this.detachFrom(pro.name, grp.group_id);
      throw new ApiError("not_found");
    }
    const oldName = current.pro_name;
    this.ctx.storage.transactionSync(() => {
      const oldOwner = this.ownerId();
      if (oldOwner !== "") this.sql.exec("UPDATE members SET role = 'admin', seq = ? WHERE member_id = ?", this.nextSeq(), oldOwner);
      this.sql.exec("UPDATE members SET role = 'owner', seq = ? WHERE member_id = ?", this.nextSeq(), caller.memberId);
      this.sql.exec("DELETE FROM transfer");
      this.jobs.delete("transfer");
      this.applyProStateTx(pro.name, pro.state, now);
    });
    if (oldName !== null && oldName !== pro.name) await this.detachFrom(oldName, grp.group_id);
    this.afterStatusChange();
    return json(this.status({ ...caller, role: "owner" }));
  }

  // ───────────────────────────── uploads (E18) and live

  /** E18 and the WebSocket `upload` (SPEC §6.5). Throws ApiError for a failure of the whole request. */
  private upload(caller: Caller, body: Record<string, unknown>): UploadResult {
    const grp = this.grp()!;
    const now = this.now();
    if (body.chunks !== undefined && !Array.isArray(body.chunks)) throw new ApiError("bad_request", "chunks must be an array.");
    const chunks = (body.chunks ?? []) as unknown[];
    if (chunks.length > MAX_CHUNKS_PER_UPLOAD) throw new ApiError("too_large", "At most 50 chunks per upload.");
    const parsed = chunks.map((c) => {
      if (!isObject(c) || typeof c.rec !== "string") throw new ApiError("bad_request", "Malformed chunk.");
      return { start: safeInt(c.start, "start"), rec: c.rec };
    });
    const hasLive = Object.hasOwn(body, "live");
    const live = body.live;
    let liveIn: { at: number; rec: string } | null | undefined;
    if (!hasLive || live === undefined) liveIn = undefined;
    else if (live === null) liveIn = null;
    else if (isObject(live) && typeof live.rec === "string") liveIn = { at: safeInt(live.at, "live.at"), rec: live.rec };
    else throw new ApiError("bad_request", "Malformed live.");

    if (grp.status === "paused" && !(parsed.length === 0 && liveIn === null)) throw new ApiError("group_paused");

    const pauses = this.sql
      .exec<{ paused_at: number; resumed_at: number | null }>("SELECT paused_at, resumed_at FROM pause_log")
      .toArray();
    const accepted: number[] = [];
    const rejected: Array<{ start: number; error: string }> = [];
    const store: Array<{ start: number; rec: Uint8Array }> = [];
    for (const c of parsed) {
      let error: string | null = null;
      const rec = decodeEnvelope(c.rec, "chunk");
      if (c.start % WINDOW !== 0) error = "bad_record";
      else if (c.start + WINDOW <= caller.joinedAt) error = "before_join";
      else if (c.start > now + CLOCK_AHEAD) error = "bad_time";
      else if (rec === null) error = "bad_record";
      else if (pauses.some((p) => p.paused_at <= c.start && c.start + WINDOW <= (p.resumed_at ?? Number.POSITIVE_INFINITY))) {
        error = "window_paused";
      }
      if (error !== null) rejected.push({ start: c.start, error });
      else {
        accepted.push(c.start);
        store.push({ start: c.start, rec: rec! });
      }
    }
    if (store.length > 0) {
      this.ctx.storage.transactionSync(() => {
        for (const c of store) {
          this.sql.exec(
            "INSERT OR REPLACE INTO chunks (member_id, start, rec, seq, updated_at) VALUES (?, ?, ?, ?, ?)",
            caller.memberId,
            c.start,
            blob(c.rec),
            this.nextSeq(),
            now,
          );
        }
      });
    }

    let liveAccepted = false;
    if (liveIn === null) {
      this.stopLive(caller.memberId, now);
      liveAccepted = true;
    } else if (liveIn !== undefined) {
      liveAccepted = this.acceptLive(caller.memberId, liveIn.at, liveIn.rec, now);
    }
    if (store.length > 0 || liveIn !== undefined) this.scheduleFlush();
    return { accepted, rejected, liveAccepted };
  }

  private loadLive(): void {
    for (const row of this.sql.exec<{ member_id: string; at: number; rec: ArrayBuffer }>("SELECT member_id, at, rec FROM live")) {
      this.live.set(row.member_id, { at: row.at, rec: new Uint8Array(row.rec), persistedAtMs: Date.now(), dirty: false });
    }
  }

  private acceptLive(memberId: string, at: number, recText: string, now: number): boolean {
    if (at < now - LIVE_MAX_AGE || at > now + CLOCK_AHEAD) return false;
    const rec = decodeEnvelope(recText, "live");
    if (rec === null) return false;
    const existing = this.live.get(memberId);
    if (existing !== undefined && at <= existing.at) return false;
    const nowMs = this.nowMs();
    const last = this.liveAcceptedMs.get(memberId);
    if (last !== undefined && nowMs - last < LIVE_MIN_INTERVAL_MS) return false;
    this.liveAcceptedMs.set(memberId, nowMs);
    const entry: LiveEntry = { at, rec, persistedAtMs: existing?.persistedAtMs ?? 0, dirty: true };
    this.live.set(memberId, entry);
    if (Date.now() - entry.persistedAtMs >= LIVE_PERSIST_INTERVAL_MS) this.persistLive(memberId, entry);
    else this.schedulePersist();
    this.pendingLive.push({ memberId, at, rec: recText });
    return true;
  }

  /** Live rows are written at most once per 60 s per member, so an evicted object is at most 60 s stale. */
  private persistLive(memberId: string, entry: LiveEntry): void {
    this.sql.exec("INSERT OR REPLACE INTO live (member_id, at, rec) VALUES (?, ?, ?)", memberId, entry.at, blob(entry.rec));
    this.sql.exec("UPDATE members SET last_live_at = ? WHERE member_id = ?", entry.at, memberId);
    entry.persistedAtMs = Date.now();
    entry.dirty = false;
  }

  private schedulePersist(): void {
    if (this.persistTimer !== null) return;
    let earliest = Number.POSITIVE_INFINITY;
    for (const entry of this.live.values()) {
      if (entry.dirty) earliest = Math.min(earliest, entry.persistedAtMs + LIVE_PERSIST_INTERVAL_MS);
    }
    if (earliest === Number.POSITIVE_INFINITY) return;
    this.persistTimer = setTimeout(() => {
      this.persistTimer = null;
      if (!this.hasSchema) return;
      const nowMs = Date.now();
      for (const [memberId, entry] of this.live) {
        if (entry.dirty && nowMs - entry.persistedAtMs >= LIVE_PERSIST_INTERVAL_MS && this.member(memberId) !== null) {
          this.persistLive(memberId, entry);
        }
      }
      this.schedulePersist();
    }, Math.max(0, earliest - Date.now()));
  }

  private stopLive(memberId: string, now: number): void {
    this.live.delete(memberId);
    this.sql.exec("DELETE FROM live WHERE member_id = ?", memberId);
    this.pendingLive.push({ memberId, at: now, rec: null });
  }

  private liveEntries(now: number): Array<{ memberId: string; at: number; rec: string }> {
    const out: Array<{ memberId: string; at: number; rec: string }> = [];
    for (const [memberId, entry] of this.live) {
      if (entry.at >= now - LIVE_VISIBLE) out.push({ memberId, at: entry.at, rec: b64urlEncode(entry.rec) });
    }
    return out.sort((a, b) => (a.memberId < b.memberId ? -1 : a.memberId > b.memberId ? 1 : 0));
  }

  private liveList(caller: Caller): Response {
    const now = this.now();
    return json({ live: this.liveEntries(now), watchers: this.watchers(caller.memberId), serverTime: now });
  }

  // ───────────────────────────── changes (E19), archive (E20), clear trail (E22)

  private changes(caller: Caller, url: URL): Response {
    const sinceText = url.searchParams.get("since");
    const limitText = url.searchParams.get("limit");
    const since = sinceText === null ? 0 : Number(sinceText);
    let limit = limitText === null ? CHANGES_DEFAULT_LIMIT : Number(limitText);
    if (!Number.isSafeInteger(since) || since < 0 || !/^\d+$/.test(sinceText ?? "0")) {
      throw new ApiError("bad_request", "since must be a non-negative integer.");
    }
    if (!Number.isSafeInteger(limit) || limit < 1 || !/^\d+$/.test(limitText ?? "1")) {
      throw new ApiError("bad_request", "limit must be a positive integer.");
    }
    limit = Math.min(limit, CHANGES_MAX_LIMIT);
    const grp = this.grp()!;
    const now = this.now();
    if (since < grp.min_cursor || since > grp.seq) return json({ reset: true, cursor: 0 });
    const page = readChanges(this.sql, { since, joinedAt: caller.joinedAt, now, limit, maxBytes: CHANGES_MAX_BYTES });
    return json({ items: page.items, cursor: page.cursor, more: page.more, serverTime: now });
  }

  private hasPendingDelete(groupId: string, memberId: string): boolean {
    return (
      this.sql
        .exec("SELECT 1 FROM r2_deletes WHERE prefix IN (?, ?) LIMIT 1", memberPrefix(groupId, memberId), groupPrefix(groupId))
        .toArray().length > 0
    );
  }

  private async archive(caller: Caller, memberId: string, month: string): Promise<Response> {
    if (!isMonth(month)) throw new ApiError("bad_request", "month must be YYYY-MM.");
    const grp = this.grp()!;
    const row = this.sql.exec("SELECT 1 FROM archives WHERE member_id = ? AND month = ?", memberId, month).toArray();
    if (row.length === 0 || this.hasPendingDelete(grp.group_id, memberId)) throw new ApiError("not_found");
    const object = await this.bucket.get(archiveKey(grp.group_id, memberId, month));
    if (object === null) throw new ApiError("not_found");
    const records = decodeArchive(new Uint8Array(await object.arrayBuffer())).filter((c) => c.start + WINDOW > caller.joinedAt);
    if (records.length === 0) throw new ApiError("not_found");
    return new Response(encodeArchive(records), {
      status: 200,
      headers: { "Content-Type": "application/octet-stream", "Cache-Control": "no-store" },
    });
  }

  private clearTrail(caller: Caller): Response {
    const grp = this.grp()!;
    const now = this.now();
    this.ctx.storage.transactionSync(() => {
      this.sql.exec("DELETE FROM chunks WHERE member_id = ?", caller.memberId);
      this.sql.exec("DELETE FROM archives WHERE member_id = ?", caller.memberId);
      this.sql.exec(
        "INSERT OR REPLACE INTO r2_deletes (prefix, at) VALUES (?, ?)",
        memberPrefix(grp.group_id, caller.memberId),
        now,
      );
      this.jobs.set("r2_delete", now);
      this.sql.exec("INSERT INTO removals (seq, what, id, at) VALUES (?, 'trail', ?, ?)", this.nextSeq(), caller.memberId, now);
    });
    this.setNextAlarm();
    this.scheduleFlush();
    return noContent();
  }

  // ───────────────────────────── pins (E23, E24)

  private dropPin(caller: Caller, body: Record<string, unknown>): Response {
    const pinId = parseId(body.pinId, "pinId");
    const createdAt = safeInt(body.createdAt, "createdAt");
    const expiresAt = body.expiresAt === null || body.expiresAt === undefined ? null : safeInt(body.expiresAt, "expiresAt");
    if (typeof body.rec !== "string") throw new ApiError("bad_request", "rec must be a string.");
    if (expiresAt !== null && expiresAt !== createdAt + ANIMAL_PIN_LIFETIME) {
      throw new ApiError("bad_record", "expiresAt must be null or createdAt + 43200.");
    }
    const rec = parseEnvelope(body.rec, "pin", "rec");

    const existing = this.sql
      .exec<{ member_id: string; seq: number }>("SELECT member_id, seq FROM pins WHERE pin_id = ?", pinId)
      .toArray()[0];
    if (existing !== undefined) {
      if (existing.member_id === caller.memberId) return json({ seq: existing.seq }, 200);
      throw new ApiError("pin_exists");
    }
    if (this.removedAs("pin", pinId)) throw new ApiError("pin_deleted");
    const grp = this.grp()!;
    if (grp.status === "paused") throw new ApiError("group_paused");
    const now = this.now();
    if (expiresAt !== null && expiresAt <= now) throw new ApiError("pin_expired");
    if (createdAt > now + CLOCK_AHEAD || (expiresAt === null && createdAt < now - PIN_MAX_AGE)) {
      throw new ApiError("bad_time");
    }
    const seq = this.ctx.storage.transactionSync(() => {
      const unexpired = this.sql
        .exec<{ n: number }>("SELECT COUNT(*) AS n FROM pins WHERE expires_at IS NULL OR expires_at > ?", now)
        .one().n;
      if (unexpired >= MAX_UNEXPIRED_PINS) throw new ApiError("rate_limited", "Too many pins in this group.", HOUR);
      this.countOrLimit(`pins:${caller.memberId}`, HOUR, MAX_PINS_PER_MEMBER_HOUR, now);
      const seq = this.nextSeq();
      this.sql.exec(
        "INSERT INTO pins (pin_id, member_id, created_at, expires_at, rec, seq) VALUES (?, ?, ?, ?, ?, ?)",
        pinId,
        caller.memberId,
        createdAt,
        expiresAt,
        blob(rec),
        seq,
      );
      return seq;
    });
    if (expiresAt !== null) this.setNextAlarm();
    this.scheduleFlush();
    return json({ seq }, 201);
  }

  private deletePin(caller: Caller, pinId: string): Response {
    const pin = this.sql.exec<{ member_id: string }>("SELECT member_id FROM pins WHERE pin_id = ?", pinId).toArray()[0];
    if (pin === undefined) throw new ApiError("not_found");
    if (caller.role === "member" && pin.member_id !== caller.memberId) throw new ApiError("forbidden");
    this.deletePinNow(pinId);
    return noContent();
  }

  private deletePinNow(pinId: string): boolean {
    const now = this.now();
    const deleted = this.ctx.storage.transactionSync(() => {
      if (this.sql.exec("DELETE FROM pins WHERE pin_id = ? RETURNING pin_id", pinId).toArray().length === 0) return false;
      this.sql.exec("INSERT INTO removals (seq, what, id, at) VALUES (?, 'pin', ?, ?)", this.nextSeq(), pinId, now);
      return true;
    });
    if (deleted) {
      this.setNextAlarm();
      this.scheduleFlush();
    }
    return deleted;
  }

  // ───────────────────────────── reports (E25)

  private async report(caller: Caller, body: Record<string, unknown>): Promise<Response> {
    const grp = this.grp()!;
    const kind = body.targetKind;
    if (kind !== "pin" && kind !== "member" && kind !== "group") throw new ApiError("bad_request", "Unknown targetKind.");
    const targetId = parseId(body.targetId, "targetId");
    const reason = body.reason;
    if (typeof reason !== "string" || !REPORT_REASONS.has(reason)) throw new ApiError("bad_request", "Unknown reason.");
    const comment = body.comment === undefined || body.comment === null ? "" : body.comment;
    if (typeof comment !== "string" || !textWithin(comment, REPORT_TEXT.comment)) {
      throw new ApiError("bad_request", "Malformed comment.");
    }
    const content = this.reportContent(kind, body.content);

    const now = this.now();
    let facts: NewReport["facts"];
    if (kind === "group") {
      if (targetId !== grp.group_id) throw new ApiError("not_found");
      facts = { targetExists: true, targetMemberId: null, targetTime: null, targetBytes: grp.profile?.byteLength ?? null };
    } else if (kind === "pin") {
      const pin = this.sql
        .exec<{ member_id: string; created_at: number; rec: ArrayBuffer }>(
          "SELECT member_id, created_at, rec FROM pins WHERE pin_id = ?",
          targetId,
        )
        .toArray()[0];
      if (pin !== undefined) {
        facts = { targetExists: true, targetMemberId: pin.member_id, targetTime: pin.created_at, targetBytes: pin.rec.byteLength };
      } else if (this.removedAs("pin", targetId)) {
        facts = { targetExists: false, targetMemberId: null, targetTime: null, targetBytes: null };
      } else {
        throw new ApiError("not_found");
      }
    } else {
      const target = this.member(targetId);
      if (target !== null) {
        facts = { targetExists: true, targetMemberId: targetId, targetTime: target.joined_at, targetBytes: target.profile.byteLength };
      } else if (this.removedAs("member", targetId)) {
        facts = { targetExists: false, targetMemberId: targetId, targetTime: null, targetBytes: null };
      } else {
        throw new ApiError("not_found");
      }
    }

    this.ctx.storage.transactionSync(() => this.countOrLimit(`reports:${caller.memberId}`, DAY, MAX_REPORTS_PER_MEMBER_DAY, now));
    const report: NewReport = {
      reportId: b64urlEncode(this.random(16)),
      createdAt: now,
      groupId: grp.group_id,
      reporterId: caller.memberId,
      targetKind: kind satisfies ReportTargetKind,
      targetId,
      reason: reason as ReportReason,
      comment,
      content,
      facts,
    };
    await this.env.ADMIN.get(this.env.ADMIN.idFromName("admin")).addReport(report);
    return json({ reportId: report.reportId }, 202);
  }

  /** Keeps only the keys allowed for the target kind, each a string within its limits (SPEC §6.5 E25). */
  private reportContent(kind: ReportTargetKind, value: unknown): Record<string, string> {
    if (value === undefined || value === null) return {};
    if (!isObject(value)) throw new ApiError("bad_request", "content must be an object.");
    const allowed: Record<string, readonly [number, number]> =
      kind === "pin"
        ? { type: REPORT_TEXT.type, animal: REPORT_TEXT.animal, note: REPORT_TEXT.note, name: REPORT_TEXT.memberName }
        : kind === "member"
          ? { name: REPORT_TEXT.memberName }
          : { name: REPORT_TEXT.groupName };
    const out: Record<string, string> = {};
    for (const [key, limits] of Object.entries(allowed)) {
      const v = value[key];
      if (v === undefined || v === null) continue;
      if (typeof v !== "string" || !textWithin(v, limits)) throw new ApiError("bad_request", `Malformed content.${key}.`);
      out[key] = v;
    }
    return out;
  }

  // ───────────────────────────── WebSocket (SPEC §6.6)

  private attachment(ws: WebSocket): Attachment | null {
    try {
      return ws.deserializeAttachment() as Attachment | null;
    } catch {
      return null;
    }
  }

  private closeSocket(ws: WebSocket, code: number, reason: string): void {
    try {
      ws.close(code, reason);
    } catch {
      // already closed
    }
  }

  private send(ws: WebSocket, message: unknown): void {
    const text = JSON.stringify(message);
    if (text.length > WS_OUT_LIMIT) {
      logLine({ t: "ws", err: "frame too large" });
      return;
    }
    try {
      ws.send(text);
    } catch {
      // the socket closed meanwhile
    }
  }

  private openSocket(request: Request, caller: Caller): Response {
    if ((request.headers.get("Upgrade") ?? "").toLowerCase() !== "websocket") throw new ApiError("upgrade_required");
    const existing = this.ctx
      .getWebSockets(caller.memberId)
      .map((ws) => ({ ws, att: this.attachment(ws) }))
      .sort((a, b) => (a.att?.openedAt ?? 0) - (b.att?.openedAt ?? 0));
    for (let i = 0; i <= existing.length - WS_MAX_SOCKETS; i++) this.closeSocket(existing[i]!.ws, 4409, "replaced");
    const pair = new WebSocketPair();
    const server = pair[1];
    this.ctx.acceptWebSocket(server, [caller.memberId]);
    const att: Attachment = { memberId: caller.memberId, watching: false, cursor: 0, helloAt: 0, openedAt: Date.now(), w: -1, st: "", more: false };
    server.serializeAttachment(att);
    this.scheduleHelloCheck();
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  private scheduleHelloCheck(): void {
    if (this.helloTimer !== null) return;
    this.helloTimer = setTimeout(() => {
      this.helloTimer = null;
      this.sweepHello();
    }, this.helloTimeoutMs + 20);
  }

  /** Closes sockets that sent no `hello` within 10 s (4408). */
  private sweepHello(): void {
    const nowMs = Date.now();
    let waiting = false;
    for (const ws of this.ctx.getWebSockets()) {
      const att = this.attachment(ws);
      if (att === null || att.helloAt !== 0) continue;
      if (nowMs - att.openedAt >= this.helloTimeoutMs) this.closeSocket(ws, 4408, "no hello");
      else waiting = true;
    }
    if (waiting) this.scheduleHelloCheck();
  }

  private watchingMembers(): Set<string> {
    const out = new Set<string>();
    for (const ws of this.ctx.getWebSockets()) {
      const att = this.attachment(ws);
      if (att !== null && att.watching && att.helloAt !== 0) out.add(att.memberId);
    }
    return out;
  }

  /** Distinct members other than `memberId` with a watching socket. */
  private watchers(memberId: string): number {
    const set = this.watchingMembers();
    return set.size - (set.has(memberId) ? 1 : 0);
  }

  override async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    this.sweepHello();
    const att = this.attachment(ws);
    if (att === null) return this.closeSocket(ws, 4400, "protocol error");
    const grp = this.grp();
    if (grp === null || grp.status === "deleted") return this.closeSocket(ws, 4404, "group deleted");
    const row = this.member(att.memberId);
    if (row === null) return this.closeSocket(ws, 4401, "pass no longer valid");
    if (typeof message !== "string") return this.closeSocket(ws, 4400, "binary frame");
    if (message.length > WS_IN_LIMIT) {
      this.send(ws, { type: "error", error: "too_large", message: "Frame too large." });
      return;
    }
    if (!this.rateLimit(att.memberId)) return this.closeSocket(ws, 4429, "rate limited");
    let msg: unknown;
    try {
      msg = JSON.parse(message);
    } catch {
      msg = null;
    }
    if (!isObject(msg) || typeof msg.type !== "string") {
      if (att.helloAt === 0) return this.closeSocket(ws, 4400, "protocol error");
      this.send(ws, { type: "error", error: "bad_request", message: "Malformed message." });
      return;
    }
    if (att.helloAt === 0 && msg.type !== "hello") return this.closeSocket(ws, 4400, "hello first");
    const caller: Caller = { memberId: att.memberId, role: row.role, joinedAt: row.joined_at };
    switch (msg.type) {
      case "hello": {
        if (msg.v !== 1 || typeof msg.watching !== "boolean" || typeof msg.cursor !== "number" || !Number.isSafeInteger(msg.cursor) || msg.cursor < 0) {
          return this.closeSocket(ws, 4400, "bad hello");
        }
        const watchingChanged = att.helloAt === 0 ? msg.watching : msg.watching !== att.watching;
        att.helloAt = Date.now();
        att.watching = msg.watching;
        att.cursor = msg.cursor;
        att.more = false;
        const now = this.now();
        const status = this.status(caller);
        att.w = status.watchers;
        att.st = this.statusFingerprint(status);
        ws.serializeAttachment(att);
        this.send(ws, { type: "welcome", v: 1, status, live: this.liveEntries(now) });
        if (watchingChanged) this.scheduleFlush();
        return;
      }
      case "watching": {
        if (typeof msg.on !== "boolean") {
          this.send(ws, { type: "error", error: "bad_request", message: "on must be a boolean." });
          return;
        }
        if (msg.on !== att.watching) {
          att.watching = msg.on;
          ws.serializeAttachment(att);
          this.scheduleFlush();
        }
        return;
      }
      case "upload": {
        const id = msg.id;
        if (typeof id !== "number" || !Number.isSafeInteger(id)) {
          this.send(ws, { type: "error", error: "bad_request", message: "upload needs an integer id." });
          return;
        }
        try {
          const result = this.upload(caller, msg);
          this.send(ws, { type: "uploaded", id, ...result });
        } catch (err) {
          if (err instanceof ApiError) this.send(ws, { type: "error", id, error: err.code, message: err.message });
          else {
            logException("GroupDO ws upload", err);
            this.send(ws, { type: "error", id, error: "internal", message: "Internal error." });
          }
        }
        return;
      }
      case "ack": {
        const cursor = msg.cursor;
        if (typeof cursor !== "number" || !Number.isSafeInteger(cursor) || cursor < 0) {
          this.send(ws, { type: "error", error: "bad_request", message: "ack needs a cursor." });
          return;
        }
        att.cursor = cursor;
        att.more = false;
        ws.serializeAttachment(att);
        if (cursor < grp.seq) this.scheduleFlush();
        return;
      }
      default:
        this.send(ws, { type: "error", error: "bad_request", message: "Unknown message type." });
    }
  }

  override async webSocketClose(ws: WebSocket, _code: number, _reason: string, _wasClean: boolean): Promise<void> {
    const att = this.attachment(ws);
    if (att !== null && att.watching) this.scheduleFlush();
  }

  override async webSocketError(ws: WebSocket, _error: unknown): Promise<void> {
    const att = this.attachment(ws);
    if (att !== null && att.watching) this.scheduleFlush();
  }

  private statusFingerprint(status: ReturnType<GroupDO["status"]>): string {
    const { seq: _seq, serverTime: _serverTime, watchers: _watchers, ...rest } = status;
    return fingerprint(JSON.stringify(rest));
  }

  /** One flush 2 s after the first change; changes inside those 2 s go out together (SPEC §6.6 Ticks). */
  private scheduleFlush(): void {
    if (this.flushTimer !== null) return;
    if (this.ctx.getWebSockets().length === 0) {
      this.pendingLive = [];
      return;
    }
    this.flushTimer = setTimeout(() => {
      this.flushTimer = null;
      try {
        this.flush();
      } catch (err) {
        logException("GroupDO flush", err);
      }
    }, this.tickDelayMs);
  }

  /** Sends each socket that has news one `tick` with only the keys that have news. */
  flush(): void {
    const live = this.pendingLive;
    this.pendingLive = [];
    const grp = this.grp();
    if (grp === null || grp.status === "deleted") return;
    const sockets = this.ctx.getWebSockets();
    if (sockets.length === 0) return;
    const now = this.now();
    const shared = this.statusShared(now);
    const members = new Map(
      this.sql
        .exec<{ member_id: string; role: Role; joined_at: number }>("SELECT member_id, role, joined_at FROM members")
        .toArray()
        .map((m) => [m.member_id, m]),
    );
    // Sockets usually share a cursor; cache each page by (cursor, joinedAt), never by cursor alone.
    const pages = new Map<string, ChangesPage | "reset">();
    for (const ws of sockets) {
      const att = this.attachment(ws);
      if (att === null || att.helloAt === 0) continue;
      const m = members.get(att.memberId);
      if (m === undefined) continue;
      const caller: Caller = { memberId: att.memberId, role: m.role, joinedAt: m.joined_at };
      const tick: Record<string, unknown> = { type: "tick", serverTime: now };
      let news = false;
      if (live.length > 0) {
        tick.live = live;
        news = true;
      }
      if (!att.more && att.cursor < grp.seq) {
        const key = `${att.cursor}:${m.joined_at}`;
        let page = pages.get(key);
        if (page === undefined) {
          page =
            att.cursor < grp.min_cursor
              ? "reset"
              : readChanges(this.sql, { since: att.cursor, joinedAt: m.joined_at, now, limit: TICK_MAX_ITEMS, maxBytes: TICK_MAX_BYTES });
          pages.set(key, page);
        }
        if (page === "reset") {
          // Below minCursor: the client must pull over HTTP, where E19 answers reset.
          tick.changes = { from: att.cursor, items: [], cursor: att.cursor, more: true };
          att.more = true;
          news = true;
        } else if (page.items.length > 0 || page.more) {
          tick.changes = { from: att.cursor, items: page.items, cursor: page.cursor, more: page.more };
          att.cursor = page.cursor;
          att.more = page.more;
          news = true;
        }
      }
      const status = this.statusFrom(shared, caller, now);
      if (status.watchers !== att.w) {
        tick.watchers = status.watchers;
        att.w = status.watchers;
        news = true;
      }
      const st = this.statusFingerprint(status);
      if (st !== att.st) {
        tick.status = status;
        att.st = st;
        news = true;
      }
      if (!news) continue;
      ws.serializeAttachment(att);
      this.send(ws, tick);
    }
  }

  // ───────────────────────────── alarms (SPEC §7.4)

  override async alarm(): Promise<void> {
    if (!this.hasSchema) return;
    const now = this.now();
    let purged = false;
    try {
      this.expirePins(now);
      await runDueJobs(
        this.jobs,
        now,
        async (job) => {
          purged = (await this.runJob(job, now)) || purged;
        },
        () => purged,
      );
    } catch (err) {
      logLine({ t: "job", kind: "alarm", err: errorText(err) });
    } finally {
      if (!purged && this.hasSchema) this.setNextAlarm();
    }
  }

  private expirePins(now: number): void {
    const expired = this.sql
      .exec<{ pin_id: string }>("SELECT pin_id FROM pins WHERE expires_at IS NOT NULL AND expires_at <= ?", now)
      .toArray();
    if (expired.length === 0) return;
    this.ctx.storage.transactionSync(() => {
      for (const { pin_id } of expired) {
        this.sql.exec("DELETE FROM pins WHERE pin_id = ?", pin_id);
        this.sql.exec("INSERT INTO removals (seq, what, id, at) VALUES (?, 'pin', ?, ?)", this.nextSeq(), pin_id, now);
      }
    });
    this.scheduleFlush();
  }

  /** Runs one due job; returns true when the group's storage was deleted (purge). */
  private async runJob(job: Job, now: number): Promise<boolean> {
    const jobs = this.jobs;
    switch (job.kind) {
      case "purge":
        return this.purge();
      case "r2_delete":
        await this.r2Delete();
        jobs.done("r2_delete", null);
        return false;
      case "pro_check":
        await this.proCheck(now);
        return false;
      case "pro_retry":
        await this.proRetry(now);
        return false;
      case "transfer": {
        this.ctx.storage.transactionSync(() => {
          this.sql.exec("DELETE FROM transfer WHERE expires_at <= ?", now);
          jobs.done("transfer", null);
        });
        this.scheduleFlush();
        return false;
      }
      case "daily":
        await this.daily(now);
        return false;
      case "archive_more": {
        const more = await this.archivePass(now);
        jobs.done("archive_more", more ? now + MINUTE : null);
        return false;
      }
      default:
        jobs.done(job.kind, null);
        return false;
    }
  }

  /** Empties every queued R2 prefix; a row goes only when a list of its prefix comes back empty (SPEC §7.5). */
  private async r2Delete(): Promise<void> {
    const rows = this.sql.exec<{ prefix: string }>("SELECT prefix FROM r2_deletes ORDER BY at").toArray();
    for (const { prefix } of rows) {
      await this.emptyPrefix(prefix);
      this.sql.exec("DELETE FROM r2_deletes WHERE prefix = ?", prefix);
    }
  }

  private async emptyPrefix(prefix: string): Promise<void> {
    for (;;) {
      const list = await this.bucket.list({ prefix, limit: R2_DELETE_BATCH });
      if (list.objects.length === 0) return;
      await this.bucket.delete(list.objects.map((o) => o.key));
    }
  }

  /** 30 days after a pause or a deletion: R2 first, then the ProDO, then all storage (R8). */
  private async purge(): Promise<boolean> {
    const grp = this.grp();
    if (grp === null) return false;
    await this.emptyPrefix(groupPrefix(grp.group_id));
    if (grp.pro_name !== null) await this.proStub(grp.pro_name).detach(grp.group_id);
    for (const ws of this.ctx.getWebSockets()) this.closeSocket(ws, 4404, "group deleted");
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
    this.hasSchema = false;
    this.live.clear();
    this.pendingLive = [];
    this.liveAcceptedMs.clear();
    return true;
  }

  private async proCheck(now: number): Promise<void> {
    const grp = this.grp()!;
    if (grp.pro_name === null || grp.status === "deleted") {
      this.jobs.done("pro_check", null);
      return;
    }
    const name = grp.pro_name;
    const result = await this.proStub(name).check(grp.group_id);
    const current = this.grp()!;
    if (current.pro_name !== name) {
      this.jobs.done("pro_check", null);
      return;
    }
    this.ctx.storage.transactionSync(() => {
      if (result.unknown) {
        // SD8: the renewal couldn't be confirmed; pause and keep checking.
        this.jobs.done("pro_check", null);
        if (current.status === "active") this.pauseTx("pro_unverified", now);
        this.jobs.set("pro_retry", now + 30 * MINUTE);
        return;
      }
      const state = result.state;
      if (state.active && state.expiresAt !== null && state.expiresAt <= now + 60) {
        // Google at the renewal instant still says ACTIVE with the old expiryTime: check again in 10 min.
        const tries = this.sql
          .exec<{ count: number }>("SELECT count FROM counters WHERE key = ?", PRO_RECHECK_KEY)
          .toArray()[0]?.count ?? 0;
        this.sql.exec(
          "UPDATE grp SET pro_product = ?, pro_expires_at = ? WHERE one = 1",
          state.productId,
          state.expiresAt,
        );
        if (tries < PRO_RECHECK_LIMIT) {
          this.sql.exec(
            "INSERT INTO counters (key, window_start, count) VALUES (?, 0, ?) ON CONFLICT (key) DO UPDATE SET count = excluded.count",
            PRO_RECHECK_KEY,
            tries + 1,
          );
          this.jobs.done("pro_check", now + PRO_RECHECK_DELAY);
        } else {
          this.sql.exec("DELETE FROM counters WHERE key = ?", PRO_RECHECK_KEY);
          this.jobs.done("pro_check", null);
          if (this.grp()!.status === "active") this.pauseTx("pro_unverified", now);
          this.jobs.set("pro_retry", now + 30 * MINUTE);
        }
        return;
      }
      this.jobs.done("pro_check", null);
      this.applyProStateTx(name, state, now);
    });
    this.afterStatusChange();
  }

  /** Every 30 min for 24 h after a pro_unverified pause, then every 6 h; an active answer resumes. */
  private async proRetry(now: number): Promise<void> {
    const grp = this.grp()!;
    if (grp.pro_name === null || grp.status !== "paused" || grp.paused_reason !== "pro_unverified") {
      this.jobs.done("pro_retry", null);
      return;
    }
    const name = grp.pro_name;
    const result = await this.proStub(name).check(grp.group_id);
    this.ctx.storage.transactionSync(() => {
      const current = this.grp()!;
      if (current.pro_name !== name || current.status !== "paused" || current.paused_reason !== "pro_unverified") {
        this.jobs.done("pro_retry", null);
        return;
      }
      if (result.unknown || (result.state.active && result.state.expiresAt !== null && result.state.expiresAt <= now + 60)) {
        const pausedAt = current.paused_at ?? now;
        this.jobs.done("pro_retry", now + (now - pausedAt < DAY ? 30 * MINUTE : 6 * HOUR));
        return;
      }
      this.jobs.done("pro_retry", null);
      this.applyProStateTx(name, result.state, now);
    });
    this.afterStatusChange();
  }

  private async daily(now: number): Promise<void> {
    const grp = this.grp()!;
    const more = await this.archivePass(now);
    if (more) this.jobs.set("archive_more", now + MINUTE);
    this.ctx.storage.transactionSync(() => {
      const pruned = this.sql
        .exec<{ seq: number | null }>("DELETE FROM removals WHERE at < ? RETURNING seq", now - REMOVALS_RETENTION)
        .toArray();
      const maxSeq = pruned.reduce((m, r) => Math.max(m, r.seq ?? 0), 0);
      if (maxSeq > 0) this.sql.exec("UPDATE grp SET min_cursor = MAX(min_cursor, ?) WHERE one = 1", maxSeq);
      this.sql.exec("DELETE FROM gone_passes WHERE at < ?", now - GONE_PASS_RETENTION);
      this.sql.exec(
        "DELETE FROM invites WHERE (state != 'pending' AND COALESCE(used_at, expires_at) < ?) OR (state = 'pending' AND expires_at < ?)",
        now - INVITE_RETENTION,
        now - INVITE_RETENTION,
      );
      this.sql.exec("DELETE FROM pause_log WHERE resumed_at IS NOT NULL AND resumed_at < ?", now - PAUSE_LOG_RETENTION);
      this.sql.exec("DELETE FROM live WHERE at < ?", now - LIVE_ROW_RETENTION);
      this.sql.exec("UPDATE members SET last_live_at = NULL WHERE last_live_at < ?", now - LIVE_ROW_RETENTION);
      this.sql.exec("DELETE FROM counters WHERE key != ? AND window_start < ?", PRO_RECHECK_KEY, now - 2 * DAY);
      let next = this.jobs.get("daily")?.at ?? now;
      while (next <= now) next += DAY;
      this.jobs.done("daily", next);
    });
    for (const [memberId, entry] of this.live) if (entry.at < now - LIVE_ROW_RETENTION) this.live.delete(memberId);
    // Lifetime purchases have no expiry alarm: ask the store on the 1st of each month.
    if (grp.pro_name !== null && grp.pro_expires_at === null && grp.status !== "deleted" && dayOfMonth(now) === 1) {
      const result = await this.proStub(grp.pro_name).check(grp.group_id);
      if (!result.unknown && this.grp()?.pro_name === grp.pro_name) {
        this.ctx.storage.transactionSync(() => this.applyProStateTx(grp.pro_name!, result.state, now));
        this.afterStatusChange();
      }
    }
  }

  /**
   * The archive pass (SPEC §7.5): chunks older than 30 days move to R2, at most 50 member-months per pass. R2 calls
   * let other requests run in between, so the final transaction re-checks. Returns true when it hit the batch limit.
   */
  async archivePass(now: number): Promise<boolean> {
    const grp = this.grp()!;
    const groupId = grp.group_id;
    const cutoff = now - ARCHIVE_AGE;
    const pending = new Set(this.sql.exec<{ prefix: string }>("SELECT prefix FROM r2_deletes").toArray().map((r) => r.prefix));
    if (pending.has(groupPrefix(groupId))) return false;
    const pairs = this.sql
      .exec<{ member_id: string; month: string }>(
        "SELECT member_id, strftime('%Y-%m', start, 'unixepoch') AS month FROM chunks WHERE start < ? GROUP BY member_id, month ORDER BY member_id, month",
        cutoff,
      )
      .toArray()
      .filter((p) => !pending.has(memberPrefix(groupId, p.member_id)));
    const batch = pairs.slice(0, ARCHIVE_BATCH);
    for (const { member_id: memberId, month } of batch) {
      const [monthStart, monthEnd] = monthRange(month);
      const startSeq = this.grp()!.seq;
      const rows = this.sql
        .exec<{ start: number; seq: number; rec: ArrayBuffer }>(
          "SELECT start, seq, rec FROM chunks WHERE member_id = ? AND start >= ? AND start < ? AND start < ? ORDER BY start",
          memberId,
          monthStart,
          monthEnd,
          cutoff,
        )
        .toArray();
      if (rows.length === 0) continue;
      const key = archiveKey(groupId, memberId, month);
      const existing = await this.bucket.get(key);
      const merged = new Map<number, Uint8Array>();
      if (existing !== null) {
        for (const c of decodeArchive(new Uint8Array(await existing.arrayBuffer()))) merged.set(c.start, c.rec);
      }
      for (const row of rows) merged.set(row.start, new Uint8Array(row.rec));
      const records: ArchivedChunk[] = [...merged].map(([start, rec]) => ({ start, rec }));
      const object = encodeArchive(records);
      await this.bucket.put(key, object);
      const archivedAt = this.now();
      this.ctx.storage.transactionSync(() => {
        const gone =
          this.member(memberId) === null ||
          this.sql
            .exec("SELECT 1 FROM removals WHERE what IN ('trail', 'member') AND id = ? AND seq > ? LIMIT 1", memberId, startSeq)
            .toArray().length > 0;
        if (gone) {
          this.sql.exec("INSERT OR REPLACE INTO r2_deletes (prefix, at) VALUES (?, ?)", memberPrefix(groupId, memberId), archivedAt);
          this.jobs.set("r2_delete", archivedAt);
          return;
        }
        for (const row of rows) {
          this.sql.exec("DELETE FROM chunks WHERE member_id = ? AND start = ? AND seq = ?", memberId, row.start, row.seq);
        }
        const previous = this.sql
          .exec<{ seq: number; max_chunk_seq: number }>(
            "SELECT seq, max_chunk_seq FROM archives WHERE member_id = ? AND month = ?",
            memberId,
            month,
          )
          .toArray()[0];
        const maxChunkSeq = Math.max(previous?.max_chunk_seq ?? 0, previous?.seq ?? 0, ...rows.map((r) => r.seq));
        const starts = records.map((r) => r.start).sort((a, b) => a - b);
        this.sql.exec(
          `INSERT OR REPLACE INTO archives (member_id, month, count, bytes, first_start, last_start, seq, max_chunk_seq, archived_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          memberId,
          month,
          records.length,
          object.byteLength,
          starts[0]!,
          starts[starts.length - 1]!,
          this.nextSeq(),
          maxChunkSeq,
          archivedAt,
        );
      });
    }
    this.setNextAlarm();
    this.scheduleFlush();
    return pairs.length > batch.length;
  }

  // ───────────────────────────── admin RPC (A3–A6, SPEC §7.7)

  private adminGroup(): GrpRow | AdminResult {
    const grp = this.grp();
    if (grp === null) return { status: 404, error: "group_not_found" };
    if (grp.status === "deleted") return { status: 410, error: "group_deleted" };
    return grp;
  }

  async adminDeletePin(pinId: string): Promise<AdminResult> {
    const grp = this.adminGroup();
    if ("status" in grp && typeof grp.status === "number") return grp;
    return this.deletePinNow(pinId) ? { status: 204 } : { status: 404, error: "not_found" };
  }

  async adminRemoveMember(memberId: string): Promise<AdminResult> {
    const grp = this.adminGroup();
    if ("status" in grp && typeof grp.status === "number") return grp;
    const row = this.member(memberId);
    if (row === null) return { status: 404, error: "not_found" };
    if (row.role === "owner") return { status: 409, error: "not_allowed" };
    this.deleteMemberNow(memberId, true);
    return { status: 204 };
  }

  async adminDeleteGroup(): Promise<AdminResult> {
    const grp = this.adminGroup();
    if ("status" in grp && typeof grp.status === "number") return grp;
    await this.deleteGroupNow();
    return { status: 204 };
  }

  async adminInfo(): Promise<AdminResult> {
    const grp = this.grp();
    if (grp === null) return { status: 404, error: "group_not_found" };
    return {
      status: 200,
      body: {
        status: grp.status,
        createdAt: grp.created_at,
        pausedAt: grp.paused_at,
        memberCount: grp.status === "deleted" ? 0 : this.memberCount(),
        databaseBytes: this.ctx.storage.sql.databaseSize,
        pro: grp.pro_name === null || grp.pro_platform === null
          ? null
          : { platform: grp.pro_platform, productId: grp.pro_product, expiresAt: grp.pro_expires_at },
      },
    };
  }
}

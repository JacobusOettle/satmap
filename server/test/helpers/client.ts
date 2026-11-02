// A small API client for the tests: requests through the Worker (`exports.default.fetch`), test groups and members,
// and the Durable Object test hooks (clock, store fetch, environment) through runInDurableObject.

import { env, exports } from "cloudflare:workers";
import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import type { AdminDO } from "../../src/admin/AdminDO";
import type { GroupDO } from "../../src/group/GroupDO";
import { b64urlEncode } from "../../src/lib/b64url";
import type { ProDO } from "../../src/pro/ProDO";
import { appleProof, signedTransaction } from "./apple-test-chain";
import { envelopeOf } from "./crypto";

export const BASE = "https://example.invalid";
/** A fixed "now" in the future, so alarms set at test times never fire on their own (tests run them). */
export const T0 = 1_900_000_000;

export interface ApiResult {
  status: number;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  body: any;
  headers: Headers;
  text: string;
}

let ipCounter = 0;

/** A different client IP per request by default, so RL_CREATE and RL_INVITE don't trip unrelated tests. */
export function freshIp(): string {
  ipCounter += 1;
  return `10.${(ipCounter >> 16) & 255}.${(ipCounter >> 8) & 255}.${ipCounter & 255}`;
}

export async function api(
  method: string,
  path: string,
  opts: { pass?: string; body?: unknown; headers?: Record<string, string>; ip?: string; rawBody?: BodyInit } = {},
): Promise<ApiResult> {
  const headers: Record<string, string> = { "CF-Connecting-IP": opts.ip ?? freshIp(), ...opts.headers };
  if (opts.pass !== undefined) headers.Authorization = `Bearer ${opts.pass}`;
  let body: BodyInit | undefined = opts.rawBody;
  if (opts.body !== undefined) {
    headers["Content-Type"] ??= "application/json";
    body = JSON.stringify(opts.body);
  }
  const response = await exports.default.fetch(new Request(BASE + path, { method, headers, body }));
  const text = await response.text();
  let parsed: unknown = null;
  if ((response.headers.get("Content-Type") ?? "").startsWith("application/json") && text !== "") parsed = JSON.parse(text);
  return { status: response.status, body: parsed, headers: response.headers, text };
}

export function randomBytes(n: number): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(n));
}

export function randomId(): string {
  return b64urlEncode(randomBytes(16));
}

export function randomSecret(): string {
  return b64urlEncode(randomBytes(32));
}

export function groupStub(groupId: string): DurableObjectStub<GroupDO> {
  return env.GROUP.get(env.GROUP.idFromName(groupId));
}

export function proStub(name: string): DurableObjectStub<ProDO> {
  return env.PRO.get(env.PRO.idFromName(name));
}

export function adminStub(): DurableObjectStub<AdminDO> {
  return env.ADMIN.get(env.ADMIN.idFromName("admin"));
}

export function inGroup<T>(groupId: string, fn: (group: GroupDO, state: DurableObjectState) => T | Promise<T>): Promise<T> {
  return runInDurableObject(groupStub(groupId), fn);
}

export function inPro<T>(name: string, fn: (pro: ProDO, state: DurableObjectState) => T | Promise<T>): Promise<T> {
  return runInDurableObject(proStub(name), fn);
}

export async function setClock(groupId: string, t: number): Promise<void> {
  await inGroup(groupId, (g) => {
    g.clockOverride = t;
  });
}

export async function setProClock(name: string, t: number): Promise<void> {
  await inPro(name, (p) => {
    p.clockOverride = t;
  });
}

export async function runAlarm(groupId: string): Promise<boolean> {
  return runDurableObjectAlarm(groupStub(groupId));
}

/** Rows of a group's SQLite table, for assertions. */
export function rows<T = Record<string, unknown>>(groupId: string, query: string, ...args: SqlStorageValue[]): Promise<T[]> {
  return inGroup(groupId, (_g, state) => state.storage.sql.exec(query, ...args).toArray() as T[]);
}

export interface Member {
  memberId: string;
  pass: string;
}

export interface TestGroup {
  groupId: string;
  owner: Member;
  joinProof: string;
  otid: string;
  proName: string;
  now: number;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  status: any;
}

export const GROUP_PROFILE = envelopeOf("groupProfilePadded");
export const MEMBER_PROFILE = envelopeOf("memberProfilePadded");

export function newMember(): Member {
  return { memberId: randomId(), pass: randomSecret() };
}

/** Creates a group with an Apple yearly Production proof (TEST_MODE, no API credentials: the proof is used as is). */
export async function createGroup(
  opts: { now?: number; groupId?: string; owner?: Member; tx?: Record<string, unknown>; joinProof?: string } = {},
): Promise<TestGroup> {
  const now = opts.now ?? T0;
  const groupId = opts.groupId ?? randomId();
  const owner = opts.owner ?? newMember();
  const joinProof = opts.joinProof ?? randomSecret();
  const jws = await signedTransaction(now, opts.tx ?? {});
  const otid = JSON.parse(new TextDecoder().decode(base64urlToBytes(jws.split(".")[1]!))).originalTransactionId as string;
  const proName = `apple:${otid}`;
  await setClock(groupId, now);
  await setProClock(proName, now);
  const res = await api("POST", "/v1/groups", {
    body: {
      groupId,
      memberId: owner.memberId,
      pass: owner.pass,
      joinProof,
      groupProfile: GROUP_PROFILE,
      memberProfile: MEMBER_PROFILE,
      termsVersion: 1,
      pro: appleProof(jws),
    },
  });
  if (res.status !== 201) throw new Error(`create failed: ${res.status} ${res.text}`);
  return { groupId, owner, joinProof, otid, proName, now, status: res.body };
}

function base64urlToBytes(text: string): Uint8Array {
  const b64 = text.replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(b64 + "=".repeat((4 - (b64.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export async function makeInvite(g: TestGroup, by: Member = g.owner): Promise<{ inviteId: string; token: string }> {
  const res = await api("POST", `/v1/groups/${g.groupId}/invites`, { pass: by.pass, body: {} });
  if (res.status !== 201) throw new Error(`invite failed: ${res.status} ${res.text}`);
  return res.body;
}

export async function redeem(g: TestGroup, token: string, member: Member = newMember()): Promise<ApiResult> {
  return api("POST", "/v1/invites/redeem", {
    body: { token, joinProof: g.joinProof, memberId: member.memberId, pass: member.pass, profile: MEMBER_PROFILE, termsVersion: 1 },
  });
}

/** Invites and joins a new member, then makes them admin when asked. */
export async function addMember(g: TestGroup, role: "member" | "admin" = "member", member: Member = newMember()): Promise<Member> {
  const { token } = await makeInvite(g);
  const res = await redeem(g, token, member);
  if (res.status !== 201) throw new Error(`join failed: ${res.status} ${res.text}`);
  if (role === "admin") {
    const r = await api("PUT", `/v1/groups/${g.groupId}/members/${member.memberId}/role`, { pass: g.owner.pass, body: { role: "admin" } });
    if (r.status !== 200) throw new Error(`role failed: ${r.status} ${r.text}`);
  }
  return member;
}

/** A new Apple purchase's proof, with its ProDO's clock set to `now` (the proof is used as is under TEST_MODE). */
export async function appleProofAt(
  now: number,
  tx: Record<string, unknown> = {},
): Promise<{ pro: { platform: string; signedTransaction: string }; proName: string; otid: string }> {
  const jws = await signedTransaction(now, tx);
  const otid = JSON.parse(new TextDecoder().decode(base64urlToBytes(jws.split(".")[1]!))).originalTransactionId as string;
  const proName = `apple:${otid}`;
  await setProClock(proName, now);
  return { pro: appleProof(jws), proName, otid };
}

export async function advance(g: TestGroup, seconds: number): Promise<number> {
  g.now += seconds;
  await setClock(g.groupId, g.now);
  await setProClock(g.proName, g.now);
  return g.now;
}

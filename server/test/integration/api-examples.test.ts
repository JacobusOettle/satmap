// Walks every endpoint with the vector ids, passes and envelopes, fixed clocks, fixed server randomness and fixed
// signed transactions, and records each request and answer for api-examples.json (SPEC §13.1). The cores' contract
// tests parse every example. Running the tests again writes the same file.

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import worker from "../../src/index";
import type { ProState } from "../../src/pro/types";
import exampleJws from "../fixtures/apple/example-jws.json";
import { BASE, MEMBER_PROFILE, adminStub, inGroup, inPro, setClock, setProClock } from "../helpers/client";
import { V, b64urlEncode, envelopeOf, fromHex, vectors } from "../helpers/crypto";
import { Recorder } from "../helpers/examples";
import { connect } from "../helpers/ws";
import { runInDurableObject } from "cloudflare:test";

const T = 1793613000; // the spec's example joinedAt (2 November 2026, 10:30 UTC)
const ACTIVE = { platform: "apple", signedTransaction: exampleJws.active };
const EXPIRED = { platform: "apple", signedTransaction: exampleJws.expired };
const ACTIVE_PRO = "apple:2000000000000001";
const EXPIRED_PRO = "apple:2000000000000002";
const ADMIN = { Authorization: "Bearer test-admin-token-0123456789abcdefghijklmnop" };

function bytes(start: number, length: number): Uint8Array {
  return Uint8Array.from({ length }, (_, i) => (start + i) & 0xff);
}
const id = (start: number) => b64urlEncode(bytes(start, 16));
const secret = (start: number) => b64urlEncode(bytes(start, 32));

const G = V.groupId;
const OWNER = { memberId: V.memberId, pass: V.pass };
const JOINER = { memberId: id(0xc0), pass: secret(0x60) };
const OTHER = { memberId: id(0xc8), pass: secret(0x80) };
const GROUP2 = id(0x10);
const PIN2 = id(0x30);

async function queueRandom(groupId: string, values: Uint8Array[]): Promise<void> {
  await inGroup(groupId, (d) => {
    d.random = (n: number) => {
      const next = values.shift();
      if (next === undefined || next.length !== n) throw new Error("test randomness used up");
      return next;
    };
  });
}

async function clock(t: number): Promise<void> {
  await setClock(G, t);
  await setProClock(ACTIVE_PRO, t);
  await setProClock(EXPIRED_PRO, t);
}

describe("api-examples.json", () => {
  it("records an example for every endpoint and every error code an app reacts to", async ({ task }) => {
    const r = new Recorder(task);
    const createBody = {
      groupId: G,
      memberId: OWNER.memberId,
      pass: OWNER.pass,
      joinProof: V.joinProof,
      groupProfile: envelopeOf("groupProfilePadded"),
      memberProfile: envelopeOf("memberProfilePadded"),
      termsVersion: 1,
      pro: ACTIVE,
    };

    // E1 create
    await clock(T);
    await setClock(GROUP2, T);
    expect((await r.http("E1-created", "POST", "/v1/groups", { body: createBody })).status).toBe(201);
    expect((await r.http("E1-retry", "POST", "/v1/groups", { body: createBody })).status).toBe(200);
    expect((await r.http("E1-group_exists", "POST", "/v1/groups", { body: { ...createBody, pass: secret(0x20) } })).status).toBe(409);
    await r.http("E1-bad_request", "POST", "/v1/groups", { body: { ...createBody, groupId: vectors.mustFail.nonCanonicalIds[0] } });
    await r.http("E1-bad_record", "POST", "/v1/groups", { body: { ...createBody, groupId: GROUP2, groupProfile: b64urlEncode(fromHex(vectors.mustFail.unknownVersionEnvelopeHex)) } });
    await r.http("E1-terms_outdated", "POST", "/v1/groups", { body: { ...createBody, groupId: GROUP2, termsVersion: 0 } });
    await r.http("E1-pro_invalid", "POST", "/v1/groups", { body: { ...createBody, groupId: GROUP2, pro: { platform: "apple", signedTransaction: "eyJhbGciOiJFUzI1NiJ9.e30.c2ln" } } });
    expect((await r.http("E1-pro_inactive", "POST", "/v1/groups", { body: { ...createBody, groupId: GROUP2, pro: EXPIRED } })).status).toBe(402);
    await inPro(ACTIVE_PRO, (p) => {
      p.envOverride = { MAX_GROUPS_PER_PURCHASE: "1" };
    });
    expect((await r.http("E1-too_many_groups", "POST", "/v1/groups", { body: { ...createBody, groupId: GROUP2 } })).status).toBe(409);
    await inPro(ACTIVE_PRO, (p) => {
      p.envOverride = null;
    });
    const google = { platform: "google", productId: "pro", purchaseToken: "examplePurchaseToken.0123456789-abcdef" };
    expect((await r.http("E1-store_unavailable", "POST", "/v1/groups", { body: { ...createBody, groupId: GROUP2, pro: google } })).status).toBe(503);
    const ip = "203.0.113.200";
    for (let i = 0; i < 3; i++) await r.http(`E1-rate_limited-${i}`, "POST", "/v1/groups", { body: { ...createBody, groupId: GROUP2, termsVersion: 0 }, ip });
    r.examples.splice(r.examples.findIndex((e) => e.id === "E1-rate_limited-0"), 3);
    expect((await r.http("E1-rate_limited", "POST", "/v1/groups", { body: { ...createBody, groupId: GROUP2 }, ip })).status).toBe(429);
    await r.http("E1-unsupported_media_type", "POST", "/v1/groups", { rawBody: "groupId=x", headers: { "Content-Type": "text/plain" } });
    await r.http("E1-too_large", "POST", "/v1/groups", { body: { ...createBody, filler: "x".repeat(66_000) }, record: null });
    const internal = await worker.fetch(
      new Request(`${BASE}/v1/groups`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(createBody) }),
      { ...env, TEST_MODE: "0", RL_CREATE: undefined } as unknown as typeof env,
    );
    r.add({ id: "E1-internal", method: "POST", path: "/v1/groups", request: createBody, status: internal.status, response: await internal.json() });

    // E2, E3
    await r.http("E2-ok", "GET", `/v1/groups/${G}`, { pass: OWNER.pass });
    await r.http("E2-group_not_found", "GET", `/v1/groups/${GROUP2}`, { pass: OWNER.pass });
    await r.http("E2-method_not_allowed", "PATCH", `/v1/groups/${G}`, { pass: OWNER.pass });
    await r.http("E3-ok", "PUT", `/v1/groups/${G}/profile`, { pass: OWNER.pass, body: { profile: envelopeOf("groupProfile") } });

    // E6–E10 invites
    await queueRandom(G, [bytes(0x70, 16), fromHex(vectors.inputs.inviteSecretHex), bytes(0x71, 16), bytes(0x90, 16), bytes(0x72, 16), bytes(0xa0, 16), bytes(0xf0, 16)]);
    const invite = await r.http("E6-created", "POST", `/v1/groups/${G}/invites`, { pass: OWNER.pass, body: {} });
    expect(invite.body.token).toBe(vectors.invite.token);
    await r.http("E7-ok", "GET", `/v1/groups/${G}/invites`, { pass: OWNER.pass });
    await r.http("E9-ok", "POST", "/v1/invites/preview", { body: { token: V.token, joinProof: V.joinProof } });
    await r.http("E9-invite_not_found", "POST", "/v1/invites/preview", { body: { token: V.token, joinProof: secret(0x40) } });
    await clock(T + 300);
    const redeem = (m: { memberId: string; pass: string }, token: string, termsVersion = 1) => ({
      token,
      joinProof: V.joinProof,
      memberId: m.memberId,
      pass: m.pass,
      profile: MEMBER_PROFILE,
      termsVersion,
    });
    expect((await r.http("E10-created", "POST", "/v1/invites/redeem", { body: redeem(JOINER, V.token) })).status).toBe(201);
    expect((await r.http("E10-retry", "POST", "/v1/invites/redeem", { body: redeem(JOINER, V.token) })).status).toBe(200);
    await r.http("E10-invite_not_found", "POST", "/v1/invites/redeem", { body: redeem(OTHER, V.token) });
    const second = await api2(r, "E6-second", G, OWNER.pass);
    await r.http("E10-member_exists", "POST", "/v1/invites/redeem", { body: redeem({ memberId: OWNER.memberId, pass: secret(0x50) }, second.token) });
    await r.http("E10-terms_outdated", "POST", "/v1/invites/redeem", { body: redeem(OTHER, second.token, 0) });
    await r.http("E8-cancelled", "DELETE", `/v1/groups/${G}/invites/${second.inviteId}`, { pass: OWNER.pass });
    await r.http("E8-not_found", "DELETE", `/v1/groups/${G}/invites/${second.inviteId}`, { pass: OWNER.pass });
    await r.http("E6-forbidden", "POST", `/v1/groups/${G}/invites`, { pass: JOINER.pass, body: {} });
    const third = await api2(r, "E6-third", G, OWNER.pass);
    expect((await api2redeem(OTHER, third.token)).status).toBe(201);

    // E11–E13
    await r.http("E11-ok", "GET", `/v1/groups/${G}/members`, { pass: JOINER.pass });
    await r.http("E12-ok", "PUT", `/v1/groups/${G}/members/me/profile`, { pass: JOINER.pass, body: { profile: envelopeOf("memberProfileUnicode") } });
    await r.http("E13-ok", "PUT", `/v1/groups/${G}/members/${JOINER.memberId}/role`, { pass: OWNER.pass, body: { role: "admin" } });
    await r.http("E13-not_allowed", "PUT", `/v1/groups/${G}/members/${OWNER.memberId}/role`, { pass: OWNER.pass, body: { role: "member" } });
    await r.http("E13-not_found", "PUT", `/v1/groups/${G}/members/${id(0xd0)}/role`, { pass: OWNER.pass, body: { role: "admin" } });
    await r.http("E3-forbidden", "PUT", `/v1/groups/${G}/profile`, { pass: OTHER.pass, body: { profile: envelopeOf("groupProfile") } });

    // E18, E21 uploads and live
    await clock(1793613622);
    await r.http("E18-ok", "POST", `/v1/groups/${G}/upload`, {
      pass: OWNER.pass,
      body: {
        chunks: [
          { start: vectors.inputs.windowStart, rec: envelopeOf("trailChunk") },
          { start: 1793614800, rec: envelopeOf("trailChunk") },
        ],
        live: { at: 1793613620, rec: envelopeOf("live") },
      },
    });
    const tooMany = Array.from({ length: 51 }, (_, i) => ({ start: vectors.inputs.windowStart - i * 600, rec: envelopeOf("trailChunkDeflated") }));
    await r.http("E18-too_large", "POST", `/v1/groups/${G}/upload`, { pass: OWNER.pass, body: { chunks: tooMany } });
    await r.http("E21-ok", "GET", `/v1/groups/${G}/live`, { pass: JOINER.pass });

    // E23, E24 pins
    const animal = { pinId: V.pinId, createdAt: 1793613900, expiresAt: 1793657100, rec: envelopeOf("pinAnimalPadded") };
    expect((await r.http("E23-created", "POST", `/v1/groups/${G}/pins`, { pass: OWNER.pass, body: animal })).status).toBe(201);
    expect((await r.http("E23-retry", "POST", `/v1/groups/${G}/pins`, { pass: OWNER.pass, body: animal })).status).toBe(200);
    await r.http("E23-pin_exists", "POST", `/v1/groups/${G}/pins`, { pass: OTHER.pass, body: animal });
    await r.http("E23-bad_record", "POST", `/v1/groups/${G}/pins`, { pass: OTHER.pass, body: { ...animal, pinId: PIN2, expiresAt: 1793657000 } });
    await r.http("E23-pin_expired", "POST", `/v1/groups/${G}/pins`, { pass: OTHER.pass, body: { ...animal, pinId: PIN2, createdAt: 1793500000, expiresAt: 1793543200 } });
    await r.http("E23-bad_time", "POST", `/v1/groups/${G}/pins`, { pass: OTHER.pass, body: { pinId: PIN2, createdAt: 1793614300, expiresAt: null, rec: envelopeOf("pinWater") } });
    const water = { pinId: PIN2, createdAt: 1793613600, expiresAt: null, rec: envelopeOf("pinWater") };
    expect((await api2post(OTHER.pass, `/v1/groups/${G}/pins`, water)).status).toBe(201);
    await r.http("E24-deleted", "DELETE", `/v1/groups/${G}/pins/${PIN2}`, { pass: OWNER.pass });
    await r.http("E24-not_found", "DELETE", `/v1/groups/${G}/pins/${PIN2}`, { pass: OWNER.pass });
    await r.http("E24-forbidden", "DELETE", `/v1/groups/${G}/pins/${V.pinId}`, { pass: OTHER.pass });
    await r.http("E23-pin_deleted", "POST", `/v1/groups/${G}/pins`, { pass: OTHER.pass, body: water });

    // E19 changes, E25 report
    await r.http("E19-page", "GET", `/v1/groups/${G}/changes?since=0&limit=500`, { pass: OTHER.pass });
    await r.http("E19-reset", "GET", `/v1/groups/${G}/changes?since=9999`, { pass: OTHER.pass });
    await r.http("E25-accepted", "POST", `/v1/groups/${G}/reports`, {
      pass: OTHER.pass,
      body: { targetKind: "pin", targetId: V.pinId, reason: "offensive", comment: "", content: { type: "animal", animal: "Rhino", name: "Thandi" } },
    });
    await r.http("E25-not_found", "POST", `/v1/groups/${G}/reports`, { pass: OTHER.pass, body: { targetKind: "member", targetId: id(0xd0), reason: "spam" } });
    await runInDurableObject(adminStub(), (a) => {
      a.clockOverride = 1793613622;
    });
    const reports = await r.http("A1-ok", "GET", "/v1/admin/reports?status=open&limit=100", { headers: ADMIN });
    await r.http("A2-ok", "POST", `/v1/admin/reports/${reports.body.reports[0].reportId}/resolve`, { headers: ADMIN, body: { action: "pin_deleted", note: "Corroborated by the reported pin's size" } });
    await r.http("A3-not_found", "DELETE", `/v1/admin/groups/${G}/pins/${PIN2}`, { headers: ADMIN });
    await r.http("A6-ok", "GET", `/v1/admin/groups/${G}`, { headers: ADMIN });

    // E26 WebSocket: upgrade, hello/welcome, upload/uploaded, error, tick
    await r.http("E26-upgrade_required", "GET", `/v1/groups/${G}/ws`, { pass: OWNER.pass });
    await r.http("A3-deleted", "DELETE", `/v1/admin/groups/${G}/pins/${V.pinId}`, { headers: ADMIN });

    // E20 archive and E22 clear trail, 31 days later
    await clock(1793613622 + 31 * 86400);
    await inGroup(G, (d) => d.archivePass(1793613622 + 31 * 86400));
    const archive = await worker.fetch(
      new Request(`${BASE}/v1/groups/${G}/archive/${OWNER.memberId}/2026-11`, { headers: { Authorization: `Bearer ${OWNER.pass}` } }),
      env,
    );
    expect(archive.status).toBe(200);
    r.add({ id: "E20-archive", method: "GET", path: `/v1/groups/${G}/archive/${OWNER.memberId}/2026-11`, request: null, status: 200, response: null });
    await r.http("E20-not_found", "GET", `/v1/groups/${G}/archive/${OWNER.memberId}/2026-10`, { pass: OWNER.pass });
    await r.http("E22-cleared", "DELETE", `/v1/groups/${G}/trail/me`, { pass: OWNER.pass });

    // E15–E17 ownership, E5 Pro proof
    await clock(1793700000);
    await r.http("E15-ok", "POST", `/v1/groups/${G}/transfer`, { pass: OWNER.pass, body: { to: JOINER.memberId } });
    await r.http("E16-declined", "DELETE", `/v1/groups/${G}/transfer`, { pass: JOINER.pass });
    await r.http("E16-not_found", "DELETE", `/v1/groups/${G}/transfer`, { pass: JOINER.pass });
    await api2post(OWNER.pass, `/v1/groups/${G}/transfer`, { to: JOINER.memberId });
    await r.http("E17-forbidden", "POST", `/v1/groups/${G}/transfer/accept`, { pass: OTHER.pass, body: { pro: ACTIVE } });
    await r.http("E17-accepted", "POST", `/v1/groups/${G}/transfer/accept`, { pass: JOINER.pass, body: { pro: ACTIVE } });
    await r.http("E5-ok", "PUT", `/v1/groups/${G}/pro`, { pass: JOINER.pass, body: { pro: ACTIVE } });
    await r.http("E5-pro_inactive", "PUT", `/v1/groups/${G}/pro`, { pass: JOINER.pass, body: { pro: EXPIRED } });
    await r.http("E5-forbidden", "PUT", `/v1/groups/${G}/pro`, { pass: OWNER.pass, body: { pro: ACTIVE } });

    // E14 leaving and removal
    await r.http("E14-owner_must_hand_over", "DELETE", `/v1/groups/${G}/members/me`, { pass: JOINER.pass });
    await r.http("E14-removed", "DELETE", `/v1/groups/${G}/members/${OTHER.memberId}`, { pass: JOINER.pass });
    await r.http("E14-not_found", "DELETE", `/v1/groups/${G}/members/${OTHER.memberId}`, { pass: JOINER.pass });
    await r.http("E2-removed", "GET", `/v1/groups/${G}`, { pass: OTHER.pass });
    await r.http("E14-left", "DELETE", `/v1/groups/${G}/members/me`, { pass: OWNER.pass });
    await r.http("E2-unauthorized", "GET", `/v1/groups/${G}`, { pass: OWNER.pass });

    // Paused: E18 group_paused, then E4 delete
    const ended: ProState = { name: ACTIVE_PRO, platform: "apple", productId: "pro.yearly", active: false, reason: "pro_ended", expiresAt: 1825149600 };
    await inGroup(G, (d) => d.proChanged(ACTIVE_PRO, ended));
    await r.http("E2-paused", "GET", `/v1/groups/${G}`, { pass: JOINER.pass });
    await r.http("E18-group_paused", "POST", `/v1/groups/${G}/upload`, { pass: JOINER.pass, body: { live: { at: 1793699999, rec: envelopeOf("live") } } });
    await r.http("E18-stop_sharing", "POST", `/v1/groups/${G}/upload`, { pass: JOINER.pass, body: { live: null } });

    // Admin and store endpoints
    await r.http("A4-not_allowed", "DELETE", `/v1/admin/groups/${G}/members/${JOINER.memberId}`, { headers: ADMIN });
    await r.http("E28-acknowledged", "POST", "/v1/store/google?token=test-rtdn-token-0123456789abcdefghijklmnop", {
      body: { message: { data: btoa(JSON.stringify({ version: "1.0", packageName: "com.koonap.spoor", eventTimeMillis: "1793700000000", testNotification: { version: "1.0" } })), messageId: "1", publishTime: "2026-11-03T10:00:00Z" }, subscription: "projects/satmap/subscriptions/rtdn" },
    });
    await r.http("E29-ok", "GET", "/v1/health");

    await r.http("E4-deleted", "DELETE", `/v1/groups/${G}`, { pass: JOINER.pass });
    await r.http("E2-group_deleted", "GET", `/v1/groups/${G}`, { pass: JOINER.pass });
    await r.http("A5-group_deleted", "DELETE", `/v1/admin/groups/${G}`, { headers: ADMIN });

    // E26 WebSocket frames, last: in the test runtime a Durable Object with open sockets answers RPC calls only
    // after its sockets are gone, so these run in a second group after everything else (W: fixed ids).
    const W = GROUP2;
    await setClock(W, 1793613622);
    expect((await api2post(undefined, "/v1/groups", { ...createBody, groupId: W })).status).toBe(201);
    await queueRandom(W, [bytes(0x73, 16), bytes(0xb0, 16)]);
    const wInvite = (await api2post(OWNER.pass, `/v1/groups/${W}/invites`, {})).body;
    expect((await api2redeem(OTHER, wInvite.token)).status).toBe(201);
    expect((await api2post(OWNER.pass, `/v1/groups/${W}/upload`, { live: { at: 1793613620, rec: envelopeOf("live") } })).status).toBe(200);
    await inGroup(W, (d) => {
      d.tickDelayMs = 300;
    });
    const owner = (await connect(W, OWNER.pass)).socket!;
    const other = (await connect(W, OTHER.pass)).socket!;
    const hello = { type: "hello", v: 1, watching: true, cursor: 0 };
    owner.send(hello);
    const welcome = await owner.next("welcome");
    r.add({ id: "E26-welcome", method: "WS", path: `/v1/groups/${W}/ws`, request: hello, status: 101, response: welcome });
    await owner.drain(500); // the first tick: the owner's catch-up from cursor 0
    const seqBefore = welcome.status.seq;
    await setClock(W, 1793613630);
    // Within one tick delay: the other member starts watching and uploads, so the owner gets one combined tick.
    const otherHello = { type: "hello", v: 1, watching: true, cursor: seqBefore };
    other.send(otherHello);
    await other.next("welcome");
    const upload = {
      type: "upload",
      id: 17,
      chunks: [{ start: vectors.inputs.windowStart, rec: envelopeOf("trailChunkDeflated") }],
      live: { at: 1793613629, rec: envelopeOf("liveUnknowns") },
    };
    other.send(upload);
    r.add({ id: "E26-uploaded", method: "WS", path: `/v1/groups/${W}/ws`, request: upload, status: 101, response: await other.next("uploaded") });
    const tick = await owner.next("tick");
    expect(tick).toMatchObject({ watchers: 1, live: [{ memberId: OTHER.memberId }], changes: { from: seqBefore, more: false } });
    r.add({ id: "E26-tick", method: "WS", path: `/v1/groups/${W}/ws`, request: null, status: 101, response: tick });
    const badUpload = { type: "upload", id: 18, chunks: tooMany };
    other.send(badUpload);
    r.add({ id: "E26-error", method: "WS", path: `/v1/groups/${W}/ws`, request: badUpload, status: 101, response: await other.next("error") });
    const ack = { type: "ack", cursor: seqBefore };
    owner.send(ack);
    r.add({ id: "E26-ack", method: "WS", path: `/v1/groups/${W}/ws`, request: ack, status: 101, response: null });
    owner.ws.close(1000);
    other.ws.close(1000);


    const ids = r.examples.map((e) => e.id);
    for (let n = 1; n <= 29; n++) {
      if (n === 27) continue; // App Store notifications carry a fresh signature each run
      expect(ids.some((x) => x.startsWith(`E${n}-`)), `E${n}`).toBe(true);
    }
    const codes = new Set(r.examples.map((e) => (e.response as { error?: string } | null)?.error).filter(Boolean));
    for (const code of [
      "bad_request", "bad_record", "unauthorized", "pro_invalid", "pro_inactive", "forbidden", "not_found", "invite_not_found",
      "group_not_found", "method_not_allowed", "group_exists", "member_exists", "pin_exists", "group_paused", "owner_must_hand_over",
      "not_allowed", "too_many_groups", "terms_outdated", "removed", "group_deleted", "pin_deleted", "too_large",
      "unsupported_media_type", "bad_time", "pin_expired", "upgrade_required", "rate_limited", "internal", "store_unavailable",
    ]) {
      expect(codes.has(code), code).toBe(true);
    }

    async function api2(rec: Recorder, recordId: string, groupId: string, pass: string): Promise<{ inviteId: string; token: string }> {
      const res = await rec.http(recordId, "POST", `/v1/groups/${groupId}/invites`, { pass, body: {} });
      rec.examples.pop(); // only the first invite is an example
      return res.body;
    }
    async function api2redeem(m: { memberId: string; pass: string }, token: string) {
      const { api } = await import("../helpers/client");
      return api("POST", "/v1/invites/redeem", { body: redeem(m, token) });
    }
    async function api2post(pass: string | undefined, path: string, body: unknown) {
      const { api } = await import("../helpers/client");
      return api("POST", path, pass === undefined ? { body } : { pass, body });
    }
  });
});

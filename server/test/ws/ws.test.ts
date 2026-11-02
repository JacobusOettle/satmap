// The WebSocket protocol (SPEC §6.6, §12.1): hello and welcome, ping, uploads and combined ticks, watchers, more
// and ack, joinedAt filtering in ticks, close codes, hibernation and the socket limit. Run with `npm run test:ws`
// (one worker, no per-file storage isolation).

import { evictDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import type { ProState } from "../../src/pro/types";
import { addMember, advance, api, createGroup, groupStub, inGroup, randomSecret, type Member, type TestGroup } from "../helpers/client";
import { envelopeOf } from "../helpers/crypto";
import { connect, type TestSocket } from "../helpers/ws";

const LIVE = envelopeOf("live");
const CHUNK = envelopeOf("trailChunk");

async function open(g: TestGroup, m: Member, hello: Record<string, unknown> = {}): Promise<{ socket: TestSocket; welcome: any }> {
  const { socket } = await connect(g.groupId, m.pass);
  if (socket === null) throw new Error("no socket");
  socket.send({ type: "hello", v: 1, watching: false, cursor: 0, ...hello });
  const welcome = await socket.next("welcome");
  return { socket, welcome };
}

async function fastTicks(g: TestGroup, ms = 50): Promise<void> {
  await inGroup(g.groupId, (d) => {
    d.tickDelayMs = ms;
  });
}

describe("connect", () => {
  it("answers HTTP errors before the upgrade: 401, 410 removed, 404, and 426 without Upgrade", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    expect((await connect(g.groupId, randomSecret())).status).toBe(401);
    await api("DELETE", `/v1/groups/${g.groupId}/members/${m.memberId}`, { pass: g.owner.pass });
    const removed = await connect(g.groupId, m.pass);
    expect(removed.status).toBe(410);
    expect(await removed.response.json()).toMatchObject({ error: "removed" });
    expect((await connect("AAAAAAAAAAAAAAAAAAAAAA", g.owner.pass)).status).toBe(404);
    expect((await api("GET", `/v1/groups/${g.groupId}/ws`, { pass: g.owner.pass })).status).toBe(426);
  });

  it("answers hello with welcome: the full status and every live entry from the last 24 h", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: m.pass, body: { live: { at: g.now, rec: LIVE } } });
    const { welcome } = await open(g, g.owner, { watching: true, cursor: 3 });
    expect(welcome.type).toBe("welcome");
    expect(welcome.v).toBe(1);
    expect(welcome.status).toMatchObject({ groupId: g.groupId, memberCount: 2, you: { memberId: g.owner.memberId, role: "owner" }, watchers: 0, seq: 3 });
    expect(welcome.live).toEqual([{ memberId: m.memberId, at: g.now, rec: LIVE }]);
  });

  it("answers ping with pong without waking the object", async () => {
    const g = await createGroup();
    const { socket } = await open(g, g.owner);
    socket.send("ping");
    expect(await socket.next("pong")).toBe("pong");
  });

  it("closes a socket that sends no hello within 10 s (4408), and one whose first message isn't hello (4400)", async () => {
    const g = await createGroup();
    await inGroup(g.groupId, (d) => {
      d.helloTimeoutMs = 200;
    });
    const silent = (await connect(g.groupId, g.owner.pass)).socket!;
    expect((await silent.closed).code).toBe(4408);
    const rude = (await connect(g.groupId, g.owner.pass)).socket!;
    rude.send({ type: "watching", on: true });
    expect((await rude.closed).code).toBe(4400);
  });

  it("allows 4 sockets per member: a fifth closes the oldest with 4409", async () => {
    const g = await createGroup();
    const sockets: TestSocket[] = [];
    for (let i = 0; i < 5; i++) {
      sockets.push((await open(g, g.owner)).socket);
      await new Promise((r) => setTimeout(r, 5));
    }
    expect((await sockets[0]!.closed).code).toBe(4409);
    sockets[4]!.send("ping");
    expect(await sockets[4]!.next("pong")).toBe("pong");
  });
});

describe("uploads and ticks", () => {
  it("answers an upload with uploaded; the others get one combined tick within 2 s", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const owner = await open(g, g.owner, { watching: true, cursor: 3 });
    const member = await open(g, m, { cursor: 3 });
    await owner.socket.drain(2500); // the member's hello needs no tick for the owner; drop anything pending
    await member.socket.drain(2500); // the owner's watching tick
    const start = g.now - (g.now % 600);
    member.socket.send({ type: "upload", id: 1, chunks: [{ start, rec: CHUNK }], live: { at: g.now, rec: LIVE } });
    expect(await member.socket.next("uploaded")).toEqual({ type: "uploaded", id: 1, accepted: [start], rejected: [], liveAccepted: true });
    await advance(g, 2);
    member.socket.send({ type: "upload", id: 2, live: { at: g.now, rec: LIVE } });
    expect((await member.socket.next("uploaded")).liveAccepted).toBe(true);
    const t0 = Date.now();
    const tick = await owner.socket.next("tick", 4000);
    expect(Date.now() - t0).toBeLessThan(2500);
    expect(tick.changes).toEqual({ from: 3, items: [{ kind: "chunk", seq: 4, memberId: m.memberId, start, rec: CHUNK }], cursor: 4, more: false });
    expect(tick.live.map((l: { at: number }) => l.at)).toEqual([g.now - 2, g.now]);
    // Only one tick for both uploads.
    expect(await owner.socket.drain(2500)).toEqual([]);
  });

  it("sends an error frame for an upload that fails as a whole, and for a malformed message", async () => {
    const g = await createGroup();
    const { socket } = await open(g, g.owner);
    socket.send({ type: "upload", id: 7, chunks: {} });
    expect(await socket.next("error")).toMatchObject({ type: "error", id: 7, error: "bad_request" });
    socket.send("{not json");
    const error = await socket.next("error");
    expect(error.error).toBe("bad_request");
    expect(error.id).toBeUndefined();
    socket.send({ type: "dance" });
    expect((await socket.next("error")).error).toBe("bad_request");
  });

  it("counts watchers across members, excluding the recipient", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    await fastTicks(g);
    const owner = await open(g, g.owner, { watching: true, cursor: 3 });
    const member = await open(g, m, { cursor: 3 });
    expect(member.welcome.status.watchers).toBe(1);
    expect(owner.welcome.status.watchers).toBe(0);
    member.socket.send({ type: "watching", on: true });
    expect((await owner.socket.next("tick")).watchers).toBe(1);
    const second = await open(g, m, { watching: true, cursor: 3 }); // the same member twice still counts once
    expect(second.welcome.status.watchers).toBe(1);
    const up = await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: g.owner.pass, body: { live: null } });
    expect(up.body.watchers).toBe(1);
    expect((await api("GET", `/v1/groups/${g.groupId}/live`, { pass: m.pass })).body.watchers).toBe(1);
    const stopped = await member.socket.next("tick"); // the owner's live: null
    expect(stopped.live).toEqual([{ memberId: g.owner.memberId, at: g.now, rec: null }]);
    owner.socket.send({ type: "watching", on: false });
    const tick = await member.socket.next("tick");
    expect(tick.watchers).toBe(0);
  });

  it("stops sending changes after a page with more until the client acks", async () => {
    const g = await createGroup();
    await fastTicks(g);
    const { socket } = await open(g, g.owner, { cursor: 2 });
    await inGroup(g.groupId, (_d, state) => {
      for (let i = 0; i < 250; i++) {
        state.storage.sql.exec("INSERT INTO removals (seq, what, id, at) VALUES (?, 'pin', ?, 0)", 3 + i, `p${i}`);
      }
      state.storage.sql.exec("UPDATE grp SET seq = 252");
    });
    await api("PUT", `/v1/groups/${g.groupId}/profile`, { pass: g.owner.pass, body: { profile: envelopeOf("groupProfile") } });
    const first = await socket.next("tick");
    expect(first.changes).toMatchObject({ from: 2, cursor: 202, more: true });
    expect(first.changes.items).toHaveLength(200);
    await api("PUT", `/v1/groups/${g.groupId}/profile`, { pass: g.owner.pass, body: { profile: envelopeOf("groupProfile") } });
    const stalled = await socket.next("tick");
    expect(stalled.changes).toBeUndefined();
    socket.send({ type: "ack", cursor: 253 });
    const after = await socket.next("tick");
    expect(after.changes).toMatchObject({ from: 253, more: false });
    expect(after.changes.items.map((i: { kind: string }) => i.kind)).toEqual(["group"]);
  });

  it("gives a late joiner no tick items for windows before its joinedAt, even with a shared cursor", async () => {
    const g = await createGroup();
    await advance(g, 3 * 600);
    const late = await addMember(g);
    await fastTicks(g);
    const seq = (await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).body.seq;
    const owner = await open(g, g.owner, { cursor: seq });
    const member = await open(g, late, { cursor: seq });
    const w = g.now - (g.now % 600);
    const res = await api("POST", `/v1/groups/${g.groupId}/upload`, {
      pass: g.owner.pass,
      body: { chunks: [{ start: w - 1800, rec: CHUNK }, { start: w - 1200, rec: CHUNK }, { start: w, rec: CHUNK }] },
    });
    expect(res.body.accepted).toHaveLength(3);
    const ownerTick = await owner.socket.next("tick");
    const memberTick = await member.socket.next("tick");
    expect(ownerTick.changes.items.map((i: { start: number }) => i.start)).toEqual([w - 1800, w - 1200, w]);
    expect(memberTick.changes.items.map((i: { start: number }) => i.start)).toEqual([w]);
    expect(memberTick.changes.cursor).toBe(ownerTick.changes.cursor);
  });

  it("pushes a pause from the store (RPC) as status with live entries set to null", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    await fastTicks(g);
    await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: m.pass, body: { live: { at: g.now, rec: LIVE } } });
    const owner = await open(g, g.owner, { cursor: 3 });
    const state: ProState = { name: g.proName, platform: "apple", productId: "pro.yearly", active: false, reason: "pro_ended", expiresAt: g.now };
    await groupStub(g.groupId).proChanged(g.proName, state);
    const tick = await owner.socket.next("tick");
    expect(tick.status).toMatchObject({ status: "paused", pausedReason: "pro_ended" });
    expect(tick.live).toEqual([{ memberId: m.memberId, at: g.now, rec: null }]);
    owner.socket.send({ type: "upload", id: 9, live: { at: g.now, rec: LIVE } });
    expect(await owner.socket.next("error")).toMatchObject({ id: 9, error: "group_paused" });
  });

  it("closes a socket that sends more than 120 messages a minute (4429)", async () => {
    const g = await createGroup();
    const { socket } = await open(g, g.owner);
    for (let i = 0; i < 125; i++) socket.send({ type: "watching", on: i % 2 === 0 });
    expect((await socket.closed).code).toBe(4429);
  });
});

describe("close codes", () => {
  it("closes a removed member's sockets with 4403 and a leaver's with 1000", async () => {
    const g = await createGroup();
    const removed = await addMember(g);
    const leaver = await addMember(g);
    const a = await open(g, removed);
    const b = await open(g, leaver);
    await api("DELETE", `/v1/groups/${g.groupId}/members/${removed.memberId}`, { pass: g.owner.pass });
    expect((await a.socket.closed).code).toBe(4403);
    await api("DELETE", `/v1/groups/${g.groupId}/members/me`, { pass: leaver.pass });
    expect((await b.socket.closed).code).toBe(1000);
  });

  it("closes every socket with 4404 when the group is deleted", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const a = await open(g, g.owner);
    const b = await open(g, m);
    expect((await api("DELETE", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).status).toBe(204);
    expect((await a.socket.closed).code).toBe(4404);
    expect((await b.socket.closed).code).toBe(4404);
  });
});

describe("hibernation", () => {
  it("keeps sockets through eviction: messages still work and watcher counts are rebuilt", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const owner = await open(g, g.owner, { watching: true, cursor: 3 });
    const member = await open(g, m, { cursor: 3 });
    await evictDurableObject(groupStub(g.groupId), { webSockets: "hibernate" });
    // After waking, the object's clock override is gone: use real time for this upload.
    const now = Math.floor(Date.now() / 1000);
    member.socket.send({ type: "upload", id: 3, live: { at: now, rec: LIVE } });
    expect((await member.socket.next("uploaded")).liveAccepted).toBe(true);
    const live = await api("GET", `/v1/groups/${g.groupId}/live`, { pass: m.pass });
    expect(live.body.watchers).toBe(1);
    owner.socket.send("ping");
    expect(await owner.socket.next("pong")).toBe("pong");
    const tick = await owner.socket.next("tick", 5000);
    expect(tick.live).toEqual([{ memberId: m.memberId, at: now, rec: LIVE }]);
  });
});

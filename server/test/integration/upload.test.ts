// E18 upload rules (every rejection code), live records and E21 (SPEC §6.5).

import { describe, expect, it } from "vitest";
import type { ProState } from "../../src/pro/types";
import { addMember, advance, api, createGroup, inGroup, rows, type TestGroup } from "../helpers/client";
import { b64urlEncode, envelopeOf } from "../helpers/crypto";

const CHUNK = envelopeOf("trailChunk");
const LIVE = envelopeOf("live");

function envelope(size: number, version = 1): string {
  const bytes = new Uint8Array(size);
  bytes[0] = version;
  return b64urlEncode(bytes);
}

async function pause(g: TestGroup): Promise<void> {
  const state: ProState = { name: g.proName, platform: "apple", productId: "pro.yearly", active: false, reason: "pro_ended", expiresAt: g.now - 1 };
  await inGroup(g.groupId, (d) => d.proChanged(g.proName, state));
}

describe("E18 chunks", () => {
  it("accepts a chunk and replaces it with a new seq on re-upload", async () => {
    const g = await createGroup();
    const start = g.now - (g.now % 600);
    const first = await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: g.owner.pass, body: { chunks: [{ start, rec: CHUNK }] } });
    expect(first.status).toBe(200);
    expect(first.body).toEqual({ accepted: [start], rejected: [], liveAccepted: false, watchers: 0, status: "active", serverTime: g.now });
    const seq1 = (await rows<{ seq: number }>(g.groupId, "SELECT seq FROM chunks"))[0]!.seq;
    await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: g.owner.pass, body: { chunks: [{ start, rec: envelopeOf("trailChunkDeflated") }] } });
    const stored = await rows<{ seq: number; rec: ArrayBuffer }>(g.groupId, "SELECT seq, rec FROM chunks");
    expect(stored).toHaveLength(1);
    expect(stored[0]!.seq).toBeGreaterThan(seq1);
    expect(stored[0]!.rec.byteLength).toBe(49);
  });

  it("rejects each chunk with its code: bad_record, before_join, bad_time, window_paused", async () => {
    const g = await createGroup();
    await advance(g, 3600);
    const m = await addMember(g);
    const w = g.now - (g.now % 600);
    // A pause that covered [w-1800, w-600) fully, and the window [w-1200, w-600) inside it.
    await inGroup(g.groupId, (_d, state) => {
      state.storage.sql.exec("INSERT INTO pause_log (paused_at, resumed_at, reason) VALUES (?, ?, 'pro_ended')", w - 1800, w - 600);
    });
    const res = await api("POST", `/v1/groups/${g.groupId}/upload`, {
      pass: g.owner.pass,
      body: {
        chunks: [
          { start: w + 1, rec: CHUNK }, // not aligned
          { start: w + 1200, rec: CHUNK }, // more than 10 min ahead
          { start: w, rec: envelope(31) }, // too short
          { start: w, rec: envelope(12289) }, // too long
          { start: w, rec: envelope(40, 2) }, // another version
          { start: w, rec: `${CHUNK}=` }, // bad base64url
          { start: w - 1200, rec: CHUNK }, // wholly inside the pause
          { start: w - 1800, rec: CHUNK }, // also inside (pause starts at the window start)
          { start: w - 600, rec: CHUNK }, // after the pause: accepted
          { start: w + 600, rec: CHUNK }, // up to 10 min ahead: accepted
        ],
      },
    });
    expect(res.body.rejected).toEqual([
      { start: w + 1, error: "bad_record" },
      { start: w + 1200, error: "bad_time" },
      { start: w, error: "bad_record" },
      { start: w, error: "bad_record" },
      { start: w, error: "bad_record" },
      { start: w, error: "bad_record" },
      { start: w - 1200, error: "window_paused" },
      { start: w - 1800, error: "window_paused" },
    ]);
    expect(res.body.accepted).toEqual([w - 600, w + 600]);
    // The member who joined at g.now can't upload windows that ended before they joined.
    const joined = await api("POST", `/v1/groups/${g.groupId}/upload`, {
      pass: m.pass,
      body: { chunks: [{ start: w - 600, rec: CHUNK }, { start: w, rec: CHUNK }] },
    });
    expect(joined.body.rejected).toEqual([{ start: w - 600, error: "before_join" }]);
    expect(joined.body.accepted).toEqual([w]);
  });

  it("answers the whole request 400, 413 or 409 for malformed bodies, too many chunks, or a paused group", async () => {
    const g = await createGroup();
    const w = g.now - (g.now % 600);
    const path = `/v1/groups/${g.groupId}/upload`;
    expect((await api("POST", path, { pass: g.owner.pass, body: { chunks: {} } })).status).toBe(400);
    expect((await api("POST", path, { pass: g.owner.pass, body: { chunks: [{ start: "x", rec: CHUNK }] } })).status).toBe(400);
    expect((await api("POST", path, { pass: g.owner.pass, body: { chunks: [{ start: w }] } })).status).toBe(400);
    expect((await api("POST", path, { pass: g.owner.pass, body: { live: { at: g.now } } })).status).toBe(400);
    expect((await api("POST", path, { pass: g.owner.pass, body: { live: "x" } })).status).toBe(400);
    const many = Array.from({ length: 51 }, (_, i) => ({ start: w - i * 600, rec: CHUNK }));
    const tooMany = await api("POST", path, { pass: g.owner.pass, body: { chunks: many } });
    expect(tooMany.status).toBe(413);
    expect(tooMany.body.error).toBe("too_large");
    const big = await api("POST", path, { pass: g.owner.pass, body: { chunks: [], filler: "x".repeat(256 * 1024) } });
    expect(big.status).toBe(413);
    expect(big.body.error).toBe("too_large");

    await pause(g);
    const paused = await api("POST", path, { pass: g.owner.pass, body: { chunks: [{ start: w, rec: CHUNK }] } });
    expect(paused.status).toBe(409);
    expect(paused.body.error).toBe("group_paused");
    expect((await api("POST", path, { pass: g.owner.pass, body: { live: { at: g.now, rec: LIVE } } })).status).toBe(409);
    expect((await api("POST", path, { pass: g.owner.pass, body: {} })).status).toBe(409);
    const stop = await api("POST", path, { pass: g.owner.pass, body: { chunks: [], live: null } });
    expect(stop.status).toBe(200);
    expect(stop.body.status).toBe("paused");
  });
});

describe("E18 live and E21", () => {
  it("accepts a live record and serves it at E21, including the caller's own entry", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const res = await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: m.pass, body: { live: { at: g.now - 2, rec: LIVE } } });
    expect(res.body.liveAccepted).toBe(true);
    const live = await api("GET", `/v1/groups/${g.groupId}/live`, { pass: m.pass });
    expect(live.body).toEqual({ live: [{ memberId: m.memberId, at: g.now - 2, rec: LIVE }], watchers: 0, serverTime: g.now });
  });

  it("refuses live records that are too old, too far ahead, not newer, too frequent or the wrong size", async () => {
    const g = await createGroup();
    const path = `/v1/groups/${g.groupId}/upload`;
    const send = async (at: number, rec = LIVE) =>
      (await api("POST", path, { pass: g.owner.pass, body: { live: { at, rec } } })).body.liveAccepted as boolean;
    expect(await send(g.now - 3601)).toBe(false);
    expect(await send(g.now + 601)).toBe(false);
    expect(await send(g.now, envelope(52))).toBe(false);
    expect(await send(g.now, envelope(129))).toBe(false);
    expect(await send(g.now, envelope(60, 3))).toBe(false);
    const t0 = g.now;
    expect(await send(t0 - 3600)).toBe(true);
    // Within 2 s of the last accepted record (server time).
    expect(await send(t0 - 3000)).toBe(false);
    await advance(g, 2);
    expect(await send(t0 - 3600)).toBe(false); // not newer than the stored one
    expect(await send(t0 + 600)).toBe(true);
  });

  it("deletes the live row on live: null and tells E21; hides entries older than 24 h", async () => {
    const g = await createGroup();
    const path = `/v1/groups/${g.groupId}/upload`;
    await api("POST", path, { pass: g.owner.pass, body: { live: { at: g.now, rec: LIVE } } });
    const stop = await api("POST", path, { pass: g.owner.pass, body: { live: null } });
    expect(stop.body.liveAccepted).toBe(true);
    expect((await api("GET", `/v1/groups/${g.groupId}/live`, { pass: g.owner.pass })).body.live).toEqual([]);
    expect(await rows(g.groupId, "SELECT * FROM live")).toEqual([]);
    await advance(g, 2);
    await api("POST", path, { pass: g.owner.pass, body: { live: { at: g.now, rec: LIVE } } });
    await advance(g, 86400);
    expect((await api("GET", `/v1/groups/${g.groupId}/live`, { pass: g.owner.pass })).body.live).toHaveLength(1);
    await advance(g, 1);
    expect((await api("GET", `/v1/groups/${g.groupId}/live`, { pass: g.owner.pass })).body.live).toEqual([]);
  });

  it("writes live rows at most once a minute per member, and last_live_at with them", async () => {
    const g = await createGroup();
    const path = `/v1/groups/${g.groupId}/upload`;
    await api("POST", path, { pass: g.owner.pass, body: { live: { at: g.now, rec: LIVE } } });
    expect(await rows(g.groupId, "SELECT at FROM live")).toEqual([{ at: g.now }]);
    expect(await rows(g.groupId, "SELECT last_live_at FROM members")).toEqual([{ last_live_at: g.now }]);
    await advance(g, 5);
    await api("POST", path, { pass: g.owner.pass, body: { live: { at: g.now, rec: LIVE } } });
    // The memory copy is newer; the row is at most 60 s stale.
    expect(await rows(g.groupId, "SELECT at FROM live")).toEqual([{ at: g.now - 5 }]);
    expect((await api("GET", `/v1/groups/${g.groupId}/live`, { pass: g.owner.pass })).body.live[0].at).toBe(g.now);
    expect((await api("GET", `/v1/groups/${g.groupId}/members`, { pass: g.owner.pass })).body.members[0].lastLiveAt).toBe(g.now);
  });
});

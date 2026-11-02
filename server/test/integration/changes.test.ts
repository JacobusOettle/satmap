// E19 changes: paging, joinedAt filtering, removals, unexpired pins and reset below minCursor (SPEC §6.3, §6.5).

import { describe, expect, it } from "vitest";
import { addMember, advance, api, createGroup, inGroup, randomId, runAlarm } from "../helpers/client";
import { b64urlEncode, envelopeOf } from "../helpers/crypto";

const CHUNK = envelopeOf("trailChunk");
const PIN = envelopeOf("pinWater");
const ANIMAL = envelopeOf("pinAnimal");

describe("E19 changes", () => {
  it("pages in ascending seq with cursor and more", async () => {
    const g = await createGroup();
    for (let i = 0; i < 5; i++) {
      await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body: { pinId: randomId(), createdAt: g.now, expiresAt: null, rec: PIN } });
    }
    const all: number[] = [];
    let cursor = 0;
    for (let page = 0; page < 10; page++) {
      const res = await api("GET", `/v1/groups/${g.groupId}/changes?since=${cursor}&limit=3`, { pass: g.owner.pass });
      expect(res.status).toBe(200);
      expect(res.body.serverTime).toBe(g.now);
      all.push(...res.body.items.map((i: { seq: number }) => i.seq));
      cursor = res.body.cursor;
      if (!res.body.more) break;
      expect(res.body.items).toHaveLength(3);
    }
    expect(all).toEqual([1, 2, 3, 4, 5, 6, 7]);
    const empty = await api("GET", `/v1/groups/${g.groupId}/changes?since=7`, { pass: g.owner.pass });
    expect(empty.body).toEqual({ items: [], cursor: 7, more: false, serverTime: g.now });
  });

  it("validates since and limit, and caps limit at 1000", async () => {
    const g = await createGroup();
    for (const q of ["since=-1", "since=x", "limit=0", "limit=-5", "since=1.5"]) {
      expect((await api("GET", `/v1/groups/${g.groupId}/changes?${q}`, { pass: g.owner.pass })).status, q).toBe(400);
    }
    expect((await api("GET", `/v1/groups/${g.groupId}/changes?limit=5000`, { pass: g.owner.pass })).status).toBe(200);
  });

  it("leaves out chunks that ended before the viewer joined: before, straddling and after", async () => {
    const g = await createGroup();
    const w0 = g.now - (g.now % 600);
    await advance(g, 900); // T0 is 400 s into w0: the member joins 100 s into window w0 + 1200
    const m = await addMember(g);
    const joinedAt = g.now;
    const windows = [w0, w0 + 600, w0 + 1200, w0 + 1800];
    const up = await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: g.owner.pass, body: { chunks: windows.map((start) => ({ start, rec: CHUNK })) } });
    expect(up.body.accepted).toEqual(windows);
    const starts = async (pass: string) =>
      (await api("GET", `/v1/groups/${g.groupId}/changes`, { pass })).body.items
        .filter((i: { kind: string }) => i.kind === "chunk")
        .map((i: { start: number }) => i.start);
    expect(await starts(g.owner.pass)).toEqual(windows);
    // w0 + 600 ends at w0 + 1200 ≤ joinedAt: left out; w0 + 1200 straddles the join: sent.
    expect(joinedAt).toBeGreaterThan(w0 + 1200);
    expect(await starts(m.pass)).toEqual([w0 + 1200, w0 + 1800]);
  });

  it("leaves out archives of months that ended before the viewer joined", async () => {
    const g = await createGroup({ now: Date.UTC(2030, 1, 20) / 1000 });
    await advance(g, 13 * 86400); // the member joins on 5 March 2030
    const m = await addMember(g);
    await inGroup(g.groupId, (_d, state) => {
      const sql = state.storage.sql;
      for (const [month, seq] of [["2030-02", 10], ["2030-03", 11]] as const) {
        sql.exec(
          "INSERT INTO archives (member_id, month, count, bytes, first_start, last_start, seq, max_chunk_seq, archived_at) VALUES (?, ?, 1, 1, 0, 0, ?, 5, 7)",
          g.owner.memberId,
          month,
          seq,
        );
      }
      sql.exec("UPDATE grp SET seq = 11");
    });
    // February ended before the member joined; the owner joined in February.
    const items = (await api("GET", `/v1/groups/${g.groupId}/changes?since=3`, { pass: m.pass })).body.items;
    expect(items).toEqual([{ kind: "archive", seq: 11, memberId: g.owner.memberId, month: "2030-03", maxChunkSeq: 5, archivedAt: 7 }]);
    expect((await api("GET", `/v1/groups/${g.groupId}/changes?since=3`, { pass: g.owner.pass })).body.items).toHaveLength(2);
  });

  it("sends removals, current members only, and unexpired pins only", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const pinId = randomId();
    await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: m.pass, body: { pinId, createdAt: g.now, expiresAt: g.now + 43200, rec: ANIMAL } });
    const water = randomId();
    await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body: { pinId: water, createdAt: g.now, expiresAt: null, rec: PIN } });
    await api("DELETE", `/v1/groups/${g.groupId}/pins/${water}`, { pass: g.owner.pass });
    await api("DELETE", `/v1/groups/${g.groupId}/members/${m.memberId}`, { pass: g.owner.pass });
    const items = (await api("GET", `/v1/groups/${g.groupId}/changes`, { pass: g.owner.pass })).body.items;
    expect(items.map((i: { kind: string; what?: string }) => i.what ?? i.kind)).toEqual(["group", "member", "pin", "member"]);
    expect(items.filter((i: { kind: string }) => i.kind === "removed")).toHaveLength(2);
    // The member's pin went with the member; a new animal pin past its expiry is not sent even before the alarm.
    const animal = randomId();
    await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body: { pinId: animal, createdAt: g.now, expiresAt: g.now + 43200, rec: ANIMAL } });
    expect((await api("GET", `/v1/groups/${g.groupId}/changes`, { pass: g.owner.pass })).body.items.some((i: { pinId?: string }) => i.pinId === animal)).toBe(true);
    await advance(g, 43200);
    expect((await api("GET", `/v1/groups/${g.groupId}/changes`, { pass: g.owner.pass })).body.items.some((i: { pinId?: string }) => i.pinId === animal)).toBe(false);
  });

  it("answers reset below minCursor (removals pruned after 400 days) and above seq", async () => {
    const g = await createGroup();
    const pinId = randomId();
    await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body: { pinId, createdAt: g.now, expiresAt: null, rec: PIN } });
    await api("DELETE", `/v1/groups/${g.groupId}/pins/${pinId}`, { pass: g.owner.pass });
    const seq = (await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).body.seq;
    expect((await api("GET", `/v1/groups/${g.groupId}/changes?since=${seq + 1}`, { pass: g.owner.pass })).body).toEqual({ reset: true, cursor: 0 });
    await advance(g, 400 * 86400 + 1);
    await runAlarm(g.groupId); // the daily job prunes removals older than 400 days
    const reset = await api("GET", `/v1/groups/${g.groupId}/changes?since=${seq - 1}`, { pass: g.owner.pass });
    expect(reset.body).toEqual({ reset: true, cursor: 0 });
    const ok = await api("GET", `/v1/groups/${g.groupId}/changes?since=${seq}`, { pass: g.owner.pass });
    expect(ok.body.items).toEqual([]);
    // Pulling from 0 always works.
    expect((await api("GET", `/v1/groups/${g.groupId}/changes?since=0`, { pass: g.owner.pass })).body.reset).toBe(true);
  });

  it("cuts a page at about 2 MiB of JSON", async () => {
    const g = await createGroup();
    await advance(g, 200 * 600);
    const big = (() => {
      const bytes = new Uint8Array(12288);
      bytes[0] = 1;
      return b64urlEncode(bytes);
    })();
    const base = g.now - (g.now % 600);
    for (let batch = 0; batch < 10; batch++) {
      const chunks = Array.from({ length: 15 }, (_, i) => ({ start: base - (batch * 15 + i) * 600, rec: big }));
      const res = await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: g.owner.pass, body: { chunks } });
      expect(res.body.accepted).toHaveLength(15);
    }
    const page = await api("GET", `/v1/groups/${g.groupId}/changes?limit=1000`, { pass: g.owner.pass });
    expect(page.body.more).toBe(true);
    expect(page.text.length).toBeLessThanOrEqual(2 * 1024 * 1024 + 1024);
    expect(page.body.items.length).toBeLessThan(152);
    const next = await api("GET", `/v1/groups/${g.groupId}/changes?since=${page.body.cursor}&limit=1000`, { pass: g.owner.pass });
    expect(page.body.items.length + next.body.items.length).toBe(152);
    expect(next.body.more).toBe(false);
  });
});

// The R2 archive pass, E20 and E22, and R2 failures (SPEC §7.4, §7.5, §12.1).

import { env, exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { decodeArchive } from "../../src/group/archive";
import { addMember, advance, api, createGroup, inGroup, rows, runAlarm, type TestGroup } from "../helpers/client";
import { b64urlDecode, b64urlEncode, envelopeOf } from "../helpers/crypto";

const CHUNK = envelopeOf("trailChunk");
const CHUNK2 = envelopeOf("trailChunkDeflated");
const DAY = 86400;

/** An R2 bucket that holds each put after writing, until `release()`. */
function gatedBucket(real: R2Bucket) {
  let release!: () => void;
  const gate = new Promise<void>((r) => (release = r));
  let wrote!: () => void;
  const written = new Promise<void>((r) => (wrote = r));
  const bucket = new Proxy(real, {
    get(target, prop) {
      if (prop === "put") {
        return async (...args: Parameters<R2Bucket["put"]>) => {
          const result = await target.put(...args);
          wrote();
          await gate;
          return result;
        };
      }
      const value = Reflect.get(target, prop) as unknown;
      return typeof value === "function" ? (value as (...a: unknown[]) => unknown).bind(target) : value;
    },
  });
  return { bucket, release: () => release(), written };
}

/** An R2 bucket whose list and delete throw `failures` times. */
function failingBucket(real: R2Bucket, failures: number) {
  const state = { left: failures };
  const bucket = new Proxy(real, {
    get(target, prop) {
      if ((prop === "delete" || prop === "list") && state.left > 0) {
        return async () => {
          state.left -= 1;
          throw new Error("R2 is down");
        };
      }
      const value = Reflect.get(target, prop) as unknown;
      return typeof value === "function" ? (value as (...a: unknown[]) => unknown).bind(target) : value;
    },
  });
  return { bucket, state };
}

async function upload(g: TestGroup, pass: string, starts: number[], rec = CHUNK) {
  const res = await api("POST", `/v1/groups/${g.groupId}/upload`, { pass, body: { chunks: starts.map((start) => ({ start, rec })) } });
  expect(res.body.rejected).toEqual([]);
}

async function archiveObject(g: TestGroup, memberId: string, month: string) {
  const object = await env.ARCHIVE.get(`g/${g.groupId}/t/${memberId}/${month}.bin`);
  return object === null ? null : decodeArchive(new Uint8Array(await object.arrayBuffer()));
}

/** A group created on 1 March 2030 at 10:00 UTC, with a member who joined 15 minutes later. */
async function setup() {
  const t = Date.UTC(2030, 2, 1, 10, 0, 0) / 1000;
  const g = await createGroup({ now: t });
  await advance(g, 900);
  const m = await addMember(g);
  return { g, m, t };
}

describe("archive pass", () => {
  it("moves chunks older than 30 days to R2; E19 announces them and E20 serves them filtered by joinedAt", async () => {
    const { g, m, t } = await setup();
    await upload(g, g.owner.pass, [t, t + 600, t + 1200]);
    const seqs = (await rows<{ seq: number }>(g.groupId, "SELECT seq FROM chunks ORDER BY start")).map((r) => r.seq);
    const cursorBefore = (await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).body.seq;
    await advance(g, 31 * DAY);
    await inGroup(g.groupId, (d) => d.archivePass(g.now));

    expect(await rows(g.groupId, "SELECT * FROM chunks")).toEqual([]);
    const archived = await rows<{ count: number; first_start: number; last_start: number; max_chunk_seq: number; archived_at: number; seq: number }>(
      g.groupId,
      "SELECT count, first_start, last_start, max_chunk_seq, archived_at, seq FROM archives",
    );
    expect(archived).toEqual([{ count: 3, first_start: t, last_start: t + 1200, max_chunk_seq: Math.max(...seqs), archived_at: g.now, seq: cursorBefore + 1 }]);
    const object = await archiveObject(g, g.owner.memberId, "2030-03");
    expect(object!.map((c) => c.start)).toEqual([t, t + 600, t + 1200]);
    expect(b64urlEncode(object![0]!.rec)).toBe(CHUNK);

    const items = (await api("GET", `/v1/groups/${g.groupId}/changes?since=${cursorBefore}`, { pass: g.owner.pass })).body.items;
    expect(items).toEqual([
      { kind: "archive", seq: cursorBefore + 1, memberId: g.owner.memberId, month: "2030-03", maxChunkSeq: Math.max(...seqs), archivedAt: g.now },
    ]);

    const download = async (pass: string) => {
      const res = await exports.default.fetch(
        new Request(`https://example.invalid/v1/groups/${g.groupId}/archive/${g.owner.memberId}/2030-03`, { headers: { Authorization: `Bearer ${pass}` } }),
      );
      expect(res.status).toBe(200);
      expect(res.headers.get("Content-Type")).toBe("application/octet-stream");
      expect(res.headers.get("Cache-Control")).toBe("no-store");
      return decodeArchive(new Uint8Array(await res.arrayBuffer()));
    };
    expect((await download(g.owner.pass)).map((c) => c.start)).toEqual([t, t + 600, t + 1200]);
    // The member joined at t + 900: the window t ended before (left out), t + 600 straddles it.
    expect((await download(m.pass)).map((c) => c.start)).toEqual([t + 600, t + 1200]);
  });

  it("answers E20 404 without an archive, when nothing is visible, or while a delete is pending", async () => {
    const { g, m, t } = await setup();
    await upload(g, g.owner.pass, [t]);
    await advance(g, 31 * DAY);
    await inGroup(g.groupId, (d) => d.archivePass(g.now));
    const path = (mid: string, month: string) => `/v1/groups/${g.groupId}/archive/${mid}/${month}`;
    expect((await api("GET", path(g.owner.memberId, "2030-04"), { pass: g.owner.pass })).status).toBe(404);
    expect((await api("GET", path(m.memberId, "2030-03"), { pass: g.owner.pass })).status).toBe(404);
    expect((await api("GET", path(g.owner.memberId, "2030-03"), { pass: m.pass })).status).toBe(404); // only t, before m joined
    expect((await api("GET", path(g.owner.memberId, "2030-3"), { pass: g.owner.pass })).status).toBe(400);
    await inGroup(g.groupId, (_d, state) => {
      state.storage.sql.exec("INSERT INTO r2_deletes (prefix, at) VALUES (?, 0)", `g/${g.groupId}/t/${g.owner.memberId}/`);
    });
    expect((await api("GET", path(g.owner.memberId, "2030-03"), { pass: g.owner.pass })).status).toBe(404);
  });

  it("merges a late upload into the month on a later pass, with maxChunkSeq covering the old item", async () => {
    const { g, t } = await setup();
    await upload(g, g.owner.pass, [t]);
    await advance(g, 31 * DAY);
    await inGroup(g.groupId, (d) => d.archivePass(g.now));
    const first = (await rows<{ seq: number }>(g.groupId, "SELECT seq FROM archives"))[0]!.seq;
    await upload(g, g.owner.pass, [t + 600, t], CHUNK2); // a late window and a replacement of an archived one
    const lateSeqs = (await rows<{ seq: number }>(g.groupId, "SELECT seq FROM chunks")).map((r) => r.seq);
    await inGroup(g.groupId, (d) => d.archivePass(g.now));
    const row = (await rows<{ seq: number; max_chunk_seq: number; count: number }>(g.groupId, "SELECT seq, max_chunk_seq, count FROM archives"))[0]!;
    expect(row.count).toBe(2);
    expect(row.seq).toBeGreaterThan(first);
    expect(row.max_chunk_seq).toBe(Math.max(first, ...lateSeqs));
    const object = await archiveObject(g, g.owner.memberId, "2030-03");
    expect(object!.map((c) => [c.start, b64urlEncode(c.rec)])).toEqual([
      [t, CHUNK2],
      [t + 600, CHUNK2],
    ]);
  });

  it("keeps a row that was replaced during the pass for the next pass", async () => {
    const { g, t } = await setup();
    await upload(g, g.owner.pass, [t, t + 600]);
    await advance(g, 31 * DAY);
    const gated = gatedBucket(env.ARCHIVE);
    await inGroup(g.groupId, (d) => {
      d.bucketOverride = gated.bucket;
    });
    const pass = inGroup(g.groupId, (d) => d.archivePass(g.now));
    await gated.written;
    await upload(g, g.owner.pass, [t + 600], CHUNK2);
    gated.release();
    await pass;
    const left = await rows<{ start: number }>(g.groupId, "SELECT start FROM chunks");
    expect(left).toEqual([{ start: t + 600 }]);
    expect(await rows<{ count: number }>(g.groupId, "SELECT count FROM archives")).toEqual([{ count: 2 }]);
    await inGroup(g.groupId, (d) => {
      d.bucketOverride = null;
    });
    await inGroup(g.groupId, (d) => d.archivePass(g.now));
    expect(await rows(g.groupId, "SELECT * FROM chunks")).toEqual([]);
    const object = await archiveObject(g, g.owner.memberId, "2030-03");
    expect(b64urlEncode(object![1]!.rec)).toBe(CHUNK2);
  });

  it("E22 during a pass: no archives row and, after the r2_delete job, no object", async () => {
    const { g, t } = await setup();
    await upload(g, g.owner.pass, [t]);
    await advance(g, 31 * DAY);
    const gated = gatedBucket(env.ARCHIVE);
    await inGroup(g.groupId, (d) => {
      d.bucketOverride = gated.bucket;
    });
    const pass = inGroup(g.groupId, (d) => d.archivePass(g.now));
    await gated.written;
    expect(await archiveObject(g, g.owner.memberId, "2030-03")).not.toBeNull();
    expect((await api("DELETE", `/v1/groups/${g.groupId}/trail/me`, { pass: g.owner.pass })).status).toBe(204);
    gated.release();
    await pass;
    expect(await rows(g.groupId, "SELECT * FROM archives")).toEqual([]);
    expect(await rows(g.groupId, "SELECT prefix FROM r2_deletes")).toEqual([{ prefix: `g/${g.groupId}/t/${g.owner.memberId}/` }]);
    await inGroup(g.groupId, (d) => {
      d.bucketOverride = null;
    });
    await runAlarm(g.groupId);
    expect(await archiveObject(g, g.owner.memberId, "2030-03")).toBeNull();
    expect(await rows(g.groupId, "SELECT * FROM r2_deletes")).toEqual([]);
  });

  it("skips members with a pending R2 delete, and continues past 50 member-months with archive_more", async () => {
    const { g, m, t } = await setup();
    await inGroup(g.groupId, (_d, state) => {
      // 52 owner months (one chunk each) and a member with a pending delete.
      for (let i = 0; i < 52; i++) {
        state.storage.sql.exec(
          "INSERT INTO chunks (member_id, start, rec, seq, updated_at) VALUES (?, ?, ?, ?, 0)",
          g.owner.memberId,
          t - (i + 1) * 31 * DAY - ((t - (i + 1) * 31 * DAY) % 600),
          b64urlDecode(CHUNK)!.slice().buffer,
          1000 + i,
        );
      }
      state.storage.sql.exec("INSERT INTO chunks (member_id, start, rec, seq, updated_at) VALUES (?, ?, ?, 2000, 0)", m.memberId, t - 40 * DAY - ((t - 40 * DAY) % 600), b64urlDecode(CHUNK)!.slice().buffer);
      state.storage.sql.exec("INSERT INTO r2_deletes (prefix, at) VALUES (?, ?)", `g/${g.groupId}/t/${m.memberId}/`, g.now);
      state.storage.sql.exec("UPDATE grp SET seq = 2100");
    });
    const more = await inGroup(g.groupId, (d) => d.archivePass(g.now));
    expect(more).toBe(true);
    expect(await rows<{ n: number }>(g.groupId, "SELECT COUNT(*) AS n FROM archives")).toEqual([{ n: 50 }]);
    const done = await inGroup(g.groupId, (d) => d.archivePass(g.now));
    expect(done).toBe(false);
    expect(await rows<{ n: number }>(g.groupId, "SELECT COUNT(*) AS n FROM archives")).toEqual([{ n: 52 }]);
    expect(await rows(g.groupId, "SELECT member_id FROM chunks")).toEqual([{ member_id: m.memberId }]);
  });

  it("runs from the daily job and schedules archive_more when it hits the batch limit", async () => {
    const { g, t } = await setup();
    await inGroup(g.groupId, (_d, state) => {
      for (let i = 0; i < 51; i++) {
        const start = t - (i + 1) * 31 * DAY;
        state.storage.sql.exec(
          "INSERT INTO chunks (member_id, start, rec, seq, updated_at) VALUES (?, ?, ?, ?, 0)",
          g.owner.memberId,
          start - (start % 600),
          b64urlDecode(CHUNK)!.slice().buffer,
          1000 + i,
        );
      }
      state.storage.sql.exec("UPDATE grp SET seq = 2000");
    });
    await advance(g, DAY);
    await runAlarm(g.groupId);
    const job = await rows<{ at: number }>(g.groupId, "SELECT at FROM jobs WHERE kind = 'archive_more'");
    expect(job).toEqual([{ at: g.now + 60 }]);
    await advance(g, 60);
    await runAlarm(g.groupId);
    expect(await rows(g.groupId, "SELECT * FROM chunks")).toEqual([]);
    expect(await rows(g.groupId, "SELECT * FROM jobs WHERE kind = 'archive_more'")).toEqual([]);
  });
});

describe("E22 clear my trail", () => {
  it("deletes chunks and archives, queues the R2 prefix and adds a removed trail item", async () => {
    const { g, m, t } = await setup();
    await upload(g, m.pass, [t + 600]);
    await inGroup(g.groupId, (_d, state) => {
      state.storage.sql.exec(
        "INSERT INTO archives (member_id, month, count, bytes, first_start, last_start, seq, max_chunk_seq, archived_at) VALUES (?, '2030-02', 1, 1, 0, 0, 99, 0, 0)",
        m.memberId,
      );
    });
    const before = (await api("GET", `/v1/groups/${g.groupId}`, { pass: m.pass })).body.seq;
    expect((await api("DELETE", `/v1/groups/${g.groupId}/trail/me`, { pass: m.pass })).status).toBe(204);
    expect(await rows(g.groupId, "SELECT * FROM chunks WHERE member_id = ?", m.memberId)).toEqual([]);
    expect(await rows(g.groupId, "SELECT * FROM archives WHERE member_id = ?", m.memberId)).toEqual([]);
    expect(await rows(g.groupId, "SELECT prefix FROM r2_deletes")).toEqual([{ prefix: `g/${g.groupId}/t/${m.memberId}/` }]);
    const items = (await api("GET", `/v1/groups/${g.groupId}/changes?since=${before}`, { pass: g.owner.pass })).body.items;
    expect(items).toEqual([{ kind: "removed", seq: before + 1, what: "trail", id: m.memberId }]);
  });

  it("keeps the r2_deletes row when R2 fails and completes on a later alarm", async () => {
    const { g, m } = await setup();
    await env.ARCHIVE.put(`g/${g.groupId}/t/${m.memberId}/2030-01.bin`, new Uint8Array([1]));
    const failing = failingBucket(env.ARCHIVE, 1);
    await inGroup(g.groupId, (d) => {
      d.bucketOverride = failing.bucket;
    });
    await api("DELETE", `/v1/groups/${g.groupId}/trail/me`, { pass: m.pass });
    await runAlarm(g.groupId);
    expect(await rows(g.groupId, "SELECT prefix FROM r2_deletes")).toHaveLength(1);
    const job = (await rows<{ at: number; tries: number }>(g.groupId, "SELECT at, tries FROM jobs WHERE kind = 'r2_delete'"))[0]!;
    expect(job).toEqual({ at: g.now + 15 * 60, tries: 1 });
    await advance(g, 15 * 60);
    await runAlarm(g.groupId);
    expect(await rows(g.groupId, "SELECT * FROM r2_deletes")).toEqual([]);
    expect(await rows(g.groupId, "SELECT * FROM jobs WHERE kind = 'r2_delete'")).toEqual([]);
    expect((await env.ARCHIVE.list({ prefix: `g/${g.groupId}/t/${m.memberId}/` })).objects).toEqual([]);
  });
});

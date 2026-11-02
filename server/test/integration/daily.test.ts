// The daily job (SPEC §7.4, §7.10): retention in SQLite, the archive pass after it, and the group's fixed minute.

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { nextDaily } from "../../src/group/GroupDO";
import { addMember, advance, api, createGroup, inGroup, randomBytes, rows, runAlarm, type TestGroup } from "../helpers/client";
import { envelopeOf } from "../helpers/crypto";

const DAY = 86400;
const LIVE = envelopeOf("live");
const CHUNK = envelopeOf("trailChunk");

async function dailyJob(g: TestGroup) {
  return (await rows<{ at: number; tries: number }>(g.groupId, "SELECT at, tries FROM jobs WHERE kind = 'daily'"))[0]!;
}

/** Advances the group's clock to its next daily run. */
async function toDaily(g: TestGroup) {
  await advance(g, (await dailyJob(g)).at - g.now);
}

/** An R2 bucket whose get and put throw. */
function brokenBucket(real: R2Bucket) {
  return new Proxy(real, {
    get(target, prop) {
      if (prop === "get" || prop === "put") {
        return async () => {
          throw new Error("R2 is down");
        };
      }
      const value = Reflect.get(target, prop) as unknown;
      return typeof value === "function" ? (value as (...a: unknown[]) => unknown).bind(target) : value;
    },
  });
}

/** Rows the daily job must delete (`old`) and keep (`recent`), relative to the group's clock. */
async function seedRetention(g: TestGroup) {
  const old = g.now - 30 * DAY - 1;
  const recent = g.now - DAY;
  await inGroup(g.groupId, (_d, state) => {
    const sql = state.storage.sql;
    for (const [at, n] of [[old, 1], [recent, 2]] as const) {
      sql.exec("INSERT INTO gone_passes (pass_hash, reason, at) VALUES (?, 'removed', ?)", randomBytes(32).buffer, at);
      sql.exec(
        "INSERT INTO invites (invite_id, secret_hash, created_by, created_at, expires_at, state, used_at) VALUES (?, ?, ?, ?, ?, 'used', ?)",
        `used-${n}`,
        randomBytes(32).buffer,
        g.owner.memberId,
        at - 60,
        at + 7 * DAY,
        at,
      );
      sql.exec(
        "INSERT INTO invites (invite_id, secret_hash, created_by, created_at, expires_at, state) VALUES (?, ?, ?, ?, ?, 'pending')",
        `expired-${n}`,
        randomBytes(32).buffer,
        g.owner.memberId,
        at - 7 * DAY,
        at,
      );
      sql.exec("INSERT INTO pause_log (paused_at, resumed_at, reason) VALUES (?, ?, 'pro_ended')", at - 60, at);
      sql.exec("INSERT INTO counters (key, window_start, count) VALUES (?, ?, 1)", `test:${n}`, at);
    }
    sql.exec("INSERT INTO counters (key, window_start, count) VALUES ('pro_recheck', 0, 2)");
  });
  return { old, recent };
}

async function expectRetention(g: TestGroup, recent: number) {
  expect(await rows(g.groupId, "SELECT at FROM gone_passes")).toEqual([{ at: recent }]);
  expect(await rows(g.groupId, "SELECT invite_id FROM invites WHERE invite_id LIKE 'used-%' OR invite_id LIKE 'expired-%' ORDER BY invite_id")).toEqual([
    { invite_id: "expired-2" },
    { invite_id: "used-2" },
  ]);
  expect(await rows(g.groupId, "SELECT resumed_at FROM pause_log")).toEqual([{ resumed_at: recent }]);
  expect(await rows(g.groupId, "SELECT key FROM counters ORDER BY key")).toEqual([{ key: "pro_recheck" }, { key: "test:2" }]);
}

describe("daily job", () => {
  it("is set at creation for a whole minute between 01:00 and 04:00 UTC, within a day", async () => {
    for (let i = 0; i < 5; i++) {
      const g = await createGroup();
      const { at, tries } = await dailyJob(g);
      expect(tries).toBe(0);
      expect(at).toBe(nextDaily(g.groupId, g.now));
      expect(at).toBeGreaterThan(g.now);
      expect(at - g.now).toBeLessThanOrEqual(DAY);
      expect(at % 60).toBe(0);
      expect(at % DAY).toBeGreaterThanOrEqual(3600);
      expect(at % DAY).toBeLessThan(4 * 3600);
    }
  });

  it("prunes gone passes, invites, ended pauses, counters and live rows older than 30 days", async () => {
    const g = await createGroup();
    const firstLive = g.now;
    await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: g.owner.pass, body: { live: { at: firstLive, rec: LIVE } } });
    await advance(g, 5); // so the members list (by joinedAt) has a fixed order
    const m = await addMember(g);
    await advance(g, 31 * DAY);
    const { recent } = await seedRetention(g);
    await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: m.pass, body: { live: { at: g.now, rec: LIVE } } });
    expect(await rows(g.groupId, "SELECT member_id, at FROM live ORDER BY at")).toEqual([
      { member_id: g.owner.memberId, at: firstLive },
      { member_id: m.memberId, at: g.now },
    ]);
    expect(await runAlarm(g.groupId)).toBe(true);

    await expectRetention(g, recent);
    // Live: the owner's 31-day-old row goes and their last_live_at is cleared; the member's stays.
    expect(await rows(g.groupId, "SELECT member_id, at FROM live")).toEqual([{ member_id: m.memberId, at: g.now }]);
    expect(await rows(g.groupId, "SELECT member_id, last_live_at FROM members ORDER BY joined_at")).toEqual([
      { member_id: g.owner.memberId, last_live_at: null },
      { member_id: m.memberId, last_live_at: g.now },
    ]);
    const members = (await api("GET", `/v1/groups/${g.groupId}/members`, { pass: m.pass })).body.members;
    expect(members.map((x: { lastLiveAt: number | null }) => x.lastLiveAt)).toEqual([null, g.now]);
    expect(await dailyJob(g)).toEqual({ at: nextDaily(g.groupId, g.now), tries: 0 });
  });

  it("still prunes when the archive pass fails, and continues the pass with archive_more", async () => {
    const g = await createGroup();
    const start = g.now - (g.now % 600);
    expect((await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: g.owner.pass, body: { chunks: [{ start, rec: CHUNK }] } })).body.rejected).toEqual([]);
    await advance(g, 31 * DAY);
    const { recent } = await seedRetention(g);
    await inGroup(g.groupId, (d) => {
      d.bucketOverride = brokenBucket(env.ARCHIVE);
    });
    await runAlarm(g.groupId);

    await expectRetention(g, recent);
    expect(await dailyJob(g)).toEqual({ at: nextDaily(g.groupId, g.now), tries: 0 });
    expect(await rows(g.groupId, "SELECT at, tries FROM jobs WHERE kind = 'archive_more'")).toEqual([{ at: g.now + 15 * 60, tries: 1 }]);
    expect(await rows(g.groupId, "SELECT start FROM chunks")).toEqual([{ start }]);

    await inGroup(g.groupId, (d) => {
      d.bucketOverride = null;
    });
    await advance(g, 15 * 60);
    await runAlarm(g.groupId);
    expect(await rows(g.groupId, "SELECT * FROM chunks")).toEqual([]);
    expect(await rows(g.groupId, "SELECT count FROM archives")).toEqual([{ count: 1 }]);
    expect(await rows(g.groupId, "SELECT * FROM jobs WHERE kind = 'archive_more'")).toEqual([]);
  });

  it("goes back to the group's minute after a run that was retried", async () => {
    const g = await createGroup();
    const slot = (await dailyJob(g)).at % DAY;
    // As after two failures: the job is due two hours past its minute.
    await inGroup(g.groupId, (_d, state) => {
      state.storage.sql.exec("UPDATE jobs SET at = at + 7200, tries = 2 WHERE kind = 'daily'");
    });
    await toDaily(g);
    await runAlarm(g.groupId);
    const next = await dailyJob(g);
    expect(next.tries).toBe(0);
    expect(next.at % DAY).toBe(slot);
    expect(next.at).toBeGreaterThan(g.now);
    expect(next.at - g.now).toBeLessThan(DAY);
  });
});

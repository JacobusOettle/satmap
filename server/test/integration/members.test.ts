// E11–E14: members, profiles, roles, removal and leaving (SPEC §6.5, SD22, SD24, R9).

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import {
  MEMBER_PROFILE,
  addMember,
  advance,
  api,
  createGroup,
  inGroup,
  makeInvite,
  randomId,
  rows,
  runAlarm,
} from "../helpers/client";
import { envelopeOf } from "../helpers/crypto";

const CHUNK = envelopeOf("trailChunk");
const LIVE = envelopeOf("live");
const PIN = envelopeOf("pinWater");

describe("E11 members", () => {
  it("lists current members by joinedAt, with lastLiveAt", async () => {
    const g = await createGroup();
    await advance(g, 5);
    const m = await addMember(g);
    await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: m.pass, body: { live: { at: g.now - 1, rec: LIVE } } });
    const res = await api("GET", `/v1/groups/${g.groupId}/members`, { pass: m.pass });
    expect(res.body.members).toEqual([
      { memberId: g.owner.memberId, role: "owner", joinedAt: g.now - 5, profile: MEMBER_PROFILE, lastLiveAt: null },
      { memberId: m.memberId, role: "member", joinedAt: g.now, profile: MEMBER_PROFILE, lastLiveAt: g.now - 1 },
    ]);
  });
});

describe("E12 my profile and E13 role", () => {
  it("returns the new seq and adds a member change item", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const profile = envelopeOf("memberProfileUnicode");
    const res = await api("PUT", `/v1/groups/${g.groupId}/members/me/profile`, { pass: m.pass, body: { profile } });
    expect(res.status).toBe(200);
    const changes = await api("GET", `/v1/groups/${g.groupId}/changes?since=${res.body.seq - 1}`, { pass: g.owner.pass });
    expect(changes.body.items).toEqual([{ kind: "member", seq: res.body.seq, memberId: m.memberId, role: "member", joinedAt: g.now, profile }]);
    const role = await api("PUT", `/v1/groups/${g.groupId}/members/${m.memberId}/role`, { pass: g.owner.pass, body: { role: "admin" } });
    expect(role.body.seq).toBe(res.body.seq + 1);
    const same = await api("PUT", `/v1/groups/${g.groupId}/members/${m.memberId}/role`, { pass: g.owner.pass, body: { role: "admin" } });
    expect(same.body.seq).toBe(role.body.seq);
    expect((await api("PUT", `/v1/groups/${g.groupId}/members/me/profile`, { pass: m.pass, body: { profile: "AAAA" } })).body.error).toBe("bad_record");
  });
});

describe("E14 remove", () => {
  it("deletes the member's data, cancels offers to them, queues the R2 delete and adds a removed item", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const start = g.now - (g.now % 600);
    expect((await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: m.pass, body: { chunks: [{ start, rec: CHUNK }], live: { at: g.now, rec: LIVE } } })).body.accepted).toEqual([start]);
    const pinId = randomId();
    expect((await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: m.pass, body: { pinId, createdAt: g.now, expiresAt: null, rec: PIN } })).status).toBe(201);
    await env.ARCHIVE.put(`g/${g.groupId}/t/${m.memberId}/2029-01.bin`, new Uint8Array([1]));
    await env.ARCHIVE.put(`g/${g.groupId}/t/${g.owner.memberId}/2029-01.bin`, new Uint8Array([1]));
    await inGroup(g.groupId, (_d, state) => {
      state.storage.sql.exec(
        "INSERT INTO archives (member_id, month, count, bytes, first_start, last_start, seq, max_chunk_seq, archived_at) VALUES (?, '2029-01', 1, 1, 0, 0, 99, 0, 0)",
        m.memberId,
      );
    });
    expect((await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: m.memberId } })).status).toBe(200);

    const before = (await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).body.seq;
    expect((await api("DELETE", `/v1/groups/${g.groupId}/members/${m.memberId}`, { pass: g.owner.pass })).status).toBe(204);

    for (const table of ["chunks", "archives", "pins", "live", "members"]) {
      expect(await rows(g.groupId, `SELECT * FROM ${table} WHERE member_id = ?`, m.memberId), table).toEqual([]);
    }
    expect(await rows(g.groupId, "SELECT prefix FROM r2_deletes")).toEqual([{ prefix: `g/${g.groupId}/t/${m.memberId}/` }]);
    expect(await rows(g.groupId, "SELECT reason FROM gone_passes")).toEqual([{ reason: "removed" }]);
    const status = (await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).body;
    expect(status.transfer).toBeNull();
    expect(status.memberCount).toBe(1);
    const changes = await api("GET", `/v1/groups/${g.groupId}/changes?since=${before}`, { pass: g.owner.pass });
    expect(changes.body.items).toEqual([{ kind: "removed", seq: before + 1, what: "member", id: m.memberId }]);
    expect((await api("GET", `/v1/groups/${g.groupId}/live`, { pass: g.owner.pass })).body.live).toEqual([]);

    // The r2_delete job empties the member's prefix only.
    await runAlarm(g.groupId);
    expect((await env.ARCHIVE.list({ prefix: `g/${g.groupId}/t/${m.memberId}/` })).objects).toEqual([]);
    expect((await env.ARCHIVE.list({ prefix: `g/${g.groupId}/t/${g.owner.memberId}/` })).objects).toHaveLength(1);
    expect(await rows(g.groupId, "SELECT * FROM r2_deletes")).toEqual([]);
  });

  it("answers the removed pass 410 removed for 30 days, then 401 (SD22)", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    await api("DELETE", `/v1/groups/${g.groupId}/members/${m.memberId}`, { pass: g.owner.pass });
    const removed = await api("GET", `/v1/groups/${g.groupId}`, { pass: m.pass });
    expect(removed.status).toBe(410);
    expect(removed.body.error).toBe("removed");
    await advance(g, 30 * 86400);
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: m.pass })).status).toBe(410);
    await advance(g, 1);
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: m.pass })).status).toBe(401);
    // The daily job prunes the row.
    await runAlarm(g.groupId);
    expect(await rows(g.groupId, "SELECT * FROM gone_passes")).toEqual([]);
  });

  it("lets a member leave: their data goes, nothing is kept for the pass, and later requests get 401", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    expect((await api("DELETE", `/v1/groups/${g.groupId}/members/me`, { pass: m.pass })).status).toBe(204);
    expect(await rows(g.groupId, "SELECT * FROM gone_passes")).toEqual([]);
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: m.pass })).status).toBe(401);
    // Leaving by your own id works the same.
    const n = await addMember(g);
    expect((await api("DELETE", `/v1/groups/${g.groupId}/members/${n.memberId}`, { pass: n.pass })).status).toBe(204);
  });

  it("answers 404 for an unknown member, so a repeated delete is done", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    expect((await api("DELETE", `/v1/groups/${g.groupId}/members/${m.memberId}`, { pass: g.owner.pass })).status).toBe(204);
    const again = await api("DELETE", `/v1/groups/${g.groupId}/members/${m.memberId}`, { pass: g.owner.pass });
    expect(again.status).toBe(404);
    expect(again.body.error).toBe("not_found");
  });

  it("never reuses a removed memberId (SD24)", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    await api("DELETE", `/v1/groups/${g.groupId}/members/me`, { pass: m.pass });
    const { token } = await makeInvite(g);
    const res = await api("POST", "/v1/invites/redeem", {
      body: { token, joinProof: g.joinProof, memberId: m.memberId, pass: m.pass, profile: MEMBER_PROFILE, termsVersion: 1 },
    });
    expect(res.status).toBe(409);
    expect(res.body.error).toBe("member_exists");
  });
});

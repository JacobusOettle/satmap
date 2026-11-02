// E4 delete: 410 for 30 days, then the purge and 404; R2 failures during the purge (SPEC §6.5, §7.4, §7.5).

import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { GROUP_PROFILE, MEMBER_PROFILE, addMember, advance, api, appleProofAt, createGroup, inGroup, makeInvite, proStub, rows, runAlarm } from "../helpers/client";

function failingBucket(real: R2Bucket, failures: number) {
  const state = { left: failures };
  return new Proxy(real, {
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
}

describe("E4 delete the group", () => {
  it("keeps only the deleted marker: 410 group_deleted for 30 days, then 404 group_not_found", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const inv = await makeInvite(g);
    await env.ARCHIVE.put(`g/${g.groupId}/t/${m.memberId}/2030-01.bin`, new Uint8Array([1]));
    expect((await api("DELETE", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).status).toBe(204);

    for (const pass of [g.owner.pass, m.pass]) {
      const res = await api("GET", `/v1/groups/${g.groupId}`, { pass });
      expect(res.status).toBe(410);
      expect(res.body.error).toBe("group_deleted");
    }
    expect((await api("GET", `/v1/groups/${g.groupId}/changes`, { pass: "x" })).body.error).toBe("group_deleted");
    expect((await api("POST", "/v1/invites/preview", { body: { token: inv.token, joinProof: g.joinProof } })).body.error).toBe("invite_not_found");
    for (const table of ["members", "invites", "chunks", "pins", "removals", "live", "pause_log", "transfer", "gone_passes"]) {
      expect(await rows(g.groupId, `SELECT * FROM ${table}`), table).toEqual([]);
    }
    expect(await rows(g.groupId, "SELECT status, profile, join_proof_hash FROM grp")).toEqual([{ status: "deleted", profile: null, join_proof_hash: null }]);
    expect(await proStub(g.proName).groupCount()).toBe(0);
    expect(await rows(g.groupId, "SELECT prefix FROM r2_deletes")).toEqual([{ prefix: `g/${g.groupId}/` }]);

    // A create with the same id doesn't bring it back.
    const again = await api("POST", "/v1/groups", {
      body: {
        groupId: g.groupId,
        memberId: g.owner.memberId,
        pass: g.owner.pass,
        joinProof: g.joinProof,
        groupProfile: GROUP_PROFILE,
        memberProfile: MEMBER_PROFILE,
        termsVersion: 1,
        pro: (await appleProofAt(g.now)).pro,
      },
    });
    expect(again.status).toBe(409);

    await runAlarm(g.groupId); // r2_delete
    expect((await env.ARCHIVE.list({ prefix: `g/${g.groupId}/` })).objects).toEqual([]);
    expect(await rows(g.groupId, "SELECT prefix FROM r2_deletes")).toEqual([]);

    await advance(g, 30 * 86400 - 1);
    await runAlarm(g.groupId);
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).status).toBe(410);
    await advance(g, 1);
    await runAlarm(g.groupId);
    const gone = await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass });
    expect(gone.status).toBe(404);
    expect(gone.body.error).toBe("group_not_found");
    expect(await inGroup(g.groupId, (_d, state) => state.storage.sql.exec("SELECT name FROM sqlite_master WHERE type = 'table'").toArray())).toEqual([]);
    expect(await inGroup(g.groupId, (_d, state) => state.storage.getAlarm())).toBeNull();
  });

  it("finishes a purge whose R2 calls fail twice, leaving SQLite untouched until then", async () => {
    const g = await createGroup();
    await env.ARCHIVE.put(`g/${g.groupId}/t/${g.owner.memberId}/2030-01.bin`, new Uint8Array([1]));
    await api("DELETE", `/v1/groups/${g.groupId}`, { pass: g.owner.pass });
    await inGroup(g.groupId, (d, state) => {
      d.bucketOverride = failingBucket(env.ARCHIVE, 2);
      state.storage.sql.exec("DELETE FROM jobs WHERE kind = 'r2_delete'");
      state.storage.sql.exec("DELETE FROM r2_deletes");
    });
    await advance(g, 30 * 86400);
    await runAlarm(g.groupId);
    expect(await rows(g.groupId, "SELECT kind, tries, at FROM jobs")).toEqual([{ kind: "purge", tries: 1, at: g.now + 15 * 60 }]);
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).status).toBe(410);
    await advance(g, 15 * 60);
    await runAlarm(g.groupId);
    expect(await rows(g.groupId, "SELECT kind, tries, at FROM jobs")).toEqual([{ kind: "purge", tries: 2, at: g.now + 3600 }]);
    await advance(g, 3600);
    await runAlarm(g.groupId);
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).status).toBe(404);
    expect((await env.ARCHIVE.list({ prefix: `g/${g.groupId}/` })).objects).toEqual([]);
  });

  it("closes nothing for others: only the owner may delete", async () => {
    const g = await createGroup();
    const admin = await addMember(g, "admin");
    expect((await api("DELETE", `/v1/groups/${g.groupId}`, { pass: admin.pass })).status).toBe(403);
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: admin.pass })).status).toBe(200);
  });
});

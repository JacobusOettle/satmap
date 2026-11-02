// E6–E10: invites, preview and redeem (SPEC §6.5, SD4, SD18, SD23, §12.1).

import { describe, expect, it } from "vitest";
import {
  MEMBER_PROFILE,
  addMember,
  advance,
  api,
  createGroup,
  inGroup,
  makeInvite,
  newMember,
  randomSecret,
  redeem,
  rows,
} from "../helpers/client";
import { b64urlDecode, b64urlEncode, vectors } from "../helpers/crypto";

describe("E6 create an invite", () => {
  it("returns an invite whose token starts with the groupId and expires in 7 days", async () => {
    const g = await createGroup();
    const res = await api("POST", `/v1/groups/${g.groupId}/invites`, { pass: g.owner.pass, body: {} });
    expect(res.status).toBe(201);
    expect(res.body.inviteId).toMatch(/^[A-Za-z0-9_-]{22}$/);
    expect(res.body.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(b64urlEncode(b64urlDecode(res.body.token)!.subarray(0, 16))).toBe(g.groupId);
    expect(res.body.createdAt).toBe(g.now);
    expect(res.body.expiresAt).toBe(g.now + 604800);
    const stored = await rows<{ secret_hash: ArrayBuffer }>(g.groupId, "SELECT secret_hash FROM invites");
    expect(stored).toHaveLength(1);
    expect(new Uint8Array(stored[0]!.secret_hash).byteLength).toBe(32);
  });

  it("is allowed to owners and admins only", async () => {
    const g = await createGroup();
    const admin = await addMember(g, "admin");
    const member = await addMember(g);
    expect((await api("POST", `/v1/groups/${g.groupId}/invites`, { pass: admin.pass, body: {} })).status).toBe(201);
    const res = await api("POST", `/v1/groups/${g.groupId}/invites`, { pass: member.pass, body: {} });
    expect(res.status).toBe(403);
    expect(res.body.error).toBe("forbidden");
  });

  it("allows 100 pending invites and 200 created per day", async () => {
    const g = await createGroup();
    await inGroup(g.groupId, (_d, state) => {
      for (let i = 0; i < 100; i++) {
        state.storage.sql.exec(
          "INSERT INTO invites (invite_id, secret_hash, created_by, created_at, expires_at, state) VALUES (?, ?, ?, ?, ?, 'pending')",
          `inv${i}`,
          crypto.getRandomValues(new Uint8Array(32)).buffer,
          g.owner.memberId,
          g.now,
          g.now + 604800,
        );
      }
    });
    const full = await api("POST", `/v1/groups/${g.groupId}/invites`, { pass: g.owner.pass, body: {} });
    expect(full.status).toBe(429);
    expect(full.headers.get("Retry-After")).not.toBeNull();
    await inGroup(g.groupId, (_d, state) => {
      state.storage.sql.exec("DELETE FROM invites");
      state.storage.sql.exec("INSERT OR REPLACE INTO counters (key, window_start, count) VALUES ('invites:day', ?, 200)", g.now - (g.now % 86400));
    });
    const daily = await api("POST", `/v1/groups/${g.groupId}/invites`, { pass: g.owner.pass, body: {} });
    expect(daily.status).toBe(429);
    await advance(g, 86400);
    expect((await api("POST", `/v1/groups/${g.groupId}/invites`, { pass: g.owner.pass, body: {} })).status).toBe(201);
  });
});

describe("E7 list and E8 cancel", () => {
  it("lists pending, unexpired invites newest first, and cancels them", async () => {
    const g = await createGroup();
    const admin = await addMember(g, "admin");
    const first = await makeInvite(g);
    await advance(g, 10);
    const second = await makeInvite(g, admin);
    const list = await api("GET", `/v1/groups/${g.groupId}/invites`, { pass: admin.pass });
    expect(list.status).toBe(200);
    expect(list.body.invites).toEqual([
      { inviteId: second.inviteId, createdBy: admin.memberId, createdAt: g.now, expiresAt: g.now + 604800 },
      { inviteId: first.inviteId, createdBy: g.owner.memberId, createdAt: g.now - 10, expiresAt: g.now - 10 + 604800 },
    ]);
    // An admin may cancel any pending invite.
    expect((await api("DELETE", `/v1/groups/${g.groupId}/invites/${first.inviteId}`, { pass: admin.pass })).status).toBe(204);
    const again = await api("DELETE", `/v1/groups/${g.groupId}/invites/${first.inviteId}`, { pass: admin.pass });
    expect(again.status).toBe(404);
    expect(again.body.error).toBe("not_found");
    expect((await api("GET", `/v1/groups/${g.groupId}/invites`, { pass: g.owner.pass })).body.invites).toHaveLength(1);
    const preview = await api("POST", "/v1/invites/preview", { body: { token: first.token, joinProof: g.joinProof } });
    expect(preview.body.error).toBe("invite_not_found");
  });

  it("is refused to members, and to a malformed inviteId", async () => {
    const g = await createGroup();
    const member = await addMember(g);
    const inv = await makeInvite(g);
    expect((await api("GET", `/v1/groups/${g.groupId}/invites`, { pass: member.pass })).status).toBe(403);
    expect((await api("DELETE", `/v1/groups/${g.groupId}/invites/${inv.inviteId}`, { pass: member.pass })).status).toBe(403);
    expect((await api("DELETE", `/v1/groups/${g.groupId}/invites/${vectors.mustFail.nonCanonicalIds[0]}`, { pass: g.owner.pass })).status).toBe(400);
  });
});

describe("E9 preview", () => {
  it("shows the group and the inviter without using up the invite", async () => {
    const g = await createGroup();
    const admin = await addMember(g, "admin");
    const inv = await makeInvite(g, admin);
    for (let i = 0; i < 2; i++) {
      const res = await api("POST", "/v1/invites/preview", { body: { token: inv.token, joinProof: g.joinProof } });
      expect(res.status).toBe(200);
      expect(res.body).toEqual({
        groupId: g.groupId,
        status: "active",
        profile: g.status.profile,
        memberCount: 2,
        inviter: { memberId: admin.memberId, profile: MEMBER_PROFILE },
        expiresAt: g.now + 604800,
      });
    }
    expect((await redeem(g, inv.token)).status).toBe(201);
  });

  it("answers 404 invite_not_found for a wrong proof, an unknown token or an unknown group", async () => {
    const g = await createGroup();
    const inv = await makeInvite(g);
    const tokenBytes = b64urlDecode(inv.token)!;
    const otherSecret = new Uint8Array(tokenBytes);
    otherSecret[31] = otherSecret[31]! ^ 1;
    for (const body of [
      { token: inv.token, joinProof: randomSecret() },
      { token: b64urlEncode(otherSecret), joinProof: g.joinProof },
      { token: randomSecret(), joinProof: g.joinProof }, // a group that doesn't exist
    ]) {
      const res = await api("POST", "/v1/invites/preview", { body });
      expect(res.status).toBe(404);
      expect(res.body.error).toBe("invite_not_found");
    }
    expect((await api("POST", "/v1/invites/preview", { body: { token: "short", joinProof: g.joinProof } })).status).toBe(400);
  });

  it("answers 404 once the invite expired (7 days)", async () => {
    const g = await createGroup();
    const inv = await makeInvite(g);
    await advance(g, 604799);
    expect((await api("POST", "/v1/invites/preview", { body: { token: inv.token, joinProof: g.joinProof } })).status).toBe(200);
    await advance(g, 1);
    expect((await api("POST", "/v1/invites/preview", { body: { token: inv.token, joinProof: g.joinProof } })).status).toBe(404);
    expect((await redeem(g, inv.token)).body.error).toBe("invite_not_found");
    expect((await api("GET", `/v1/groups/${g.groupId}/invites`, { pass: g.owner.pass })).body.invites).toEqual([]);
    expect((await api("DELETE", `/v1/groups/${g.groupId}/invites/${inv.inviteId}`, { pass: g.owner.pass })).status).toBe(404);
  });

  it("applies RL_INVITE: 10 per IP per minute, for preview and redeem separately", async () => {
    const g = await createGroup();
    const inv = await makeInvite(g);
    const ip = "192.0.2.10";
    for (let i = 0; i < 10; i++) {
      expect((await api("POST", "/v1/invites/preview", { body: { token: inv.token, joinProof: g.joinProof }, ip })).status).toBe(200);
    }
    const limited = await api("POST", "/v1/invites/preview", { body: { token: inv.token, joinProof: g.joinProof }, ip });
    expect(limited.status).toBe(429);
    expect(limited.headers.get("Retry-After")).toBe("60");
    const m = newMember();
    const res = await api("POST", "/v1/invites/redeem", {
      body: { token: inv.token, joinProof: g.joinProof, memberId: m.memberId, pass: m.pass, profile: MEMBER_PROFILE, termsVersion: 1 },
      ip,
    });
    expect(res.status).toBe(201);
  });
});

describe("E10 redeem", () => {
  it("joins as a member with joinedAt now and a member change item", async () => {
    const g = await createGroup();
    const inv = await makeInvite(g);
    const m = newMember();
    const res = await redeem(g, inv.token, m);
    expect(res.status).toBe(201);
    expect(res.body.you).toEqual({ memberId: m.memberId, role: "member", joinedAt: g.now });
    expect(res.body.memberCount).toBe(2);
    const changes = await api("GET", `/v1/groups/${g.groupId}/changes?since=2`, { pass: m.pass });
    expect(changes.body.items).toEqual([{ kind: "member", seq: 3, memberId: m.memberId, role: "member", joinedAt: g.now, profile: MEMBER_PROFILE }]);
    const stored = await rows<{ state: string; used_by: string }>(g.groupId, "SELECT state, used_by FROM invites");
    expect(stored).toEqual([{ state: "used", used_by: m.memberId }]);
  });

  it("works once: a retry with the same pass gets 200 and the same status, anyone else 404", async () => {
    const g = await createGroup();
    const inv = await makeInvite(g);
    const m = newMember();
    const first = await redeem(g, inv.token, m);
    const retry = await redeem(g, inv.token, m);
    expect(retry.status).toBe(200);
    expect(retry.body.you).toEqual(first.body.you);
    const other = await redeem(g, inv.token, newMember());
    expect(other.status).toBe(404);
    expect(other.body.error).toBe("invite_not_found");
    // After the member leaves, the retry no longer works.
    expect((await api("DELETE", `/v1/groups/${g.groupId}/members/me`, { pass: m.pass })).status).toBe(204);
    expect((await redeem(g, inv.token, m)).status).toBe(404);
  });

  it("is atomic: of two redeems at once only one wins", async () => {
    const g = await createGroup();
    const inv = await makeInvite(g);
    const results = await Promise.all([redeem(g, inv.token), redeem(g, inv.token), redeem(g, inv.token)]);
    expect(results.map((r) => r.status).sort()).toEqual([201, 404, 404]);
    expect((await api("GET", `/v1/groups/${g.groupId}/members`, { pass: g.owner.pass })).body.members).toHaveLength(2);
  });

  it("refuses a memberId that is taken now or was removed (SD24), and a wrong termsVersion", async () => {
    const g = await createGroup();
    const member = await addMember(g);
    const clash = await redeem(g, (await makeInvite(g)).token, { memberId: member.memberId, pass: randomSecret() });
    expect(clash.status).toBe(409);
    expect(clash.body.error).toBe("member_exists");
    expect((await api("DELETE", `/v1/groups/${g.groupId}/members/${member.memberId}`, { pass: g.owner.pass })).status).toBe(204);
    const inv = await makeInvite(g);
    const reuse = await redeem(g, inv.token, { memberId: member.memberId, pass: randomSecret() });
    expect(reuse.body.error).toBe("member_exists");
    // The invite is still pending after the clash.
    expect((await redeem(g, inv.token)).status).toBe(201);

    const inv2 = await makeInvite(g);
    const m = newMember();
    const old = await api("POST", "/v1/invites/redeem", {
      body: { token: inv2.token, joinProof: g.joinProof, memberId: m.memberId, pass: m.pass, profile: MEMBER_PROFILE, termsVersion: 0 },
    });
    expect(old.status).toBe(409);
    expect(old.body.error).toBe("terms_outdated");
  });

  it("validates the body", async () => {
    const g = await createGroup();
    const inv = await makeInvite(g);
    const m = newMember();
    const base = { token: inv.token, joinProof: g.joinProof, memberId: m.memberId, pass: m.pass, profile: MEMBER_PROFILE, termsVersion: 1 };
    for (const [over, code] of [
      [{ memberId: vectors.mustFail.nonCanonicalIds[1] }, "bad_request"],
      [{ pass: "x" }, "bad_request"],
      [{ profile: "AAAA" }, "bad_record"],
      [{ joinProof: 5 }, "bad_request"],
    ] as const) {
      const res = await api("POST", "/v1/invites/redeem", { body: { ...base, ...over } });
      expect(res.status, JSON.stringify(over)).toBe(400);
      expect(res.body.error).toBe(code);
    }
  });
});

describe("SD23: an invite dies with its maker's admin role", () => {
  for (const how of ["removed", "demoted", "left"] as const) {
    it(`cancels the pending invites of an admin who was ${how}`, async () => {
      const g = await createGroup();
      const admin = await addMember(g, "admin");
      const inv = await makeInvite(g, admin);
      const other = await makeInvite(g);
      if (how === "removed") {
        expect((await api("DELETE", `/v1/groups/${g.groupId}/members/${admin.memberId}`, { pass: g.owner.pass })).status).toBe(204);
      } else if (how === "demoted") {
        expect((await api("PUT", `/v1/groups/${g.groupId}/members/${admin.memberId}/role`, { pass: g.owner.pass, body: { role: "member" } })).status).toBe(200);
      } else {
        expect((await api("DELETE", `/v1/groups/${g.groupId}/members/me`, { pass: admin.pass })).status).toBe(204);
      }
      const preview = await api("POST", "/v1/invites/preview", { body: { token: inv.token, joinProof: g.joinProof } });
      expect(preview.status).toBe(404);
      expect(preview.body.error).toBe("invite_not_found");
      expect((await redeem(g, inv.token)).body.error).toBe("invite_not_found");
      // The owner's invite still works.
      expect((await redeem(g, other.token)).status).toBe(201);
    });
  }
});

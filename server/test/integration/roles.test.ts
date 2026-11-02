// The role table of SPEC §1.3, cell by cell: what the owner, an admin and a member may do.

import { describe, expect, it } from "vitest";
import { GROUP_PROFILE, MEMBER_PROFILE, addMember, api, appleProofAt, createGroup, randomId, type Member, type TestGroup } from "../helpers/client";
import { envelopeOf } from "../helpers/crypto";

type Role = "owner" | "admin" | "member";

interface World {
  g: TestGroup;
  admin: Member;
  member: Member;
  actors: Record<Role, Member>;
}

async function world(): Promise<World> {
  const g = await createGroup();
  const admin = await addMember(g, "admin");
  const member = await addMember(g);
  return { g, admin, member, actors: { owner: g.owner, admin, member } };
}

const PIN_REC = envelopeOf("pinWater");

async function dropPin(g: TestGroup, by: Member): Promise<string> {
  const pinId = randomId();
  const res = await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: by.pass, body: { pinId, createdAt: g.now, expiresAt: null, rec: PIN_REC } });
  if (res.status !== 201) throw new Error(`pin: ${res.status} ${res.text}`);
  return pinId;
}

/** Runs `act` once per role in a fresh group and returns the statuses. */
async function statuses(act: (w: World, actor: Member) => Promise<number>): Promise<Record<Role, number>> {
  const out = {} as Record<Role, number>;
  for (const role of ["owner", "admin", "member"] as const) {
    const w = await world();
    out[role] = await act(w, w.actors[role]);
  }
  return out;
}

describe("§1.3 role table", () => {
  it("share own location, drop pins, delete own pins, clear own trail: everyone", async () => {
    expect(
      await statuses(async (w, a) => (await api("POST", `/v1/groups/${w.g.groupId}/upload`, { pass: a.pass, body: { live: { at: w.g.now, rec: envelopeOf("live") } } })).status),
    ).toEqual({ owner: 200, admin: 200, member: 200 });
    expect(await statuses(async (w, a) => (await dropPin(w.g, a), 201))).toEqual({ owner: 201, admin: 201, member: 201 });
    expect(
      await statuses(async (w, a) => (await api("DELETE", `/v1/groups/${w.g.groupId}/pins/${await dropPin(w.g, a)}`, { pass: a.pass })).status),
    ).toEqual({ owner: 204, admin: 204, member: 204 });
    expect(await statuses(async (w, a) => (await api("DELETE", `/v1/groups/${w.g.groupId}/trail/me`, { pass: a.pass })).status)).toEqual({
      owner: 204,
      admin: 204,
      member: 204,
    });
  });

  it("change own display name and colour: everyone", async () => {
    expect(
      await statuses(async (w, a) => (await api("PUT", `/v1/groups/${w.g.groupId}/members/me/profile`, { pass: a.pass, body: { profile: MEMBER_PROFILE } })).status),
    ).toEqual({ owner: 200, admin: 200, member: 200 });
  });

  it("report any member or pin: everyone; report the group name: admins and members, not the owner", async () => {
    expect(
      await statuses(async (w, a) =>
        (await api("POST", `/v1/groups/${w.g.groupId}/reports`, { pass: a.pass, body: { targetKind: "member", targetId: w.member.memberId, reason: "spam" } })).status,
      ),
    ).toEqual({ owner: 202, admin: 202, member: 202 });
    expect(
      await statuses(async (w, a) =>
        (await api("POST", `/v1/groups/${w.g.groupId}/reports`, { pass: a.pass, body: { targetKind: "pin", targetId: await dropPin(w.g, w.member), reason: "other" } })).status,
      ),
    ).toEqual({ owner: 202, admin: 202, member: 202 });
    expect(
      await statuses(async (w, a) =>
        (await api("POST", `/v1/groups/${w.g.groupId}/reports`, { pass: a.pass, body: { targetKind: "group", targetId: w.g.groupId, reason: "offensive" } })).status,
      ),
    ).toEqual({ owner: 403, admin: 202, member: 202 });
  });

  it("leave the group: not the owner (owner_must_hand_over)", async () => {
    const res = await statuses(async (w, a) => (await api("DELETE", `/v1/groups/${w.g.groupId}/members/me`, { pass: a.pass })).status);
    expect(res).toEqual({ owner: 409, admin: 204, member: 204 });
    const w = await world();
    expect((await api("DELETE", `/v1/groups/${w.g.groupId}/members/me`, { pass: w.g.owner.pass })).body.error).toBe("owner_must_hand_over");
  });

  it("create invites, list and cancel any pending invite: owner and admins", async () => {
    expect(await statuses(async (w, a) => (await api("POST", `/v1/groups/${w.g.groupId}/invites`, { pass: a.pass, body: {} })).status)).toEqual({
      owner: 201,
      admin: 201,
      member: 403,
    });
    expect(await statuses(async (w, a) => (await api("GET", `/v1/groups/${w.g.groupId}/invites`, { pass: a.pass })).status)).toEqual({
      owner: 200,
      admin: 200,
      member: 403,
    });
    expect(
      await statuses(async (w, a) => {
        const inv = (await api("POST", `/v1/groups/${w.g.groupId}/invites`, { pass: w.g.owner.pass, body: {} })).body;
        return (await api("DELETE", `/v1/groups/${w.g.groupId}/invites/${inv.inviteId}`, { pass: a.pass })).status;
      }),
    ).toEqual({ owner: 204, admin: 204, member: 403 });
  });

  it("remove a member whose role is member: owner and admins", async () => {
    expect(
      await statuses(async (w, a) => {
        const target = await addMember(w.g);
        return (await api("DELETE", `/v1/groups/${w.g.groupId}/members/${target.memberId}`, { pass: a.pass })).status;
      }),
    ).toEqual({ owner: 204, admin: 204, member: 403 });
  });

  it("remove an admin: only the owner", async () => {
    expect(
      await statuses(async (w, a) => {
        const target = await addMember(w.g, "admin");
        return (await api("DELETE", `/v1/groups/${w.g.groupId}/members/${target.memberId}`, { pass: a.pass })).status;
      }),
    ).toEqual({ owner: 204, admin: 403, member: 403 });
  });

  it("nobody can remove the owner", async () => {
    const res = await statuses(async (w, a) => (await api("DELETE", `/v1/groups/${w.g.groupId}/members/${w.g.owner.memberId}`, { pass: a.pass })).status);
    expect(res).toEqual({ owner: 409, admin: 403, member: 403 });
  });

  it("make a member an admin, or an admin a member: only the owner", async () => {
    expect(
      await statuses(async (w, a) => (await api("PUT", `/v1/groups/${w.g.groupId}/members/${w.member.memberId}/role`, { pass: a.pass, body: { role: "admin" } })).status),
    ).toEqual({ owner: 200, admin: 403, member: 403 });
    expect(
      await statuses(async (w, a) => (await api("PUT", `/v1/groups/${w.g.groupId}/members/${w.admin.memberId}/role`, { pass: a.pass, body: { role: "member" } })).status),
    ).toEqual({ owner: 200, admin: 403, member: 403 });
  });

  it("rename the group: owner and admins", async () => {
    expect(
      await statuses(async (w, a) => (await api("PUT", `/v1/groups/${w.g.groupId}/profile`, { pass: a.pass, body: { profile: GROUP_PROFILE } })).status),
    ).toEqual({ owner: 200, admin: 200, member: 403 });
  });

  it("delete any pin: owner and admins; members only their own", async () => {
    expect(
      await statuses(async (w, a) => {
        const other = await addMember(w.g);
        return (await api("DELETE", `/v1/groups/${w.g.groupId}/pins/${await dropPin(w.g, other)}`, { pass: a.pass })).status;
      }),
    ).toEqual({ owner: 204, admin: 204, member: 403 });
  });

  it("offer ownership to a member: only the owner", async () => {
    expect(
      await statuses(async (w, a) => {
        const to = a.memberId === w.member.memberId ? w.admin.memberId : w.member.memberId;
        return (await api("POST", `/v1/groups/${w.g.groupId}/transfer`, { pass: a.pass, body: { to } })).status;
      }),
    ).toEqual({ owner: 200, admin: 403, member: 403 });
  });

  it("accept an ownership offer: only the member it was offered to, with Pro", async () => {
    const w = await world();
    expect((await api("POST", `/v1/groups/${w.g.groupId}/transfer`, { pass: w.g.owner.pass, body: { to: w.member.memberId } })).status).toBe(200);
    const { pro } = await appleProofAt(w.g.now);
    expect((await api("POST", `/v1/groups/${w.g.groupId}/transfer/accept`, { pass: w.admin.pass, body: { pro } })).status).toBe(403);
    expect((await api("POST", `/v1/groups/${w.g.groupId}/transfer/accept`, { pass: w.g.owner.pass, body: { pro } })).status).toBe(403);
    const { pro: noPro } = (await appleProofAt(w.g.now, { expiresDate: (w.g.now - 1) * 1000 }));
    const inactive = await api("POST", `/v1/groups/${w.g.groupId}/transfer/accept`, { pass: w.member.pass, body: { pro: noPro } });
    expect(inactive.status).toBe(402);
    expect((await api("POST", `/v1/groups/${w.g.groupId}/transfer/accept`, { pass: w.member.pass, body: { pro } })).status).toBe(200);
  });

  it("delete the group: only the owner", async () => {
    expect(await statuses(async (w, a) => (await api("DELETE", `/v1/groups/${w.g.groupId}`, { pass: a.pass })).status)).toEqual({
      owner: 204,
      admin: 403,
      member: 403,
    });
  });

  it("send a new Pro proof: only the owner", async () => {
    expect(
      await statuses(async (w, a) => {
        const { pro } = await appleProofAt(w.g.now);
        return (await api("PUT", `/v1/groups/${w.g.groupId}/pro`, { pass: a.pass, body: { pro } })).status;
      }),
    ).toEqual({ owner: 200, admin: 403, member: 403 });
  });

  it("role changes and offers aimed at the owner or yourself answer 409 not_allowed", async () => {
    const w = await world();
    for (const target of [w.g.owner.memberId, "me"]) {
      const res = await api("PUT", `/v1/groups/${w.g.groupId}/members/${target}/role`, { pass: w.g.owner.pass, body: { role: "admin" } });
      expect(res.status).toBe(409);
      expect(res.body.error).toBe("not_allowed");
    }
    const offer = await api("POST", `/v1/groups/${w.g.groupId}/transfer`, { pass: w.g.owner.pass, body: { to: w.g.owner.memberId } });
    expect(offer.body.error).toBe("not_allowed");
    expect((await api("PUT", `/v1/groups/${w.g.groupId}/members/${randomId()}/role`, { pass: w.g.owner.pass, body: { role: "admin" } })).status).toBe(404);
    expect((await api("PUT", `/v1/groups/${w.g.groupId}/members/${w.member.memberId}/role`, { pass: w.g.owner.pass, body: { role: "owner" } })).status).toBe(400);
  });
});

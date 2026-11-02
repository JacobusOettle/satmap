// E15–E17: ownership offers, decline, expiry and accept with Pro (SPEC §6.5, §10.8).

import { describe, expect, it } from "vitest";
import { addMember, advance, api, appleProofAt, createGroup, proStub, rows, runAlarm } from "../helpers/client";

describe("E15 offer and E16 cancel or decline", () => {
  it("shows an open offer in the status, replaces an earlier one, and expires after 7 days", async () => {
    const g = await createGroup();
    const a = await addMember(g);
    const b = await addMember(g);
    const first = await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: a.memberId } });
    expect(first.status).toBe(200);
    expect(first.body.transfer).toEqual({ to: a.memberId, expiresAt: g.now + 604800 });
    await advance(g, 100);
    const second = await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: b.memberId } });
    expect(second.body.transfer).toEqual({ to: b.memberId, expiresAt: g.now + 604800 });
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: a.pass })).body.transfer).toEqual({ to: b.memberId, expiresAt: g.now + 604800 });

    await advance(g, 604800);
    // Expired: hidden at once, deleted by the alarm.
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: b.pass })).body.transfer).toBeNull();
    expect(await runAlarm(g.groupId)).toBe(true);
    expect(await rows(g.groupId, "SELECT * FROM transfer")).toEqual([]);
    expect((await api("DELETE", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass })).status).toBe(404);
  });

  it("lets the target decline and the owner cancel; others get 403; no offer is 404", async () => {
    const g = await createGroup();
    const a = await addMember(g);
    const other = await addMember(g, "admin");
    expect((await api("DELETE", `/v1/groups/${g.groupId}/transfer`, { pass: a.pass })).status).toBe(404);
    await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: a.memberId } });
    expect((await api("DELETE", `/v1/groups/${g.groupId}/transfer`, { pass: other.pass })).status).toBe(403);
    expect((await api("DELETE", `/v1/groups/${g.groupId}/transfer`, { pass: a.pass })).status).toBe(204);
    await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: a.memberId } });
    expect((await api("DELETE", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass })).status).toBe(204);
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: a.pass })).body.transfer).toBeNull();
  });

  it("answers 404 for an offer to someone who is not a member", async () => {
    const g = await createGroup();
    const res = await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: "AAAAAAAAAAAAAAAAAAAAAA" } });
    expect(res.status).toBe(404);
    expect((await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: "nope" } })).status).toBe(400);
  });
});

describe("E17 accept", () => {
  it("makes the target owner and the old owner admin, and moves the group to the new purchase", async () => {
    const g = await createGroup();
    const a = await addMember(g);
    await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: a.memberId } });
    const before = (await api("GET", `/v1/groups/${g.groupId}`, { pass: a.pass })).body.seq;
    const { pro, proName } = await appleProofAt(g.now, { productId: "pro.monthly", expiresDate: (g.now + 30 * 86400) * 1000 });
    const res = await api("POST", `/v1/groups/${g.groupId}/transfer/accept`, { pass: a.pass, body: { pro } });
    expect(res.status).toBe(200);
    expect(res.body.you.role).toBe("owner");
    expect(res.body.ownerId).toBe(a.memberId);
    expect(res.body.transfer).toBeNull();
    expect(res.body.pro).toEqual({ platform: "apple", productId: "pro.monthly", expiresAt: g.now + 30 * 86400 });
    const changes = await api("GET", `/v1/groups/${g.groupId}/changes?since=${before}`, { pass: a.pass });
    expect(changes.body.items.map((i: { memberId: string; role: string }) => [i.memberId, i.role])).toEqual([
      [g.owner.memberId, "admin"],
      [a.memberId, "owner"],
    ]);
    expect(await proStub(proName).groupCount()).toBe(1);
    expect(await proStub(g.proName).groupCount()).toBe(0);
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).body.you.role).toBe("admin");
  });

  it("needs an open offer (404) to the caller (403) and a working proof (402)", async () => {
    const g = await createGroup();
    const a = await addMember(g);
    const { pro } = await appleProofAt(g.now);
    expect((await api("POST", `/v1/groups/${g.groupId}/transfer/accept`, { pass: a.pass, body: { pro } })).status).toBe(404);
    await api("POST", `/v1/groups/${g.groupId}/transfer`, { pass: g.owner.pass, body: { to: a.memberId } });
    const bad = await api("POST", `/v1/groups/${g.groupId}/transfer/accept`, { pass: a.pass, body: { pro: { platform: "apple", signedTransaction: "x.y.z" } } });
    expect(bad.status).toBe(402);
    expect(bad.body.error).toBe("pro_invalid");
    expect((await api("POST", `/v1/groups/${g.groupId}/transfer/accept`, { pass: a.pass, body: {} })).status).toBe(400);
  });
});

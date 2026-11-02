// E25 reports (facts, limits, target checks) and the admin endpoints A1–A6 (SPEC §6.5, §7.7, SD10, SD28).

import { env } from "cloudflare:workers";
import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it, vi } from "vitest";
import worker from "../../src/index";
import { BASE, addMember, adminStub, api, createGroup, randomId, rows } from "../helpers/client";
import { envelopeOf } from "../helpers/crypto";

const ADMIN = "test-admin-token-0123456789abcdefghijklmnop";
const PIN = envelopeOf("pinAnimalPadded");

async function admin(method: string, path: string, body?: unknown) {
  return api(method, path, { body, headers: { Authorization: `Bearer ${ADMIN}` } });
}

async function listFor(groupId: string, query = "status=all") {
  const res = await admin("GET", `/v1/admin/reports?${query}&limit=500`);
  expect(res.status).toBe(200);
  return (res.body.reports as Array<Record<string, unknown>>).filter((r) => r.groupId === groupId);
}

describe("E25 report", () => {
  it("stores the reporter's claim with the server's own facts about a pin", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const pinId = randomId();
    await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: m.pass, body: { pinId, createdAt: g.now - 60, expiresAt: g.now - 60 + 43200, rec: PIN } });
    const res = await api("POST", `/v1/groups/${g.groupId}/reports`, {
      pass: g.owner.pass,
      body: {
        targetKind: "pin",
        targetId: pinId,
        reason: "offensive",
        comment: "Not a real sighting",
        content: { type: "animal", animal: "Rhino", note: "Two adults", name: "Thandi", lat: -24.9, extra: "dropped" },
      },
    });
    expect(res.status).toBe(202);
    expect(res.body.reportId).toMatch(/^[A-Za-z0-9_-]{22}$/);
    const [report] = await listFor(g.groupId);
    expect(report).toEqual({
      reportId: res.body.reportId,
      createdAt: g.now,
      groupId: g.groupId,
      reporterId: g.owner.memberId,
      targetKind: "pin",
      targetId: pinId,
      reason: "offensive",
      comment: "Not a real sighting",
      content: { type: "animal", animal: "Rhino", note: "Two adults", name: "Thandi" },
      facts: { targetExists: true, targetMemberId: m.memberId, targetTime: g.now - 60, targetBytes: 157 },
      reporters: 1,
      status: "open",
      resolvedAt: null,
      action: null,
      adminNote: null,
    });
  });

  it("records facts for members, the group name and targets already gone", async () => {
    const g = await createGroup();
    const a = await addMember(g);
    const b = await addMember(g);
    const pinId = randomId();
    await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: b.pass, body: { pinId, createdAt: g.now, expiresAt: null, rec: PIN } });
    await api("DELETE", `/v1/groups/${g.groupId}/pins/${pinId}`, { pass: b.pass });
    const report = (body: Record<string, unknown>) => api("POST", `/v1/groups/${g.groupId}/reports`, { pass: a.pass, body: { reason: "other", ...body } });
    expect((await report({ targetKind: "member", targetId: b.memberId, content: { name: "B" } })).status).toBe(202);
    expect((await report({ targetKind: "group", targetId: g.groupId, content: { name: "Kruger trip" } })).status).toBe(202);
    expect((await report({ targetKind: "pin", targetId: pinId })).status).toBe(202);
    await api("DELETE", `/v1/groups/${g.groupId}/members/${b.memberId}`, { pass: g.owner.pass });
    expect((await report({ targetKind: "member", targetId: b.memberId })).status).toBe(202);
    const facts = (await listFor(g.groupId)).map((r) => [r.targetKind, r.facts]);
    expect(facts).toEqual(
      expect.arrayContaining([
        ["member", { targetExists: true, targetMemberId: b.memberId, targetTime: g.now, targetBytes: 93 }],
        ["group", { targetExists: true, targetMemberId: null, targetTime: null, targetBytes: 93 }],
        ["pin", { targetExists: false, targetMemberId: null, targetTime: null, targetBytes: null }],
        ["member", { targetExists: false, targetMemberId: b.memberId, targetTime: null, targetBytes: null }],
      ]),
    );
  });

  it("answers 404 for a target that never existed and 400 for malformed reports", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const report = (body: Record<string, unknown>) => api("POST", `/v1/groups/${g.groupId}/reports`, { pass: m.pass, body });
    expect((await report({ targetKind: "pin", targetId: randomId(), reason: "spam" })).status).toBe(404);
    expect((await report({ targetKind: "member", targetId: randomId(), reason: "spam" })).status).toBe(404);
    expect((await report({ targetKind: "group", targetId: randomId(), reason: "spam" })).status).toBe(404);
    for (const body of [
      { targetKind: "photo", targetId: m.memberId, reason: "spam" },
      { targetKind: "member", targetId: "x", reason: "spam" },
      { targetKind: "member", targetId: m.memberId, reason: "rude" },
      { targetKind: "member", targetId: m.memberId, reason: "spam", comment: "x".repeat(501) },
      { targetKind: "member", targetId: m.memberId, reason: "spam", comment: 5 },
      { targetKind: "member", targetId: m.memberId, reason: "spam", content: { name: "n".repeat(31) } },
      { targetKind: "group", targetId: g.groupId, reason: "spam", content: { name: "n".repeat(41) } },
      { targetKind: "member", targetId: m.memberId, reason: "spam", content: "x" },
    ]) {
      const res = await report(body);
      expect(res.status, JSON.stringify(body)).toBe(400);
    }
    // Code points, not bytes: 30 rhinos are a valid member name.
    expect((await report({ targetKind: "member", targetId: m.memberId, reason: "spam", content: { name: "🦏".repeat(30) } })).status).toBe(202);
    expect((await report({ targetKind: "group", targetId: g.groupId, reason: "spam", content: { name: "n".repeat(40) } })).status).toBe(202);
  });

  it("allows 20 reports per member per day", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    for (let i = 0; i < 20; i++) {
      expect((await api("POST", `/v1/groups/${g.groupId}/reports`, { pass: m.pass, body: { targetKind: "group", targetId: g.groupId, reason: "spam" } })).status).toBe(202);
    }
    const limited = await api("POST", `/v1/groups/${g.groupId}/reports`, { pass: m.pass, body: { targetKind: "group", targetId: g.groupId, reason: "spam" } });
    expect(limited.status).toBe(429);
  });
});

describe("admin endpoints", () => {
  it("answer 404 everywhere when ADMIN_TOKEN is unset, and 401 for a wrong token", async () => {
    for (const path of ["/v1/admin/reports", `/v1/admin/groups/${randomId()}`]) {
      const res = await worker.fetch(new Request(BASE + path, { headers: { Authorization: `Bearer ${ADMIN}` } }), { ...env, ADMIN_TOKEN: "" } as typeof env);
      expect(res.status).toBe(404);
    }
    expect((await api("GET", "/v1/admin/reports", { headers: { Authorization: "Bearer nope" } })).status).toBe(401);
    expect((await api("GET", "/v1/admin/reports")).status).toBe(401);
  });

  it("A1 lists newest first with the number of distinct reporters; A2 resolves", async () => {
    const g = await createGroup();
    const a = await addMember(g);
    const b = await addMember(g);
    const target = await addMember(g);
    for (const reporter of [a, b, a]) {
      await api("POST", `/v1/groups/${g.groupId}/reports`, { pass: reporter.pass, body: { targetKind: "member", targetId: target.memberId, reason: "harassment" } });
    }
    const reports = await listFor(g.groupId, "status=open");
    expect(reports).toHaveLength(3);
    expect(reports.every((r) => r.reporters === 2)).toBe(true);
    const id = reports[0]!.reportId as string;
    const resolved = await admin("POST", `/v1/admin/reports/${id}/resolve`, { action: "member_removed", note: "two reporters" });
    expect(resolved.status).toBe(200);
    expect(resolved.body).toMatchObject({ reportId: id, status: "resolved", action: "member_removed", adminNote: "two reporters", resolvedAt: expect.any(Number) });
    expect(await listFor(g.groupId, "status=open")).toHaveLength(2);
    expect(await listFor(g.groupId, "status=resolved")).toHaveLength(1);
    expect((await admin("POST", `/v1/admin/reports/${randomId()}/resolve`, { action: "none" })).status).toBe(404);
    expect((await admin("POST", `/v1/admin/reports/${id}/resolve`, { action: "ban" })).status).toBe(400);
    expect((await admin("GET", "/v1/admin/reports?status=new")).status).toBe(400);
    const before = await admin("GET", `/v1/admin/reports?status=all&before=1&limit=5`);
    expect(before.body.reports).toEqual([]);
  });

  it("A3 deletes a pin, A4 removes a member (not the owner), A5 deletes the group, A6 shows metadata", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const pinId = randomId();
    await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: m.pass, body: { pinId, createdAt: g.now, expiresAt: null, rec: PIN } });
    expect((await admin("DELETE", `/v1/admin/groups/${g.groupId}/pins/${pinId}`)).status).toBe(204);
    expect((await admin("DELETE", `/v1/admin/groups/${g.groupId}/pins/${pinId}`)).status).toBe(404);
    expect(await rows(g.groupId, "SELECT what FROM removals")).toEqual([{ what: "pin" }]);

    const info = await admin("GET", `/v1/admin/groups/${g.groupId}`);
    expect(info.status).toBe(200);
    expect(info.body).toEqual({
      status: "active",
      createdAt: g.now,
      pausedAt: null,
      memberCount: 2,
      databaseBytes: expect.any(Number),
      pro: { platform: "apple", productId: "pro.yearly", expiresAt: g.now + 365 * 86400 },
    });

    const owner = await admin("DELETE", `/v1/admin/groups/${g.groupId}/members/${g.owner.memberId}`);
    expect(owner.status).toBe(409);
    expect(owner.body.error).toBe("not_allowed");
    expect((await admin("DELETE", `/v1/admin/groups/${g.groupId}/members/${m.memberId}`)).status).toBe(204);
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: m.pass })).body.error).toBe("removed");

    expect((await admin("DELETE", `/v1/admin/groups/${g.groupId}`)).status).toBe(204);
    expect((await api("GET", `/v1/groups/${g.groupId}`, { pass: g.owner.pass })).body.error).toBe("group_deleted");
    expect((await admin("GET", `/v1/admin/groups/${g.groupId}`)).body.status).toBe("deleted");
    expect((await admin("DELETE", `/v1/admin/groups/${g.groupId}`)).status).toBe(410);
    expect((await admin("GET", `/v1/admin/groups/${randomId()}`)).status).toBe(404);
    expect((await admin("GET", `/v1/admin/groups/x`)).status).toBe(400);
  });

  it("posts an alert without content to REPORT_WEBHOOK_URL, retrying a failure", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const posts: Array<{ url: string; body: unknown }> = [];
    let fail = true;
    await runInDurableObject(adminStub(), (a) => {
      a.envOverride = { REPORT_WEBHOOK_URL: "https://hooks.example.invalid/report" };
      a.httpFetch = async (url, init) => {
        posts.push({ url, body: JSON.parse(String(init?.body)) });
        return new Response(null, { status: fail ? 500 : 204 });
      };
    });
    const res = await api("POST", `/v1/groups/${g.groupId}/reports`, { pass: m.pass, body: { targetKind: "group", targetId: g.groupId, reason: "dangerous", content: { name: "secret" } } });
    // The alarm is due at once: it may already be running on its own.
    await runDurableObjectAlarm(adminStub());
    await vi.waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toEqual({ url: "https://hooks.example.invalid/report", body: { reportId: res.body.reportId, createdAt: g.now, reason: "dangerous", targetKind: "group" } });
    fail = false;
    await runInDurableObject(adminStub(), (a) => {
      a.clockOverride = Math.floor(Date.now() / 1000) + 3601;
    });
    await runDurableObjectAlarm(adminStub());
    expect(posts.length).toBeGreaterThanOrEqual(2);
    const outbox = await runInDurableObject(adminStub(), (_a, state) => state.storage.sql.exec("SELECT * FROM outbox WHERE report_id = ?", res.body.reportId).toArray());
    expect(outbox).toEqual([]);
    await runInDurableObject(adminStub(), (a) => {
      a.envOverride = null;
      a.clockOverride = null;
    });
  });

  it("cleans up resolved reports after 180 days and open ones after 365 days", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const make = async () =>
      (await api("POST", `/v1/groups/${g.groupId}/reports`, { pass: m.pass, body: { targetKind: "group", targetId: g.groupId, reason: "spam" } })).body.reportId as string;
    const resolved = await make();
    const open = await make();
    await runInDurableObject(adminStub(), (a) => {
      a.clockOverride = g.now;
    });
    await admin("POST", `/v1/admin/reports/${resolved}/resolve`, { action: "none" });
    const left = async (now: number) => {
      await runInDurableObject(adminStub(), (a, state) => {
        a.clockOverride = now;
        state.storage.sql.exec("UPDATE jobs SET at = ? WHERE kind = 'cleanup'", now);
      });
      await runDurableObjectAlarm(adminStub());
      return (await listFor(g.groupId)).map((r) => r.reportId as string).sort();
    };
    expect(await left(g.now + 180 * 86400)).toEqual([open, resolved].sort());
    expect(await left(g.now + 180 * 86400 + 1)).toEqual([open]);
    expect(await left(g.now + 365 * 86400 + 1)).toEqual([]);
    await runInDurableObject(adminStub(), (a) => {
      a.clockOverride = null;
    });
  });
});

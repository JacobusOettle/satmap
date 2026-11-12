// Worker routing (SPEC §7.2): web pages, well-known files, health, 404/405/426, and the log lines (§7.9).

import { describe, expect, it, vi } from "vitest";
import pkg from "../../package.json";
import { addMember, api, createGroup, makeInvite, randomId, redeem } from "../helpers/client";
import { envelopeOf, vectors } from "../helpers/crypto";

describe("routing", () => {
  it("serves the web pages through the Worker", async () => {
    const landing = await api("GET", `/j/${vectors.invite.token}`);
    expect(landing.status).toBe(200);
    expect(landing.headers.get("Content-Security-Policy")).toContain("script-src 'sha256-");
    expect((await api("GET", `/j/${vectors.invite.token}/`)).status).toBe(200);
    expect((await api("GET", "/j/not-a-token")).status).toBe(404);
    expect((await api("GET", "/j/")).status).toBe(404);
    expect((await api("GET", "/.well-known/apple-app-site-association")).body.applinks.details[0].components).toEqual([{ "/": "/j/*", comment: "Group invites" }]);
    expect((await api("GET", "/.well-known/assetlinks.json")).body[0].target.package_name).toBe("com.koonap.spoor");
    for (const path of ["/terms", "/privacy", "/"]) {
      const res = await api("GET", path);
      expect(res.status, path).toBe(200);
      expect(res.headers.get("Content-Type")).toBe("text/html; charset=utf-8");
    }
    const head = await api("HEAD", "/privacy");
    expect(head.status).toBe(200);
    expect(head.text).toBe("");
    expect((await api("POST", "/privacy", { body: {} })).status).toBe(405);
  });

  it("answers health, 404, 405 and 426", async () => {
    const health = await api("GET", "/v1/health");
    expect(health.body).toEqual({ ok: true, version: pkg.version });
    expect(health.headers.get("Cache-Control")).toBe("no-store");
    const unknown = await api("GET", "/v1/nothing");
    expect(unknown.status).toBe(404);
    expect(unknown.body).toEqual({ error: "not_found", message: expect.any(String) });
    expect((await api("GET", "/robots.txt")).status).toBe(404);
    expect((await api("DELETE", "/v1/groups")).status).toBe(405);
    expect((await api("GET", `/v1/groups/${randomId()}/upload`)).body.error).toBe("method_not_allowed");
    const ws = await api("GET", `/v1/groups/${randomId()}/ws`);
    expect(ws.status).toBe(426);
    expect(ws.body.error).toBe("upgrade_required");
  });

  it("logs one line per request without ids, tokens or passes (SPEC §7.9)", async () => {
    const lines: string[] = [];
    const spy = vi.spyOn(console, "log").mockImplementation((...args: unknown[]) => {
      lines.push(args.map(String).join(" "));
    });
    try {
      const g = await createGroup();
      const m = await addMember(g);
      const inv = await makeInvite(g);
      await redeem(g, inv.token);
      await api("POST", "/v1/invites/preview", { body: { token: inv.token, joinProof: g.joinProof } });
      await api("POST", `/v1/groups/${g.groupId}/upload`, { pass: m.pass, body: { chunks: [{ start: 1, rec: envelopeOf("trailChunk") }], live: { at: g.now, rec: envelopeOf("live") } } });
      await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: m.pass, body: { pinId: randomId(), createdAt: g.now, expiresAt: null, rec: envelopeOf("pinWater") } });
      await api("POST", `/v1/groups/${g.groupId}/reports`, { pass: m.pass, body: { targetKind: "member", targetId: g.owner.memberId, reason: "spam", content: { name: "Thandi" } } });
      await api("GET", `/v1/groups/${g.groupId}`, { pass: "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8" });
      await api("GET", `/v1/groups/${vectors.mustFail.nonCanonicalIds[0]}`, { pass: m.pass });
      await api("POST", "/v1/store/apple", { body: { signedPayload: "eyJhbGciOiJFUzI1NiJ9.eyJzaWduZWREYXRlIjoxfQ.c2ln" } });
      await api("GET", `/j/${inv.token}`);
    } finally {
      spy.mockRestore();
    }
    const requests = lines.filter((l) => l.includes('"t":"req"'));
    expect(requests.length).toBeGreaterThanOrEqual(10);
    expect(requests).toContain(JSON.stringify({ t: "req", route: "POST /v1/groups/:gid/upload", status: 200, ms: JSON.parse(requests.find((l) => l.includes("upload"))!).ms }));
    expect(requests.some((l) => l.includes('"err":"unauthorized"'))).toBe(true);
    expect(requests.some((l) => l.includes('"route":"GET /j/:token"'))).toBe(true);
    for (const line of lines) {
      expect(line, line).not.toMatch(/[A-Za-z0-9_-]{22,}/);
      expect(line).not.toMatch(/\d+\.\d+\.\d+\.\d+/); // no IP addresses
    }
  });
});

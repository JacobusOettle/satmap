// E23 drop a pin and E24 delete a pin: every error, the 12 h expiry alarm and the limits (SPEC §6.5, SD3, SD24).

import { describe, expect, it } from "vitest";
import type { ProState } from "../../src/pro/types";
import { addMember, advance, api, createGroup, inGroup, randomId, rows, runAlarm } from "../helpers/client";
import { b64urlEncode, envelopeOf } from "../helpers/crypto";

const WATER = envelopeOf("pinWater");
const ANIMAL = envelopeOf("pinAnimal");

function envelope(size: number): string {
  const bytes = new Uint8Array(size);
  bytes[0] = 1;
  return b64urlEncode(bytes);
}

describe("E23 drop a pin", () => {
  it("answers 201 with the seq, 200 to a retry by the same member, 409 pin_exists to another", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const pinId = randomId();
    const body = { pinId, createdAt: g.now, expiresAt: null, rec: WATER };
    const first = await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: m.pass, body });
    expect(first.status).toBe(201);
    expect(first.body).toEqual({ seq: 4 });
    const retry = await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: m.pass, body });
    expect(retry.status).toBe(200);
    expect(retry.body).toEqual({ seq: 4 });
    const other = await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body });
    expect(other.status).toBe(409);
    expect(other.body.error).toBe("pin_exists");
    const changes = await api("GET", `/v1/groups/${g.groupId}/changes?since=3`, { pass: g.owner.pass });
    expect(changes.body.items).toEqual([{ kind: "pin", seq: 4, pinId, memberId: m.memberId, createdAt: g.now, expiresAt: null, rec: WATER }]);
  });

  it("refuses a deleted pinId with 410 pin_deleted (SD24)", async () => {
    const g = await createGroup();
    const pinId = randomId();
    const body = { pinId, createdAt: g.now, expiresAt: null, rec: WATER };
    await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body });
    expect((await api("DELETE", `/v1/groups/${g.groupId}/pins/${pinId}`, { pass: g.owner.pass })).status).toBe(204);
    const res = await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body });
    expect(res.status).toBe(410);
    expect(res.body.error).toBe("pin_deleted");
  });

  it("checks expiresAt, the envelope and the times", async () => {
    const g = await createGroup();
    const path = `/v1/groups/${g.groupId}/pins`;
    const drop = (body: Record<string, unknown>) => api("POST", path, { pass: g.owner.pass, body: { pinId: randomId(), createdAt: g.now, expiresAt: null, rec: WATER, ...body } });
    const cases: Array<[Record<string, unknown>, number, string]> = [
      [{ expiresAt: g.now + 43199 }, 400, "bad_record"],
      [{ expiresAt: g.now + 43201 }, 400, "bad_record"],
      [{ rec: envelope(48) }, 400, "bad_record"],
      [{ rec: envelope(1025) }, 400, "bad_record"],
      [{ rec: 5 }, 400, "bad_request"],
      [{ pinId: "bad" }, 400, "bad_request"],
      [{ createdAt: "now" }, 400, "bad_request"],
      [{ createdAt: g.now - 43200, expiresAt: g.now }, 422, "pin_expired"],
      [{ createdAt: g.now + 601 }, 422, "bad_time"],
      [{ createdAt: g.now - 604801 }, 422, "bad_time"],
    ];
    for (const [over, status, code] of cases) {
      const res = await drop(over);
      expect(res.status, JSON.stringify(over)).toBe(status);
      expect(res.body.error, JSON.stringify(over)).toBe(code);
    }
    expect((await drop({ rec: envelope(49) })).status).toBe(201);
    expect((await drop({ rec: envelope(1024) })).status).toBe(201);
    expect((await drop({ createdAt: g.now + 600, expiresAt: g.now + 600 + 43200, rec: ANIMAL })).status).toBe(201);
    expect((await drop({ createdAt: g.now - 604800 })).status).toBe(201);
  });

  it("refuses new pins while paused (409 group_paused), but deleting works", async () => {
    const g = await createGroup();
    const pinId = randomId();
    await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body: { pinId, createdAt: g.now, expiresAt: null, rec: WATER } });
    const state: ProState = { name: g.proName, platform: "apple", productId: "pro.yearly", active: false, reason: "pro_ended", expiresAt: g.now };
    await inGroup(g.groupId, (d) => d.proChanged(g.proName, state));
    const res = await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body: { pinId: randomId(), createdAt: g.now, expiresAt: null, rec: WATER } });
    expect(res.status).toBe(409);
    expect(res.body.error).toBe("group_paused");
    expect((await api("DELETE", `/v1/groups/${g.groupId}/pins/${pinId}`, { pass: g.owner.pass })).status).toBe(204);
  });

  it("allows 60 pins per member per hour", async () => {
    const g = await createGroup();
    const m = await addMember(g);
    const path = `/v1/groups/${g.groupId}/pins`;
    for (let i = 0; i < 60; i++) {
      expect((await api("POST", path, { pass: m.pass, body: { pinId: randomId(), createdAt: g.now, expiresAt: null, rec: WATER } })).status).toBe(201);
    }
    const limited = await api("POST", path, { pass: m.pass, body: { pinId: randomId(), createdAt: g.now, expiresAt: null, rec: WATER } });
    expect(limited.status).toBe(429);
    expect(Number(limited.headers.get("Retry-After"))).toBeGreaterThan(0);
    // Others still can, and the member can again an hour later.
    expect((await api("POST", path, { pass: g.owner.pass, body: { pinId: randomId(), createdAt: g.now, expiresAt: null, rec: WATER } })).status).toBe(201);
    await advance(g, 3600);
    expect((await api("POST", path, { pass: m.pass, body: { pinId: randomId(), createdAt: g.now, expiresAt: null, rec: WATER } })).status).toBe(201);
  });

  it("allows 2000 unexpired pins per group", async () => {
    const g = await createGroup();
    await inGroup(g.groupId, (_d, state) => {
      for (let i = 0; i < 2000; i++) {
        state.storage.sql.exec(
          "INSERT INTO pins (pin_id, member_id, created_at, expires_at, rec, seq) VALUES (?, ?, ?, NULL, ?, ?)",
          `pin${i}`,
          g.owner.memberId,
          g.now,
          new Uint8Array(49).buffer,
          100 + i,
        );
      }
    });
    const res = await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body: { pinId: randomId(), createdAt: g.now, expiresAt: null, rec: WATER } });
    expect(res.status).toBe(429);
  });
});

describe("animal pins expire after 12 h (R10)", () => {
  it("are deleted by the alarm with a removed pin item", async () => {
    const g = await createGroup();
    const pinId = randomId();
    await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body: { pinId, createdAt: g.now, expiresAt: g.now + 43200, rec: ANIMAL } });
    const alarm = await inGroup(g.groupId, (_d, state) => state.storage.getAlarm());
    expect(alarm).toBeLessThanOrEqual((g.now + 43200) * 1000); // the earliest of the jobs and the pin expiry
    await advance(g, 43200);
    expect(await runAlarm(g.groupId)).toBe(true);
    expect(await rows(g.groupId, "SELECT * FROM pins")).toEqual([]);
    const items = (await api("GET", `/v1/groups/${g.groupId}/changes?since=3`, { pass: g.owner.pass })).body.items;
    expect(items).toEqual([{ kind: "removed", seq: 4, what: "pin", id: pinId }]);
    // An expired pin's id is never reused.
    const again = await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body: { pinId, createdAt: g.now, expiresAt: g.now + 43200, rec: ANIMAL } });
    expect(again.status).toBe(410);
  });
});

describe("E24 delete a pin", () => {
  it("adds a removed item; an unknown pin is 404", async () => {
    const g = await createGroup();
    const pinId = randomId();
    await api("POST", `/v1/groups/${g.groupId}/pins`, { pass: g.owner.pass, body: { pinId, createdAt: g.now, expiresAt: null, rec: WATER } });
    expect((await api("DELETE", `/v1/groups/${g.groupId}/pins/${pinId}`, { pass: g.owner.pass })).status).toBe(204);
    const again = await api("DELETE", `/v1/groups/${g.groupId}/pins/${pinId}`, { pass: g.owner.pass });
    expect(again.status).toBe(404);
    expect(again.body.error).toBe("not_found");
    expect((await api("DELETE", `/v1/groups/${g.groupId}/pins/x`, { pass: g.owner.pass })).status).toBe(400);
  });
});

// The archive object format (§4.9), routes, configuration gating (§7.8), log scrubbing (§7.9) and time helpers.

import { describe, expect, it } from "vitest";
import { archiveKey, decodeArchive, encodeArchive, groupPrefix, memberPrefix } from "../../src/group/archive";
import { readConfig } from "../../src/lib/config";
import { ApiError, errorResponse, ERROR_STATUS } from "../../src/lib/errors";
import { hex } from "../../src/lib/hash";
import { scrub } from "../../src/lib/log";
import { isMonth, monthOf, monthRange, windowStart } from "../../src/lib/time";
import { matchRoute } from "../../src/routes";
import { vectors } from "../helpers/crypto";

describe("archive object", () => {
  it("is SMGA, version 1, then Int64 start, UInt32 length and the envelope, little-endian", () => {
    const rec = new Uint8Array([1, 2, 3]);
    const bytes = encodeArchive([{ start: 1793613600, rec }]);
    expect(hex(bytes)).toBe("534d474101" + "205fe86a00000000" + "03000000" + "010203");
  });

  it("sorts by windowStart and round-trips", () => {
    const records = [
      { start: 1793614800, rec: new Uint8Array([9]) },
      { start: 1793613600, rec: new Uint8Array([7, 8]) },
    ];
    const decoded = decodeArchive(encodeArchive(records));
    expect(decoded.map((r) => r.start)).toEqual([1793613600, 1793614800]);
    expect(decoded[0]!.rec).toEqual(new Uint8Array([7, 8]));
  });

  it("refuses two records for one window and malformed objects", () => {
    expect(() => encodeArchive([{ start: 600, rec: new Uint8Array() }, { start: 600, rec: new Uint8Array() }])).toThrow();
    expect(() => decodeArchive(new Uint8Array([0x53, 0x4d, 0x47, 0x41]))).toThrow();
    expect(() => decodeArchive(new Uint8Array([0x53, 0x4d, 0x47, 0x41, 2]))).toThrow();
    const good = encodeArchive([{ start: 600, rec: new Uint8Array([1, 2]) }]);
    expect(() => decodeArchive(good.slice(0, good.length - 1))).toThrow();
    expect(decodeArchive(encodeArchive([]))).toEqual([]);
  });

  it("names keys and prefixes", () => {
    expect(archiveKey("G", "M", "2026-09")).toBe("g/G/t/M/2026-09.bin");
    expect(memberPrefix("G", "M")).toBe("g/G/t/M/");
    expect(groupPrefix("G")).toBe("g/G/");
  });
});

describe("routes", () => {
  it("matches every endpoint and tells 404 from 405", () => {
    const g = vectors.inputs.groupId;
    expect(matchRoute("POST", "/v1/groups")).toMatchObject({ type: "route", route: { id: "E1" } });
    expect(matchRoute("PUT", `/v1/groups/${g}/members/me/profile`)).toMatchObject({ route: { id: "E12" } });
    expect(matchRoute("PUT", `/v1/groups/${g}/members/me/role`)).toMatchObject({ route: { id: "E13" }, params: { gid: g, mid: "me" } });
    expect(matchRoute("DELETE", `/v1/groups/${g}/trail/me`)).toMatchObject({ route: { id: "E22" } });
    expect(matchRoute("GET", `/v1/groups/${g}/archive/m/2026-09`)).toMatchObject({ route: { id: "E20" }, params: { month: "2026-09" } });
    expect(matchRoute("GET", `/v1/groups/${g}/upload`)).toEqual({ type: "method_not_allowed" });
    expect(matchRoute("GET", `/v1/groups/${g}/nothing`)).toEqual({ type: "none" });
    expect(matchRoute("GET", "/v1/groups/")).toEqual({ type: "none" });
  });
});

describe("configuration", () => {
  it("has the §7.8 defaults", () => {
    const cfg = readConfig({});
    expect(cfg).toMatchObject({
      testMode: false,
      publicBaseUrl: "https://example.invalid",
      contactEmail: "groups@example.invalid",
      proProductsApple: ["pro.monthly", "pro.yearly", "pro.lifetime"],
      proProductsGoogle: ["pro", "pro_lifetime"],
      maxGroupsPerPurchase: 20,
      termsVersion: 1,
      allowTestPurchases: true,
      appleAllowXcode: false,
      allowUnverifiedPro: false,
      appleRootOverrideB64: "",
      apple: null,
    });
  });

  it("honours the test-only variables only under TEST_MODE=1", () => {
    const vars = { APPLE_ALLOW_XCODE: "true", ALLOW_UNVERIFIED_PRO: "true", APPLE_ROOT_OVERRIDE_B64: "AAAA" };
    expect(readConfig({ ...vars, TEST_MODE: "0" })).toMatchObject({ appleAllowXcode: false, allowUnverifiedPro: false, appleRootOverrideB64: "" });
    expect(readConfig({ ...vars, TEST_MODE: "1" })).toMatchObject({ appleAllowXcode: true, allowUnverifiedPro: true, appleRootOverrideB64: "AAAA" });
  });

  it("needs all three App Store Server API secrets", () => {
    expect(readConfig({ APPLE_ISSUER_ID: "i", APPLE_KEY_ID: "k" }).apple).toBeNull();
    expect(readConfig({ APPLE_ISSUER_ID: "i", APPLE_KEY_ID: "k", APPLE_PRIVATE_KEY: "p" }).apple).toEqual({ issuerId: "i", keyId: "k", privateKey: "p" });
  });
});

describe("errors", () => {
  it("answer the §6.2 statuses, with Retry-After on 429 and 503", async () => {
    expect(ERROR_STATUS.pro_inactive).toBe(402);
    expect(ERROR_STATUS.upgrade_required).toBe(426);
    const res = errorResponse("rate_limited");
    expect(res.status).toBe(429);
    expect(res.headers.get("Retry-After")).toBe("60");
    expect(res.headers.get("Cache-Control")).toBe("no-store");
    expect(await res.json()).toMatchObject({ error: "rate_limited" });
    expect(errorResponse("store_unavailable").headers.get("Retry-After")).not.toBeNull();
    expect(new ApiError("bad_time").status).toBe(422);
  });
});

describe("log scrubbing", () => {
  it("replaces id-shaped substrings", () => {
    expect(scrub(`group ${vectors.inputs.groupId} pass ${vectors.inputs.pass} jws a.b.c`)).toBe("group … pass … jws a.b.c");
    expect(scrub("short ids like abc stay")).toBe("short ids like abc stay");
  });
});

describe("time", () => {
  it("computes months and windows in UTC", () => {
    expect(monthOf(1793613600)).toBe("2026-11");
    expect(monthRange("2026-12")).toEqual([Date.UTC(2026, 11, 1) / 1000, Date.UTC(2027, 0, 1) / 1000]);
    expect(isMonth("2026-13")).toBe(false);
    expect(isMonth("2026-09")).toBe(true);
    expect(windowStart(1793613605)).toBe(1793613600);
  });
});

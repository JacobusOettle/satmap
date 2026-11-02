// SPEC §2.1, §2.2, §3.6 server checks and §6.8 envelope sizes.

import { describe, expect, it } from "vitest";
import { b64Decode, b64Encode, b64urlDecode, b64urlDecodeCanonical, b64urlEncode } from "../../src/lib/b64url";
import { decodeEnvelope, parseEnvelope } from "../../src/lib/envelope";
import { ApiError } from "../../src/lib/errors";
import { bytesEqual, fromHex, hex, sha256, textEqual } from "../../src/lib/hash";
import { decode32, isCanonicalId, parseId, splitToken } from "../../src/lib/ids";
import { ENVELOPE_SIZES } from "../../src/lib/limits";
import { vectors } from "../helpers/crypto";

describe("base64url", () => {
  it("round-trips every length without padding", () => {
    for (let n = 0; n < 40; n++) {
      const bytes = crypto.getRandomValues(new Uint8Array(n));
      const text = b64urlEncode(bytes);
      expect(text).not.toMatch(/[=+/]/);
      expect(b64urlDecode(text)).toEqual(bytes);
    }
  });

  it("matches the vector texts", () => {
    expect(b64urlEncode(fromHex(vectors.inputs.groupIdHex))).toBe(vectors.inputs.groupId);
    expect(b64urlEncode(fromHex(vectors.inputs.memberIdHex))).toBe(vectors.inputs.memberId);
    expect(b64urlEncode(fromHex(vectors.inputs.pinIdHex))).toBe(vectors.inputs.pinId);
    expect(b64urlEncode(fromHex(vectors.inputs.passHex))).toBe(vectors.inputs.pass);
    expect(b64urlEncode(fromHex(vectors.inputs.groupKeyHex))).toBe(vectors.inputs.groupKey);
  });

  it("rejects every mustFail.badBase64url text", () => {
    for (const text of vectors.mustFail.badBase64url) expect(b64urlDecode(text), text).toBeNull();
  });

  it("rejects a length that is 1 mod 4 and characters outside the alphabet", () => {
    expect(b64urlDecode("A")).toBeNull();
    expect(b64urlDecode("AAAAA")).toBeNull();
    expect(b64urlDecode("AA\nA")).toBeNull();
    expect(b64urlDecode("AAé")).toBeNull();
  });

  it("accepts non-zero unused bits, but the canonical reader doesn't", () => {
    for (const text of vectors.mustFail.nonCanonicalIds) {
      expect(b64urlDecode(text)).not.toBeNull();
      expect(b64urlDecodeCanonical(text)).toBeNull();
    }
  });

  it("decodes standard base64 with padding", () => {
    const bytes = new Uint8Array([0xfb, 0xff, 0x01]);
    expect(b64Decode(b64Encode(bytes))).toEqual(bytes);
    expect(b64Decode("+/8=")).toEqual(new Uint8Array([0xfb, 0xff]));
    expect(b64Decode("+/8")).toEqual(new Uint8Array([0xfb, 0xff]));
    expect(b64Decode("+/=8")).toBeNull();
  });
});

describe("ids", () => {
  it("accepts canonical 16-byte ids", () => {
    expect(isCanonicalId(vectors.inputs.groupId)).toBe(true);
    expect(isCanonicalId(vectors.inputs.memberId)).toBe(true);
    expect(parseId(vectors.inputs.pinId)).toBe(vectors.inputs.pinId);
  });

  it("answers 400 bad_request for mustFail.nonCanonicalIds and malformed ids", () => {
    for (const text of [...vectors.mustFail.nonCanonicalIds, ...vectors.mustFail.badBase64url, "short", 5, null]) {
      expect(isCanonicalId(text)).toBe(false);
      try {
        parseId(text);
        expect.unreachable();
      } catch (err) {
        expect(err).toBeInstanceOf(ApiError);
        expect((err as ApiError).code).toBe("bad_request");
      }
    }
  });

  it("splits the vector invite token into groupId and secret", () => {
    const split = splitToken(vectors.invite.token)!;
    expect(split.groupId).toBe(vectors.inputs.groupId);
    expect(hex(split.secret)).toBe(vectors.inputs.inviteSecretHex);
    expect(splitToken(vectors.invite.token.slice(1))).toBeNull();
    expect(splitToken(`${vectors.invite.token}A`)).toBeNull();
    expect(splitToken(42)).toBeNull();
  });

  it("decodes 43-character secrets to 32 bytes", () => {
    expect(hex(decode32(vectors.inputs.pass)!)).toBe(vectors.inputs.passHex);
    expect(decode32(vectors.inputs.groupId)).toBeNull();
    expect(decode32(`${vectors.inputs.pass}=`)).toBeNull();
  });
});

describe("server hashes (§3.6)", () => {
  it("hashes the 32 raw pass bytes", async () => {
    expect(hex(await sha256(decode32(vectors.inputs.pass)!))).toBe(vectors.hashes.passSha256Hex);
  });

  it("hashes the 32 raw joinProof bytes", async () => {
    expect(hex(await sha256(decode32(vectors.hashes.joinProof)!))).toBe(vectors.hashes.joinProofSha256Hex);
    expect(hex(decode32(vectors.hashes.joinProof)!)).toBe(vectors.subkeys.join);
  });

  it("hashes the 16 raw invite secret bytes", async () => {
    expect(hex(await sha256(fromHex(vectors.inputs.inviteSecretHex)))).toBe(vectors.hashes.inviteSecretSha256Hex);
  });

  it("compares in constant time", async () => {
    expect(bytesEqual(new Uint8Array([1, 2]), new Uint8Array([1, 2]))).toBe(true);
    expect(bytesEqual(new Uint8Array([1, 2]), new Uint8Array([1, 3]))).toBe(false);
    expect(bytesEqual(new Uint8Array([1, 2]), new Uint8Array([1, 2, 3]))).toBe(false);
    expect(await textEqual("secret", "secret")).toBe(true);
    expect(await textEqual("secret", "secreT")).toBe(false);
    expect(await textEqual("", "x")).toBe(false);
  });
});

describe("envelopes (§3.2, §6.8)", () => {
  const kindOf: Record<string, keyof typeof ENVELOPE_SIZES> = {
    groupProfile: "groupProfile",
    groupProfilePadded: "groupProfile",
    memberProfile: "memberProfile",
    memberProfileUnicode: "memberProfile",
    memberProfilePadded: "memberProfile",
    trailChunk: "chunk",
    trailChunkDeflated: "chunk",
    live: "live",
    liveUnknowns: "live",
    pinAnimal: "pin",
    pinWater: "pin",
    pinAnimalPadded: "pin",
  };

  it("accepts every vector envelope for its kind", () => {
    for (const [name, record] of Object.entries(vectors.records)) {
      const kind = kindOf[name]!;
      expect(decodeEnvelope(record.envelope, kind), name).not.toBeNull();
    }
  });

  it("checks the length range of every kind, inclusive", () => {
    for (const [kind, [min, max]] of Object.entries(ENVELOPE_SIZES) as Array<[keyof typeof ENVELOPE_SIZES, readonly [number, number]]>) {
      const make = (n: number) => {
        const bytes = new Uint8Array(n);
        bytes[0] = 1;
        return b64urlEncode(bytes);
      };
      expect(decodeEnvelope(make(min), kind), `${kind} min`).not.toBeNull();
      expect(decodeEnvelope(make(max), kind), `${kind} max`).not.toBeNull();
      expect(decodeEnvelope(make(min - 1), kind), `${kind} min-1`).toBeNull();
      expect(decodeEnvelope(make(max + 1), kind), `${kind} max+1`).toBeNull();
    }
  });

  it("rejects another version and bad base64url", () => {
    expect(decodeEnvelope(b64urlEncode(fromHex(vectors.mustFail.unknownVersionEnvelopeHex)), "chunk")).toBeNull();
    expect(decodeEnvelope(b64urlEncode(fromHex(vectors.mustFail.shortEnvelopeHex)), "chunk")).toBeNull();
    expect(decodeEnvelope(`${vectors.records.trailChunk.envelope}=`, "chunk")).toBeNull();
  });

  it("answers bad_request for a non-string and bad_record for a bad envelope", () => {
    expect(() => parseEnvelope(5, "pin", "rec")).toThrowError(expect.objectContaining({ code: "bad_request" }));
    expect(() => parseEnvelope("AAAA", "pin", "rec")).toThrowError(expect.objectContaining({ code: "bad_record" }));
    expect(parseEnvelope(vectors.records.pinWater.envelope, "pin", "rec")).toEqual(fromHex(vectors.records.pinWater.envelopeHex));
  });
});

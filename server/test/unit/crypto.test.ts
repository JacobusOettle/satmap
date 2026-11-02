// The test client's crypto and AAD builders against every vectors.json record (SPEC §3.3–§3.6), so the envelopes
// the integration tests make are the real thing.

import { describe, expect, it } from "vitest";
import { AAD, OpenFailure, V, fromHex, hex, open, seal, subkey, vectors } from "../helpers/crypto";

const keyPurpose = (name: string) =>
  vectors.subkeyInfo[name as keyof typeof vectors.subkeyInfo].slice("satmap.group.v1|key|".length);

async function keyFor(name: string): Promise<Uint8Array> {
  return subkey(V.groupKey, V.groupIdBytes, keyPurpose(name));
}

describe("subkeys", () => {
  it("derives the six vector subkeys from their subkeyInfo purposes", async () => {
    for (const [name, expected] of Object.entries(vectors.subkeys)) {
      expect(hex(await keyFor(name)), name).toBe(expected);
    }
  });
});

describe("AAD builders", () => {
  const { groupId, memberId, pinId } = vectors.inputs;
  it("build the vector AAD strings", () => {
    expect(AAD.group(groupId)).toBe(vectors.records.groupProfile.aad);
    expect(AAD.member(groupId, memberId)).toBe(vectors.records.memberProfile.aad);
    expect(AAD.trail(groupId, memberId, vectors.inputs.windowStart)).toBe(vectors.records.trailChunk.aad);
    expect(AAD.live(groupId, memberId, 1793613620)).toBe(vectors.records.live.aad);
    expect(AAD.live(groupId, memberId, 1793613625)).toBe(vectors.records.liveUnknowns.aad);
    expect(AAD.pin(groupId, pinId, memberId, 1793613900, 1793657100)).toBe(vectors.records.pinAnimal.aad);
    expect(AAD.pin(groupId, pinId, memberId, 1793613900, null)).toBe(vectors.records.pinWater.aad);
    expect(AAD.trail(groupId, memberId, 1793614200)).toBe(vectors.mustFail.trailWrongWindowAad);
  });
});

describe("records", () => {
  it("seals every record plaintext to exactly its envelope, and opens it back", async () => {
    for (const [name, record] of Object.entries(vectors.records)) {
      const key = await keyFor(record.key);
      const sealed = await seal(fromHex(record.plainHex), key, record.aad, fromHex(record.nonceHex));
      expect(hex(sealed), name).toBe(record.envelopeHex);
      expect(hex(await open(fromHex(record.envelopeHex), key, record.aad)), name).toBe(record.plainHex);
    }
  });

  it("fails every mustFail envelope case", async () => {
    const trailKey = await keyFor("trail");
    const aad = vectors.records.trailChunk.aad;
    const kind = async (envelopeHex: string, a = aad) => {
      try {
        await open(fromHex(envelopeHex), trailKey, a);
        return "opened";
      } catch (err) {
        return (err as OpenFailure).kind;
      }
    };
    expect(await kind(vectors.mustFail.tamperedTrailEnvelopeHex)).toBe("unreadable");
    expect(await kind(vectors.records.trailChunk.envelopeHex, vectors.mustFail.trailWrongWindowAad)).toBe("unreadable");
    expect(await kind(vectors.mustFail.unknownVersionEnvelopeHex)).toBe("unsupportedVersion");
    expect(await kind(vectors.mustFail.shortEnvelopeHex)).toBe("unreadable");
  });
});

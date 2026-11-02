// The test client's record crypto (SPEC §3): HKDF-SHA256 subkeys, AES-256-GCM envelopes and the AAD builders,
// with WebCrypto. Checked against vectors.json in test/unit/crypto.test.ts, then used to make realistic envelopes.

import vectors from "../../../Tests/SatMapCoreTests/Fixtures/groups/vectors.json";
import { b64urlDecode, b64urlEncode } from "../../src/lib/b64url";
import { fromHex, hex } from "../../src/lib/hash";

export { vectors };

const encoder = new TextEncoder();

export async function subkey(groupKey: Uint8Array, groupId: Uint8Array, purpose: string): Promise<Uint8Array> {
  const ikm = await crypto.subtle.importKey("raw", groupKey, "HKDF", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "HKDF", hash: "SHA-256", salt: groupId, info: encoder.encode(`satmap.group.v1|key|${purpose}`) },
    ikm,
    256,
  );
  return new Uint8Array(bits);
}

export async function seal(plain: Uint8Array, key: Uint8Array, aad: string, nonce?: Uint8Array): Promise<Uint8Array> {
  const iv = nonce ?? crypto.getRandomValues(new Uint8Array(12));
  const k = await crypto.subtle.importKey("raw", key, "AES-GCM", false, ["encrypt"]);
  const sealed = new Uint8Array(
    await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: encoder.encode(aad), tagLength: 128 }, k, plain),
  );
  const out = new Uint8Array(1 + 12 + sealed.byteLength);
  out[0] = 0x01;
  out.set(iv, 1);
  out.set(sealed, 13);
  return out;
}

export class OpenFailure extends Error {
  constructor(readonly kind: "unsupportedVersion" | "unreadable") {
    super(kind);
  }
}

export async function open(envelope: Uint8Array, key: Uint8Array, aad: string): Promise<Uint8Array> {
  if (envelope.byteLength >= 1 && envelope[0] !== 0x01) throw new OpenFailure("unsupportedVersion");
  if (envelope.byteLength < 29) throw new OpenFailure("unreadable");
  const k = await crypto.subtle.importKey("raw", key, "AES-GCM", false, ["decrypt"]);
  try {
    return new Uint8Array(
      await crypto.subtle.decrypt(
        { name: "AES-GCM", iv: envelope.subarray(1, 13), additionalData: encoder.encode(aad), tagLength: 128 },
        k,
        envelope.subarray(13),
      ),
    );
  } catch {
    throw new OpenFailure("unreadable");
  }
}

export const AAD = {
  group: (groupId: string) => `satmap.group.v1|group|${groupId}`,
  member: (groupId: string, memberId: string) => `satmap.group.v1|member|${groupId}|${memberId}`,
  trail: (groupId: string, memberId: string, start: number) => `satmap.group.v1|trail|${groupId}|${memberId}|${start}`,
  live: (groupId: string, memberId: string, at: number) => `satmap.group.v1|live|${groupId}|${memberId}|${at}`,
  pin: (groupId: string, pinId: string, memberId: string, createdAt: number, expiresAt: number | null) =>
    `satmap.group.v1|pin|${groupId}|${pinId}|${memberId}|${createdAt}|${expiresAt === null ? "-" : expiresAt}`,
};

export const V = {
  groupKey: fromHex(vectors.inputs.groupKeyHex),
  groupIdBytes: fromHex(vectors.inputs.groupIdHex),
  groupId: vectors.inputs.groupId,
  memberId: vectors.inputs.memberId,
  pinId: vectors.inputs.pinId,
  pass: vectors.inputs.pass,
  joinProof: vectors.hashes.joinProof,
  windowStart: vectors.inputs.windowStart,
  token: vectors.invite.token,
  inviteSecretHex: vectors.inputs.inviteSecretHex,
};

export function envelopeOf(record: keyof typeof vectors.records): string {
  return b64urlEncode(fromHex(vectors.records[record].envelopeHex));
}

/** A real envelope for any group: the given plaintext sealed with a random key (the server can't tell). */
export async function fakeEnvelope(size: number): Promise<string> {
  const plain = new Uint8Array(Math.max(0, size - 29));
  const key = crypto.getRandomValues(new Uint8Array(32));
  return b64urlEncode(await seal(plain, key, "test"));
}

export { b64urlDecode, b64urlEncode, fromHex, hex };

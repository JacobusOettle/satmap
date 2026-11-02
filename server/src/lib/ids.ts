// Identifiers (SPEC §2.2): canonical 16-byte id texts, 32-byte secrets, invite tokens.

import { b64urlDecode, b64urlDecodeCanonical, b64urlEncode } from "./b64url";
import { ApiError } from "./errors";
import { randomBytes } from "./hash";

const ID_TEXT = /^[A-Za-z0-9_-]{22}$/;
const SECRET_TEXT = /^[A-Za-z0-9_-]{43}$/;

/** True when `text` is a 22-character id in canonical form (decodes to 16 bytes, unused bits zero). */
export function isCanonicalId(text: unknown): text is string {
  return typeof text === "string" && ID_TEXT.test(text) && b64urlDecodeCanonical(text)?.byteLength === 16;
}

/** A `groupId`, `memberId`, `pinId`, `inviteId` or `reportId` from a path or body; `400 bad_request` otherwise. */
export function parseId(value: unknown, what = "id"): string {
  if (!isCanonicalId(value)) throw new ApiError("bad_request", `Malformed ${what}.`);
  return value;
}

/** Decodes a 43-character text to its 32 bytes (pass, joinProof, token); null otherwise. */
export function decode32(value: unknown): Uint8Array | null {
  if (typeof value !== "string" || !SECRET_TEXT.test(value)) return null;
  const bytes = b64urlDecode(value);
  return bytes !== null && bytes.byteLength === 32 ? bytes : null;
}

export function parse32(value: unknown, what: string): Uint8Array {
  const bytes = decode32(value);
  if (bytes === null) throw new ApiError("bad_request", `Malformed ${what}.`);
  return bytes;
}

/** Splits an invite token (`groupId` ‖ `inviteSecret`); null when it isn't 43 base64url characters. */
export function splitToken(token: unknown): { groupId: string; secret: Uint8Array } | null {
  const bytes = decode32(token);
  if (bytes === null) return null;
  return { groupId: b64urlEncode(bytes.subarray(0, 16)), secret: bytes.slice(16) };
}

export function newId(): string {
  return b64urlEncode(randomBytes(16));
}

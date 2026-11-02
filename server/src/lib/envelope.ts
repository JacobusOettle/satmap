// Record envelope shape checks (SPEC §3.2, §6.8). The server never opens an envelope; it checks only the version
// byte, the length and the base64url text.

import { b64urlDecode } from "./b64url";
import { ApiError } from "./errors";
import { ENVELOPE_SIZES } from "./limits";

export type EnvelopeKind = keyof typeof ENVELOPE_SIZES;

export const ENVELOPE_VERSION = 0x01;

/** The envelope bytes, or null when the text, version or length is wrong for `kind`. */
export function decodeEnvelope(text: string, kind: EnvelopeKind): Uint8Array | null {
  const [min, max] = ENVELOPE_SIZES[kind];
  // A cheap bound before decoding: n bytes need at least ⌈4n/3⌉ characters.
  if (text.length > Math.ceil((max * 4) / 3) || text.length < Math.floor((min * 4) / 3)) return null;
  const bytes = b64urlDecode(text);
  if (bytes === null || bytes.byteLength < min || bytes.byteLength > max) return null;
  if (bytes[0] !== ENVELOPE_VERSION) return null;
  return bytes;
}

/** As `decodeEnvelope` for a body field: a non-string is `400 bad_request`, a bad envelope `400 bad_record`. */
export function parseEnvelope(value: unknown, kind: EnvelopeKind, what: string): Uint8Array {
  if (typeof value !== "string") throw new ApiError("bad_request", `${what} must be a string.`);
  const bytes = decodeEnvelope(value, kind);
  if (bytes === null) throw new ApiError("bad_record", `${what} is not a valid envelope.`);
  return bytes;
}

// base64url (RFC 4648 §5) without padding, with the strict reader rules of SPEC §2.1.

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
const STD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function lookup(alphabet: string): Int8Array {
  const table = new Int8Array(128).fill(-1);
  for (let i = 0; i < alphabet.length; i++) table[alphabet.charCodeAt(i)] = i;
  return table;
}

const URL_LOOKUP = lookup(ALPHABET);
const STD_LOOKUP = lookup(STD_ALPHABET);

function encodeWith(bytes: Uint8Array, alphabet: string, pad: boolean): string {
  let out = "";
  let i = 0;
  for (; i + 2 < bytes.length; i += 3) {
    const n = (bytes[i]! << 16) | (bytes[i + 1]! << 8) | bytes[i + 2]!;
    out += alphabet[n >> 18]! + alphabet[(n >> 12) & 63]! + alphabet[(n >> 6) & 63]! + alphabet[n & 63]!;
  }
  const rest = bytes.length - i;
  if (rest === 1) {
    const n = bytes[i]! << 16;
    out += alphabet[n >> 18]! + alphabet[(n >> 12) & 63]! + (pad ? "==" : "");
  } else if (rest === 2) {
    const n = (bytes[i]! << 16) | (bytes[i + 1]! << 8);
    out += alphabet[n >> 18]! + alphabet[(n >> 12) & 63]! + alphabet[(n >> 6) & 63]! + (pad ? "=" : "");
  }
  return out;
}

function decodeWith(text: string, table: Int8Array): Uint8Array | null {
  if (text.length % 4 === 1) return null;
  const out = new Uint8Array(Math.floor((text.length * 3) / 4));
  let o = 0;
  let acc = 0;
  let bits = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    const v = c < 128 ? table[c]! : -1;
    if (v < 0) return null;
    acc = (acc << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[o++] = (acc >> bits) & 0xff;
    }
  }
  return out.subarray(0, o);
}

/** base64url text of `bytes`, never padded. */
export function b64urlEncode(bytes: Uint8Array): string {
  return encodeWith(bytes, ALPHABET, false);
}

/**
 * Decodes base64url text. Returns null for any `=`, `+`, `/`, whitespace or other character outside the alphabet,
 * and for a length that is 1 mod 4. Non-zero unused trailing bits are accepted (SPEC §2.1).
 */
export function b64urlDecode(text: string): Uint8Array | null {
  return decodeWith(text, URL_LOOKUP);
}

/** Decodes only the canonical form: the text must equal the encoding of its decoded bytes (id texts, SPEC §2.1). */
export function b64urlDecodeCanonical(text: string): Uint8Array | null {
  const bytes = b64urlDecode(text);
  if (bytes === null || b64urlEncode(bytes) !== text) return null;
  return bytes;
}

/** Standard base64 with optional padding (x5c certificates, Pub/Sub message data). Null when malformed. */
export function b64Decode(text: string): Uint8Array | null {
  let body = text;
  if (body.length % 4 === 0 && body.endsWith("==")) body = body.slice(0, -2);
  else if (body.length % 4 === 0 && body.endsWith("=")) body = body.slice(0, -1);
  return decodeWith(body, STD_LOOKUP);
}

/** Standard base64 with padding. */
export function b64Encode(bytes: Uint8Array): string {
  return encodeWith(bytes, STD_ALPHABET, true);
}

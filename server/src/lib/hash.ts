// SHA-256, constant-time comparison and random bytes (WebCrypto; SPEC §3.1, §7.3).

const encoder = new TextEncoder();

export async function sha256(data: Uint8Array | string): Promise<Uint8Array> {
  const bytes = typeof data === "string" ? encoder.encode(data) : data;
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

/** Constant-time equality of two byte strings; false when the lengths differ. */
export function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.byteLength !== b.byteLength) return false;
  return crypto.subtle.timingSafeEqual(a, b);
}

/** Constant-time equality of two texts of any length: compares their SHA-256 hashes. */
export async function textEqual(a: string, b: string): Promise<boolean> {
  const [ha, hb] = await Promise.all([sha256(a), sha256(b)]);
  return crypto.subtle.timingSafeEqual(ha, hb) && a.length === b.length;
}

export function randomBytes(count: number): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(count));
}

export function hex(bytes: Uint8Array): string {
  let out = "";
  for (const b of bytes) out += b.toString(16).padStart(2, "0");
  return out;
}

export function fromHex(text: string): Uint8Array {
  const out = new Uint8Array(text.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(text.slice(i * 2, i * 2 + 2), 16);
  return out;
}

export function concatBytes(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.byteLength, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.byteLength;
  }
  return out;
}

// The R2 archive object (SPEC §4.9): ASCII "SMGA", version 0x01, then per window Int64 windowStart, UInt32 length L
// and the chunk's envelope exactly as uploaded (little-endian), sorted by windowStart, at most one per window.

export interface ArchivedChunk {
  start: number;
  rec: Uint8Array;
}

const MAGIC = [0x53, 0x4d, 0x47, 0x41]; // "SMGA"
const VERSION = 0x01;
const HEADER = 5;

export function archiveKey(groupId: string, memberId: string, month: string): string {
  return `g/${groupId}/t/${memberId}/${month}.bin`;
}

export function memberPrefix(groupId: string, memberId: string): string {
  return `g/${groupId}/t/${memberId}/`;
}

export function groupPrefix(groupId: string): string {
  return `g/${groupId}/`;
}

export function encodeArchive(chunks: Iterable<ArchivedChunk>): Uint8Array {
  const sorted = [...chunks].sort((a, b) => a.start - b.start);
  const size = HEADER + sorted.reduce((n, c) => n + 12 + c.rec.byteLength, 0);
  const out = new Uint8Array(size);
  const view = new DataView(out.buffer);
  out.set(MAGIC, 0);
  out[4] = VERSION;
  let o = HEADER;
  let previous: number | null = null;
  for (const c of sorted) {
    if (c.start === previous) throw new Error("two records for one window");
    previous = c.start;
    view.setBigInt64(o, BigInt(c.start), true);
    view.setUint32(o + 8, c.rec.byteLength, true);
    out.set(c.rec, o + 12);
    o += 12 + c.rec.byteLength;
  }
  return out;
}

/** The records of an archive object; throws on a malformed object. */
export function decodeArchive(bytes: Uint8Array): ArchivedChunk[] {
  if (bytes.byteLength < HEADER || MAGIC.some((m, i) => bytes[i] !== m) || bytes[4] !== VERSION) {
    throw new Error("not an archive object");
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const out: ArchivedChunk[] = [];
  let o = HEADER;
  while (o < bytes.byteLength) {
    if (o + 12 > bytes.byteLength) throw new Error("truncated archive record");
    const start = Number(view.getBigInt64(o, true));
    const length = view.getUint32(o + 8, true);
    if (o + 12 + length > bytes.byteLength) throw new Error("truncated archive record");
    if (out.length > 0 && start <= out[out.length - 1]!.start) throw new Error("archive records out of order");
    out.push({ start, rec: bytes.slice(o + 12, o + 12 + length) });
    o += 12 + length;
  }
  return out;
}

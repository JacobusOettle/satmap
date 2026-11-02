// The change feed (SPEC §6.3, §6.5 E19) and the tick's `changes` (§6.6): every row clients must see carries the
// group-wide `seq` it was last written with, so "changes since a cursor" is one ordered union over the tables.

import { b64urlEncode } from "../lib/b64url";
import { WINDOW } from "../lib/limits";
import { monthOf } from "../lib/time";

export type ChangeItem =
  | { kind: "group"; seq: number; profile: string }
  | { kind: "member"; seq: number; memberId: string; role: string; joinedAt: number; profile: string }
  | { kind: "chunk"; seq: number; memberId: string; start: number; rec: string }
  | { kind: "archive"; seq: number; memberId: string; month: string; maxChunkSeq: number; archivedAt: number }
  | {
      kind: "pin";
      seq: number;
      pinId: string;
      memberId: string;
      createdAt: number;
      expiresAt: number | null;
      rec: string;
    }
  | { kind: "removed"; seq: number; what: "pin" | "member" | "trail"; id: string };

export interface ChangesPage {
  items: ChangeItem[];
  cursor: number;
  more: boolean;
}

type Row = {
  kind: string;
  seq: number;
  a: string | null;
  b: string | null;
  c: number | null;
  d: number | null;
  rec: ArrayBuffer | null;
}

function blob(value: ArrayBuffer | null): string {
  return value === null ? "" : b64urlEncode(new Uint8Array(value));
}

function toItem(row: Row): ChangeItem {
  switch (row.kind) {
    case "group":
      return { kind: "group", seq: row.seq, profile: blob(row.rec) };
    case "member":
      return { kind: "member", seq: row.seq, memberId: row.a!, role: row.b!, joinedAt: row.c!, profile: blob(row.rec) };
    case "chunk":
      return { kind: "chunk", seq: row.seq, memberId: row.a!, start: row.c!, rec: blob(row.rec) };
    case "archive":
      return { kind: "archive", seq: row.seq, memberId: row.a!, month: row.b!, maxChunkSeq: row.c!, archivedAt: row.d! };
    case "pin":
      return {
        kind: "pin",
        seq: row.seq,
        pinId: row.a!,
        memberId: row.b!,
        createdAt: row.c!,
        expiresAt: row.d,
        rec: blob(row.rec),
      };
    default:
      return { kind: "removed", seq: row.seq, what: row.a as "pin" | "member" | "trail", id: row.b! };
  }
}

/**
 * Items with `seq > since` for a viewer who joined at `joinedAt`, in ascending `seq`, at most `limit` items and about
 * `maxBytes` of JSON (always at least one item when any is left).
 *
 * - Chunks with `start + 600 ≤ joinedAt` are left out; archives when their whole month ends before `joinedAt`.
 * - Pins: only unexpired ones, whatever their age (SD2). Members: current members only.
 */
export function readChanges(
  sql: SqlStorage,
  q: { since: number; joinedAt: number; now: number; limit: number; maxBytes: number },
): ChangesPage {
  // A month ends before joinedAt exactly when it sorts before joinedAt's own month.
  const firstMonth = monthOf(q.joinedAt);
  const take = q.limit + 1;
  // One query per table, each in `seq` order through its seq index, merged here: Durable Object SQLite allows only
  // a few terms in a compound SELECT, so one UNION ALL over the six tables is refused.
  const parts: Row[][] = [
    sql
      .exec<Row>(
        `SELECT 'group' AS kind, profile_seq AS seq, NULL AS a, NULL AS b, NULL AS c, NULL AS d, profile AS rec
           FROM grp WHERE one = 1 AND profile_seq > ? AND profile IS NOT NULL`,
        q.since,
      )
      .toArray(),
    sql
      .exec<Row>(
        `SELECT 'member' AS kind, seq, member_id AS a, role AS b, joined_at AS c, NULL AS d, profile AS rec
           FROM members WHERE seq > ? ORDER BY seq LIMIT ?`,
        q.since,
        take,
      )
      .toArray(),
    sql
      .exec<Row>(
        `SELECT 'chunk' AS kind, seq, member_id AS a, NULL AS b, start AS c, NULL AS d, rec
           FROM chunks WHERE seq > ? AND start + ${WINDOW} > ? ORDER BY seq LIMIT ?`,
        q.since,
        q.joinedAt,
        take,
      )
      .toArray(),
    sql
      .exec<Row>(
        `SELECT 'archive' AS kind, seq, member_id AS a, month AS b, max_chunk_seq AS c, archived_at AS d, NULL AS rec
           FROM archives WHERE seq > ? AND month >= ? ORDER BY seq LIMIT ?`,
        q.since,
        firstMonth,
        take,
      )
      .toArray(),
    sql
      .exec<Row>(
        `SELECT 'pin' AS kind, seq, pin_id AS a, member_id AS b, created_at AS c, expires_at AS d, rec
           FROM pins WHERE seq > ? AND (expires_at IS NULL OR expires_at > ?) ORDER BY seq LIMIT ?`,
        q.since,
        q.now,
        take,
      )
      .toArray(),
    sql
      .exec<Row>(
        `SELECT 'removed' AS kind, seq, what AS a, id AS b, NULL AS c, NULL AS d, NULL AS rec
           FROM removals WHERE seq > ? ORDER BY seq LIMIT ?`,
        q.since,
        take,
      )
      .toArray(),
  ];
  const rows = parts
    .flat()
    .sort((x, y) => x.seq - y.seq)
    .slice(0, take);
  const items: ChangeItem[] = [];
  let bytes = 2;
  let more = rows.length > q.limit;
  for (const row of rows.slice(0, q.limit)) {
    const item = toItem(row);
    const size = JSON.stringify(item).length + 1;
    if (items.length > 0 && bytes + size > q.maxBytes) {
      more = true;
      break;
    }
    items.push(item);
    bytes += size;
  }
  return { items, cursor: items.length > 0 ? items[items.length - 1]!.seq : q.since, more };
}

// GroupDO's SQLite schema (SPEC §7.3) and migrations by schema_version.
//
// The tables are created when a group is created (E1), not when the object starts, so a request to an id that
// was never a group (a guessed id, an unknown invite) leaves no stored object behind.

import { JOBS_DDL } from "../lib/jobs";

export const SCHEMA_VERSION = 1;

export const SCHEMA = `
CREATE TABLE IF NOT EXISTS grp (
  one INTEGER PRIMARY KEY CHECK (one = 1),
  group_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('active','paused','deleted')),
  paused_reason TEXT, paused_at INTEGER, delete_at INTEGER, deleted_at INTEGER,
  profile BLOB, profile_seq INTEGER NOT NULL DEFAULT 0,
  join_proof_hash BLOB,
  seq INTEGER NOT NULL DEFAULT 0,
  min_cursor INTEGER NOT NULL DEFAULT 0,
  pro_name TEXT, pro_platform TEXT, pro_product TEXT,
  pro_expires_at INTEGER,
  schema_version INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS members (
  member_id TEXT PRIMARY KEY,
  pass_hash BLOB NOT NULL UNIQUE,
  role TEXT NOT NULL CHECK (role IN ('owner','admin','member')),
  joined_at INTEGER NOT NULL,
  profile BLOB NOT NULL,
  seq INTEGER NOT NULL,
  terms_version INTEGER NOT NULL,
  last_live_at INTEGER
);
CREATE INDEX IF NOT EXISTS members_seq ON members(seq);
CREATE TABLE IF NOT EXISTS gone_passes (pass_hash BLOB PRIMARY KEY, reason TEXT NOT NULL, at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS invites (
  invite_id TEXT PRIMARY KEY,
  secret_hash BLOB NOT NULL UNIQUE,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('pending','used','cancelled')),
  used_by TEXT, used_pass_hash BLOB, used_at INTEGER
);
CREATE TABLE IF NOT EXISTS chunks (
  member_id TEXT NOT NULL, start INTEGER NOT NULL, rec BLOB NOT NULL,
  seq INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  PRIMARY KEY (member_id, start)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS chunks_seq ON chunks(seq);
CREATE INDEX IF NOT EXISTS chunks_start ON chunks(start);
CREATE TABLE IF NOT EXISTS archives (
  member_id TEXT NOT NULL, month TEXT NOT NULL, count INTEGER NOT NULL, bytes INTEGER NOT NULL,
  first_start INTEGER NOT NULL, last_start INTEGER NOT NULL, seq INTEGER NOT NULL,
  max_chunk_seq INTEGER NOT NULL, archived_at INTEGER NOT NULL,
  PRIMARY KEY (member_id, month)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS archives_seq ON archives(seq);
CREATE TABLE IF NOT EXISTS pins (
  pin_id TEXT PRIMARY KEY, member_id TEXT NOT NULL, created_at INTEGER NOT NULL,
  expires_at INTEGER, rec BLOB NOT NULL, seq INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS pins_seq ON pins(seq);
CREATE INDEX IF NOT EXISTS pins_member ON pins(member_id);
CREATE INDEX IF NOT EXISTS pins_expiry ON pins(expires_at) WHERE expires_at IS NOT NULL;
CREATE TABLE IF NOT EXISTS removals (
  seq INTEGER PRIMARY KEY, what TEXT NOT NULL CHECK (what IN ('pin','member','trail')),
  id TEXT NOT NULL, at INTEGER NOT NULL
);
-- Not in the SPEC DDL: an index for the SD24 "id never reused" and report target look-ups by id.
CREATE INDEX IF NOT EXISTS removals_id ON removals(what, id);
CREATE TABLE IF NOT EXISTS live (member_id TEXT PRIMARY KEY, at INTEGER NOT NULL, rec BLOB NOT NULL) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS pause_log (paused_at INTEGER PRIMARY KEY, resumed_at INTEGER, reason TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS transfer (
  one INTEGER PRIMARY KEY CHECK (one = 1), to_member TEXT NOT NULL,
  offered_at INTEGER NOT NULL, expires_at INTEGER NOT NULL
);
${JOBS_DDL}
CREATE TABLE IF NOT EXISTS r2_deletes (prefix TEXT PRIMARY KEY, at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS counters (key TEXT PRIMARY KEY, window_start INTEGER NOT NULL, count INTEGER NOT NULL);
`;

export function schemaExists(sql: SqlStorage): boolean {
  return sql.exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'grp'").toArray().length > 0;
}

export function createSchema(sql: SqlStorage): void {
  sql.exec(SCHEMA);
}

/**
 * Brings an existing group's tables up to SCHEMA_VERSION. Returns false when this object holds no group. Each later
 * version adds a step here (`if (version < 2) { …; version = 2 }`) and bumps grp.schema_version in the same run.
 */
export function migrate(sql: SqlStorage): boolean {
  if (!schemaExists(sql)) return false;
  sql.exec(SCHEMA);
  const row = sql.exec<{ schema_version: number }>("SELECT schema_version FROM grp WHERE one = 1").toArray()[0];
  if (row !== undefined && row.schema_version < SCHEMA_VERSION) {
    sql.exec("UPDATE grp SET schema_version = ?", SCHEMA_VERSION);
  }
  return true;
}

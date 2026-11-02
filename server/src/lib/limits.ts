// Numbers from SPEC §6 and §7, in one place.

export const KIB = 1024;

/** Request bodies (SPEC §6.1, §6.8). */
export const BODY_LIMIT = 256 * KIB;
export const BODY_LIMIT_SMALL = 64 * KIB; // E1, E9, E10, E25

/** WebSocket frames (SPEC §6.6). */
export const WS_IN_LIMIT = 256 * KIB;
export const WS_OUT_LIMIT = 512 * KIB;
export const WS_HELLO_TIMEOUT_MS = 10_000;
export const WS_MAX_SOCKETS = 4;
export const TICK_DELAY_MS = 2_000;
export const TICK_MAX_ITEMS = 200;
export const TICK_MAX_BYTES = 256 * KIB;

/** E19 page (SPEC §6.5). */
export const CHANGES_DEFAULT_LIMIT = 500;
export const CHANGES_MAX_LIMIT = 1000;
export const CHANGES_MAX_BYTES = 2 * KIB * KIB;

/** Times, in seconds. */
export const MINUTE = 60;
export const HOUR = 3600;
export const DAY = 86_400;
export const WINDOW = 600; // trail window (SPEC §4.1)
export const INVITE_LIFETIME = 7 * DAY;
export const TRANSFER_LIFETIME = 7 * DAY;
export const ANIMAL_PIN_LIFETIME = 43_200;
export const PAUSE_RETENTION = 30 * DAY; // R8: delete_at = paused_at + 30 days
export const DELETED_RETENTION = 30 * DAY;
export const GONE_PASS_RETENTION = 30 * DAY;
export const REMOVALS_RETENTION = 400 * DAY;
export const INVITE_RETENTION = 30 * DAY;
export const PAUSE_LOG_RETENTION = 30 * DAY;
export const LIVE_ROW_RETENTION = 30 * DAY;
export const LIVE_VISIBLE = DAY; // E21 and welcome: entries from the last 24 h
export const ARCHIVE_AGE = 30 * DAY;
export const CLOCK_AHEAD = 600; // uploads may be up to 10 min ahead of the server clock
export const LIVE_MAX_AGE = 3600;
export const PIN_MAX_AGE = 7 * DAY; // pins that never expire: createdAt ≥ now − 604800
export const TEST_PURCHASE_LIFETIME = 14 * DAY; // SD20

/** Live positions (SPEC §6.5 E18, §7.3). */
export const LIVE_MIN_INTERVAL_MS = 2_000;
export const LIVE_PERSIST_INTERVAL_MS = 60_000;

/** Counts (SPEC §6.8). */
export const MAX_CHUNKS_PER_UPLOAD = 50;
export const MAX_PENDING_INVITES = 100;
export const MAX_INVITES_PER_DAY = 200;
export const MAX_PINS_PER_MEMBER_HOUR = 60;
export const MAX_UNEXPIRED_PINS = 2000;
export const MAX_REPORTS_PER_MEMBER_DAY = 20;
export const MAX_REQUESTS_PER_MEMBER_MINUTE = 120;

/** Archive pass (SPEC §7.5). */
export const ARCHIVE_BATCH = 50;
export const R2_DELETE_BATCH = 1000;

/** Envelope sizes in bytes, inclusive (SPEC §6.8). */
export const ENVELOPE_SIZES = {
  groupProfile: [39, 512],
  memberProfile: [39, 512],
  chunk: [32, 12_288],
  live: [53, 128],
  pin: [49, 1024],
} as const;

/** Report text limits: [code points, UTF-8 bytes] (SPEC §2.5, §6.5 E25). */
export const REPORT_TEXT = {
  comment: [500, 2000],
  groupName: [40, 160],
  memberName: [30, 120],
  animal: [40, 160],
  note: [140, 560],
  type: [20, 80],
} as const;

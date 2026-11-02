// The `jobs` table and the alarm error rule shared by the three Durable Objects (SPEC §7.4): a job that throws stays
// in `jobs` with `tries + 1`, retried 15 min after the first failure and 1 h after each later one; a job that
// succeeds resets `tries`. alarm() never throws.

import { errorText, logLine } from "./log";

export interface Job {
  kind: string;
  at: number;
  tries: number;
}

export const JOBS_DDL =
  "CREATE TABLE IF NOT EXISTS jobs (kind TEXT PRIMARY KEY, at INTEGER NOT NULL, tries INTEGER NOT NULL DEFAULT 0);";

export class Jobs {
  constructor(private readonly sql: SqlStorage) {}

  /** Schedules `kind` at `at` (seconds), keeping its failure count. */
  set(kind: string, at: number): void {
    this.sql.exec(
      "INSERT INTO jobs (kind, at, tries) VALUES (?, ?, 0) ON CONFLICT (kind) DO UPDATE SET at = excluded.at",
      kind,
      at,
    );
  }

  /** Schedules `kind` at `at` unless it is already scheduled earlier. */
  setEarliest(kind: string, at: number): void {
    this.sql.exec(
      "INSERT INTO jobs (kind, at, tries) VALUES (?, ?, 0) ON CONFLICT (kind) DO UPDATE SET at = MIN(at, excluded.at)",
      kind,
      at,
    );
  }

  get(kind: string): Job | null {
    const rows = this.sql.exec<{ kind: string; at: number; tries: number }>(
      "SELECT kind, at, tries FROM jobs WHERE kind = ?",
      kind,
    ).toArray();
    return rows[0] ?? null;
  }

  delete(kind: string): void {
    this.sql.exec("DELETE FROM jobs WHERE kind = ?", kind);
  }

  due(now: number): Job[] {
    return this.sql
      .exec<{ kind: string; at: number; tries: number }>("SELECT kind, at, tries FROM jobs WHERE at <= ? ORDER BY at", now)
      .toArray();
  }

  /** A successful run: reset the failure count and schedule the next run (or delete with `next` null). */
  done(kind: string, next: number | null): void {
    if (next === null) this.delete(kind);
    else this.sql.exec("UPDATE jobs SET at = ?, tries = 0 WHERE kind = ?", next, kind);
  }

  failed(kind: string, now: number, err: unknown): void {
    const job = this.get(kind);
    const tries = job?.tries ?? 0;
    const at = now + (tries === 0 ? 15 * 60 : 60 * 60);
    this.sql.exec(
      "INSERT INTO jobs (kind, at, tries) VALUES (?, ?, ?) ON CONFLICT (kind) DO UPDATE SET at = excluded.at, tries = excluded.tries",
      kind,
      at,
      tries + 1,
    );
    logLine({ t: "job", kind, err: errorText(err) });
  }

  earliest(): number | null {
    const row = this.sql.exec<{ at: number | null }>("SELECT MIN(at) AS at FROM jobs").one();
    return row.at;
  }
}

/** Runs each due job inside its own try/catch, in order. `run` reschedules or deletes the job on success. */
export async function runDueJobs(
  jobs: Jobs,
  now: number,
  run: (job: Job) => Promise<void>,
  stopped: () => boolean = () => false,
): Promise<void> {
  for (const job of jobs.due(now)) {
    if (stopped()) return; // the object deleted its storage
    // A job run earlier in this alarm may have deleted or moved this one.
    const current = jobs.get(job.kind);
    if (current === null || current.at > now) continue;
    try {
      await run(current);
    } catch (err) {
      jobs.failed(job.kind, now, err);
    }
  }
}

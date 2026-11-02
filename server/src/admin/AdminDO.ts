// AdminDO: the reports (SPEC §6.5 E25, §7.7), in one Durable Object named "admin". Reports never hold positions
// (SD10). A report's `content` is the reporter's claim; the `target_*` columns are the server's own facts.

import { DurableObject } from "cloudflare:workers";
import type { AppEnv } from "../env";
import { readConfig, type Config } from "../lib/config";
import { Jobs, JOBS_DDL, runDueJobs } from "../lib/jobs";
import { DAY, HOUR } from "../lib/limits";
import { logLine } from "../lib/log";
import { systemNow } from "../lib/time";
import type { HttpFetch } from "../pro/types";
import type { NewReport } from "./types";

const SCHEMA = `
CREATE TABLE IF NOT EXISTS reports (
  report_id TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  group_id TEXT NOT NULL,
  reporter_id TEXT NOT NULL,
  target_kind TEXT NOT NULL CHECK (target_kind IN ('pin','member','group')),
  target_id TEXT NOT NULL,
  reason TEXT NOT NULL,
  comment TEXT NOT NULL,
  content_json TEXT NOT NULL,
  target_exists INTEGER NOT NULL,
  target_member_id TEXT,
  target_time INTEGER,
  target_bytes INTEGER,
  status TEXT NOT NULL CHECK (status IN ('open','resolved')),
  resolved_at INTEGER,
  action TEXT,
  admin_note TEXT
);
CREATE INDEX IF NOT EXISTS reports_created ON reports(created_at);
CREATE INDEX IF NOT EXISTS reports_target ON reports(group_id, target_kind, target_id);
-- Webhook alerts still to deliver (REPORT_WEBHOOK_URL), retried hourly for a day.
CREATE TABLE IF NOT EXISTS outbox (report_id TEXT PRIMARY KEY, tries INTEGER NOT NULL, next_at INTEGER NOT NULL);
${JOBS_DDL}
`;

const RESOLVED_RETENTION = 180 * DAY;
const OPEN_RETENTION = 365 * DAY;
const WEBHOOK_TRIES = 24;

export type ReportStatusFilter = "open" | "resolved" | "all";
export type ReportAction = "none" | "pin_deleted" | "member_removed" | "group_deleted";

type ReportRow = {
  report_id: string;
  created_at: number;
  group_id: string;
  reporter_id: string;
  target_kind: string;
  target_id: string;
  reason: string;
  comment: string;
  content_json: string;
  target_exists: number;
  target_member_id: string | null;
  target_time: number | null;
  target_bytes: number | null;
  status: string;
  resolved_at: number | null;
  action: string | null;
  admin_note: string | null;
  reporters: number;
}

export interface ReportView {
  reportId: string;
  createdAt: number;
  groupId: string;
  reporterId: string;
  targetKind: string;
  targetId: string;
  reason: string;
  comment: string;
  content: Record<string, string>;
  facts: { targetExists: boolean; targetMemberId: string | null; targetTime: number | null; targetBytes: number | null };
  reporters: number;
  status: string;
  resolvedAt: number | null;
  action: string | null;
  adminNote: string | null;
}

const SELECT_REPORTS = `
  SELECT r.*, (SELECT COUNT(DISTINCT o.reporter_id) FROM reports o
               WHERE o.group_id = r.group_id AND o.target_kind = r.target_kind AND o.target_id = r.target_id) AS reporters
  FROM reports r`;

function view(row: ReportRow): ReportView {
  return {
    reportId: row.report_id,
    createdAt: row.created_at,
    groupId: row.group_id,
    reporterId: row.reporter_id,
    targetKind: row.target_kind,
    targetId: row.target_id,
    reason: row.reason,
    comment: row.comment,
    content: JSON.parse(row.content_json) as Record<string, string>,
    facts: {
      targetExists: row.target_exists === 1,
      targetMemberId: row.target_member_id,
      targetTime: row.target_time,
      targetBytes: row.target_bytes,
    },
    reporters: row.reporters,
    status: row.status,
    resolvedAt: row.resolved_at,
    action: row.action,
    adminNote: row.admin_note,
  };
}

export class AdminDO extends DurableObject<AppEnv> {
  clockOverride: number | null = null;
  httpFetch: HttpFetch = (input, init) => fetch(input, init);
  envOverride: Record<string, string> | null = null;

  constructor(ctx: DurableObjectState, env: AppEnv) {
    super(ctx, env);
    void ctx.blockConcurrencyWhile(async () => {
      ctx.storage.sql.exec(SCHEMA);
    });
  }

  get conf(): Config {
    return readConfig({ ...this.env, ...this.envOverride });
  }

  now(): number {
    return this.clockOverride ?? systemNow();
  }

  private get sql(): SqlStorage {
    return this.ctx.storage.sql;
  }

  private get jobs(): Jobs {
    return new Jobs(this.sql);
  }

  async addReport(r: NewReport): Promise<void> {
    const now = this.now();
    this.ctx.storage.transactionSync(() => {
      this.sql.exec(
        `INSERT INTO reports (report_id, created_at, group_id, reporter_id, target_kind, target_id, reason, comment,
           content_json, target_exists, target_member_id, target_time, target_bytes, status)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open')`,
        r.reportId,
        r.createdAt,
        r.groupId,
        r.reporterId,
        r.targetKind,
        r.targetId,
        r.reason,
        r.comment,
        JSON.stringify(r.content),
        r.facts.targetExists ? 1 : 0,
        r.facts.targetMemberId,
        r.facts.targetTime,
        r.facts.targetBytes,
      );
      if (this.conf.reportWebhookUrl !== "") {
        this.sql.exec("INSERT OR REPLACE INTO outbox (report_id, tries, next_at) VALUES (?, 0, ?)", r.reportId, now);
        this.jobs.set("webhook", now);
      }
      if (this.jobs.get("cleanup") === null) this.jobs.set("cleanup", now + DAY);
    });
    this.setAlarm();
  }

  async listReports(q: { status: ReportStatusFilter; limit: number; before: number | null }): Promise<ReportView[]> {
    const where: string[] = [];
    const args: (string | number)[] = [];
    if (q.status !== "all") {
      where.push("r.status = ?");
      args.push(q.status);
    }
    if (q.before !== null) {
      where.push("r.created_at < ?");
      args.push(q.before);
    }
    const sql = `${SELECT_REPORTS} ${where.length > 0 ? `WHERE ${where.join(" AND ")}` : ""}
      ORDER BY r.created_at DESC, r.report_id DESC LIMIT ?`;
    return this.sql.exec<ReportRow>(sql, ...args, q.limit).toArray().map(view);
  }

  async resolve(reportId: string, action: ReportAction, note: string): Promise<ReportView | null> {
    const now = this.now();
    const changed = this.sql
      .exec(
        "UPDATE reports SET status = 'resolved', resolved_at = ?, action = ?, admin_note = ? WHERE report_id = ? RETURNING report_id",
        now,
        action,
        note,
        reportId,
      )
      .toArray().length;
    if (changed === 0) return null;
    if (this.jobs.get("cleanup") === null) {
      this.jobs.set("cleanup", now + DAY);
      this.setAlarm();
    }
    return view(this.sql.exec<ReportRow>(`${SELECT_REPORTS} WHERE r.report_id = ?`, reportId).one());
  }

  private setAlarm(): void {
    const at = this.jobs.earliest();
    if (at === null) void this.ctx.storage.deleteAlarm();
    else void this.ctx.storage.setAlarm(at * 1000);
  }

  override async alarm(): Promise<void> {
    const now = this.now();
    try {
      await runDueJobs(this.jobs, now, async (job) => {
        if (job.kind === "cleanup") {
          this.sql.exec(
            "DELETE FROM reports WHERE (status = 'resolved' AND resolved_at < ?) OR (status = 'open' AND created_at < ?)",
            now - RESOLVED_RETENTION,
            now - OPEN_RETENTION,
          );
          const left = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM reports").one().n;
          this.jobs.done("cleanup", left > 0 ? now + DAY : null);
        } else if (job.kind === "webhook") {
          await this.deliver(now);
        } else {
          this.jobs.done(job.kind, null);
        }
      });
    } finally {
      this.setAlarm();
    }
  }

  /** POSTs `{reportId, createdAt, reason, targetKind}` (no content) to REPORT_WEBHOOK_URL for each due alert. */
  private async deliver(now: number): Promise<void> {
    const url = this.conf.reportWebhookUrl;
    const due = this.sql
      .exec<{ report_id: string; tries: number; created_at: number | null; reason: string | null; target_kind: string | null }>(
        `SELECT o.report_id, o.tries, r.created_at, r.reason, r.target_kind FROM outbox o
         LEFT JOIN reports r ON r.report_id = o.report_id WHERE o.next_at <= ?`,
        now,
      )
      .toArray();
    for (const item of due) {
      let ok = false;
      if (url !== "" && item.created_at !== null) {
        try {
          const response = await this.httpFetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              reportId: item.report_id,
              createdAt: item.created_at,
              reason: item.reason,
              targetKind: item.target_kind,
            }),
          });
          ok = response.ok;
          logLine({ t: "webhook", status: response.status });
        } catch {
          logLine({ t: "webhook", status: 0 });
        }
      }
      if (ok || url === "" || item.created_at === null || item.tries + 1 >= WEBHOOK_TRIES) {
        this.sql.exec("DELETE FROM outbox WHERE report_id = ?", item.report_id);
      } else {
        this.sql.exec("UPDATE outbox SET tries = tries + 1, next_at = ? WHERE report_id = ?", now + HOUR, item.report_id);
      }
    }
    const next = this.sql.exec<{ at: number | null }>("SELECT MIN(next_at) AS at FROM outbox").one().at;
    this.jobs.done("webhook", next);
  }
}

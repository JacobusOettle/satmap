// What GroupDO hands to AdminDO for a report (SPEC §6.5 E25, §7.7).

export type ReportTargetKind = "pin" | "member" | "group";
export type ReportReason = "offensive" | "harassment" | "spam" | "dangerous" | "other";

export interface ReportFacts {
  targetExists: boolean;
  targetMemberId: string | null;
  targetTime: number | null;
  targetBytes: number | null;
}

export interface NewReport {
  reportId: string;
  createdAt: number;
  groupId: string;
  reporterId: string;
  targetKind: ReportTargetKind;
  targetId: string;
  reason: ReportReason;
  comment: string;
  content: Record<string, string>;
  facts: ReportFacts;
}

/** The answer of an admin RPC on GroupDO (A3–A6): a status and, for errors, the code. */
export interface AdminResult {
  status: number;
  error?: string;
  body?: unknown;
}

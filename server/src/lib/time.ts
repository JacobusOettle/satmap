// Times on the wire are Unix seconds (SPEC §2.3). Months are `YYYY-MM` in UTC.

import { WINDOW } from "./limits";

export function systemNow(): number {
  return Math.floor(Date.now() / 1000);
}

/** `YYYY-MM` (UTC) of a Unix time. */
export function monthOf(t: number): string {
  const d = new Date(t * 1000);
  return `${d.getUTCFullYear().toString().padStart(4, "0")}-${(d.getUTCMonth() + 1).toString().padStart(2, "0")}`;
}

const MONTH_TEXT = /^(\d{4})-(0[1-9]|1[0-2])$/;

export function isMonth(text: string): boolean {
  return MONTH_TEXT.test(text);
}

/** First second of the month, and the first second of the next month. */
export function monthRange(month: string): [number, number] {
  const m = MONTH_TEXT.exec(month);
  if (!m) throw new Error("bad month");
  const year = Number(m[1]);
  const index = Number(m[2]) - 1;
  return [Date.UTC(year, index, 1) / 1000, Date.UTC(year, index + 1, 1) / 1000];
}

export function windowStart(t: number): number {
  return t - (t % WINDOW);
}

/** Start of the UTC day holding `t`. */
export function dayStart(t: number): number {
  return t - (t % 86_400);
}

export function dayOfMonth(t: number): number {
  return new Date(t * 1000).getUTCDate();
}

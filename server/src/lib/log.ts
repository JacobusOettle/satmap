// Logging without personal data (SPEC §7.9): one JSON line per event, never ids, tokens, passes, proofs, IPs,
// user agents, bodies or per-member record sizes.

/** Any run of 22 or more base64url characters could be an id, a pass, a token or a JWS part. */
const ID_SHAPED = /[A-Za-z0-9_-]{22,}/g;

export function scrub(text: string): string {
  return text.replace(ID_SHAPED, "…");
}

type Field = string | number | boolean | null | undefined;

export function logLine(fields: Record<string, Field>): void {
  const clean: Record<string, Field> = {};
  for (const [key, value] of Object.entries(fields)) {
    if (value === undefined) continue;
    clean[key] = typeof value === "string" ? scrub(value) : value;
  }
  console.log(JSON.stringify(clean));
}

export function logException(route: string, err: unknown): void {
  const name = err instanceof Error ? err.name : typeof err;
  const message = err instanceof Error ? err.message : String(err);
  logLine({ t: "exc", route, name, message: message.slice(0, 500) });
}

export function errorText(err: unknown): string {
  return scrub(err instanceof Error ? `${err.name}: ${err.message}` : String(err)).slice(0, 300);
}

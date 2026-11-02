// SatMap groups Worker (docs/groups/SPEC.md §6, §7.2): routing, body limits, the RL_CREATE and RL_INVITE rate
// limits, store notification checks, the admin endpoints, static pages, error mapping and one log line per request.

import pkg from "../package.json";
import type { ReportAction, ReportStatusFilter } from "./admin/AdminDO";
import type { AdminResult } from "./admin/types";
import type { AppEnv } from "./env";
import { readConfig, type Config } from "./lib/config";
import { ApiError, apiErrorResponse, errorResponse, isErrorCode, json, noContent } from "./lib/errors";
import { textEqual } from "./lib/hash";
import { isCanonicalId, splitToken } from "./lib/ids";
import { logException, logLine } from "./lib/log";
import { AppleVerifyError, appleSettings, verifyNotification } from "./pro/apple";
import { decodeRtdn } from "./pro/google";
import { appleProName, googleProName } from "./pro/ProDO";
import { matchRoute, routeTemplate, type Route } from "./routes";
import { landingPage } from "./web/landing";
import { homePage, privacyPage, termsPage } from "./web/pages";
import { appleAppSiteAssociation, assetLinks } from "./web/wellknown";

export { GroupDO } from "./group/GroupDO";
export { ProDO } from "./pro/ProDO";
export { AdminDO } from "./admin/AdminDO";

export const VERSION: string = pkg.version;

interface Handled {
  route: string;
  response: Response;
}

const JSON_TYPE = /^application\/json\s*(;.*)?$/i;

/** Reads a request body up to `limit` bytes: Content-Length first, then the bytes actually read (SPEC §6.1). */
async function readBody(request: Request, limit: number): Promise<Uint8Array> {
  const declared = request.headers.get("Content-Length");
  if (declared !== null && Number(declared) > limit) throw new ApiError("too_large");
  if (request.body === null) return new Uint8Array(0);
  const reader = request.body.getReader();
  const parts: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel();
      throw new ApiError("too_large");
    }
    parts.push(value);
  }
  const out = new Uint8Array(total);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.byteLength;
  }
  return out;
}

function parseJson(bytes: Uint8Array): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    throw new ApiError("bad_request", "The body is not JSON.");
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ApiError("bad_request", "The body must be a JSON object.");
  }
  return value as Record<string, unknown>;
}

/** Applies a required rate-limit binding (SPEC §6.8). Under TEST_MODE=1 a missing binding means no limit. */
async function rateLimit(binding: RateLimit | undefined, name: string, key: string, cfg: Config): Promise<void> {
  if (binding === undefined || binding === null) {
    if (cfg.testMode) return;
    logLine({ t: "cfg", missing: name });
    throw new ApiError("internal", "A required binding is missing.");
  }
  const { success } = await binding.limit({ key });
  if (!success) throw new ApiError("rate_limited", undefined, 60);
}

function clientIp(request: Request): string {
  // Used only as a rate-limit key; never stored or logged.
  return request.headers.get("CF-Connecting-IP") ?? "unknown";
}

function groupStub(env: AppEnv, groupId: string) {
  return env.GROUP.get(env.GROUP.idFromName(groupId), { locationHint: "afr" });
}

function forward(request: Request, body: Uint8Array | null): Request {
  return new Request(request.url, { method: request.method, headers: request.headers, body });
}

function adminResponse(result: AdminResult): Response {
  if (result.status === 204) return noContent();
  if (result.error !== undefined && isErrorCode(result.error)) return errorResponse(result.error);
  return json(result.body ?? {}, result.status);
}

async function handleWeb(request: Request, url: URL, cfg: Config): Promise<Handled | null> {
  const path = url.pathname;
  let route: string;
  let make: () => Promise<Response> | Response;
  if (path.startsWith("/j/")) {
    route = "GET /j/:token";
    make = () => landingPage(path.slice(3), cfg);
  } else if (path === "/.well-known/apple-app-site-association") {
    route = `GET ${path}`;
    make = () => appleAppSiteAssociation(cfg);
  } else if (path === "/.well-known/assetlinks.json") {
    route = `GET ${path}`;
    make = () => assetLinks(cfg);
  } else if (path === "/terms") {
    route = "GET /terms";
    make = () => termsPage(cfg);
  } else if (path === "/privacy") {
    route = "GET /privacy";
    make = () => privacyPage(cfg);
  } else if (path === "/") {
    route = "GET /";
    make = () => homePage(cfg);
  } else {
    return null;
  }
  if (request.method !== "GET" && request.method !== "HEAD") {
    return { route, response: errorResponse("method_not_allowed") };
  }
  const response = await make();
  return { route, response: request.method === "HEAD" ? new Response(null, response) : response };
}

async function handleApi(request: Request, env: AppEnv, url: URL, cfg: Config): Promise<Handled> {
  const match = matchRoute(request.method, url.pathname);
  if (match.type === "none") return { route: "unknown", response: errorResponse("not_found") };
  if (match.type === "method_not_allowed") return { route: "unknown", response: errorResponse("method_not_allowed") };
  const { route, params } = match;
  const template = routeTemplate(route);
  try {
    return { route: template, response: await handleRoute(route, params, request, env, url, cfg) };
  } catch (err) {
    if (err instanceof ApiError) return { route: template, response: apiErrorResponse(err) };
    logException(template, err);
    return { route: template, response: errorResponse("internal") };
  }
}

async function handleRoute(
  route: Route,
  params: Record<string, string>,
  request: Request,
  env: AppEnv,
  url: URL,
  cfg: Config,
): Promise<Response> {
  if (route.id.startsWith("A")) return handleAdmin(route, params, request, env, url, cfg);
  if (route.id === "E29") return json({ ok: true, version: VERSION });

  // E28's endpoint doesn't exist without its secret, so check it before reading anything.
  if (route.id === "E28") {
    if (cfg.googleRtdnToken.length < 32) throw new ApiError("not_found");
    if (!(await textEqual(url.searchParams.get("token") ?? "", cfg.googleRtdnToken))) throw new ApiError("forbidden");
  }

  let body: Uint8Array | null = null;
  if (route.limit > 0) {
    if (!JSON_TYPE.test(request.headers.get("Content-Type") ?? "")) throw new ApiError("unsupported_media_type");
    body = await readBody(request, route.limit);
  }

  switch (route.id) {
    case "E1": {
      await rateLimit(env.RL_CREATE, "RL_CREATE", `create:${clientIp(request)}`, cfg);
      const groupId = parseJson(body!).groupId;
      if (!isCanonicalId(groupId)) throw new ApiError("bad_request", "Malformed groupId.");
      return groupStub(env, groupId).fetch(forward(request, body));
    }
    case "E9":
    case "E10": {
      const op = route.id === "E9" ? "preview" : "redeem";
      await rateLimit(env.RL_INVITE, "RL_INVITE", `${op}:${clientIp(request)}`, cfg);
      const split = splitToken(parseJson(body!).token);
      if (split === null) throw new ApiError("bad_request", "Malformed token.");
      return groupStub(env, split.groupId).fetch(forward(request, body));
    }
    case "E27":
      return appleNotification(parseJson(body!), env, cfg);
    case "E28":
      return googleNotification(parseJson(body!), env);
  }

  // /v1/groups/{groupId}/…
  const groupId = params.gid;
  if (!isCanonicalId(groupId)) throw new ApiError("bad_request", "Malformed groupId.");
  if (route.id === "E26") {
    if ((request.headers.get("Upgrade") ?? "").toLowerCase() !== "websocket") throw new ApiError("upgrade_required");
    return groupStub(env, groupId).fetch(request);
  }
  return groupStub(env, groupId).fetch(forward(request, body));
}

async function appleNotification(body: Record<string, unknown>, env: AppEnv, cfg: Config): Promise<Response> {
  if (typeof body.signedPayload !== "string") throw new ApiError("bad_request", "signedPayload must be a string.");
  let notification;
  try {
    notification = await verifyNotification(body.signedPayload, appleSettings(cfg));
  } catch (err) {
    if (err instanceof AppleVerifyError) throw new ApiError("bad_request", "The notification does not verify.");
    throw err;
  }
  const tx = notification.transaction;
  if (notification.notificationType !== "TEST" && tx !== null) {
    const name = appleProName(tx.originalTransactionId);
    await env.PRO.get(env.PRO.idFromName(name)).notifyApple(name, notification);
  }
  return json({}, 200);
}

async function googleNotification(body: Record<string, unknown>, env: AppEnv): Promise<Response> {
  const rtdn = decodeRtdn(body);
  if (rtdn === null) throw new ApiError("bad_request", "Not a Pub/Sub push message.");
  if (rtdn.kind !== "test" && rtdn.purchaseToken !== null) {
    const name = await googleProName(rtdn.purchaseToken);
    await env.PRO.get(env.PRO.idFromName(name)).notifyGoogle(name, rtdn);
  }
  return noContent();
}

const ADMIN_ACTIONS: ReadonlySet<string> = new Set(["none", "pin_deleted", "member_removed", "group_deleted"]);

async function handleAdmin(
  route: Route,
  params: Record<string, string>,
  request: Request,
  env: AppEnv,
  url: URL,
  cfg: Config,
): Promise<Response> {
  if (cfg.adminToken === "") throw new ApiError("not_found");
  const header = request.headers.get("Authorization") ?? "";
  const given = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!(await textEqual(given, cfg.adminToken))) throw new ApiError("unauthorized");
  const admin = env.ADMIN.get(env.ADMIN.idFromName("admin"));

  switch (route.id) {
    case "A1": {
      const status = url.searchParams.get("status") ?? "open";
      if (status !== "open" && status !== "resolved" && status !== "all") throw new ApiError("bad_request", "Bad status.");
      const limitText = url.searchParams.get("limit") ?? "100";
      const beforeText = url.searchParams.get("before");
      if (!/^\d+$/.test(limitText) || (beforeText !== null && !/^\d+$/.test(beforeText))) {
        throw new ApiError("bad_request", "Bad limit or before.");
      }
      const limit = Math.min(Math.max(Number(limitText), 1), 500);
      const reports = await admin.listReports({
        status: status as ReportStatusFilter,
        limit,
        before: beforeText === null ? null : Number(beforeText),
      });
      return json({ reports });
    }
    case "A2": {
      const reportId = params.rid;
      if (!isCanonicalId(reportId)) throw new ApiError("bad_request", "Malformed reportId.");
      if (!JSON_TYPE.test(request.headers.get("Content-Type") ?? "")) throw new ApiError("unsupported_media_type");
      const body = parseJson(await readBody(request, route.limit));
      if (typeof body.action !== "string" || !ADMIN_ACTIONS.has(body.action)) throw new ApiError("bad_request", "Bad action.");
      const note = body.note === undefined || body.note === null ? "" : body.note;
      if (typeof note !== "string" || note.length > 2000) throw new ApiError("bad_request", "Bad note.");
      const report = await admin.resolve(reportId, body.action as ReportAction, note);
      if (report === null) throw new ApiError("not_found");
      return json(report);
    }
  }

  const groupId = params.gid;
  if (!isCanonicalId(groupId)) throw new ApiError("bad_request", "Malformed groupId.");
  const group = groupStub(env, groupId);
  switch (route.id) {
    case "A3": {
      if (!isCanonicalId(params.pid)) throw new ApiError("bad_request", "Malformed pinId.");
      return adminResponse(await group.adminDeletePin(params.pid));
    }
    case "A4": {
      if (!isCanonicalId(params.mid)) throw new ApiError("bad_request", "Malformed memberId.");
      return adminResponse(await group.adminRemoveMember(params.mid));
    }
    case "A5":
      return adminResponse(await group.adminDeleteGroup());
    default:
      return adminResponse(await group.adminInfo());
  }
}

/** The error code of an error response, for the log line (bodies are tiny JSON). */
async function errorCode(response: Response): Promise<string | undefined> {
  if (response.status < 400 || !(response.headers.get("Content-Type") ?? "").startsWith("application/json")) return undefined;
  try {
    const body = (await response.clone().json()) as { error?: unknown };
    return typeof body.error === "string" ? body.error : undefined;
  } catch {
    return undefined;
  }
}

export default {
  async fetch(request, env): Promise<Response> {
    const started = Date.now();
    const url = new URL(request.url);
    const cfg = readConfig(env);
    let handled: Handled;
    try {
      handled =
        (await handleWeb(request, url, cfg)) ??
        (url.pathname.startsWith("/v1/")
          ? await handleApi(request, env, url, cfg)
          : { route: "unknown", response: errorResponse("not_found") });
    } catch (err) {
      logException("worker", err);
      handled = { route: "unknown", response: errorResponse("internal") };
    }
    const { response } = handled;
    logLine({
      t: "req",
      route: handled.route,
      status: response.status,
      ms: Date.now() - started,
      err: await errorCode(response),
    });
    return response;
  },
} satisfies ExportedHandler<AppEnv>;

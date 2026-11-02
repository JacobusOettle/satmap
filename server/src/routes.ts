// The API's routes (SPEC §6.4), shared by the Worker (method checks, body limits, log templates) and GroupDO
// (dispatch). Path segments starting with ":" match one non-empty segment.

import { BODY_LIMIT, BODY_LIMIT_SMALL } from "./lib/limits";

export interface Route {
  id: string;
  method: string;
  path: string;
  /** Body limit in bytes; 0 = the request takes no body. */
  limit: number;
}

const r = (id: string, method: string, path: string, limit = method === "GET" || method === "DELETE" ? 0 : BODY_LIMIT): Route => ({
  id,
  method,
  path,
  limit,
});

export const ROUTES: Route[] = [
  r("E1", "POST", "/v1/groups", BODY_LIMIT_SMALL),
  r("E2", "GET", "/v1/groups/:gid"),
  r("E3", "PUT", "/v1/groups/:gid/profile"),
  r("E4", "DELETE", "/v1/groups/:gid"),
  r("E5", "PUT", "/v1/groups/:gid/pro"),
  r("E6", "POST", "/v1/groups/:gid/invites"),
  r("E7", "GET", "/v1/groups/:gid/invites"),
  r("E8", "DELETE", "/v1/groups/:gid/invites/:iid"),
  r("E9", "POST", "/v1/invites/preview", BODY_LIMIT_SMALL),
  r("E10", "POST", "/v1/invites/redeem", BODY_LIMIT_SMALL),
  r("E11", "GET", "/v1/groups/:gid/members"),
  r("E12", "PUT", "/v1/groups/:gid/members/me/profile"),
  r("E13", "PUT", "/v1/groups/:gid/members/:mid/role"),
  r("E14", "DELETE", "/v1/groups/:gid/members/:mid"),
  r("E15", "POST", "/v1/groups/:gid/transfer"),
  r("E16", "DELETE", "/v1/groups/:gid/transfer"),
  r("E17", "POST", "/v1/groups/:gid/transfer/accept"),
  r("E18", "POST", "/v1/groups/:gid/upload"),
  r("E19", "GET", "/v1/groups/:gid/changes"),
  r("E20", "GET", "/v1/groups/:gid/archive/:mid/:month"),
  r("E21", "GET", "/v1/groups/:gid/live"),
  r("E22", "DELETE", "/v1/groups/:gid/trail/me"),
  r("E23", "POST", "/v1/groups/:gid/pins"),
  r("E24", "DELETE", "/v1/groups/:gid/pins/:pid"),
  r("E25", "POST", "/v1/groups/:gid/reports", BODY_LIMIT_SMALL),
  r("E26", "GET", "/v1/groups/:gid/ws"),
  r("E27", "POST", "/v1/store/apple"),
  r("E28", "POST", "/v1/store/google"),
  r("E29", "GET", "/v1/health"),
  r("A1", "GET", "/v1/admin/reports"),
  r("A2", "POST", "/v1/admin/reports/:rid/resolve"),
  r("A3", "DELETE", "/v1/admin/groups/:gid/pins/:pid"),
  r("A4", "DELETE", "/v1/admin/groups/:gid/members/:mid"),
  r("A5", "DELETE", "/v1/admin/groups/:gid"),
  r("A6", "GET", "/v1/admin/groups/:gid"),
];

const SPLIT = ROUTES.map((route) => ({ route, parts: route.path.split("/").slice(1) }));

export type RouteMatch =
  | { type: "route"; route: Route; params: Record<string, string> }
  | { type: "method_not_allowed" }
  | { type: "none" };

export function matchRoute(method: string, pathname: string): RouteMatch {
  const parts = pathname.split("/").slice(1);
  let pathMatched = false;
  // A literal segment beats a parameter ("members/me/profile" before "members/:mid/…").
  for (const { route, parts: pattern } of SPLIT) {
    if (pattern.length !== parts.length) continue;
    const params: Record<string, string> = {};
    let ok = true;
    for (let i = 0; i < pattern.length; i++) {
      const p = pattern[i]!;
      const v = parts[i]!;
      if (p.startsWith(":")) {
        if (v === "") {
          ok = false;
          break;
        }
        params[p.slice(1)] = v; // ids are base64url and months digits: nothing to percent-decode
      } else if (p !== v) {
        ok = false;
        break;
      }
    }
    if (!ok) continue;
    pathMatched = true;
    if (route.method === method) return { type: "route", route, params };
  }
  return pathMatched ? { type: "method_not_allowed" } : { type: "none" };
}

/** The route's path with ids replaced by placeholders, for logs (SPEC §7.9). */
export function routeTemplate(route: Route): string {
  return `${route.method} ${route.path}`;
}

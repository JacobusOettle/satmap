// Error codes and JSON responses (SPEC §6.1, §6.2).

export const ERROR_STATUS = {
  bad_request: 400,
  bad_record: 400,
  unauthorized: 401,
  pro_invalid: 402,
  pro_inactive: 402,
  forbidden: 403,
  not_found: 404,
  invite_not_found: 404,
  group_not_found: 404,
  method_not_allowed: 405,
  group_exists: 409,
  member_exists: 409,
  pin_exists: 409,
  group_paused: 409,
  owner_must_hand_over: 409,
  not_allowed: 409,
  too_many_groups: 409,
  terms_outdated: 409,
  removed: 410,
  group_deleted: 410,
  pin_deleted: 410,
  too_large: 413,
  unsupported_media_type: 415,
  bad_time: 422,
  pin_expired: 422,
  upgrade_required: 426,
  rate_limited: 429,
  internal: 500,
  store_unavailable: 503,
} as const;

export type ErrorCode = keyof typeof ERROR_STATUS;

const DEFAULT_MESSAGES: Record<ErrorCode, string> = {
  bad_request: "The request is malformed.",
  bad_record: "An envelope has the wrong version, length or encoding.",
  unauthorized: "The pass matches no member.",
  pro_invalid: "The Pro proof does not verify.",
  pro_inactive: "The Pro entitlement is not active.",
  forbidden: "Your role may not do this.",
  not_found: "Not found.",
  invite_not_found: "The invite is unknown, used, cancelled or expired.",
  group_not_found: "No such group.",
  method_not_allowed: "Method not allowed.",
  group_exists: "The group id is taken.",
  member_exists: "The member id is taken.",
  pin_exists: "The pin id is taken.",
  group_paused: "The group is paused.",
  owner_must_hand_over: "The owner must hand over ownership or delete the group.",
  not_allowed: "Not allowed for the owner or for yourself.",
  too_many_groups: "This purchase already owns the maximum number of groups.",
  terms_outdated: "The accepted terms version is too old.",
  removed: "This pass was removed from the group.",
  group_deleted: "The group was deleted.",
  pin_deleted: "The pin was deleted.",
  too_large: "Too large.",
  unsupported_media_type: "The body must be JSON.",
  bad_time: "A time is out of range.",
  pin_expired: "The pin has already expired.",
  upgrade_required: "A WebSocket upgrade is required.",
  rate_limited: "Too many requests.",
  internal: "Internal error.",
  store_unavailable: "The store could not be asked.",
};

/** An error answer. Thrown inside handlers and turned into a response by the handler's caller. */
export class ApiError extends Error {
  readonly code: ErrorCode;
  readonly retryAfter: number | undefined;

  constructor(code: ErrorCode, message?: string, retryAfter?: number) {
    super(message ?? DEFAULT_MESSAGES[code]);
    this.name = "ApiError";
    this.code = code;
    this.retryAfter = retryAfter;
  }

  get status(): number {
    return ERROR_STATUS[this.code];
  }
}

export const API_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
} as const;

export function json(body: unknown, status = 200, extra?: Record<string, string>): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...API_HEADERS, ...extra } });
}

export function noContent(): Response {
  return new Response(null, { status: 204, headers: { "Cache-Control": "no-store" } });
}

export function errorResponse(code: ErrorCode, message?: string, retryAfter?: number): Response {
  const headers: Record<string, string> = {};
  if (retryAfter !== undefined) headers["Retry-After"] = String(Math.max(1, Math.ceil(retryAfter)));
  else if (code === "rate_limited" || code === "store_unavailable") headers["Retry-After"] = code === "rate_limited" ? "60" : "300";
  return json({ error: code, message: message ?? DEFAULT_MESSAGES[code] }, ERROR_STATUS[code], headers);
}

export function apiErrorResponse(err: ApiError): Response {
  return errorResponse(err.code, err.message, err.retryAfter);
}

export function isErrorCode(value: unknown): value is ErrorCode {
  return typeof value === "string" && Object.hasOwn(ERROR_STATUS, value);
}

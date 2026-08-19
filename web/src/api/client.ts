/** Typed client for the local Go API. It owns headers, JSON parsing, and error
 * normalization so views never touch fetch directly. */

/** Error codes the backend can return, plus the two the client itself raises. */
export type ApiErrorCode =
  | "unauthorized"
  | "forbidden_origin"
  | "not_found"
  | "method_not_allowed"
  | "bad_request"
  | "database_unavailable"
  | "query_failed"
  | "query_timeout"
  | "query_cancelled"
  | "internal_error"
  /** The request never reached the server: it is not running. */
  | "server_unreachable"
  /** The server answered with something that is not our error envelope. */
  | "malformed_response";

export class ApiError extends Error {
  readonly code: ApiErrorCode;
  readonly status: number;

  constructor(code: ApiErrorCode, message: string, status = 0) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
  }
}

export interface ResultColumn {
  name: string;
  typeOid?: number;
  typeName: string;
}

/** Values are pre-stringified server-side except booleans and nulls, so no
 * numeric or int8 value loses precision in JSON. */
export type ResultValue = string | boolean | null;

export interface QueryResult {
  columns: ResultColumn[];
  rows: ResultValue[][];
  rowCount: number;
  durationMs: number;
}

export interface Health {
  status: string;
  version: string;
  database: "configured" | "not_configured";
}

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export class ApiClient {
  private readonly token: string;
  private readonly fetchImpl: FetchLike;

  constructor(token: string, fetchImpl: FetchLike = (i, init) => fetch(i, init)) {
    this.token = token;
    this.fetchImpl = fetchImpl;
  }

  health(signal?: AbortSignal): Promise<Health> {
    return this.request<Health>("GET", "/api/health", signal);
  }

  /** M0 scaffolding: runs the fixed `SELECT 1` on the development database. */
  selectOne(signal?: AbortSignal): Promise<QueryResult> {
    return this.request<QueryResult>("POST", "/api/bootstrap/select-one", signal);
  }

  private async request<T>(method: string, path: string, signal?: AbortSignal): Promise<T> {
    let response: Response;
    try {
      response = await this.fetchImpl(path, {
        method,
        signal,
        headers: {
          Authorization: `Bearer ${this.token}`,
          Accept: "application/json",
        },
        // The token, not a cookie, is the credential. Never send ambient ones.
        credentials: "omit",
      });
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === "AbortError") throw cause;
      throw new ApiError(
        "server_unreachable",
        "The Database IDE server is not responding. Check that the process is still running.",
      );
    }

    const body: unknown = await response.json().catch(() => null);

    if (!response.ok) {
      throw toApiError(body, response.status);
    }
    if (body === null || typeof body !== "object") {
      throw new ApiError("malformed_response", "The server returned an unreadable response.", response.status);
    }
    return body as T;
  }
}

function toApiError(body: unknown, status: number): ApiError {
  const envelope = body as { error?: { code?: unknown; message?: unknown } } | null;
  const code = envelope?.error?.code;
  const message = envelope?.error?.message;
  if (typeof code === "string" && typeof message === "string") {
    return new ApiError(code as ApiErrorCode, message, status);
  }
  return new ApiError("malformed_response", `The server returned an unexpected ${status} response.`, status);
}

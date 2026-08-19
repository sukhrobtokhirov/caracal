/** Typed client for the local Go API. It owns headers, JSON parsing, and error
 * normalization so views never touch fetch directly. */

/** Error codes the backend can return, plus the two the client itself raises. */
export type ApiErrorCode =
  | "unauthorized"
  | "forbidden_origin"
  | "not_found"
  | "method_not_allowed"
  | "bad_request"
  | "internal_error"
  // Vault
  | "locked"
  | "already_set_up"
  | "not_set_up"
  | "wrong_master_password"
  | "weak_master_password"
  | "too_many_attempts"
  | "secret_unreadable"
  // Connections
  | "validation_failed"
  | "duplicate_name"
  | "connection_unavailable"
  | "wrong_engine"
  | "unsupported_configuration"
  // Engines
  | "database_unavailable"
  | "connect_timeout"
  | "authentication_failed"
  | "tls_verification_failed"
  | "query_failed"
  | "command_failed"
  | "query_timeout"
  | "query_cancelled"
  /** The request never reached the server: it is not running. */
  | "server_unreachable"
  /** The server answered with something that is not our error envelope. */
  | "malformed_response";

/** One field-level validation problem, used to mark and focus form inputs. */
export interface FieldError {
  field: string;
  message: string;
}

export class ApiError extends Error {
  readonly code: ApiErrorCode;
  readonly status: number;
  /** Per-field problems, present when code is "validation_failed". */
  readonly fields: FieldError[];
  /** Seconds to wait, present when code is "too_many_attempts". */
  readonly retryAfterSeconds?: number;

  constructor(
    code: ApiErrorCode,
    message: string,
    status = 0,
    details?: { fields?: FieldError[]; retryAfterSeconds?: number },
  ) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
    this.fields = details?.fields ?? [];
    this.retryAfterSeconds = details?.retryAfterSeconds;
  }
}

export interface Health {
  status: string;
  version: string;
}

export type AuthState = "setup_required" | "locked" | "unlocked";

export interface AuthStatus {
  state: AuthState;
  minPasswordLength: number;
}

export type Engine = "postgres" | "redis";
export type Environment = "dev" | "staging" | "prod";
export type TlsMode = "disable" | "require" | "verify-full";
export type RuntimeStatus = "closed" | "opening" | "open" | "error";

export interface RuntimeState {
  status: RuntimeStatus;
  lastError?: string;
  openedAt?: string;
}

/** A saved connection as the API returns it: never carries a password. */
export interface Connection {
  id: string;
  name: string;
  engine: Engine;
  host: string;
  port: number;
  database: string;
  username: string;
  tlsMode: TlsMode;
  environment: Environment;
  readOnly: boolean;
  color: string;
  createdAt: string;
  /** Whether a password is stored — never the password or its length. */
  hasSecret: boolean;
  runtime: RuntimeState;
}

/** The create/update request body.
 *
 * `secret` is omitted to leave a stored password untouched. Sending
 * `{changed: true, value: ""}` deliberately clears it. */
export interface ConnectionInput {
  name: string;
  engine: Engine;
  host: string;
  port: number;
  database: string;
  username: string;
  tlsMode: TlsMode;
  environment: Environment;
  readOnly: boolean;
  color: string;
  secret?: { changed: boolean; value: string };
}

export interface TestResult {
  ok: boolean;
  engine: string;
  serverVersion?: string;
  latencyMs: number;
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
    return this.request<Health>("GET", "/api/health", { signal });
  }

  authStatus(signal?: AbortSignal): Promise<AuthStatus> {
    return this.request<AuthStatus>("GET", "/api/auth/status", { signal });
  }

  setupMasterPassword(password: string): Promise<AuthStatus> {
    return this.request<AuthStatus>("POST", "/api/auth/setup", { body: { password } });
  }

  unlock(password: string): Promise<AuthStatus> {
    return this.request<AuthStatus>("POST", "/api/auth/unlock", { body: { password } });
  }

  lock(): Promise<AuthStatus> {
    return this.request<AuthStatus>("POST", "/api/auth/lock", {});
  }

  async listConnections(signal?: AbortSignal): Promise<Connection[]> {
    const body = await this.request<{ connections: Connection[] }>("GET", "/api/connections", { signal });
    return body.connections ?? [];
  }

  createConnection(input: ConnectionInput): Promise<Connection> {
    return this.request<Connection>("POST", "/api/connections", { body: input });
  }

  updateConnection(id: string, input: ConnectionInput): Promise<Connection> {
    return this.request<Connection>("PUT", `/api/connections/${encodeURIComponent(id)}`, { body: input });
  }

  async deleteConnection(id: string): Promise<void> {
    await this.request<null>("DELETE", `/api/connections/${encodeURIComponent(id)}`, {});
  }

  testConnection(id: string, signal?: AbortSignal): Promise<TestResult> {
    return this.request<TestResult>("POST", `/api/connections/${encodeURIComponent(id)}/test`, { signal });
  }

  openConnection(id: string, signal?: AbortSignal): Promise<Connection> {
    return this.request<Connection>("POST", `/api/connections/${encodeURIComponent(id)}/open`, { signal });
  }

  closeConnection(id: string): Promise<Connection> {
    return this.request<Connection>("POST", `/api/connections/${encodeURIComponent(id)}/close`, {});
  }

  private async request<T>(
    method: string,
    path: string,
    options: { body?: unknown; signal?: AbortSignal },
  ): Promise<T> {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.token}`,
      Accept: "application/json",
    };
    if (options.body !== undefined) headers["Content-Type"] = "application/json";

    let response: Response;
    try {
      response = await this.fetchImpl(path, {
        method,
        headers,
        signal: options.signal,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
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

    if (response.status === 204) return null as T;

    const body: unknown = await response.json().catch(() => null);

    if (!response.ok) throw toApiError(body, response.status);

    if (body === null || typeof body !== "object") {
      throw new ApiError("malformed_response", "The server returned an unreadable response.", response.status);
    }
    return body as T;
  }
}

function toApiError(body: unknown, status: number): ApiError {
  const envelope = body as
    | { error?: { code?: unknown; message?: unknown; details?: Record<string, unknown> } }
    | null;
  const code = envelope?.error?.code;
  const message = envelope?.error?.message;
  if (typeof code === "string" && typeof message === "string") {
    return new ApiError(code as ApiErrorCode, message, status, {
      fields: parseFields(envelope?.error?.details?.["fields"]),
      retryAfterSeconds: numberOrUndefined(envelope?.error?.details?.["retryAfterSeconds"]),
    });
  }
  return new ApiError("malformed_response", `The server returned an unexpected ${status} response.`, status);
}

function parseFields(raw: unknown): FieldError[] {
  if (!Array.isArray(raw)) return [];
  const out: FieldError[] = [];
  for (const entry of raw) {
    if (entry && typeof entry === "object") {
      const { field, message } = entry as { field?: unknown; message?: unknown };
      if (typeof field === "string" && typeof message === "string") out.push({ field, message });
    }
  }
  return out;
}

function numberOrUndefined(raw: unknown): number | undefined {
  return typeof raw === "number" ? raw : undefined;
}

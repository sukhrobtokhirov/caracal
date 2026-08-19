import type { AuthStatus, Connection, Health, TestResult } from "../api/client";

export const HEALTH: Health = { status: "ok", version: "test" };

export function authStatus(state: AuthStatus["state"]): AuthStatus {
  return { state, minPasswordLength: 8 };
}

export function connection(overrides: Partial<Connection> = {}): Connection {
  return {
    id: "conn-1",
    name: "prod db",
    engine: "postgres",
    host: "db.internal",
    port: 5432,
    database: "app",
    username: "app_ro",
    tlsMode: "require",
    environment: "prod",
    readOnly: true,
    color: "#ff5555",
    createdAt: "2026-08-20T12:00:00Z",
    hasSecret: true,
    runtime: { status: "closed" },
    ...overrides,
  };
}

export function testResult(overrides: Partial<TestResult> = {}): TestResult {
  return { ok: true, engine: "postgres", serverVersion: "16.3", latencyMs: 7, ...overrides };
}

export function json(status: number, body: unknown): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function apiError(status: number, code: string, message: string, details?: unknown): Response {
  return json(status, { error: { code, message, details } });
}

/** A route table keyed by "METHOD /path". Unmatched requests throw, so a test
 * that triggers an unexpected call fails loudly. */
export type Routes = Record<string, (body: unknown) => Response | Promise<Response>>;

export function routedFetch(routes: Routes) {
  return async (path: string, init?: RequestInit): Promise<Response> => {
    const key = `${init?.method ?? "GET"} ${path}`;
    const route = routes[key];
    if (!route) throw new Error(`unexpected request: ${key}`);
    const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    return route(body);
  };
}

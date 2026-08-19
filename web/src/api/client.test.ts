import { describe, expect, it, vi } from "vitest";
import { ApiClient, ApiError, type QueryResult } from "./client";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("ApiClient", () => {
  it("sends the bearer token and omits ambient credentials", async () => {
    const fetchImpl = vi.fn(async (_path: string, _init?: RequestInit) =>
      jsonResponse(200, { status: "ok", version: "dev", database: "configured" }),
    );
    await new ApiClient("tok", fetchImpl).health();

    expect(fetchImpl).toHaveBeenCalledOnce();
    const [path, init] = fetchImpl.mock.calls[0]!;
    expect(path).toBe("/api/health");
    expect((init!.headers as Record<string, string>).Authorization).toBe("Bearer tok");
    expect(init!.credentials).toBe("omit");
  });

  it("never puts the token in the URL after bootstrap", async () => {
    const fetchImpl = vi.fn(async (_path: string, _init?: RequestInit) =>
      jsonResponse(200, { status: "ok", version: "dev", database: "configured" }),
    );
    await new ApiClient("tok", fetchImpl).health();
    expect(fetchImpl.mock.calls[0]![0]).not.toContain("tok");
  });

  it("returns the parsed query result", async () => {
    const payload: QueryResult = {
      columns: [{ name: "value", typeOid: 23, typeName: "int4" }],
      rows: [["1"]],
      rowCount: 1,
      durationMs: 3,
    };
    const result = await new ApiClient("tok", async () => jsonResponse(200, payload)).selectOne();
    expect(result).toEqual(payload);
  });

  it.each([
    [401, "unauthorized", "A valid session token is required."],
    [403, "forbidden_origin", "This request must be made from the local application page."],
    [503, "database_unavailable", "No PostgreSQL connection is configured."],
    [422, "query_failed", "ERROR 42601: syntax error"],
  ])("maps a %i response to its error code", async (status, code, message) => {
    const client = new ApiClient("tok", async () => jsonResponse(status, { error: { code, message } }));
    const err = await client.selectOne().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe(code);
    expect((err as ApiError).message).toBe(message);
    expect((err as ApiError).status).toBe(status);
  });

  it("reports an unreachable server rather than a raw network error", async () => {
    const client = new ApiClient("tok", async () => {
      throw new TypeError("Failed to fetch");
    });
    const err = (await client.health().catch((e: unknown) => e)) as ApiError;
    expect(err.code).toBe("server_unreachable");
    expect(err.message).not.toContain("Failed to fetch");
  });

  it("reports a non-envelope error response as malformed", async () => {
    const client = new ApiClient("tok", async () => new Response("<html>oops</html>", { status: 500 }));
    const err = (await client.health().catch((e: unknown) => e)) as ApiError;
    expect(err.code).toBe("malformed_response");
  });

  it("propagates aborts unchanged", async () => {
    const client = new ApiClient("tok", async () => {
      throw new DOMException("aborted", "AbortError");
    });
    const err = (await client.health().catch((e: unknown) => e)) as DOMException;
    expect(err.name).toBe("AbortError");
  });
});

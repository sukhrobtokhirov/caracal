import { describe, expect, it, vi } from "vitest";
import { ApiClient, ApiError, type Connection } from "./client";
import { apiError, connection, json, routedFetch, HEALTH } from "../test/factories";

describe("ApiClient", () => {
  it("sends the bearer token and omits ambient credentials", async () => {
    const fetchImpl = vi.fn(async (_path: string, _init?: RequestInit) => json(200, HEALTH));
    await new ApiClient("tok", fetchImpl).health();

    expect(fetchImpl).toHaveBeenCalledOnce();
    const [path, init] = fetchImpl.mock.calls[0]!;
    expect(path).toBe("/api/health");
    expect((init!.headers as Record<string, string>).Authorization).toBe("Bearer tok");
    expect(init!.credentials).toBe("omit");
  });

  it("never puts the token in the URL after bootstrap", async () => {
    const fetchImpl = vi.fn(async (_path: string, _init?: RequestInit) => json(200, HEALTH));
    await new ApiClient("tok", fetchImpl).health();
    expect(fetchImpl.mock.calls[0]![0]).not.toContain("tok");
  });

  it("sends and parses the connection list", async () => {
    const client = new ApiClient(
      "tok",
      routedFetch({ "GET /api/connections": () => json(200, { connections: [connection()] }) }),
    );
    const list = await client.listConnections();
    expect(list).toHaveLength(1);
    expect(list[0]!.name).toBe("prod db");
    expect(list[0]!.hasSecret).toBe(true);
  });

  it("tolerates a list response without the connections key", async () => {
    const client = new ApiClient("tok", routedFetch({ "GET /api/connections": () => json(200, {}) }));
    await expect(client.listConnections()).resolves.toEqual([]);
  });

  it("serializes the create body verbatim", async () => {
    let received: unknown;
    const client = new ApiClient(
      "tok",
      routedFetch({
        "POST /api/connections": (body) => {
          received = body;
          return json(201, connection());
        },
      }),
    );
    const input = {
      name: "prod db",
      engine: "postgres" as const,
      host: "db.internal",
      port: 5432,
      database: "app",
      username: "app_ro",
      tlsMode: "require" as const,
      environment: "prod" as const,
      readOnly: true,
      color: "#ff5555",
      secret: { changed: true, value: "hunter2" },
    };
    await client.createConnection(input);
    expect(received).toEqual(input);
  });

  it("URL-encodes connection identifiers", async () => {
    const fetchImpl = vi.fn(async (_path: string, _init?: RequestInit) => json(200, connection()));
    await new ApiClient("tok", fetchImpl).openConnection("a b/c");
    expect(fetchImpl.mock.calls[0]![0]).toBe("/api/connections/a%20b%2Fc/open");
  });

  it("handles a 204 delete with no body", async () => {
    const client = new ApiClient(
      "tok",
      routedFetch({ "DELETE /api/connections/conn-1": () => json(204, null) }),
    );
    await expect(client.deleteConnection("conn-1")).resolves.toBeUndefined();
  });

  it.each([
    [401, "unauthorized", "A valid session token is required."],
    [403, "forbidden_origin", "This request must be made from the local application page."],
    [423, "locked", "The application is locked."],
    [409, "duplicate_name", "A connection with that name already exists."],
    [502, "authentication_failed", "The server rejected the username or password."],
  ])("maps a %i response to its error code", async (status, code, message) => {
    const client = new ApiClient("tok", async () => apiError(status, code, message));
    const err = await client.listConnections().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe(code);
    expect((err as ApiError).message).toBe(message);
    expect((err as ApiError).status).toBe(status);
  });

  it("exposes per-field validation errors", async () => {
    const client = new ApiClient("tok", async () =>
      apiError(422, "validation_failed", "Some fields need attention.", {
        fields: [
          { field: "host", message: "This is not a valid host name or IP address." },
          { field: "port", message: "A port must be between 1 and 65535." },
        ],
      }),
    );
    const err = (await client.listConnections().catch((e: unknown) => e)) as ApiError;
    expect(err.code).toBe("validation_failed");
    expect(err.fields.map((f) => f.field)).toEqual(["host", "port"]);
  });

  it("ignores malformed field entries", async () => {
    const client = new ApiClient("tok", async () =>
      apiError(422, "validation_failed", "…", { fields: ["nope", { field: 1 }, null] }),
    );
    const err = (await client.listConnections().catch((e: unknown) => e)) as ApiError;
    expect(err.fields).toEqual([]);
  });

  it("exposes the unlock cooldown", async () => {
    const client = new ApiClient("tok", async () =>
      apiError(429, "too_many_attempts", "Too many failed attempts.", { retryAfterSeconds: 30 }),
    );
    const err = (await client.unlock("pw").catch((e: unknown) => e)) as ApiError;
    expect(err.retryAfterSeconds).toBe(30);
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

  it("never exposes a password field on a returned connection", async () => {
    const client = new ApiClient(
      "tok",
      routedFetch({ "GET /api/connections": () => json(200, { connections: [connection()] }) }),
    );
    const [first] = await client.listConnections();
    expect(Object.keys(first as Connection)).not.toContain("password");
    expect(Object.keys(first as Connection)).not.toContain("secret");
  });
});

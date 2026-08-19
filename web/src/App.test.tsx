import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./App";
import { ApiClient, type Health, type QueryResult } from "./api/client";

const OK_RESULT: QueryResult = {
  columns: [{ name: "value", typeOid: 23, typeName: "int4" }],
  rows: [["1"]],
  rowCount: 1,
  durationMs: 3,
};

const HEALTHY: Health = { status: "ok", version: "dev", database: "configured" };

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** Routes each API path to a canned response. */
function clientFor(routes: Record<string, () => Promise<Response>>) {
  return new ApiClient("tok", async (path) => {
    const route = routes[path];
    if (!route) throw new Error(`unexpected request to ${path}`);
    return route();
  });
}

const healthy = async () => json(200, HEALTHY);

describe("App", () => {
  it("explains a missing session token instead of failing silently", () => {
    render(<App client={null} />);
    expect(screen.getByRole("alert")).toHaveTextContent("No session token");
    expect(screen.queryByRole("button", { name: /run select 1/i })).not.toBeInTheDocument();
  });

  it("warns when no development database is configured", async () => {
    const client = clientFor({
      "/api/health": async () => json(200, { ...HEALTHY, database: "not_configured" }),
    });
    render(<App client={client} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("No development database configured");
  });

  it("shows the running state and then the result", async () => {
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const client = clientFor({
      "/api/health": healthy,
      "/api/bootstrap/select-one": async () => {
        await gate;
        return json(200, OK_RESULT);
      },
    });

    render(<App client={client} />);
    const button = screen.getByRole("button", { name: /run select 1/i });
    await userEvent.click(button);

    expect(await screen.findByRole("status")).toHaveTextContent("Running the query…");
    expect(button).toBeDisabled();

    release!();

    expect(await screen.findByText("1")).toBeInTheDocument();
    expect(screen.getByText(/1 row in 3 ms/)).toBeInTheDocument();
    expect(screen.getByRole("columnheader")).toHaveTextContent("value");
    await waitFor(() => expect(button).toBeEnabled());
  });

  it("surfaces an authentication failure", async () => {
    const client = clientFor({
      "/api/health": healthy,
      "/api/bootstrap/select-one": async () =>
        json(401, { error: { code: "unauthorized", message: "A valid session token is required." } }),
    });
    render(<App client={client} />);
    await userEvent.click(screen.getByRole("button", { name: /run select 1/i }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Authentication failed");
    expect(alert).toHaveTextContent("Reopen the URL printed by the server");
  });

  it("surfaces a database failure", async () => {
    const client = clientFor({
      "/api/health": healthy,
      "/api/bootstrap/select-one": async () =>
        json(502, { error: { code: "database_unavailable", message: "PostgreSQL could not be reached." } }),
    });
    render(<App client={client} />);
    await userEvent.click(screen.getByRole("button", { name: /run select 1/i }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("PostgreSQL unavailable");
    expect(alert).toHaveTextContent("PostgreSQL could not be reached.");
  });

  it("surfaces a server that is not running", async () => {
    const client = new ApiClient("tok", async (path) => {
      if (path === "/api/health") return healthy();
      throw new TypeError("Failed to fetch");
    });
    render(<App client={client} />);
    await userEvent.click(screen.getByRole("button", { name: /run select 1/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Server unavailable");
  });

  it("does not run the query on load", async () => {
    const selectOne = vi.fn();
    const client = clientFor({ "/api/health": healthy, "/api/bootstrap/select-one": selectOne });
    render(<App client={client} />);
    await screen.findByText(/PostgreSQL configured/);
    expect(selectOne).not.toHaveBeenCalled();
    expect(screen.getByText("No query has been run yet.")).toBeInTheDocument();
  });
});

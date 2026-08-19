import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./App";
import { ApiClient } from "./api/client";
import { apiError, authStatus, connection, json, routedFetch, HEALTH, type Routes } from "./test/factories";

function renderApp(routes: Routes) {
  render(<App client={new ApiClient("tok", routedFetch(routes))} />);
}

const health = () => json(200, HEALTH);

describe("App", () => {
  it("explains a missing session token instead of failing silently", () => {
    render(<App client={null} />);
    expect(screen.getByRole("alert")).toHaveTextContent("No session token");
  });

  it("shows the setup screen on first run", async () => {
    renderApp({
      "GET /api/health": health,
      "GET /api/auth/status": () => json(200, authStatus("setup_required")),
    });
    expect(await screen.findByRole("heading", { name: /choose a master password/i })).toBeInTheDocument();
  });

  it("shows the unlock screen when a master password already exists", async () => {
    renderApp({
      "GET /api/health": health,
      "GET /api/auth/status": () => json(200, authStatus("locked")),
    });
    expect(await screen.findByRole("heading", { name: /^unlock$/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /new connection/i })).not.toBeInTheDocument();
  });

  it("shows the connection manager when already unlocked", async () => {
    renderApp({
      "GET /api/health": health,
      "GET /api/auth/status": () => json(200, authStatus("unlocked")),
      "GET /api/connections": () => json(200, { connections: [connection()] }),
    });
    expect(await screen.findByRole("button", { name: /prod db/ })).toBeInTheDocument();
  });

  it("moves from unlock to the connection manager after a successful unlock", async () => {
    let state: "locked" | "unlocked" = "locked";
    renderApp({
      "GET /api/health": health,
      "GET /api/auth/status": () => json(200, authStatus(state)),
      "POST /api/auth/unlock": () => {
        state = "unlocked";
        return json(200, authStatus("unlocked"));
      },
      "GET /api/connections": () => json(200, { connections: [connection()] }),
    });

    await userEvent.type(await screen.findByLabelText("Master password"), "correct horse battery");
    await userEvent.click(screen.getByRole("button", { name: /^unlock$/i }));

    expect(await screen.findByRole("button", { name: /prod db/ })).toBeInTheDocument();
  });

  it("returns to the unlock screen when the manager reports the vault locked", async () => {
    renderApp({
      "GET /api/health": health,
      "GET /api/auth/status": () => json(200, authStatus("unlocked")),
      "GET /api/connections": () => apiError(423, "locked", "The application is locked."),
    });
    expect(await screen.findByRole("heading", { name: /^unlock$/i })).toBeInTheDocument();
  });

  it("surfaces an authentication failure", async () => {
    renderApp({
      "GET /api/health": health,
      "GET /api/auth/status": () =>
        apiError(401, "unauthorized", "A valid session token is required."),
    });
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Authentication failed");
    expect(alert).toHaveTextContent("Reopen the URL printed by the server");
  });

  it("surfaces a server that is not running", async () => {
    render(
      <App
        client={
          new ApiClient("tok", async () => {
            throw new TypeError("Failed to fetch");
          })
        }
      />,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("Server unavailable");
  });

  it("shows the server version once known", async () => {
    renderApp({
      "GET /api/health": () => json(200, { status: "ok", version: "1.2.3" }),
      "GET /api/auth/status": () => json(200, authStatus("locked")),
    });
    await waitFor(() => expect(screen.getByText("1.2.3")).toBeInTheDocument());
  });

  it("does not load connections before unlocking", async () => {
    const connections = vi.fn();
    renderApp({
      "GET /api/health": health,
      "GET /api/auth/status": () => json(200, authStatus("locked")),
      "GET /api/connections": connections,
    });
    await screen.findByRole("heading", { name: /^unlock$/i });
    expect(connections).not.toHaveBeenCalled();
  });
});

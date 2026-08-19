import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ApiClient } from "../api/client";
import { ConnectionsScreen } from "./ConnectionsScreen";
import { apiError, connection, json, routedFetch, testResult, type Routes } from "../test/factories";

function renderScreen(routes: Routes, onLocked = vi.fn()) {
  const client = new ApiClient("tok", routedFetch(routes));
  render(<ConnectionsScreen client={client} onLocked={onLocked} />);
  return onLocked;
}

const listOf = (...items: ReturnType<typeof connection>[]) => () =>
  json(200, { connections: items });

describe("ConnectionsScreen", () => {
  it("lists saved connections", async () => {
    renderScreen({ "GET /api/connections": listOf(connection()) });
    expect(await screen.findByRole("button", { name: /prod db/ })).toBeInTheDocument();
  });

  it("shows an empty state when nothing is saved", async () => {
    renderScreen({ "GET /api/connections": listOf() });
    expect(await screen.findByText("No connections yet.")).toBeInTheDocument();
  });

  it("keeps the selected connection's environment visible in the shell", async () => {
    renderScreen({ "GET /api/connections": listOf(connection()) });
    await userEvent.click(await screen.findByRole("button", { name: /prod db/ }));

    const shell = screen.getByRole("status", { name: "Selected connection" });
    expect(within(shell).getByText("prod db")).toBeInTheDocument();
    expect(within(shell).getByText(/PROD/)).toBeInTheDocument();
    expect(within(shell).getByText("READ ONLY")).toBeInTheDocument();
  });

  it("never displays a saved password in the detail view", async () => {
    renderScreen({ "GET /api/connections": listOf(connection()) });
    await userEvent.click(await screen.findByRole("button", { name: /prod db/ }));

    expect(screen.getByText("Saved (encrypted)")).toBeInTheDocument();
    expect(document.body.textContent).not.toContain("hunter2");
  });

  it("creates a connection and reloads the list", async () => {
    let created: unknown;
    let listed = [connection({ id: "existing", name: "existing one", environment: "dev" })];
    renderScreen({
      "GET /api/connections": () => json(200, { connections: listed }),
      "POST /api/connections": (body) => {
        created = body;
        listed = [...listed, connection({ id: "new", name: "new db", environment: "dev" })];
        return json(201, connection({ id: "new", name: "new db" }));
      },
    });

    await userEvent.click(await screen.findByRole("button", { name: /new connection/i }));
    await userEvent.type(screen.getByLabelText("Name"), "new db");
    await userEvent.click(screen.getByRole("button", { name: /create connection/i }));

    await waitFor(() => expect(created).toMatchObject({ name: "new db" }));
    expect(await screen.findByRole("button", { name: /new db/ })).toBeInTheDocument();
  });

  it("shows per-field errors from a rejected save", async () => {
    renderScreen({
      "GET /api/connections": listOf(),
      "POST /api/connections": () =>
        apiError(422, "validation_failed", "Some fields need attention.", {
          fields: [{ field: "host", message: "This is not a valid host name or IP address." }],
        }),
    });

    await userEvent.click(await screen.findByRole("button", { name: /new connection/i }));
    await userEvent.type(screen.getByLabelText("Name"), "bad");
    await userEvent.click(screen.getByRole("button", { name: /create connection/i }));

    expect(
      await screen.findByText("This is not a valid host name or IP address."),
    ).toBeInTheDocument();
  });

  it("reports a successful test with the server version", async () => {
    renderScreen({
      "GET /api/connections": listOf(connection()),
      "POST /api/connections/conn-1/test": () => json(200, testResult()),
    });
    await userEvent.click(await screen.findByRole("button", { name: /prod db/ }));
    await userEvent.click(screen.getByRole("button", { name: /^test$/i }));

    const banner = await screen.findByText(/connection succeeded/i);
    expect(banner.parentElement).toHaveTextContent("16.3");
    expect(banner.parentElement).toHaveTextContent("7 ms");
  });

  it("reports a failed test without leaking driver detail", async () => {
    renderScreen({
      "GET /api/connections": listOf(connection()),
      "POST /api/connections/conn-1/test": () =>
        apiError(502, "authentication_failed", "The server rejected the username or password."),
    });
    await userEvent.click(await screen.findByRole("button", { name: /prod db/ }));
    await userEvent.click(screen.getByRole("button", { name: /^test$/i }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Authentication failed");
    expect(alert).toHaveTextContent("The server rejected the username or password.");
  });

  it("opens and then closes a connection", async () => {
    let current = connection();
    renderScreen({
      "GET /api/connections": () => json(200, { connections: [current] }),
      "POST /api/connections/conn-1/open": () => {
        current = connection({ runtime: { status: "open" } });
        return json(200, current);
      },
      "POST /api/connections/conn-1/close": () => {
        current = connection({ runtime: { status: "closed" } });
        return json(200, current);
      },
    });

    await userEvent.click(await screen.findByRole("button", { name: /prod db/ }));
    await userEvent.click(screen.getByRole("button", { name: /^open$/i }));
    expect(await screen.findByRole("button", { name: /^close$/i })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /^close$/i }));
    expect(await screen.findByRole("button", { name: /^open$/i })).toBeInTheDocument();
  });

  it("surfaces a failed open in the connection's status", async () => {
    let current = connection();
    renderScreen({
      "GET /api/connections": () => json(200, { connections: [current] }),
      "POST /api/connections/conn-1/open": () => {
        current = connection({
          runtime: { status: "error", lastError: "Could not reach the host before the timeout." },
        });
        return apiError(502, "connect_timeout", "Could not reach the host before the timeout.");
      },
    });

    await userEvent.click(await screen.findByRole("button", { name: /prod db/ }));
    await userEvent.click(screen.getByRole("button", { name: /^open$/i }));

    expect(await screen.findByText("Last attempt failed")).toBeInTheDocument();
  });

  it("requires a deliberate confirmation before deleting", async () => {
    let deleted = false;
    renderScreen({
      "GET /api/connections": () => json(200, { connections: deleted ? [] : [connection()] }),
      "DELETE /api/connections/conn-1": () => {
        deleted = true;
        return json(204, null);
      },
    });

    await userEvent.click(await screen.findByRole("button", { name: /prod db/ }));
    await userEvent.click(screen.getByRole("button", { name: /^delete$/i }));

    // The first click only asks; nothing has been deleted yet.
    expect(deleted).toBe(false);
    const dialog = screen.getByRole("alertdialog");
    expect(dialog).toHaveTextContent("cannot be undone");

    await userEvent.click(within(dialog).getByRole("button", { name: /yes, delete it/i }));
    await waitFor(() => expect(deleted).toBe(true));
    expect(await screen.findByText("No connections yet.")).toBeInTheDocument();
  });

  it("can back out of a deletion", async () => {
    let deleted = false;
    renderScreen({
      "GET /api/connections": listOf(connection()),
      "DELETE /api/connections/conn-1": () => {
        deleted = true;
        return json(204, null);
      },
    });

    await userEvent.click(await screen.findByRole("button", { name: /prod db/ }));
    await userEvent.click(screen.getByRole("button", { name: /^delete$/i }));
    await userEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: /cancel/i }));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(deleted).toBe(false);
  });

  it("edits without touching the stored password", async () => {
    let sent: unknown;
    renderScreen({
      "GET /api/connections": listOf(connection()),
      "PUT /api/connections/conn-1": (body) => {
        sent = body;
        return json(200, connection({ color: "#00ff00" }));
      },
    });

    await userEvent.click(await screen.findByRole("button", { name: /prod db/ }));
    await userEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    const color = screen.getByLabelText("Color (optional)");
    await userEvent.clear(color);
    await userEvent.type(color, "#00ff00");
    await userEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(sent).toBeDefined());
    expect(sent).toMatchObject({ color: "#00ff00" });
    expect((sent as { secret?: unknown }).secret).toBeUndefined();
  });

  it("returns to the locked state when the server reports it", async () => {
    const onLocked = renderScreen({
      "GET /api/connections": () => apiError(423, "locked", "The application is locked."),
    });
    await waitFor(() => expect(onLocked).toHaveBeenCalled());
  });

  it("locks on request", async () => {
    const onLocked = renderScreen({
      "GET /api/connections": listOf(connection()),
      "POST /api/auth/lock": () => json(200, { state: "locked", minPasswordLength: 8 }),
    });
    await userEvent.click(await screen.findByRole("button", { name: /^lock$/i }));
    await waitFor(() => expect(onLocked).toHaveBeenCalled());
  });
});

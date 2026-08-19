import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ApiClient } from "../api/client";
import { VaultScreen } from "./VaultScreen";
import { apiError, authStatus, json, routedFetch } from "../test/factories";

const MASTER = "correct horse battery";

describe("VaultScreen — first run", () => {
  it("sets the master password when both entries match", async () => {
    let sent: unknown;
    const client = new ApiClient(
      "tok",
      routedFetch({
        "POST /api/auth/setup": (body) => {
          sent = body;
          return json(200, authStatus("unlocked"));
        },
      }),
    );
    const onUnlocked = vi.fn();
    render(<VaultScreen client={client} status={authStatus("setup_required")} onUnlocked={onUnlocked} />);

    await userEvent.type(screen.getByLabelText("Master password"), MASTER);
    await userEvent.type(screen.getByLabelText("Confirm master password"), MASTER);
    await userEvent.click(screen.getByRole("button", { name: /set master password/i }));

    expect(sent).toEqual({ password: MASTER });
    expect(onUnlocked).toHaveBeenCalledOnce();
  });

  it("refuses to submit when the confirmation does not match", async () => {
    const client = new ApiClient("tok", routedFetch({}));
    render(<VaultScreen client={client} status={authStatus("setup_required")} onUnlocked={vi.fn()} />);

    await userEvent.type(screen.getByLabelText("Master password"), MASTER);
    await userEvent.type(screen.getByLabelText("Confirm master password"), "something else");
    await userEvent.click(screen.getByRole("button", { name: /set master password/i }));

    // routedFetch({}) throws on any request, so reaching the server would fail
    // the test; the visible error proves it stopped client-side.
    expect(await screen.findByRole("alert")).toHaveTextContent("Passwords do not match");
  });

  it("states the minimum length and warns that the password cannot be recovered", () => {
    const client = new ApiClient("tok", routedFetch({}));
    render(<VaultScreen client={client} status={authStatus("setup_required")} onUnlocked={vi.fn()} />);

    expect(screen.getByText(/at least 8 characters/i)).toBeInTheDocument();
    expect(screen.getByText(/cannot be recovered/i)).toBeInTheDocument();
  });

  it("surfaces a rejected weak password", async () => {
    const client = new ApiClient(
      "tok",
      routedFetch({
        "POST /api/auth/setup": () =>
          apiError(422, "weak_master_password", "The master password must be at least 8 characters."),
      }),
    );
    render(<VaultScreen client={client} status={authStatus("setup_required")} onUnlocked={vi.fn()} />);

    await userEvent.type(screen.getByLabelText("Master password"), "shortpw1");
    await userEvent.type(screen.getByLabelText("Confirm master password"), "shortpw1");
    await userEvent.click(screen.getByRole("button", { name: /set master password/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Password too short");
  });
});

describe("VaultScreen — unlock", () => {
  function renderUnlock(routes: Parameters<typeof routedFetch>[0], onUnlocked = vi.fn()) {
    const client = new ApiClient("tok", routedFetch(routes));
    render(<VaultScreen client={client} status={authStatus("locked")} onUnlocked={onUnlocked} />);
    return onUnlocked;
  }

  it("asks only for the password, not a confirmation", () => {
    renderUnlock({});
    expect(screen.getByLabelText("Master password")).toBeInTheDocument();
    expect(screen.queryByLabelText("Confirm master password")).not.toBeInTheDocument();
  });

  it("unlocks with the correct password", async () => {
    const onUnlocked = renderUnlock({
      "POST /api/auth/unlock": () => json(200, authStatus("unlocked")),
    });
    await userEvent.type(screen.getByLabelText("Master password"), MASTER);
    await userEvent.click(screen.getByRole("button", { name: /^unlock$/i }));
    expect(onUnlocked).toHaveBeenCalledOnce();
  });

  it("reports a wrong password generically and clears the field", async () => {
    const onUnlocked = renderUnlock({
      "POST /api/auth/unlock": () =>
        apiError(401, "wrong_master_password", "The master password is incorrect."),
    });
    const field = screen.getByLabelText("Master password");
    await userEvent.type(field, "not it at all");
    await userEvent.click(screen.getByRole("button", { name: /^unlock$/i }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Incorrect master password");
    expect(alert).toHaveTextContent("The master password is incorrect.");
    expect(field).toHaveValue("");
    expect(onUnlocked).not.toHaveBeenCalled();
  });

  it("shows the cooldown after too many attempts", async () => {
    renderUnlock({
      "POST /api/auth/unlock": () =>
        apiError(429, "too_many_attempts", "Too many failed attempts.", { retryAfterSeconds: 30 }),
    });
    await userEvent.type(screen.getByLabelText("Master password"), "wrong guess");
    await userEvent.click(screen.getByRole("button", { name: /^unlock$/i }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Too many attempts");
    expect(alert).toHaveTextContent("30 seconds");
  });

  it("does not submit an empty password", () => {
    renderUnlock({});
    expect(screen.getByRole("button", { name: /^unlock$/i })).toBeDisabled();
  });

  it("prevents double submission while a request is in flight", async () => {
    let calls = 0;
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const client = new ApiClient(
      "tok",
      routedFetch({
        "POST /api/auth/unlock": async () => {
          calls += 1;
          await gate;
          return json(200, authStatus("unlocked"));
        },
      }),
    );
    render(<VaultScreen client={client} status={authStatus("locked")} onUnlocked={vi.fn()} />);

    await userEvent.type(screen.getByLabelText("Master password"), MASTER);
    const button = screen.getByRole("button", { name: /^unlock$/i });
    await userEvent.click(button);
    expect(button).toBeDisabled();

    release!();
    expect(calls).toBe(1);
  });
});

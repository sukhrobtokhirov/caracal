import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ConnectionForm } from "./ConnectionForm";
import { connection } from "../test/factories";
import type { ConnectionInput } from "../api/client";

function renderForm(overrides: Partial<Parameters<typeof ConnectionForm>[0]> = {}) {
  const onSubmit = vi.fn();
  const onTest = vi.fn();
  const onCancel = vi.fn();
  render(
    <ConnectionForm
      connection={null}
      busy={false}
      testing={false}
      fieldErrors={[]}
      error={null}
      onSubmit={onSubmit}
      onTest={onTest}
      onCancel={onCancel}
      {...overrides}
    />,
  );
  return { onSubmit, onTest, onCancel };
}

function submitted(onSubmit: ReturnType<typeof vi.fn>): ConnectionInput {
  expect(onSubmit).toHaveBeenCalledOnce();
  return onSubmit.mock.calls[0]![0] as ConnectionInput;
}

describe("ConnectionForm — creating", () => {
  it("starts with PostgreSQL defaults", () => {
    renderForm();
    expect(screen.getByLabelText("Engine")).toHaveValue("postgres");
    expect(screen.getByLabelText("Port")).toHaveValue("5432");
    expect(screen.getByLabelText("Environment")).toHaveValue("dev");
    expect(screen.getByLabelText("TLS")).toHaveValue("disable");
  });

  it("moves the port and database to the Redis defaults when the engine changes", async () => {
    renderForm();
    await userEvent.selectOptions(screen.getByLabelText("Engine"), "redis");

    expect(screen.getByLabelText("Port")).toHaveValue("6379");
    expect(screen.getByLabelText("Database index")).toHaveValue("0");
  });

  it("keeps a port the user chose when the engine changes", async () => {
    renderForm();
    const port = screen.getByLabelText("Port");
    await userEvent.clear(port);
    await userEvent.type(port, "15432");

    await userEvent.selectOptions(screen.getByLabelText("Engine"), "redis");
    expect(port).toHaveValue("15432");
  });

  it("offers verify-full for PostgreSQL but not for Redis", async () => {
    renderForm();
    const tls = screen.getByLabelText("TLS");
    expect(Array.from(tls.querySelectorAll("option")).map((o) => o.value)).toEqual([
      "disable",
      "require",
      "verify-full",
    ]);

    await userEvent.selectOptions(screen.getByLabelText("Engine"), "redis");
    expect(Array.from(tls.querySelectorAll("option")).map((o) => o.value)).toEqual([
      "disable",
      "require",
    ]);
  });

  it("submits the entered values with an explicit secret change", async () => {
    const { onSubmit } = renderForm();
    await userEvent.type(screen.getByLabelText("Name"), "prod db");
    await userEvent.clear(screen.getByLabelText("Host"));
    await userEvent.type(screen.getByLabelText("Host"), "db.internal");
    await userEvent.type(screen.getByLabelText("Username"), "app_ro");
    await userEvent.type(screen.getByLabelText("Password"), "hunter2");
    await userEvent.selectOptions(screen.getByLabelText("Environment"), "prod");
    await userEvent.click(screen.getByLabelText("Read only"));
    await userEvent.click(screen.getByRole("button", { name: /create connection/i }));

    const input = submitted(onSubmit);
    expect(input).toMatchObject({
      name: "prod db",
      engine: "postgres",
      host: "db.internal",
      port: 5432,
      username: "app_ro",
      environment: "prod",
      readOnly: true,
      secret: { changed: true, value: "hunter2" },
    });
  });

  it("warns as soon as production is selected", async () => {
    renderForm();
    expect(screen.queryByText(/production connection/i)).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Environment"), "prod");
    expect(screen.getByText(/production connection/i)).toBeInTheDocument();
  });

  it("does not offer a test button before the connection exists", () => {
    renderForm();
    expect(screen.queryByRole("button", { name: /test connection/i })).not.toBeInTheDocument();
  });
});

describe("ConnectionForm — editing", () => {
  it("never prefills the saved password and leaves it unchanged by default", async () => {
    const { onSubmit } = renderForm({ connection: connection({ hasSecret: true }) });

    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
    expect(screen.getByText("Leave saved password unchanged")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /save changes/i }));
    // No `secret` key at all is what tells the server to keep the stored one.
    expect(submitted(onSubmit).secret).toBeUndefined();
  });

  it("replaces the password only when the user asks to", async () => {
    const { onSubmit } = renderForm({ connection: connection({ hasSecret: true }) });

    await userEvent.click(screen.getByRole("button", { name: /replace password/i }));
    const field = screen.getByLabelText("Password");
    expect(field).toHaveValue("");
    await userEvent.type(field, "new-password");
    await userEvent.click(screen.getByRole("button", { name: /save changes/i }));

    expect(submitted(onSubmit).secret).toEqual({ changed: true, value: "new-password" });
  });

  it("can go back to keeping the saved password", async () => {
    const { onSubmit } = renderForm({ connection: connection({ hasSecret: true }) });

    await userEvent.click(screen.getByRole("button", { name: /replace password/i }));
    await userEvent.type(screen.getByLabelText("Password"), "typed then abandoned");
    await userEvent.click(screen.getByRole("button", { name: /keep the saved password/i }));
    await userEvent.click(screen.getByRole("button", { name: /save changes/i }));

    expect(submitted(onSubmit).secret).toBeUndefined();
  });

  it("shows a password field directly when nothing is stored", () => {
    renderForm({ connection: connection({ hasSecret: false }) });
    expect(screen.getByLabelText("Password")).toBeInTheDocument();
    expect(screen.queryByText("Leave saved password unchanged")).not.toBeInTheDocument();
  });

  it("prefills the connection's non-secret values", () => {
    renderForm({ connection: connection() });
    expect(screen.getByLabelText("Name")).toHaveValue("prod db");
    expect(screen.getByLabelText("Host")).toHaveValue("db.internal");
    expect(screen.getByLabelText("Port")).toHaveValue("5432");
    expect(screen.getByLabelText("Database")).toHaveValue("app");
    expect(screen.getByLabelText("Environment")).toHaveValue("prod");
    expect(screen.getByLabelText("Read only")).toBeChecked();
  });
});

describe("ConnectionForm — errors and progress", () => {
  it("marks and focuses the first field the server rejected", async () => {
    renderForm({
      connection: connection(),
      fieldErrors: [
        { field: "host", message: "This is not a valid host name or IP address." },
        { field: "port", message: "A port must be between 1 and 65535." },
      ],
    });

    expect(screen.getByText("This is not a valid host name or IP address.")).toBeInTheDocument();
    expect(screen.getByText("A port must be between 1 and 65535.")).toBeInTheDocument();
    expect(screen.getByLabelText("Host")).toHaveFocus();
  });

  it("blocks submission and shows progress while saving", () => {
    renderForm({ busy: true });
    const save = screen.getByRole("button", { name: /saving/i });
    expect(save).toBeDisabled();
    expect(screen.getByLabelText("Name")).toBeDisabled();
  });

  it("shows progress while testing", () => {
    renderForm({ connection: connection(), testing: true });
    expect(screen.getByRole("button", { name: /testing/i })).toBeDisabled();
  });

  it("keeps typed values when a test fails", async () => {
    const { onTest } = renderForm({ connection: connection() });
    await userEvent.clear(screen.getByLabelText("Host"));
    await userEvent.type(screen.getByLabelText("Host"), "new.host");
    await userEvent.click(screen.getByRole("button", { name: /test connection/i }));

    expect(onTest).toHaveBeenCalledOnce();
    expect(screen.getByLabelText("Host")).toHaveValue("new.host");
  });
});

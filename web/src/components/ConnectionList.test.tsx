import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ConnectionList } from "./ConnectionList";
import { connection } from "../test/factories";

describe("ConnectionList", () => {
  it("explains an empty state instead of showing a blank panel", () => {
    render(<ConnectionList connections={[]} selectedId={null} onSelect={vi.fn()} />);
    expect(screen.getByText("No connections yet.")).toBeInTheDocument();
  });

  it("groups by environment with production first", () => {
    render(
      <ConnectionList
        connections={[
          connection({ id: "d", name: "dev cache", environment: "dev", engine: "redis" }),
          connection({ id: "s", name: "staging db", environment: "staging" }),
          connection({ id: "p", name: "prod db", environment: "prod" }),
        ]}
        selectedId={null}
        onSelect={vi.fn()}
      />,
    );
    const headings = screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent);
    expect(headings).toEqual(["Production", "Staging", "Development"]);
  });

  it("marks production with the word PROD, not colour alone", () => {
    render(
      <ConnectionList
        connections={[connection({ environment: "prod" })]}
        selectedId={null}
        onSelect={vi.fn()}
      />,
    );
    const row = screen.getByRole("button", { name: /prod db/ });
    expect(within(row).getByText(/PROD/)).toBeInTheDocument();
  });

  it("shows READ ONLY only for read-only connections", () => {
    render(
      <ConnectionList
        connections={[
          connection({ id: "a", name: "read only one", readOnly: true }),
          connection({ id: "b", name: "writable one", readOnly: false, environment: "dev" }),
        ]}
        selectedId={null}
        onSelect={vi.fn()}
      />,
    );
    expect(
      within(screen.getByRole("button", { name: /read only one/ })).getByText("READ ONLY"),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole("button", { name: /writable one/ })).queryByText("READ ONLY"),
    ).not.toBeInTheDocument();
  });

  it("shows each connection's target and live status", () => {
    render(
      <ConnectionList
        connections={[connection({ runtime: { status: "open" } })]}
        selectedId={null}
        onSelect={vi.fn()}
      />,
    );
    const row = screen.getByRole("button", { name: /prod db/ });
    expect(within(row).getByText("db.internal:5432 / app")).toBeInTheDocument();
    expect(within(row).getByText("Open")).toBeInTheDocument();
  });

  it("reports which row is current and reports selection", async () => {
    const onSelect = vi.fn();
    render(
      <ConnectionList
        connections={[connection({ id: "conn-1" })]}
        selectedId="conn-1"
        onSelect={onSelect}
      />,
    );
    const row = screen.getByRole("button", { name: /prod db/ });
    expect(row).toHaveAttribute("aria-current", "true");

    await userEvent.click(row);
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: "conn-1" }));
  });

  it("labels the engine for assistive technology", () => {
    render(
      <ConnectionList
        connections={[connection({ engine: "redis", environment: "dev" })]}
        selectedId={null}
        onSelect={vi.fn()}
      />,
    );
    expect(screen.getByRole("img", { name: "Redis" })).toBeInTheDocument();
  });
});

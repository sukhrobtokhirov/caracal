import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { ResultTable } from "./ResultTable";

describe("ResultTable", () => {
  it("renders NULL distinctly from an empty string", () => {
    render(
      <ResultTable
        result={{
          columns: [
            { name: "a", typeName: "text" },
            { name: "b", typeName: "text" },
          ],
          rows: [[null, ""]],
          rowCount: 1,
          durationMs: 1,
        }}
      />,
    );
    const nullCell = screen.getByText("NULL");
    expect(nullCell).toHaveClass("null");
    // The empty string renders as its own marked cell, not as the text "NULL".
    expect(screen.getAllByRole("cell")[1]!.querySelector(".empty")).not.toBeNull();
  });

  it("renders a bigint beyond float64 precision exactly", () => {
    render(
      <ResultTable
        result={{
          columns: [{ name: "big", typeName: "int8" }],
          rows: [["9223372036854775807"]],
          rowCount: 1,
          durationMs: 1,
        }}
      />,
    );
    expect(screen.getByText("9223372036854775807")).toBeInTheDocument();
  });

  it("shows the column type alongside the name", () => {
    render(
      <ResultTable
        result={{ columns: [{ name: "value", typeName: "int4" }], rows: [], rowCount: 0, durationMs: 0 }}
      />,
    );
    const header = screen.getByRole("columnheader");
    expect(header).toHaveTextContent("value");
    expect(header).toHaveTextContent("int4");
  });
});

import type { QueryResult, ResultValue } from "../api/client";

/** Renders one result set. NULL is visually distinct from an empty string —
 * confusing the two is the fastest way to lose a user's trust in a grid. */
export function ResultTable({ result }: { result: QueryResult }) {
  if (result.columns.length === 0) {
    return <p className="muted">The statement returned no columns.</p>;
  }
  return (
    <table className="result">
      <thead>
        <tr>
          {result.columns.map((col) => (
            <th key={col.name} scope="col">
              <span className="col-name">{col.name}</span>
              <span className="col-type">{col.typeName}</span>
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {result.rows.map((row, rowIndex) => (
          <tr key={rowIndex}>
            {result.columns.map((col, colIndex) => (
              <td key={col.name}>
                <Cell value={row[colIndex] ?? null} />
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Cell({ value }: { value: ResultValue }) {
  if (value === null) return <span className="null">NULL</span>;
  if (typeof value === "boolean") return <span className="bool">{value ? "true" : "false"}</span>;
  if (value === "") return <span className="empty" title="empty string" />;
  return <>{value}</>;
}

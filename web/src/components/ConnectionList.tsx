import type { Connection, Environment } from "../api/client";
import { EngineIcon, EnvironmentBadge, ReadOnlyBadge, StatusBadge } from "./badges";

const GROUP_ORDER: Environment[] = ["prod", "staging", "dev"];
const GROUP_TITLE: Record<Environment, string> = {
  prod: "Production",
  staging: "Staging",
  dev: "Development",
};

/** The saved connections, grouped by environment with production first so the
 * dangerous ones are never scrolled out of sight. */
export function ConnectionList({
  connections,
  selectedId,
  onSelect,
}: {
  connections: Connection[];
  selectedId: string | null;
  onSelect: (connection: Connection) => void;
}) {
  if (connections.length === 0) {
    return (
      <div className="empty-state">
        <p>No connections yet.</p>
        <p className="muted">Add your first PostgreSQL or Redis server to get started.</p>
      </div>
    );
  }

  return (
    <div className="connection-list">
      {GROUP_ORDER.map((environment) => {
        const group = connections.filter((c) => c.environment === environment);
        if (group.length === 0) return null;
        return (
          <section key={environment} className={`group group-${environment}`}>
            <h3>{GROUP_TITLE[environment]}</h3>
            <ul>
              {group.map((connection) => (
                <li key={connection.id}>
                  <button
                    type="button"
                    className={`connection-row${connection.id === selectedId ? " selected" : ""}${
                      connection.environment === "prod" ? " prod" : ""
                    }`}
                    onClick={() => onSelect(connection)}
                    aria-current={connection.id === selectedId}
                  >
                    <span
                      className="swatch"
                      style={connection.color ? { background: connection.color } : undefined}
                      aria-hidden="true"
                    />
                    <EngineIcon engine={connection.engine} />
                    <span className="details">
                      <span className="name">{connection.name}</span>
                      <span className="target">
                        {connection.host}:{connection.port}
                        {connection.database ? ` / ${connection.database}` : ""}
                      </span>
                    </span>
                    <span className="markers">
                      <EnvironmentBadge environment={connection.environment} />
                      {connection.readOnly ? <ReadOnlyBadge /> : null}
                      <StatusBadge status={connection.runtime.status} />
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

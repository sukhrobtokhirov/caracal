import type { Connection, Environment, RuntimeStatus } from "../api/client";

const ENVIRONMENT_LABEL: Record<Environment, string> = {
  dev: "DEV",
  staging: "STAGING",
  prod: "PROD",
};

/** Environment marker.
 *
 * Production is never signalled by color alone: it carries the word PROD and a
 * warning glyph, so it survives a colorblind user, a monochrome screenshot, and
 * a glance at the wrong tab. */
export function EnvironmentBadge({ environment }: { environment: Environment }) {
  return (
    <span className={`badge env-${environment}`}>
      {environment === "prod" ? "⚠ " : ""}
      {ENVIRONMENT_LABEL[environment]}
    </span>
  );
}

/** Read-only marker, shown as words rather than an icon for the same reason. */
export function ReadOnlyBadge() {
  return <span className="badge read-only">READ ONLY</span>;
}

const STATUS_LABEL: Record<RuntimeStatus, string> = {
  closed: "Closed",
  opening: "Opening…",
  open: "Open",
  error: "Error",
};

export function StatusBadge({ status }: { status: RuntimeStatus }) {
  return (
    <span className={`badge status-${status}`}>
      <span className="dot" aria-hidden="true" />
      {STATUS_LABEL[status]}
    </span>
  );
}

export function EngineIcon({ engine }: { engine: Connection["engine"] }) {
  const label = engine === "postgres" ? "PostgreSQL" : "Redis";
  return (
    <span className={`engine engine-${engine}`} title={label} aria-label={label} role="img">
      {engine === "postgres" ? "PG" : "RD"}
    </span>
  );
}

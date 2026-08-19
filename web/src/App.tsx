import { useCallback, useEffect, useRef, useState } from "react";
import { ApiClient, ApiError, type Health, type QueryResult } from "./api/client";
import { ResultTable } from "./components/ResultTable";

type RunState =
  | { kind: "idle" }
  | { kind: "running" }
  | { kind: "success"; result: QueryResult }
  | { kind: "failed"; error: ApiError };

export function App({ client }: { client: ApiClient | null }) {
  const [health, setHealth] = useState<Health | null>(null);
  const [healthError, setHealthError] = useState<ApiError | null>(null);
  const [run, setRun] = useState<RunState>({ kind: "idle" });
  const inFlight = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!client) return;
    const controller = new AbortController();
    client
      .health(controller.signal)
      .then((h) => {
        setHealth(h);
        setHealthError(null);
      })
      .catch((err: unknown) => {
        if (err instanceof ApiError) setHealthError(err);
      });
    return () => controller.abort();
  }, [client]);

  useEffect(() => () => inFlight.current?.abort(), []);

  const runQuery = useCallback(async () => {
    if (!client) return;
    inFlight.current?.abort();
    const controller = new AbortController();
    inFlight.current = controller;
    setRun({ kind: "running" });
    try {
      const result = await client.selectOne(controller.signal);
      setRun({ kind: "success", result });
    } catch (err: unknown) {
      if (controller.signal.aborted) return;
      setRun({
        kind: "failed",
        error:
          err instanceof ApiError
            ? err
            : new ApiError("internal_error", "Something went wrong in the application."),
      });
    }
  }, [client]);

  if (!client) {
    return (
      <Shell>
        <Banner tone="error" title="No session token">
          Open the URL that the <code>dbide</code> process printed at startup. It contains the token
          that authorizes this page, and it is only valid for the running process.
        </Banner>
      </Shell>
    );
  }

  return (
    <Shell>
      <StatusLine health={health} error={healthError} />

      <section className="panel">
        <h2>Bootstrap query</h2>
        <p className="muted">
          Runs the fixed statement <code>SELECT 1 AS value</code> against the development database.
        </p>
        <button type="button" onClick={runQuery} disabled={run.kind === "running"}>
          {run.kind === "running" ? "Running…" : "Run SELECT 1"}
        </button>

        <div className="output" aria-live="polite">
          {run.kind === "idle" && <p className="muted">No query has been run yet.</p>}
          {run.kind === "running" && <p role="status">Running the query…</p>}
          {run.kind === "success" && (
            <>
              <p className="summary">
                {run.result.rowCount} {run.result.rowCount === 1 ? "row" : "rows"} in{" "}
                {run.result.durationMs} ms
              </p>
              <ResultTable result={run.result} />
            </>
          )}
          {run.kind === "failed" && <ErrorView error={run.error} />}
        </div>
      </section>
    </Shell>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <main>
      <header>
        <h1>Database IDE</h1>
        <p className="tagline">PostgreSQL and Redis in one free tool.</p>
      </header>
      {children}
    </main>
  );
}

function StatusLine({ health, error }: { health: Health | null; error: ApiError | null }) {
  if (error) {
    return (
      <Banner tone="error" title={titleFor(error)}>
        {error.message}
      </Banner>
    );
  }
  if (!health) return <p className="muted">Checking the server…</p>;
  if (health.database === "not_configured") {
    return (
      <Banner tone="warning" title="No development database configured">
        Set <code>DBIDE_DEV_POSTGRES_DSN</code> and restart the server to run the bootstrap query.
      </Banner>
    );
  }
  return (
    <p className="muted">
      Server <strong>{health.version}</strong> · PostgreSQL configured
    </p>
  );
}

function ErrorView({ error }: { error: ApiError }) {
  return (
    <Banner tone="error" title={titleFor(error)}>
      {error.message}
      {error.code === "unauthorized" && (
        <> Reopen the URL printed by the server to get a fresh token.</>
      )}
    </Banner>
  );
}

function titleFor(error: ApiError): string {
  switch (error.code) {
    case "unauthorized":
      return "Authentication failed";
    case "forbidden_origin":
      return "Blocked request origin";
    case "server_unreachable":
      return "Server unavailable";
    case "database_unavailable":
      return "PostgreSQL unavailable";
    case "query_timeout":
      return "Query timed out";
    case "query_cancelled":
      return "Query cancelled";
    case "query_failed":
      return "Query failed";
    default:
      return "Something went wrong";
  }
}

function Banner({
  tone,
  title,
  children,
}: {
  tone: "error" | "warning";
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className={`banner ${tone}`} role="alert">
      <strong>{title}</strong>
      <span>{children}</span>
    </div>
  );
}

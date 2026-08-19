import { useCallback, useEffect, useState } from "react";
import {
  ApiClient,
  ApiError,
  type Connection,
  type ConnectionInput,
  type FieldError,
  type TestResult,
} from "../api/client";
import { Banner } from "../components/Banner";
import { ConnectionForm } from "../components/ConnectionForm";
import { ConnectionList } from "../components/ConnectionList";
import { EnvironmentBadge, ReadOnlyBadge, StatusBadge } from "../components/badges";

type Editor = { mode: "closed" } | { mode: "create" } | { mode: "edit"; connection: Connection };

export function ConnectionsScreen({
  client,
  onLocked,
}: {
  client: ApiClient;
  onLocked: () => void;
}) {
  const [connections, setConnections] = useState<Connection[] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [editor, setEditor] = useState<Editor>({ mode: "closed" });
  const [listError, setListError] = useState<ApiError | null>(null);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldError[]>([]);
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<Connection | null>(null);

  const refresh = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const list = await client.listConnections(signal);
        setConnections(list);
        setListError(null);
        return list;
      } catch (err: unknown) {
        if (err instanceof DOMException && err.name === "AbortError") return null;
        if (err instanceof ApiError) {
          if (err.code === "locked") {
            onLocked();
            return null;
          }
          setListError(err);
        }
        return null;
      }
    },
    [client, onLocked],
  );

  useEffect(() => {
    const controller = new AbortController();
    void refresh(controller.signal);
    return () => controller.abort();
  }, [refresh]);

  const selected = connections?.find((c) => c.id === selectedId) ?? null;

  function handleFailure(err: unknown) {
    const apiError =
      err instanceof ApiError
        ? err
        : new ApiError("internal_error", "Something went wrong in the application.");
    if (apiError.code === "locked") {
      onLocked();
      return;
    }
    setActionError(apiError);
    setFieldErrors(apiError.fields);
  }

  async function save(input: ConnectionInput) {
    setBusy(true);
    setActionError(null);
    setFieldErrors([]);
    setTestResult(null);
    try {
      const saved =
        editor.mode === "edit"
          ? await client.updateConnection(editor.connection.id, input)
          : await client.createConnection(input);
      await refresh();
      setSelectedId(saved.id);
      setEditor({ mode: "closed" });
    } catch (err: unknown) {
      handleFailure(err);
    } finally {
      setBusy(false);
    }
  }

  /** Test saves nothing: it exercises the stored connection as it is on the
   * server, so a not-yet-saved edit must be saved first. */
  async function test(connection: Connection) {
    setTesting(true);
    setActionError(null);
    setTestResult(null);
    try {
      setTestResult(await client.testConnection(connection.id));
    } catch (err: unknown) {
      handleFailure(err);
    } finally {
      setTesting(false);
    }
  }

  async function toggleOpen(connection: Connection) {
    setBusy(true);
    setActionError(null);
    try {
      if (connection.runtime.status === "open") {
        await client.closeConnection(connection.id);
      } else {
        await client.openConnection(connection.id);
      }
    } catch (err: unknown) {
      handleFailure(err);
    } finally {
      await refresh();
      setBusy(false);
    }
  }

  async function confirmDelete(connection: Connection) {
    setBusy(true);
    setActionError(null);
    try {
      await client.deleteConnection(connection.id);
      setPendingDelete(null);
      setSelectedId(null);
      setEditor({ mode: "closed" });
      await refresh();
    } catch (err: unknown) {
      handleFailure(err);
    } finally {
      setBusy(false);
    }
  }

  async function lock() {
    try {
      await client.lock();
    } finally {
      onLocked();
    }
  }

  return (
    <div className="connections-screen">
      <div className="toolbar">
        <div className="context" role="status" aria-label="Selected connection">
          {selected ? (
            <>
              <strong>{selected.name}</strong>
              <EnvironmentBadge environment={selected.environment} />
              {selected.readOnly ? <ReadOnlyBadge /> : null}
              <StatusBadge status={selected.runtime.status} />
            </>
          ) : (
            <span className="muted">No connection selected</span>
          )}
        </div>
        <div className="toolbar-actions">
          <button
            type="button"
            onClick={() => {
              setEditor({ mode: "create" });
              setActionError(null);
              setFieldErrors([]);
              setTestResult(null);
            }}
          >
            New connection
          </button>
          <button type="button" className="secondary" onClick={lock}>
            Lock
          </button>
        </div>
      </div>

      {listError ? (
        <Banner tone="error" title="Could not load connections">
          {listError.message}
        </Banner>
      ) : null}

      <div className="columns">
        <div className="column list">
          {connections === null && !listError ? (
            <p className="muted">Loading connections…</p>
          ) : (
            <ConnectionList
              connections={connections ?? []}
              selectedId={selectedId}
              onSelect={(connection) => {
                setSelectedId(connection.id);
                setEditor({ mode: "closed" });
                setActionError(null);
                setTestResult(null);
              }}
            />
          )}
        </div>

        <div className="column detail">
          {editor.mode !== "closed" ? (
            <section className="panel">
              <ConnectionForm
              connection={editor.mode === "edit" ? editor.connection : null}
              busy={busy}
              testing={testing}
              fieldErrors={fieldErrors}
              error={actionError}
              onSubmit={save}
              onTest={() => {
                if (editor.mode === "edit") void test(editor.connection);
              }}
                onCancel={() => {
                  setEditor({ mode: "closed" });
                  setActionError(null);
                  setFieldErrors([]);
                }}
              />
            </section>
          ) : selected ? (
            <ConnectionDetail
              connection={selected}
              busy={busy}
              testing={testing}
              error={actionError}
              testResult={testResult}
              pendingDelete={pendingDelete?.id === selected.id}
              onEdit={() => {
                setEditor({ mode: "edit", connection: selected });
                setActionError(null);
                setFieldErrors([]);
                setTestResult(null);
              }}
              onTest={() => void test(selected)}
              onToggleOpen={() => void toggleOpen(selected)}
              onRequestDelete={() => setPendingDelete(selected)}
              onCancelDelete={() => setPendingDelete(null)}
              onConfirmDelete={() => void confirmDelete(selected)}
            />
          ) : (
            <div className="empty-state">
              <p className="muted">Select a connection, or create one.</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function ConnectionDetail({
  connection,
  busy,
  testing,
  error,
  testResult,
  pendingDelete,
  onEdit,
  onTest,
  onToggleOpen,
  onRequestDelete,
  onCancelDelete,
  onConfirmDelete,
}: {
  connection: Connection;
  busy: boolean;
  testing: boolean;
  error: ApiError | null;
  testResult: TestResult | null;
  pendingDelete: boolean;
  onEdit: () => void;
  onTest: () => void;
  onToggleOpen: () => void;
  onRequestDelete: () => void;
  onCancelDelete: () => void;
  onConfirmDelete: () => void;
}) {
  const isOpen = connection.runtime.status === "open";
  return (
    <section className={`panel detail-panel${connection.environment === "prod" ? " prod" : ""}`}>
      <header className="detail-header">
        <h2>{connection.name}</h2>
        <div className="markers">
          <EnvironmentBadge environment={connection.environment} />
          {connection.readOnly ? <ReadOnlyBadge /> : null}
          <StatusBadge status={connection.runtime.status} />
        </div>
      </header>

      <dl className="properties">
        <dt>Engine</dt>
        <dd>{connection.engine === "postgres" ? "PostgreSQL" : "Redis"}</dd>
        <dt>Host</dt>
        <dd>
          {connection.host}:{connection.port}
        </dd>
        <dt>{connection.engine === "redis" ? "Database index" : "Database"}</dt>
        <dd>{connection.database || <span className="muted">not set</span>}</dd>
        <dt>Username</dt>
        <dd>{connection.username || <span className="muted">not set</span>}</dd>
        <dt>TLS</dt>
        <dd>{connection.tlsMode}</dd>
        <dt>Password</dt>
        <dd>{connection.hasSecret ? "Saved (encrypted)" : <span className="muted">none</span>}</dd>
      </dl>

      {connection.runtime.status === "error" && connection.runtime.lastError ? (
        <Banner tone="error" title="Last attempt failed">
          {connection.runtime.lastError}
        </Banner>
      ) : null}

      {error ? (
        <Banner tone="error" title={titleFor(error)}>
          {error.message}
        </Banner>
      ) : null}

      {testResult?.ok ? (
        <Banner tone="success" title="Connection succeeded">
          {testResult.serverVersion ? `Server ${testResult.serverVersion} · ` : ""}
          {testResult.latencyMs} ms
        </Banner>
      ) : null}

      <div className="actions">
        <button type="button" onClick={onToggleOpen} disabled={busy || testing}>
          {isOpen ? "Close" : "Open"}
        </button>
        <button type="button" onClick={onTest} disabled={busy || testing}>
          {testing ? "Testing…" : "Test"}
        </button>
        <button type="button" className="secondary" onClick={onEdit} disabled={busy || testing}>
          Edit
        </button>
        {pendingDelete ? null : (
          <button type="button" className="danger" onClick={onRequestDelete} disabled={busy || testing}>
            Delete
          </button>
        )}
      </div>

      {pendingDelete ? (
        <div className="confirm" role="alertdialog" aria-label="Confirm deletion">
          <p>
            Delete <strong>{connection.name}</strong>? Its saved credential is destroyed and any
            open client is closed. This cannot be undone.
          </p>
          <div className="actions">
            <button type="button" className="danger" onClick={onConfirmDelete} disabled={busy}>
              {busy ? "Deleting…" : "Yes, delete it"}
            </button>
            <button type="button" className="secondary" onClick={onCancelDelete} disabled={busy}>
              Cancel
            </button>
          </div>
        </div>
      ) : null}
    </section>
  );
}

function titleFor(error: ApiError): string {
  switch (error.code) {
    case "authentication_failed":
      return "Authentication failed";
    case "connect_timeout":
      return "Could not reach the host";
    case "tls_verification_failed":
      return "TLS verification failed";
    case "database_unavailable":
      return "Server unavailable";
    case "secret_unreadable":
      return "Saved credential unreadable";
    case "duplicate_name":
      return "Name already used";
    case "server_unreachable":
      return "Application server unavailable";
    default:
      return "Something went wrong";
  }
}

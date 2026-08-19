import { useEffect, useMemo, useRef, useState } from "react";
import type {
  ApiError,
  Connection,
  ConnectionInput,
  Engine,
  Environment,
  FieldError,
  TlsMode,
} from "../api/client";
import { Banner } from "../components/Banner";

const ENGINE_DEFAULT_PORT: Record<Engine, number> = { postgres: 5432, redis: 6379 };
const TLS_MODES: Record<Engine, TlsMode[]> = {
  postgres: ["disable", "require", "verify-full"],
  redis: ["disable", "require"],
};
const ENVIRONMENTS: Environment[] = ["dev", "staging", "prod"];

/** Form state keeps port as a string so a half-typed value is not coerced to 0
 * while the user is still typing. */
interface FormState {
  name: string;
  engine: Engine;
  host: string;
  port: string;
  database: string;
  username: string;
  tlsMode: TlsMode;
  environment: Environment;
  readOnly: boolean;
  color: string;
  password: string;
  /** False while an existing connection's stored password is left alone. */
  replaceSecret: boolean;
}

function blankState(): FormState {
  return {
    name: "",
    engine: "postgres",
    host: "127.0.0.1",
    port: String(ENGINE_DEFAULT_PORT.postgres),
    database: "postgres",
    username: "",
    tlsMode: "disable",
    environment: "dev",
    readOnly: false,
    color: "",
    password: "",
    replaceSecret: true,
  };
}

function stateFrom(connection: Connection): FormState {
  return {
    name: connection.name,
    engine: connection.engine,
    host: connection.host,
    port: String(connection.port),
    database: connection.database,
    username: connection.username,
    tlsMode: connection.tlsMode,
    environment: connection.environment,
    readOnly: connection.readOnly,
    color: connection.color,
    password: "",
    // A saved password is never prefilled, and is left untouched until the user
    // explicitly asks to replace it.
    replaceSecret: !connection.hasSecret,
  };
}

export interface ConnectionFormProps {
  /** The connection being edited, or null when creating a new one. */
  connection: Connection | null;
  busy: boolean;
  testing: boolean;
  /** Server-side field errors from the last failed submission. */
  fieldErrors: FieldError[];
  error: ApiError | null;
  onSubmit: (input: ConnectionInput) => void;
  onTest: (input: ConnectionInput) => void;
  onCancel: () => void;
}

export function ConnectionForm({
  connection,
  busy,
  testing,
  fieldErrors,
  error,
  onSubmit,
  onTest,
  onCancel,
}: ConnectionFormProps) {
  const [state, setState] = useState<FormState>(() =>
    connection ? stateFrom(connection) : blankState(),
  );
  const formRef = useRef<HTMLFormElement>(null);

  // Re-seed when the edited connection changes. Non-secret values entered
  // during a failed test are preserved because the identity does not change.
  useEffect(() => {
    setState(connection ? stateFrom(connection) : blankState());
  }, [connection?.id, connection === null]);

  const errorFor = useMemo(() => {
    const map = new Map<string, string>();
    for (const f of fieldErrors) map.set(f.field, f.message);
    return map;
  }, [fieldErrors]);

  // Focus the first field the server rejected, so a long form does not require
  // hunting for the problem.
  useEffect(() => {
    if (fieldErrors.length === 0) return;
    const first = fieldErrors[0];
    if (!first) return;
    const el = formRef.current?.querySelector<HTMLElement>(`[name="${first.field}"]`);
    el?.focus();
  }, [fieldErrors]);

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setState((prev) => ({ ...prev, [key]: value }));
  }

  function changeEngine(engine: Engine) {
    setState((prev) => {
      const wasDefaultPort = prev.port === String(ENGINE_DEFAULT_PORT[prev.engine]);
      const allowedTls = TLS_MODES[engine];
      return {
        ...prev,
        engine,
        // Only move the port if the user had not chosen one themselves.
        port: wasDefaultPort ? String(ENGINE_DEFAULT_PORT[engine]) : prev.port,
        database: engine === "redis" ? "0" : prev.database === "0" ? "postgres" : prev.database,
        tlsMode: allowedTls.includes(prev.tlsMode) ? prev.tlsMode : "disable",
      };
    });
  }

  function toInput(): ConnectionInput {
    const input: ConnectionInput = {
      name: state.name,
      engine: state.engine,
      host: state.host,
      port: Number.parseInt(state.port, 10) || 0,
      database: state.database,
      username: state.username,
      tlsMode: state.tlsMode,
      environment: state.environment,
      readOnly: state.readOnly,
      color: state.color,
    };
    // Omitting `secret` entirely is what tells the server to leave the stored
    // password alone; sending an empty value would clear it.
    if (state.replaceSecret) {
      input.secret = { changed: true, value: state.password };
    }
    return input;
  }

  const isEdit = connection !== null;
  const disabled = busy || testing;

  return (
    <form
      ref={formRef}
      className="connection-form"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        if (disabled) return; // prevent double submission
        onSubmit(toInput());
      }}
    >
      <h2>{isEdit ? `Edit ${connection.name}` : "New connection"}</h2>

      {error && error.code !== "validation_failed" ? (
        <Banner tone="error" title="Could not save">
          {error.message}
        </Banner>
      ) : null}

      <Field label="Name" name="name" error={errorFor.get("name")}>
        <input
          id="field-name"
          name="name"
          value={state.name}
          onChange={(e) => set("name", e.target.value)}
          disabled={disabled}
          autoFocus
        />
      </Field>

      <Field label="Engine" name="engine" error={errorFor.get("engine")}>
        <select
          id="field-engine"
          name="engine"
          value={state.engine}
          onChange={(e) => changeEngine(e.target.value as Engine)}
          disabled={disabled}
        >
          <option value="postgres">PostgreSQL</option>
          <option value="redis">Redis</option>
        </select>
      </Field>

      <div className="row">
        <Field label="Host" name="host" error={errorFor.get("host")}>
          <input
            id="field-host"
            name="host"
            value={state.host}
            onChange={(e) => set("host", e.target.value)}
            disabled={disabled}
            placeholder="db.internal"
          />
        </Field>
        <Field label="Port" name="port" error={errorFor.get("port")}>
          <input
            id="field-port"
            name="port"
            inputMode="numeric"
            value={state.port}
            onChange={(e) => set("port", e.target.value)}
            disabled={disabled}
          />
        </Field>
      </div>

      <div className="row">
        <Field
          label={state.engine === "redis" ? "Database index" : "Database"}
          name="database"
          error={errorFor.get("database")}
        >
          <input
            id="field-database"
            name="database"
            value={state.database}
            onChange={(e) => set("database", e.target.value)}
            disabled={disabled}
            placeholder={state.engine === "redis" ? "0" : "postgres"}
          />
        </Field>
        <Field
          label={state.engine === "redis" ? "Username (optional)" : "Username"}
          name="username"
          error={errorFor.get("username")}
        >
          <input
            id="field-username"
            name="username"
            value={state.username}
            onChange={(e) => set("username", e.target.value)}
            disabled={disabled}
            autoComplete="off"
          />
        </Field>
      </div>

      <fieldset className="secret">
        <legend>Password</legend>
        {isEdit && connection.hasSecret && !state.replaceSecret ? (
          <div className="secret-kept">
            <span className="muted">Leave saved password unchanged</span>
            <button
              type="button"
              className="link"
              onClick={() => set("replaceSecret", true)}
              disabled={disabled}
            >
              Replace password
            </button>
          </div>
        ) : (
          <>
            <label htmlFor="field-password">Password</label>
            <input
              id="field-password"
              name="password"
              type="password"
              value={state.password}
              onChange={(e) => set("password", e.target.value)}
              disabled={disabled}
              autoComplete="new-password"
              placeholder={state.engine === "redis" ? "Leave empty if no password is set" : ""}
            />
            {isEdit && connection.hasSecret ? (
              <button
                type="button"
                className="link"
                onClick={() => setState((p) => ({ ...p, replaceSecret: false, password: "" }))}
                disabled={disabled}
              >
                Keep the saved password instead
              </button>
            ) : null}
            <p className="hint">
              Saved encrypted with your master password. Leaving this empty stores no password.
            </p>
          </>
        )}
      </fieldset>

      <div className="row">
        <Field label="TLS" name="tlsMode" error={errorFor.get("tlsMode")}>
          <select
            id="field-tlsMode"
            name="tlsMode"
            value={state.tlsMode}
            onChange={(e) => set("tlsMode", e.target.value as TlsMode)}
            disabled={disabled}
          >
            {TLS_MODES[state.engine].map((mode) => (
              <option key={mode} value={mode}>
                {mode}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Environment" name="environment" error={errorFor.get("environment")}>
          <select
            id="field-environment"
            name="environment"
            value={state.environment}
            onChange={(e) => set("environment", e.target.value as Environment)}
            disabled={disabled}
          >
            {ENVIRONMENTS.map((env) => (
              <option key={env} value={env}>
                {env}
              </option>
            ))}
          </select>
        </Field>
      </div>

      {state.environment === "prod" ? (
        <Banner tone="warning" title="Production connection">
          This connection will be marked PROD everywhere it appears.
        </Banner>
      ) : null}

      <Field label="Color (optional)" name="color" error={errorFor.get("color")}>
        <input
          id="field-color"
          name="color"
          value={state.color}
          onChange={(e) => set("color", e.target.value)}
          disabled={disabled}
          placeholder="#4c8dff"
        />
      </Field>

      <label className="checkbox">
        <input
          type="checkbox"
          name="readOnly"
          checked={state.readOnly}
          onChange={(e) => set("readOnly", e.target.checked)}
          disabled={disabled}
        />
        Read only
      </label>

      <div className="actions">
        <button type="submit" disabled={disabled}>
          {busy ? "Saving…" : isEdit ? "Save changes" : "Create connection"}
        </button>
        {isEdit ? (
          <button type="button" onClick={() => onTest(toInput())} disabled={disabled}>
            {testing ? "Testing…" : "Test connection"}
          </button>
        ) : null}
        <button type="button" className="secondary" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function Field({
  label,
  name,
  error,
  children,
}: {
  label: string;
  name: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <div className={`field${error ? " invalid" : ""}`}>
      <label htmlFor={`field-${name}`}>{label}</label>
      {children}
      {error ? (
        <p className="field-error" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

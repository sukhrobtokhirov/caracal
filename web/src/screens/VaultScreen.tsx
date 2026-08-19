import { useRef, useState } from "react";
import { ApiClient, ApiError, type AuthStatus } from "../api/client";
import { Banner } from "../components/Banner";

/** First-run setup and returning-user unlock.
 *
 * Both states are the same form with different copy; setup additionally asks
 * for confirmation, because a mistyped master password on first run would seal
 * every credential the user then saves under a password they do not know. */
export function VaultScreen({
  client,
  status,
  onUnlocked,
}: {
  client: ApiClient;
  status: AuthStatus;
  onUnlocked: () => void;
}) {
  const isSetup = status.state === "setup_required";
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const [mismatch, setMismatch] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const passwordRef = useRef<HTMLInputElement>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return; // a double submit would spend two Argon2id derivations

    if (isSetup && password !== confirmation) {
      setMismatch("The two passwords do not match.");
      setError(null);
      passwordRef.current?.focus();
      return;
    }
    setMismatch(null);
    setError(null);
    setBusy(true);
    try {
      if (isSetup) {
        await client.setupMasterPassword(password);
      } else {
        await client.unlock(password);
      }
      setPassword("");
      setConfirmation("");
      onUnlocked();
    } catch (err: unknown) {
      setError(
        err instanceof ApiError
          ? err
          : new ApiError("internal_error", "Something went wrong in the application."),
      );
      setPassword("");
      passwordRef.current?.focus();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="vault-screen">
      <section className="panel narrow">
        <h2>{isSetup ? "Choose a master password" : "Unlock"}</h2>
        <p className="muted">
          {isSetup
            ? "This password encrypts every database credential you save. It is never stored, so it cannot be recovered — if you forget it, your saved passwords are gone."
            : "Enter your master password to use your saved connections."}
        </p>

        {error ? (
          <Banner tone="error" title={titleFor(error)}>
            {error.message}
            {error.code === "too_many_attempts" && error.retryAfterSeconds
              ? ` Try again in ${error.retryAfterSeconds} seconds.`
              : null}
          </Banner>
        ) : null}
        {mismatch ? (
          <Banner tone="error" title="Passwords do not match">
            {mismatch}
          </Banner>
        ) : null}

        <form onSubmit={submit} noValidate>
          <label htmlFor="master-password">Master password</label>
          <input
            id="master-password"
            ref={passwordRef}
            type="password"
            autoComplete={isSetup ? "new-password" : "current-password"}
            autoFocus
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={busy}
          />
          {isSetup ? (
            <>
              <p className="hint">
                At least {status.minPasswordLength} characters. Length matters far more than
                punctuation here — a memorable phrase beats a short scramble.
              </p>
              <label htmlFor="master-password-confirm">Confirm master password</label>
              <input
                id="master-password-confirm"
                type="password"
                autoComplete="new-password"
                value={confirmation}
                onChange={(e) => setConfirmation(e.target.value)}
                disabled={busy}
              />
            </>
          ) : null}

          <button type="submit" disabled={busy || password === ""}>
            {busy ? "Working…" : isSetup ? "Set master password" : "Unlock"}
          </button>
        </form>
      </section>
    </div>
  );
}

function titleFor(error: ApiError): string {
  switch (error.code) {
    case "wrong_master_password":
      return "Incorrect master password";
    case "weak_master_password":
      return "Password too short";
    case "too_many_attempts":
      return "Too many attempts";
    case "already_set_up":
      return "Already set up";
    case "server_unreachable":
      return "Server unavailable";
    default:
      return "Something went wrong";
  }
}

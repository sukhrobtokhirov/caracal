import { useCallback, useEffect, useState } from "react";
import { ApiClient, ApiError, type AuthStatus, type Health } from "./api/client";
import { Banner } from "./components/Banner";
import { ConnectionsScreen } from "./screens/ConnectionsScreen";
import { VaultScreen } from "./screens/VaultScreen";

export function App({ client }: { client: ApiClient | null }) {
  const [health, setHealth] = useState<Health | null>(null);
  const [auth, setAuth] = useState<AuthStatus | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  const loadStatus = useCallback(
    async (signal?: AbortSignal) => {
      if (!client) return;
      try {
        const [nextHealth, nextAuth] = await Promise.all([
          client.health(signal),
          client.authStatus(signal),
        ]);
        setHealth(nextHealth);
        setAuth(nextAuth);
        setError(null);
      } catch (err: unknown) {
        if (err instanceof DOMException && err.name === "AbortError") return;
        if (err instanceof ApiError) setError(err);
      }
    },
    [client],
  );

  useEffect(() => {
    const controller = new AbortController();
    void loadStatus(controller.signal);
    return () => controller.abort();
  }, [loadStatus]);

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

  if (error) {
    return (
      <Shell version={health?.version}>
        <Banner tone="error" title={titleFor(error)}>
          {error.message}
          {error.code === "unauthorized" ? (
            <> Reopen the URL printed by the server to get a fresh token.</>
          ) : null}
        </Banner>
      </Shell>
    );
  }

  if (!auth) {
    return (
      <Shell>
        <p className="muted">Connecting to the local server…</p>
      </Shell>
    );
  }

  return (
    <Shell version={health?.version}>
      {auth.state === "unlocked" ? (
        <ConnectionsScreen
          client={client}
          onLocked={() => setAuth({ ...auth, state: "locked" })}
        />
      ) : (
        <VaultScreen client={client} status={auth} onUnlocked={() => void loadStatus()} />
      )}
    </Shell>
  );
}

function Shell({ version, children }: { version?: string; children: React.ReactNode }) {
  return (
    <main>
      <header>
        <div>
          <h1>Database IDE</h1>
          <p className="tagline">PostgreSQL and Redis in one free tool.</p>
        </div>
        {version ? <span className="version">{version}</span> : null}
      </header>
      {children}
    </main>
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
    default:
      return "Something went wrong";
  }
}

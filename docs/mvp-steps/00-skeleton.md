# M0 — Skeleton

## Outcome

Produce the smallest end-to-end application that proves the chosen architecture: one Go binary binds to localhost, serves an embedded React application, authenticates API traffic with a startup token, connects to one temporary hardcoded PostgreSQL database, and renders the result of `SELECT 1`.

This milestone is about retiring architecture and packaging risk. It is not a miniature connection manager or SQL IDE.

## User-visible behavior

When the user starts the binary:

1. The server selects an available local port on `127.0.0.1`.
2. It generates a cryptographically random session token.
3. It prints and, when supported, opens a URL such as `http://127.0.0.1:43127/?token=...`.
4. The browser loads the embedded application.
5. A single **Run SELECT 1** button executes the query.
6. The page shows the returned column, value, duration, and any error.

The page must also make these states understandable: server unavailable, authentication failed, PostgreSQL unavailable, query running, query succeeded, and query failed.

## Scope

### Included

- Go module and executable entry point
- Loopback-only HTTP server
- Session-token middleware
- Origin validation for the query request
- Vite, React, and TypeScript application
- Static assets embedded with `go:embed`
- Temporary PostgreSQL configuration for one developer database
- One fixed query and a minimal result view
- A repeatable build command that produces one executable
- Basic backend and frontend tests

### Not included

- SQLite, saved connections, or credential encryption
- Editable SQL
- General result-grid behavior
- Redis
- Query history
- Installers, signing, or release automation

## Suggested project shape

The exact package names may change, but keep boundaries visible from the start:

```text
cmd/dbide/                 executable startup
internal/httpapp/          server, routes, middleware, JSON errors
internal/postgres/         temporary PostgreSQL query adapter
web/                       React + TypeScript source
web/dist/                  generated production assets
internal/webassets/        go:embed wrapper for web/dist
Makefile                   development and production commands
```

Generated frontend files should not leak into business packages. The embedded file system should be replaceable with Vite's development server during local frontend work.

## Work packages

### 0.1 Bootstrap the Go server

- Create a Go 1.22+ module and `cmd/dbide` entry point.
- Listen with an explicit host of `127.0.0.1`. If the port is configurable, validate that configuration cannot silently widen the bind address in v0.1.
- Add `GET /api/health` returning build information and readiness without exposing secrets.
- Configure conservative HTTP server timeouts and a maximum request-header size.
- Handle `SIGINT`/`SIGTERM`: stop accepting requests, cancel active work, close database resources, and shut down within a bounded period.
- Use structured logs with severity, component, and safe error text. Do not log the session token as an independent field; printing the one launch URL is sufficient.

Example health response:

```json
{
  "status": "ok",
  "version": "dev"
}
```

### 0.2 Add local-session protection

- Generate at least 32 random bytes using `crypto/rand` and encode them for URLs.
- Put the token in the initial launch URL so the SPA can bootstrap.
- After bootstrap, keep the token in memory and send it in an authorization header on every API request. Remove it from the visible address bar with `history.replaceState` so it is less likely to be copied or retained in screenshots.
- Compare tokens in constant time.
- Reject missing or invalid tokens with `401`.
- Allow only the exact local application origins created by the running server. Treat a missing `Origin` according to a documented same-origin policy; do not accept arbitrary origins.
- Do not enable permissive CORS.

Add middleware tests for valid token, missing token, invalid token, permitted origin, and hostile origin.

### 0.3 Create and embed the frontend

- Scaffold Vite with React and TypeScript.
- Add a small API client that owns token extraction, headers, JSON parsing, and normalized errors.
- Implement loading, running, success, and failure views for the fixed query.
- Configure the production build so `web/dist` is embedded in the Go binary.
- Serve `index.html` as the SPA fallback, but never use that fallback for unknown `/api/*` routes.
- Set content types correctly and add basic defensive headers such as `X-Content-Type-Options: nosniff` and a restrictive Content Security Policy compatible with the production bundle.

In development, document how Vite proxies `/api` to the Go process while preserving the same authentication model.

### 0.4 Prove the PostgreSQL round trip

- Read the temporary connection string from a clearly named development environment variable. Never commit credentials or a working connection string.
- Establish a small `pgxpool` pool with a short connection timeout.
- Expose a temporary authenticated endpoint for the fixed query, for example `POST /api/bootstrap/select-one`.
- Ignore user-provided SQL at this stage; the handler must execute the literal `SELECT 1 AS value`.
- Apply a query timeout and close rows on all paths.
- Return a response shaped similarly to the future query result so the frontend work is reusable.

Example response:

```json
{
  "columns": [{"name": "value", "typeName": "int4"}],
  "rows": [["1"]],
  "rowCount": 1,
  "durationMs": 3
}
```

The hardcoded endpoint and environment-based connection are scaffolding. Mark them for removal in M1/M2.

### 0.5 Make the build repeatable

Provide a small, documented command surface:

- `make dev-backend` runs the Go server.
- `make dev-frontend` runs Vite.
- `make test` runs backend and frontend tests.
- `make build` installs/builds frontend dependencies, builds the SPA, then compiles the Go binary with embedded assets.

The final executable must not need Node.js or frontend files at runtime. From a temporary empty directory, start the binary and confirm that the UI still loads.

### 0.6 Launch the browser safely

- Wait until the listener is ready before opening the URL.
- Use the platform browser opener only as a convenience; always print the URL as a fallback.
- If opening fails, keep the server running and show a useful log message.
- Add a flag such as `--no-open` for headless use and automated tests.

## Testing

### Automated

- Token generation produces valid, non-repeating values.
- Authentication middleware accepts and rejects the correct cases.
- Origin middleware rejects a foreign webpage origin.
- Unknown API routes return JSON `404`, not `index.html`.
- The embedded asset handler serves the built entry page.
- PostgreSQL adapter maps success, timeout, and connection failure into safe responses.
- Frontend API client renders running, success, authentication error, and database error states.

A PostgreSQL integration test may be opt-in when no test database is available, but CI must eventually provide one.

### Manual acceptance scenario

1. Set the documented temporary PostgreSQL environment variable.
2. Run `make build`.
3. Copy only the produced executable into an empty temporary directory.
4. Start it with no frontend development server running.
5. Open the printed URL and click **Run SELECT 1**.
6. Confirm the page displays `1` and a duration.
7. Remove or alter the URL token and confirm API access fails.
8. Stop the process and confirm it exits cleanly.

## Completion checklist

- [x] The process listens only on `127.0.0.1`.
- [x] Every API request requires the startup token.
- [x] Mutating requests enforce the expected origin.
- [x] The React production build is embedded in the executable.
- [x] `SELECT 1` makes a real PostgreSQL round trip and displays its result.
- [x] Credentials and session tokens are absent from normal logs and repository files.
- [x] `make test` and `make build` succeed from documented prerequisites.
- [x] The executable works without Node.js or loose static assets at runtime.

## Exit criterion

`make build` produces one binary that opens a browser and successfully round-trips `SELECT 1` to PostgreSQL.

---

## Implementation notes

Status: **complete**. Verified on 2026-08-20 against PostgreSQL 16 in Docker.

### Intentional deviations from this guide

| Deviation | Reason |
|---|---|
| Vite writes to `internal/webassets/dist`, not `web/dist` | `go:embed` cannot reference paths outside its own package directory. The generated bundle still stays out of every business package, which is what the guide's constraint protects. |
| The static bundle is served without a token; only `/api/*` requires one | The bundle is public code with no secrets in it. Gating the document load would also gate the request that delivers the token. The API — the only thing that reaches a database — stays fully authenticated. |
| `GET /api/health` sits behind the token like every other API route | The guide's completion checklist requires the token on every API request, so readiness checks must present it too. |

### Documented policies the guide left open

- **Missing `Origin` header.** Allowed on `GET`/`HEAD`/`OPTIONS`, rejected on
  mutating methods. Browsers attach `Origin` to every non-safe `fetch`,
  including same-origin ones, so a `POST` without it did not come from our page.
  The token check runs before the origin check so a hostile page cannot use
  error codes to probe which origins are accepted.
- **Allowed origins.** Exactly `http://127.0.0.1:<port>` and
  `http://localhost:<port>` for the bound port, plus one optional
  `--dev-origin` for the Vite dev server.

### Scaffolding marked for removal

- `DBIDE_DEV_POSTGRES_DSN` and the single `postgres.Adapter` in `main` — replaced
  by the encrypted connection store in M1.
- `POST /api/bootstrap/select-one` and `Adapter.SelectOne` — replaced by the real
  query endpoint in M2.
- `postgres.typeName`'s hardcoded OID table — replaced by a per-server `pg_type`
  lookup in M2.

### Verification record

- `make test`: 30 Go tests and 25 frontend tests pass; TypeScript type check clean.
- Opt-in integration test passes against PostgreSQL 16.
- The binary alone, copied to an empty directory, serves the UI and round-trips
  `SELECT 1` with no Node.js and no loose assets present.
- Rejected as expected over HTTP: missing token, altered token, hostile
  `Origin`, and unknown `/api/*` path (JSON `404`, never `index.html`).
- Browser check: the page loads under the production CSP with zero console
  errors, and the token is removed from the address bar on load.
- `SIGTERM` shuts the server down cleanly within the grace period.

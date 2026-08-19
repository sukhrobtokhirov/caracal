# Database IDE

PostgreSQL and Redis in one free tool.

One Go binary serves a local web UI on `127.0.0.1`. No license wall, no Eclipse,
no installer.

> **Status: M0 (skeleton).** The architecture is proven end to end — a single
> binary, an embedded SPA, token-authenticated API, and a real PostgreSQL round
> trip — but there is no connection manager or SQL editor yet. See
> [`docs/mvp-steps/`](docs/mvp-steps/README.md) for the roadmap.

## Requirements

- Go 1.24 or newer
- Node.js 20 or newer (build time only — the released binary needs neither Node
  nor loose static files)

## Build and run

```sh
make build                      # builds the SPA and compiles bin/dbide
export DBIDE_DEV_POSTGRES_DSN='postgres://user:pass@127.0.0.1:5432/postgres?sslmode=disable'
./bin/dbide
```

The process prints a URL containing a one-time session token and opens it:

```
  Database IDE is running.
  Open: http://127.0.0.1:43127/?token=...
```

Useful flags:

| Flag | Effect |
|---|---|
| `--port N` | Bind a fixed port instead of a free one. The host is always `127.0.0.1`. |
| `--no-open` | Print the URL without launching a browser. |
| `--dev-origin URL` | Additionally accept one development origin (the Vite dev server). |
| `--verbose` | Debug logging. |

## Development

Two processes, two terminals:

```sh
make deps          # once
make dev-backend   # Go server on 127.0.0.1:8080, accepting the Vite origin
make dev-frontend  # Vite on http://localhost:5173, proxying /api to the backend
```

`make dev-backend` prints a launch URL for port 8080. Copy its `?token=...` and
open `http://localhost:5173/?token=<token>` so the SPA can claim a token while
Vite serves the assets.

```sh
make test          # Go tests, TypeScript type check, and frontend tests
make vet fmt
```

The PostgreSQL integration test is opt-in. Point it at a throwaway database:

```sh
docker run -d --name dbide-pg -e POSTGRES_PASSWORD=postgres -p 55432:5432 postgres:16-alpine
DBIDE_TEST_POSTGRES_DSN='postgres://postgres:postgres@127.0.0.1:55432/postgres?sslmode=disable' go test ./...
```

## Security model

This process holds live database credentials, so it is deliberately hostile to
anything that is not the page it served:

- The listener binds `127.0.0.1` only. No flag can widen it in v0.1.
- Every API request must carry the per-process session token (32 random bytes),
  compared in constant time. The token lives in memory, is stripped from the
  address bar on load, and never persists.
- Mutating requests must carry an `Origin` this server owns. This is what stops
  an arbitrary webpage from reaching the API through DNS rebinding.
- CORS is never enabled, and a restrictive Content Security Policy is applied.
- Credentials and connection strings never appear in logs or error responses.

## Layout

```
cmd/dbide/            executable entry point
internal/httpapp/     server, routes, middleware, JSON error envelope
internal/postgres/    PostgreSQL adapter and safe error classification
internal/webassets/   go:embed wrapper; Vite writes its build into dist/
internal/browser/     platform browser opener
web/                  React + TypeScript source
```

## License

Apache-2.0 (see [`db-ide-mvp-plan.md`](db-ide-mvp-plan.md) §8; the `LICENSE`
file lands in M5).

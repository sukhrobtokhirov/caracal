# Database IDE

PostgreSQL and Redis in one free tool.

One Go binary serves a local web UI on `127.0.0.1`. No license wall, no Eclipse,
no installer.

> **Status: M1 (connection manager).** You can save encrypted PostgreSQL and
> Redis connections that survive a restart, test them, and open and close live
> clients. There is no SQL editor or key browser yet — those are M2 and M3. See
> [`docs/mvp-steps/`](docs/mvp-steps/README.md) for the roadmap.

## Requirements

- Go 1.25 or newer (the toolchain is downloaded automatically if needed)
- Node.js 20 or newer (build time only — the released binary needs neither Node
  nor loose static files)

## Build and run

```sh
make build     # builds the SPA and compiles bin/dbide
./bin/dbide
```

On first run you choose a master password. It encrypts every database
credential you save and is never stored anywhere, so it cannot be recovered —
if you forget it, your saved passwords are gone.

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
| `--data-dir DIR` | Hold the configuration database somewhere other than the platform default. |
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

The database integration tests are opt-in. Point them at throwaway servers:

```sh
docker run -d --name dbide-pg -e POSTGRES_PASSWORD=postgres -p 55432:5432 postgres:16-alpine
docker run -d --name dbide-redis -p 56379:6379 redis:7-alpine redis-server --requirepass cachepass

DBIDE_TEST_POSTGRES_DSN='postgres://postgres:postgres@127.0.0.1:55432/postgres?sslmode=disable' \
DBIDE_TEST_REDIS_ADDR='127.0.0.1:56379' \
DBIDE_TEST_REDIS_PASSWORD='cachepass' \
  go test ./...
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
- Database clients are built from individual fields, never from an assembled URL
  containing a password.

### Stored credentials

Saved passwords are sealed on disk, not merely hidden:

- The master password is stretched with **Argon2id** (64 MiB, 3 passes) into a
  256-bit key. Only the salt, the cost parameters, and an encrypted verifier are
  stored — never the password or the key.
- Each credential is sealed with **AES-256-GCM** under a fresh 96-bit nonce, and
  the connection's identity is authenticated alongside it, so a ciphertext moved
  to another record fails to open rather than decrypting under the wrong server.
- The derived key exists only in the running process. Locking discards it and
  closes every live database client.
- Failed unlock attempts enter a widening cooldown.

This defends against someone reading the configuration file. It does not defend
against someone who already controls the running process.

## Layout

```
cmd/dbide/            executable entry point
internal/httpapp/     server, routes, middleware, JSON error envelope
internal/manager/     connection lifecycle: store + vault + registry
internal/connections/ connection domain types, defaults, validation
internal/store/       SQLite configuration database and migrations
internal/secrets/     Argon2id derivation, AES-GCM sealing, the vault
internal/registry/    live pgx pools and Redis clients
internal/postgres/    PostgreSQL adapter and safe error classification
internal/redisx/      Redis adapter and safe error classification
internal/appdata/     per-platform configuration directory
internal/webassets/   go:embed wrapper; Vite writes its build into dist/
internal/browser/     platform browser opener
web/                  React + TypeScript source
```

Configuration lives in the platform application data directory
(`~/Library/Application Support/dbide` on macOS, `%AppData%\dbide` on Windows,
`$XDG_DATA_HOME/dbide` or `~/.local/share/dbide` on Linux).

## License

Apache-2.0 (see [`db-ide-mvp-plan.md`](db-ide-mvp-plan.md) §8; the `LICENSE`
file lands in M5).

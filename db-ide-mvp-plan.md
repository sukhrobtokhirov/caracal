# Database IDE — MVP Plan

One free tool where PostgreSQL and Redis live side by side. Single binary, no license wall, no Eclipse.

Detailed implementation guides: [`docs/mvp-steps/`](docs/mvp-steps/README.md). Each M0–M5 milestone has its own feature specification, work packages, tests, and completion checklist.

---

## 0. Scope contract

Write this down and defend it. Scope creep is the main way this project dies.

**In scope (v0.1)**

- Connection manager for PG + Redis, encrypted credentials
- PG: schema tree, SQL editor, execute, result grid (read-only), CSV export
- Redis: SCAN-based key browser, type-aware value viewer, TTL display, raw command console
- Query history
- Cross-platform single binary

**Out of scope — v2 or later, no exceptions**

| Deferred | Why |
|---|---|
| Schema-aware autocomplete | Weeks of work; a plain SQL grammar highlighter is 90% of the benefit |
| Editing result rows in place | Needs primary-key detection, dirty tracking, transaction UI |
| ERD / diagram view | Impressive in screenshots, rarely used daily |
| Migrations, schema diff | Whole separate product |
| MySQL, Mongo, ClickHouse | Two engines is already the hard part |
| Multi-user / team sync | You are the only user for now |
| Redis Cluster / Sentinel | Standalone first; cluster changes the whole connection model |
| Query plan visualizer | `EXPLAIN` output in the grid is enough |

---

## 1. Architecture

```
┌─────────────────────────────────────────────┐
│  Browser (localhost:PORT?token=...)          │
│  React + TS SPA — served from embed.FS       │
└──────────────────┬──────────────────────────┘
                   │ HTTP/JSON
┌──────────────────▼──────────────────────────┐
│  Go binary                                   │
│  ├── http server (127.0.0.1 only)            │
│  ├── auth: session token + Origin check      │
│  ├── config store: SQLite (pure-Go driver)   │
│  ├── secret box: argon2id + AES-GCM          │
│  └── connection registry                     │
│      ├── pgx pools    (id → *pgxpool.Pool)   │
│      └── redis clients (id → *redis.Client)  │
└─────────────────────────────────────────────┘
```

**Why this shape:** cross-platform for free, no GUI toolkit to fight, one artifact to distribute, and you can run it next to a staging DB in Docker if you ever want to. You already know Go, so the backend is not the risky part — the frontend is.

### Tech choices

| Layer | Pick | Note |
|---|---|---|
| Backend | Go 1.22+ | `net/http` + `chi` is plenty; no framework needed |
| PG driver | `jackc/pgx/v5` | Native protocol, proper type handling, context cancellation |
| Redis client | `redis/go-redis/v9` | Pipelining, cursor helpers |
| Local store | `modernc.org/sqlite` | **Pure Go — no cgo**, so cross-compilation stays trivial |
| Frontend | React + TypeScript | Chosen for library availability, not elegance |
| Editor | CodeMirror 6 | Lighter than Monaco; `@codemirror/lang-sql` exists |
| Grid | TanStack Table + TanStack Virtual | Virtualization is non-negotiable |
| Build | Vite → `dist/` → `go:embed` | One `make build` produces the binary |

If you'd rather use Svelte, the only real loss is TanStack ecosystem maturity. Not fatal, but React is the lower-risk path here.

---

## 2. Security — do this on day one, not later

You are running an HTTP server on the developer's machine that holds live production database credentials. That is a genuinely attractive target.

1. **Bind to `127.0.0.1` explicitly.** Not `:0`, not `0.0.0.0`.
2. **Random session token** generated at startup, printed in the launch URL, required on every request. Without this, any webpage the user visits can `fetch()` your API and dump their database. This is a real attack class (DNS rebinding), not paranoia.
3. **Check the `Origin` header** on every mutating request. Reject anything unexpected.
4. **Never log credentials or full query text with parameters** at default verbosity.
5. **Credential encryption:** master password → argon2id → AES-GCM sealed secrets in SQLite. OS keychain (`zalando/go-keyring`) is nicer but adds platform quirks — do it in v2.

---

## 3. Local schema

```sql
CREATE TABLE connections (
  id            TEXT PRIMARY KEY,       -- uuid
  name          TEXT NOT NULL,
  engine        TEXT NOT NULL,          -- 'postgres' | 'redis'
  host          TEXT NOT NULL,
  port          INTEGER NOT NULL,
  database      TEXT,                   -- pg dbname | redis db index
  username      TEXT,
  secret_sealed BLOB,                   -- AES-GCM
  tls_mode      TEXT,                   -- disable|require|verify-full
  environment   TEXT NOT NULL DEFAULT 'dev',  -- dev|staging|prod
  read_only     INTEGER NOT NULL DEFAULT 0,
  color         TEXT,
  created_at    TIMESTAMP NOT NULL
);

CREATE TABLE query_history (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
  statement     TEXT NOT NULL,
  duration_ms   INTEGER,
  row_count     INTEGER,
  status        TEXT NOT NULL,          -- ok|error|cancelled
  error         TEXT,
  executed_at   TIMESTAMP NOT NULL
);
CREATE INDEX idx_history_conn_time ON query_history(connection_id, executed_at DESC);
```

`environment` and `read_only` are two columns that cost you an hour and prevent the worst day of your career. A red banner on prod connections and a confirm dialog on writes is the kind of thing paid tools charge for.

---

## 4. API surface

Keep it boring and small.

```
POST   /api/connections                 create
GET    /api/connections                 list (never returns secrets)
PUT    /api/connections/:id             update
DELETE /api/connections/:id
POST   /api/connections/:id/test        dial, auth, disconnect
POST   /api/connections/:id/open        establish pool
POST   /api/connections/:id/close

# Postgres
GET    /api/pg/:id/schemas
GET    /api/pg/:id/objects?schema=&kind=table|view|function
GET    /api/pg/:id/columns?schema=&table=
POST   /api/pg/:id/query                {sql, limit, offset}
POST   /api/pg/:id/cancel               {queryId}
GET    /api/pg/:id/export?queryId=      CSV stream

# Redis
GET    /api/redis/:id/info
GET    /api/redis/:id/scan?cursor=&match=&type=&count=
GET    /api/redis/:id/key?name=         type, ttl, memory usage
GET    /api/redis/:id/value?name=&cursor=&offset=&limit=
POST   /api/redis/:id/command           {args: []}
```

Skip WebSockets for the MVP. HTTP with server-side paging covers everything except live `MONITOR`, which is deferred anyway.

---

## 5. Where the time actually goes

The connection code is a day. These are the parts that will surprise you.

### 5.1 Splitting SQL scripts

A naive split on `;` **will** break. Postgres has dollar-quoting:

```sql
CREATE FUNCTION f() RETURNS void AS $$
  BEGIN ... ; ... ; END;
$$ LANGUAGE plpgsql;
```

You need a small lexer that tracks: single quotes, double quotes, `E''` escapes, `--` line comments, `/* */` nested block comments, and `$tag$ ... $tag$`. Write it early, unit-test it hard. Roughly 150 lines and it's the single highest-bug-density file in the project.

### 5.2 Type fidelity in the grid

JSON is lossy for Postgres types. Non-obvious ones:

- **`numeric`** — does not fit in a JS `number`. Send as string, always. Silent precision loss here destroys user trust.
- **`int8`/`bigint`** — same problem past 2^53.
- **NULL vs empty string** — must be visually distinct in the grid. Render NULL as a dimmed italic `NULL`.
- **`bytea`** — hex preview, never dump raw into the DOM.
- **`timestamptz`** — decide once whether you display in UTC or local, and label it.
- **arrays, `jsonb`, composite types, `uuid`, `inet`, ranges** — pgx gives you Go values; you need an explicit encoder per OID class with a string fallback.

Design the wire format as `{columns: [{name, typeOid, typeName}], rows: [[...]]}` with everything already stringified server-side except booleans and nulls. Fighting this later means rewriting the grid.

### 5.3 Not fetching 10 million rows

Default to a hard cap (1000 rows) with an explicit "fetch more" button. Two implementation options:

- **Simple:** iterate `pgx.Rows`, stop at the cap, close. Loses the rest.
- **Better:** `DECLARE cursor` inside a transaction, `FETCH FORWARD n`, keep the tx alive with an idle timeout. Real pagination, but you're now managing transaction lifetimes.

Start simple. Move to cursors when it annoys you.

### 5.4 Query cancellation

pgx sends a CancelRequest when the context is cancelled. Wire this from day one — assign each execution a `queryId`, hold its `context.CancelFunc`, expose `/cancel`. Without it, one bad query freezes a tab forever and the app feels broken.

### 5.5 Redis SCAN

The rule: **never `KEYS *`.** It blocks the server single-threadedly and will take down production. Half the distrust of Redis GUIs comes from tools that got this wrong.

Practical SCAN behaviour that catches people out:

- With `MATCH`, many iterations legitimately return **zero keys** while the cursor advances. You cannot treat empty as done — only `cursor == 0` means done.
- Loop server-side until you've collected N keys **or** burned M iterations, then return the cursor to the client. Never loop unbounded.
- `COUNT` is a hint, not a guarantee.
- Getting each key's type is a separate round trip — **pipeline** `TYPE` + `TTL` + `MEMORY USAGE` for the whole batch. Serial calls make a 200-key page take seconds.

### 5.6 Redis large values

Same discipline one level down. `HGETALL` on a million-field hash is `KEYS *` wearing a hat.

| Type | Paged read |
|---|---|
| string | `GETRANGE`, truncate + "show full" |
| hash | `HSCAN` |
| set | `SSCAN` |
| zset | `ZRANGE` with offsets (score-ordered, so real pagination) |
| list | `LRANGE` with offsets |
| stream | `XRANGE` with `COUNT` |

Auto-detect JSON in string/hash values and pretty-print it. Small feature, disproportionate daily value.

### 5.7 Dangerous command guard

The console should block by default, with an explicit override toggle: `FLUSHALL`, `FLUSHDB`, `KEYS`, `SHUTDOWN`, `DEBUG`, `CONFIG SET`, `MONITOR`, `SWAPDB`. On connections marked `prod`, require a typed confirmation.

---

## 6. Milestones

Sized for evenings and weekends alongside a full-time job. Each milestone ends in something you can actually use.

### M0 — Skeleton (week 1)
Go server on localhost with token auth, Vite frontend embedded via `go:embed`, one hardcoded PG connection, a button that runs `SELECT 1` and shows the result.
**Done when:** `make build` produces one binary that opens a browser and round-trips a query.

### M1 — Connection manager (week 2)
SQLite store, argon2id master password, sealed secrets, CRUD UI, test-connection button, environment tagging with colour.
**Done when:** you can add your real work connections and they survive a restart.

### M2 — Postgres read path (weeks 3–4)
Schema tree (lazy-loaded from `pg_catalog`), CodeMirror editor with SQL highlighting, the statement splitter, execute + cancel, virtualized grid with the type encoders, CSV export.
**Done when:** you run a real query from work in it instead of psql.

### M3 — Redis read path (weeks 5–6)
SCAN browser with pattern + type filter and prefix tree grouping, pipelined metadata, per-type paged value viewers, TTL display, `INFO` dashboard, command console with the danger guard.
**Done when:** you debug an actual cache issue in it instead of redis-cli.

### M4 — Polish (week 7)
Query history panel, keyboard shortcuts (Cmd/Ctrl+Enter to run, Cmd+K for connection switch), tab management, error surfacing with PG error position highlighting, dark theme, empty states.
**Done when:** nothing makes you wince during normal use.

### M5 — Release (week 8)
`goreleaser` + GitHub Actions building darwin/linux/windows × amd64/arm64, README with an animated GIF, LICENSE, CONTRIBUTING, issue templates, v0.1.0 tag.
**Done when:** a stranger can download a binary and connect to their database in under two minutes.

---

## 7. Definition of done

Not a feature checklist — a behaviour test:

> For one full working day, you used this instead of psql, redis-cli, and DBeaver, and never had to switch back.

If you switch back, note exactly why. That's your v0.2 backlog, written by the only user who matters right now.

---

## 8. Open-source mechanics

- **License: Apache-2.0.** Includes an explicit patent grant, which MIT lacks. Contributors and companies both prefer it. (Avoid SSPL — you're not defending a cloud business, and it would cost you adoption.)
- **Name it something searchable.** Check GitHub, npm, and crates before committing. A generic name means nobody finds it.
- **README leads with a GIF.** Nobody reads a feature list. Show the key browser and the result grid in five seconds.
- **State the positioning in one line:** "Postgres and Redis in one free tool." That's the sentence that gets it shared.
- Ship prebuilt binaries. Requiring `go install` cuts your audience by an order of magnitude.

---

## 9. Risks worth naming

| Risk | Mitigation |
|---|---|
| Frontend work is 70% of the effort and it's your weaker side | Budget accordingly; use component libraries aggressively, don't hand-roll a grid |
| Two engines double the surface before either is good | Ship PG-only as v0.1 if week 4 arrives and M2 isn't done. Redis in v0.2 is fine |
| Motivation dies at week 5 | M2 gives you a tool you personally use — that's the fuel for M3 |
| A data-loss bug destroys trust permanently | MVP is read-only for a reason. Keep it that way until the foundations are solid |

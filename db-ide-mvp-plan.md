# Caracal — MVP Plan

A desktop IDE where PostgreSQL and Redis live side by side. Kotlin and Compose Desktop, one installer per platform, no license wall.

The product category is DataGrip, DBeaver, and TablePlus: an application you launch from your dock and keep open all day, with a connection sidebar, editor tabs, a result grid, and a key browser. It is a native desktop application — Compose renders through Skia, there is no HTML, no webview, and no local HTTP server.

Detailed implementation guides: [`docs/mvp-steps/`](docs/mvp-steps/README.md). Each M0–M5 milestone has its own feature specification, work packages, tests, and completion checklist.

> **Stack history.** M0 and M1 were built in Go with a React SPA in a browser. The project moved to Kotlin/Compose on 2026-08-20 so it is written in the language its author is actually fluent in, and to reach JDBC's engine breadth. The Go implementation is preserved under the `go-implementation` tag. §10 records what carried over and what did not — the engine behavior in §5 is most of this document's value, and nearly all of it survived the move.

---

## 0. Scope contract

Write this down and defend it. Scope creep is the main way this project dies.

**In scope (v0.1)**

- Native desktop window from the first commit
- Connection manager for PG + Redis, encrypted credentials
- PG: schema tree, SQL editor, execute, result grid (read-only), CSV export
- Redis: SCAN-based key browser, type-aware value viewer, TTL display, raw command console
- Query history
- Cross-platform installers with a bundled runtime

**Out of scope — v2 or later, no exceptions**

| Deferred | Why |
|---|---|
| Schema-aware autocomplete | Weeks of work; a plain SQL grammar highlighter is 90% of the benefit |
| Editing result rows in place | Needs primary-key detection, dirty tracking, transaction UI |
| ERD / diagram view | Impressive in screenshots, rarely used daily |
| Migrations, schema diff | Whole separate product |
| MySQL, Mongo, ClickHouse | JDBC makes these cheap later; two engines is still the hard part now |
| Multi-user / team sync | You are the only user for now |
| Building on the IntelliJ Platform | It is how DataGrip exists, and it is a different, much larger project |
| Redis Cluster / Sentinel | Standalone first; cluster changes the whole connection model |
| Query plan visualizer | `EXPLAIN` output in the grid is enough |
| Auto-update, OS file associations, tray icon | Shell polish that only matters once people run it daily |

**The JDBC temptation.** Adding MySQL is now a driver dependency and a dialect check rather than a subsystem, which makes it exactly the kind of scope creep that feels free and is not. Every engine multiplies the type mapping, the error classification, the schema queries, and the test matrix. Two engines in v0.1. Revisit at v0.2 with evidence from real use.

---

## 1. Architecture

One process. One window. Two Gradle modules.

```
┌────────────────────────────────────────────────────────────┐
│  :app  —  Compose Desktop window (Skia)                    │
│                                                            │
│  ┌ connections ┬ SQL editor tabs ────────────────┐         │
│  │   sidebar   │ result grid · Redis key browser │         │
│  └─────────────┴─────────────────────────────────┘         │
│                                                            │
│  Kotlin + Compose · no HTML, no webview, no HTTP           │
└────────────────────────┬───────────────────────────────────┘
                         │ suspend function calls
                         │ (structured concurrency, real cancellation)
┌────────────────────────▼───────────────────────────────────┐
│  :core  —  plain JVM, zero Compose dependencies            │
│  ├── connections  domain types, defaults, validation       │
│  ├── vault        Argon2id + AES-GCM  (BouncyCastle)       │
│  ├── store        SQLite via sqlite-jdbc, migrations       │
│  ├── registry     HikariCP pools + Lettuce clients         │
│  ├── postgres     pgjdbc adapter, type mapping             │
│  └── redis        Lettuce adapter, SCAN + paged reads      │
└────────────────────────────────────────────────────────────┘
```

**Why this shape:** you write Kotlin, which is the single largest factor in whether this ships. Compose gives a real native window with no browser chrome and no webview to feed. JDBC means the second, third, and fourth SQL engine are cheap when you want them. `jpackage` bundles a trimmed JVM, so nobody installs Java to run it.

**The one hard rule: `:core` never depends on Compose.** Domain types, the vault, the store, the registry, and both engine adapters are plain JVM code, testable headlessly with JUnit and Testcontainers, with no window and no display server. This is what keeps the test suite fast and meaningful, and it is what the previous stack's HTTP boundary was buying — kept here as a module boundary that costs nothing at runtime.

**What the move deleted.** The old design served the UI over loopback HTTP, so it needed a session token, an `Origin` check, a CSP, and a defense against DNS rebinding. None of that exists any more: the UI calls suspend functions in the same process. An entire attack surface and roughly a milestone of work disappeared with the browser. §2 is short now for a real reason, not because the bar dropped.

### Tech choices

| Layer | Pick | Note |
|---|---|---|
| Language | Kotlin 2.x on JDK 25 (LTS) | Coroutines are the reason cancellation stays sane |
| UI | Compose Multiplatform 1.10+ for Desktop | Stable as of Jan 2026, with working hot reload |
| PG driver | `org.postgresql:postgresql` (pgjdbc) | `ResultSet` gives you `BigDecimal` and `Long` directly |
| Pooling | HikariCP | The default answer; do not hand-roll one |
| Redis client | Lettuce | Netty-based, async-native, maps cleanly onto coroutines |
| Local store | `org.xerial:sqlite-jdbc` | Bundles native libraries for every target platform |
| Argon2id | BouncyCastle `Argon2BytesGenerator` | Pure Java — no JNI, so packaging stays simple |
| AES-256-GCM | JCA (`AES/GCM/NoPadding`) | In the JDK; add no dependency for this |
| SQL editor | RSyntaxTextArea inside `SwingPanel` | See §5.8 — this is a deliberate compromise |
| Grid | Hand-built on `LazyColumn` | See §5.9 — this is the largest single frontend cost |
| Async | kotlinx.coroutines + virtual threads | JDBC blocks; virtual threads make that cheap on JDK 21+ |
| Build | Gradle (Kotlin DSL) + Compose plugin | `packageDistributionForCurrentOS` wraps `jpackage` |
| Tests | JUnit 5 + Testcontainers | Real PostgreSQL and Redis in CI, not mocks |

**On Jewel.** JetBrains' [Jewel](https://github.com/JetBrains/jewel) recreates the IntelliJ New UI look as Compose Desktop components, and it is usable outside the IDE. It is tempting, and it is the fastest route to looking like a JetBrains product. It is also explicitly in active development, its APIs change often, and it does not guarantee binary compatibility across releases — it now lives inside `intellij-community`. Evaluate it in M4 as a theming layer over components you already own. Do not build M0–M3 on top of it.

**Rejected.** Swing or JavaFX directly: you would spend the saved grid work on everything else instead. Eclipse RCP/SWT: it is what DBeaver is, and inheriting that shell is inheriting its weight. IntelliJ Platform: that is DataGrip, and it is a far larger project than this one.

---

## 2. Security

The HTTP threat model is gone with the browser. What remains is the part that always mattered: this process holds live production database credentials on disk and in memory.

1. **Credential encryption.** Master password → Argon2id → AES-256-GCM sealed secrets in SQLite. The derived key lives only in memory and is discarded on lock.
2. **Never log credentials, connection strings, Redis command arguments, or parameterized query values** at default verbosity. `SQLException` messages and JDBC URLs both leak; scrub before logging.
3. **Never build a JDBC URL containing a password.** Pass credentials as `Properties`, so they cannot end up in a log line, a stack trace, or a crash report.
4. **Zero secrets in crash reports and heap dumps you ship.** If you add crash reporting later, exclude the vault. Use `CharArray`/`ByteArray` for the master password and clear it after derivation rather than holding a `String` the GC may keep for hours.
5. **Treat production connections as visibly and behaviorally different.** A red banner and a typed confirmation are the cheapest insurance in this document.
6. **Enforce read-only in `:core`, not in the UI.** A disabled button is not a security boundary.
7. OS keychain integration (`credential-manager`-style, per platform) is nicer than a master password but adds platform quirks — v2.

---

## 3. Local schema

Unchanged from the original plan. The move to JDBC changed nothing here.

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

`environment` and `read_only` are two columns that cost you an hour and prevent the worst day of your career.

Use plain JDBC and hand-written migrations. Do not reach for JPA/Hibernate here: this is a handful of tables with no object graph, and an ORM would add startup cost and a mapping layer to a schema you can hold in your head. Exposed is a reasonable middle ground if you want type-safe SQL, but it is not required.

---

## 4. Internal API surface

There is no network API any more. What was a list of HTTP endpoints is now a set of `:core` interfaces the UI calls directly. Keep them boring and small — and keep them `suspend`, so cancellation propagates from a closed tab down to `Statement.cancel()` without any plumbing of your own.

```kotlin
interface ConnectionService {
    suspend fun list(): List<ConnectionSummary>          // never returns secrets
    suspend fun create(draft: ConnectionDraft): Connection
    suspend fun update(id: ConnectionId, draft: ConnectionDraft): Connection
    suspend fun delete(id: ConnectionId)
    suspend fun test(id: ConnectionId): TestResult        // dial, auth, disconnect
    suspend fun open(id: ConnectionId)
    suspend fun close(id: ConnectionId)
}

interface PostgresService {
    suspend fun schemas(id: ConnectionId): List<Schema>
    suspend fun objects(id: ConnectionId, schema: String, kind: ObjectKind): List<DbObject>
    suspend fun columns(id: ConnectionId, schema: String, table: String): List<Column>
    suspend fun execute(id: ConnectionId, sql: String, limit: Int): QueryResult
    fun exportCsv(result: QueryResult, sink: Path): Flow<ExportProgress>
}

interface RedisService {
    suspend fun info(id: ConnectionId): RedisInfo
    suspend fun scan(id: ConnectionId, cursor: String, match: String?, type: KeyType?, count: Int): ScanPage
    suspend fun key(id: ConnectionId, name: String): KeyMetadata      // type, ttl, memory
    suspend fun value(id: ConnectionId, name: String, page: ValuePage): ValueSlice
    suspend fun command(id: ConnectionId, args: List<String>): CommandResult
}
```

Two rules that keep this honest:

- **The UI never touches a `Connection`, `ResultSet`, or Lettuce command.** It receives domain types. A `ResultSet` that escapes `:core` is a resource leak waiting for a lazy `LazyColumn` to scroll.
- **Cancellation is structured, not bolted on.** A coroutine scope per editor tab means closing the tab cancels the query. §5.4 covers what that has to do at the JDBC level.

Long-running or streaming work (`SCAN` pages, CSV export progress) returns a `Flow`. Everything else is a plain `suspend fun`.

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

You need a small lexer that tracks: single quotes, double quotes, `E''` escapes, `--` line comments, `/* */` nested block comments, and `$tag$ ... $tag$`. Write it early, unit-test it hard. Roughly 150 lines of Kotlin and it is the single highest-bug-density file in the project. It is also pure, dependency-free, and trivially testable — write it first, on a train, with no database in sight.

### 5.2 Type fidelity in the grid

**This got dramatically easier, and it is the best thing JDBC did for this project.** The old design serialized results to JSON, which is lossy: `numeric` and `int8` had to be stringified server-side or silently lose precision, and the wire format had to be designed around that. There is no wire format now. `ResultSet.getObject` hands you a `BigDecimal` for `numeric` and a `Long` for `int8`, and they stay that way all the way into the cell renderer.

What still needs deliberate handling:

- **NULL vs empty string** — must be visually distinct. Render NULL as a dimmed italic `NULL`. `ResultSet.wasNull()` is how you tell, and it is easy to forget after `getString`.
- **`bytea`** — hex preview with a size cap, never the whole array in a cell.
- **`timestamptz`** — pgjdbc gives you `OffsetDateTime`. Decide once whether you display UTC or local, and label it in the column header.
- **arrays, `jsonb`, composite types, ranges** — pgjdbc returns `PGobject` or `java.sql.Array`. Write an explicit renderer per class with a `toString` fallback, and never let an unknown type crash the grid.
- **`uuid`, `inet`** — fine by default, but confirm rather than assume.

Model a cell as a sealed interface (`CellValue.Null`, `CellValue.Text`, `CellValue.Number`, `CellValue.Binary`, …) rather than `Any?`. The grid renderer then has an exhaustive `when`, and adding a type becomes a compile error instead of a rendering bug.

### 5.3 Not fetching 10 million rows

Default to a hard cap (1000 rows) with an explicit "fetch more" button.

JDBC specifics that bite:

- **`Statement.setFetchSize` does nothing on pgjdbc in autocommit mode.** The driver buffers the entire result set in heap regardless. You must disable autocommit *and* set a fetch size to get real streaming — otherwise a careless `SELECT *` becomes an `OutOfMemoryError` rather than a slow query.
- Set `setMaxRows` as a belt-and-braces cap alongside the application-level limit.
- Close `ResultSet`, `Statement`, and the pooled `Connection` deterministically. Kotlin's `use {}` makes this easy; a `Flow` that outlives the connection makes it impossible. Materialize the capped page inside `:core` and hand the UI a plain list.

Start simple: read up to the cap, close everything, return. Move to a held transaction with a cursor when it annoys you.

### 5.4 Query cancellation

This is where coroutines earn their place, and also where they mislead you.

Cancelling a coroutine does **not** cancel a blocking JDBC call. The pattern that actually works:

- Run the query on `Dispatchers.IO` (backed by virtual threads on JDK 21+, so a blocked thread is cheap).
- Keep a reference to the `Statement`.
- Install a cancellation handler that calls `statement.cancel()` from a *different* thread — pgjdbc opens a side connection and issues a real `CancelRequest`.
- Also set `setQueryTimeout` as a backstop for the case where the user never cancels.

Wire this from day one. Without it, one bad query freezes a tab forever and the app feels broken.

### 5.5 Redis SCAN

The rule: **never `KEYS *`.** It blocks the server single-threadedly and will take down production. Half the distrust of Redis GUIs comes from tools that got this wrong.

Practical SCAN behaviour that catches people out:

- With `MATCH`, many iterations legitimately return **zero keys** while the cursor advances. You cannot treat empty as done — only `cursor == 0` means done.
- Loop inside `:core` until you have collected N keys **or** burned M iterations, then return the cursor to the caller. Never loop unbounded.
- `COUNT` is a hint, not a guarantee.
- Getting each key's type is a separate round trip — **pipeline** `TYPE` + `TTL` + `MEMORY USAGE` for the whole batch. Lettuce's async API returns futures you can await together with `awaitAll`; serial calls make a 200-key page take seconds.

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

### 5.8 The SQL editor

Compose has no code editor. This is the first thing the stack move cost you, and it is worth being clear-eyed about the options:

- **`BasicTextField` + `VisualTransformation`.** Pure Compose, no interop. Re-highlights the whole document on every keystroke, so it degrades on large scripts. Fine for a 20-line query, painful for a 2000-line migration file someone pastes in.
- **RSyntaxTextArea inside `SwingPanel`.** A mature Swing editor — syntax highlighting for SQL, code folding, find/replace, bracket matching — embedded through Compose's Swing interop. This is the recommended path: it is a known pattern, and it buys you an editor that is already better than what you would build in three weekends.

The interop costs are real and you should know them before committing: `SwingPanel` renders in a separate layer, so Compose content cannot reliably overlap it — a dropdown or dialog drawn over the editor will clip. Keep autocomplete popups, tooltips, and modals out of the editor's rectangle, or accept Swing components for them too. Theme sync is manual: the Swing editor will not follow your Compose dark theme unless you push colors into it.

Prototype this in M2 before building around it. If interop proves worse than the highlighting cost, fall back to `BasicTextField` with a cap on the highlighted region.

### 5.9 The result grid

Compose has no DataGrid. You are building one, and this is the largest single piece of UI work in the project.

`LazyColumn` gives you vertical virtualization. It does not give you:

- synchronized horizontal scrolling between a frozen header and the body;
- column resize and reorder;
- cell selection, range selection, and copy-as-TSV;
- stable column widths measured from a sample of rows rather than all of them;
- horizontal virtualization for a 200-column result.

Budget this properly — it is weeks, not evenings, and it is the risk that most deserves a spike before M2 starts. Measure with 100k rows and 50 columns early; a grid that is smooth at 1k rows and janky at 100k is the normal failure.

Do not skip the copy path. A grid you cannot copy out of fails the definition of done in §7 on day one.

---

## 6. Milestones

Sized for evenings and weekends alongside a full-time job. Each milestone ends in something you can actually use.

The desktop shell is not a milestone any more. Compose gives you a window in the first hour, so M0 opens one and every milestone after it is a real application rather than a browser tab with a promise attached.

### M0 — Skeleton (week 1)
Gradle build, Compose window, one hardcoded PG connection via JDBC, a button that runs `SELECT 1` and shows the result, `packageDistributionForCurrentOS` producing an installer.
**Done when:** a built installer on a clean machine opens a window and round-trips a query with no JDK installed.

### M1 — Connection manager (week 2)
SQLite store, Argon2id master password, sealed secrets, CRUD UI, test-connection button, environment tagging with colour.
**Done when:** you can add your real work connections and they survive a restart.

### M2 — Postgres read path (weeks 3–5)
Schema tree (lazy-loaded from `pg_catalog`), the statement splitter, the SQL editor from §5.8, execute + cancel, the virtualized grid from §5.9, CSV export.
**Done when:** you run a real query from work in it instead of psql.

> This milestone is a week longer than the original plan. The grid and the editor were library dependencies before and are your code now. Pretending otherwise just moves the slip to week 5.

### M3 — Redis read path (weeks 6–7)
SCAN browser with pattern + type filter and prefix tree grouping, pipelined metadata, per-type paged value viewers, TTL display, `INFO` dashboard, command console with the danger guard.
**Done when:** you debug an actual cache issue in it instead of redis-cli.

### M4 — Polish (week 8)
Query history panel, keyboard shortcuts, tab management, native menus, window state persistence, error surfacing with PG error position highlighting, dark theme (evaluate Jewel here), empty states.
**Done when:** nothing makes you wince during normal use.

### M5 — Release (week 9)
GitHub Actions building `.dmg`, `.msi`, and `.deb` on their own runners, README with an animated GIF, LICENSE, CONTRIBUTING, issue templates, v0.1.0 tag.
**Done when:** a stranger can download an installer and connect to their database in under two minutes.

---

## 7. Definition of done

Not a feature checklist — a behaviour test:

> For one full working day, you used this instead of psql, redis-cli, and DBeaver — as the application you left open in your dock, not a tab you kept losing — and never had to switch back.

If you switch back, note exactly why. That's your v0.2 backlog, written by the only user who matters right now.

---

## 8. Open-source mechanics

- **License: Apache-2.0.** Includes an explicit patent grant, which MIT lacks. Contributors and companies both prefer it. Review the licenses of pgjdbc (BSD-2), Lettuce, HikariCP (Apache-2.0), sqlite-jdbc (Apache-2.0), BouncyCastle (MIT-like), and RSyntaxTextArea (BSD-3) during M5, and ship the required notices. **Done, and two entries here were wrong:** Lettuce 7.x is MIT, not Apache-2.0, and RSyntaxTextArea is not a dependency at all — the SQL editor is Compose code. The list that ships is [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md), compiled from what the build resolves, and it includes the one this section never mentioned: the bundled Java runtime, GPLv2 with the Classpath Exception.
- **Name it something searchable.** Check GitHub, Maven Central, and general search before committing. A generic name means nobody finds it.
- **README leads with a GIF.** Nobody reads a feature list. Show the key browser and the result grid in five seconds, in the application window with no browser chrome in frame.
- **State the positioning in one line:** "A free desktop IDE for Postgres and Redis." Say *desktop IDE*, not *tool* — the category is what tells someone in four words whether to click.
- Ship installers, not a jar. `java -jar` cuts your audience by an order of magnitude, and the whole point of `jpackage` is that nobody needs a JDK.
- **Be honest about download size.** A bundled runtime puts installers somewhere around 60–100 MB. State it rather than letting people discover it.

---

## 9. Risks worth naming

| Risk | Mitigation |
|---|---|
| The grid and editor are now your code, not dependencies | This is the defining risk of the stack choice. Spike both before M2 starts; M2 is budgeted three weeks for exactly this reason |
| `SwingPanel` interop constrains the editor's UI | Prototype overlap behaviour early. Keep popups outside the editor rectangle or fall back to `BasicTextField` |
| Motivation dies re-implementing what already worked in Go | M0 and M1 are a known problem in a new language — the fastest part of the rewrite. Get to M2 quickly, where the new work is |
| JVM startup and memory make it feel like DBeaver | Measure cold start from M0 and treat it as a budget, not an outcome. `jlink` a minimal runtime; keep the vault derivation off the startup path until unlock |
| pgjdbc buffers a huge result set into heap | §5.3 — autocommit off plus fetch size, and `setMaxRows` as a backstop. Test with a deliberately enormous table before M2 closes |
| Frontend work is most of the effort and it's your weaker side | Unchanged by the stack move, and slightly worse. Budget accordingly |
| Jewel's API churn breaks your UI | Do not build on it. Evaluate it in M4 as theming over components you already own |
| Two engines double the surface before either is good | Ship PG-only as v0.1 if week 6 arrives and M2 isn't done. Redis in v0.2 is fine |
| A data-loss bug destroys trust permanently | MVP is read-only for a reason. Keep it that way until the foundations are solid |

---

## 10. Stack move record — 2026-08-20

**Carried over unchanged.** §0 scope contract, §3 local schema, §5.1 SQL splitting, §5.5–5.7 Redis behaviour, §7 definition of done, §8 open-source mechanics. This is the majority of the plan's value: it is product and engine reasoning, not language reasoning.

**Simplified by the move.**

| Was | Now |
|---|---|
| HTTP API, session token, `Origin` check, CORS, CSP, DNS-rebinding defense | Deleted. The UI calls suspend functions in-process |
| JSON wire format with stringified `numeric`/`int8` | Deleted. `BigDecimal` and `Long` reach the cell renderer intact |
| A separate M5 milestone to acquire a native window | Deleted. Compose opens one in M0 |
| `context.Context` plumbed through every layer | Structured concurrency; a tab's scope cancels its query |
| Two-language build (Go + Node), two test runners | One language, one build, one test runner |

**Made harder by the move.**

| Was | Now |
|---|---|
| CodeMirror 6 | RSyntaxTextArea via Swing interop, with the constraints in §5.8 |
| TanStack Table + TanStack Virtual | A grid you build on `LazyColumn` — §5.9 |
| ~20 MB pure-Go binary, trivially cross-compiled | ~60–100 MB installer with a bundled runtime, built per platform |
| Instant cold start | JVM startup, which needs measuring and budgeting |

**Unchanged in difficulty.** Everything in §5.2 through §5.7 that is really about databases rather than about languages.

The Go implementation is tagged `go-implementation`. Do not delete the tag: the M1 vault design and its test cases are worth reading while rebuilding them in Kotlin.

# Database IDE

A desktop IDE for PostgreSQL and Redis.

One application window where both engines sit side by side — the daily workflow
of DataGrip or DBeaver without the license wall. Kotlin and Compose Desktop,
rendered natively through Skia. No browser, no webview, no local web server, and
no JDK to install: the installer bundles its own trimmed runtime.

> **Status: rebuilding on Kotlin.** M0 and M1 shipped in Go with a React SPA in a
> browser window. On 2026-08-20 the project moved to Kotlin + Compose Desktop —
> the language its author actually writes, and the route to JDBC's engine
> breadth. That Go implementation is preserved under the `go-implementation` git
> tag; the plan's [§10](db-ide-mvp-plan.md#10-stack-move-record--2026-08-20)
> records exactly what the move simplified and what it made harder.
>
> Kotlin **M0 through M3 are done**: a packaged window opens an encrypted
> connection manager, where PostgreSQL and Redis connections are saved under a
> master password, survive a restart, and reconnect on unlock. A PostgreSQL
> connection then opens a schema browser, a SQL editor, a virtualized result grid,
> and CSV export; a Redis connection opens a bounded `SCAN` key browser with
> prefix grouping, a paged viewer for each of the six value types, an `INFO`
> summary, and a command console behind the dangerous-command guard. **M4, polish,
> is done** — query history is readable, paged, and reopenable; SQL work lives in
> tabs that keep their own connection, query, and result, and that will not close on
> unsaved work or a running statement without asking; and the keyboard reaches all of
> it, including a connection switcher that will not let Enter dial production; and a
> failure is shown where it happened, with the character PostgreSQL pointed at
> underlined in the editor — and un-underlined, with a sentence saying why, the
> moment the script moves out from under it. Every pane that waits on a server says
> what it is waiting for, every empty one says how to fill it, and a connection that
> drops says your open scripts are still here and offers the button that brings them
> back. Light, dark, and system are one set of named tokens rather than a colour per
> component, every readable pair in both of them is held to WCAG AA by a test that
> does the arithmetic, and the window no longer opens with a frame of white before it
> remembers it is dark. The keyboard reaches both trees and both tab strips by their
> arrow keys, a dialog takes the caret when it opens and gives it back when it goes,
> a query that finishes announces how it ended without reading the result out, and at
> 200% on a small laptop the panes give way rather than pushing Run off the edge.
> Twenty open tabs hold eight results between them and say so where the other twelve
> were, rather than holding twenty and hoping. What is left of M4 is the part no test
> can sign off: [its exit criterion](docs/mvp-steps/04-product-polish.md#exit-criterion)
> is a day of real use. See [`docs/mvp-steps/`](docs/mvp-steps/README.md).

## The shape of the product

| | |
|---|---|
| **Delivery** | A native installer per platform — `.dmg`, `.msi`, `.deb`. Install, launch, connect. |
| **Interface** | A desktop window: connection sidebar, editor tabs, result grid, key browser. |
| **Engines** | PostgreSQL and Redis in the same workspace, not two separate tools. |
| **Data** | Everything is local. Credentials are encrypted on disk under a master password. |
| **Scope** | Read-only by default, deliberately. A connection can be marked writable; writes are then gated by a confirmation, typed out on `prod`. See [`db-ide-mvp-plan.md`](db-ide-mvp-plan.md) §0. |

There is no hosted service, no multi-user mode, and no network listener. The UI
calls suspend functions in the same process as the database code.

## Stack

| Layer | Choice |
|---|---|
| Language | Kotlin 2.x on JDK 25 (LTS) |
| UI | Compose Multiplatform for Desktop |
| PostgreSQL | pgjdbc + HikariCP |
| Redis | Lettuce |
| Local store | SQLite via `sqlite-jdbc` |
| Crypto | Argon2id (BouncyCastle) + AES-256-GCM (JCA) |
| Build | Gradle (Kotlin DSL), packaged with `jpackage` |
| Tests | JUnit 5 + Testcontainers |

## Requirements

Users of a released build need nothing installed — the installer carries its own
runtime. To build from source:

- JDK 25 (or 21+); the Gradle toolchain resolves it if configured
- Docker, only for the opt-in integration tests

## Build and run

```sh
./gradlew :app:run                              # launch from source
./gradlew :app:packageDistributionForCurrentOS  # installer for this platform
```

On first run you choose a master password. It encrypts every database credential
you save and is never stored anywhere, so it cannot be recovered — if you forget
it, your saved passwords are gone.

To keep development runs off your real configuration, point them at a scratch
directory:

```sh
DBIDE_DATA_DIR=/tmp/dbide ./gradlew :app:run
```

If the checkout lives in a folder synced by iCloud Drive, packaging needs an
output directory outside it — `codesign` rejects the xattr the sync attaches:

```sh
./gradlew :app:packageDistributionForCurrentOS -Pdbide.distributionsDir=/tmp/dbide
```

`jpackage` cannot cross-compile. Each platform's installer is built on that
platform; CI does this on three runners.

## Development

```sh
./gradlew :core:test     # headless — no display server, no window
./gradlew check          # everything, including Compose UI tests and the module-boundary check
./gradlew :app:run       # launch from source
```

`:core` has no Compose dependency and never will — `:core:assertNoComposeDependency`
fails the build if one appears, and `:core`'s tests run with AWT headless so a
window can never quietly become a requirement. Domain types, the vault, the
store, the connection registry, and both engine adapters are plain JVM code that
tests headlessly. If a test needs a window to run, it belongs in `:app`.

Integration tests use Testcontainers and are opt-in, because they need Docker:

```sh
DBIDE_INTEGRATION=1 ./gradlew :core:test
```

Opt-in locally, mandatory in CI. `.github/workflows/ci.yml` runs `check` on Linux,
macOS, and Windows, and sets `DBIDE_INTEGRATION=1` on the Linux runner — the only
one of the three with a Docker daemon. Installers are built by the same workflow
on all three, for a `v*` tag or on request.

## Security model

The previous design served its UI over loopback HTTP, which needed a session
token, an `Origin` check, and a defense against DNS rebinding. A native window
needs none of that, and it is all gone. What remains is the part that always
mattered — this process holds live database credentials.

- Credentials are sealed on disk under a key derived from the master password.
- Connection strings, credentials, Redis command arguments, and parameterized
  query values never appear in logs or error messages.
- JDBC URLs are never assembled with a password in them; credentials are passed
  as `Properties` so they cannot leak into a stack trace.
- Read-only and production guards are enforced in `:core`. A disabled button is
  not a security boundary: a connection marked read only opens its whole pool in a
  PostgreSQL `READ ONLY` transaction, so a write is refused by the server whether it
  arrived as an `UPDATE`, inside a CTE, or inside a function body compiled last year.
- New connections are read only until someone unticks the box, and a writable
  connection asks before it runs a statement that modifies data — on `prod`, by
  making you type the connection's name.
- The database user's own grants remain the real boundary. A read-only role is
  still the right way to browse production.
- Production-tagged connections are visibly and behaviorally distinct.
- Every execution is recorded in query history — the statement as submitted, how it
  ended, and a message that has already been redacted. It is kept in the same
  owner-only database as the sealed credentials, and never written to a log: a
  `WHERE email = '…'` is a record of a person as much as of a query. History is
  capped per connection and goes with the connection when it is deleted. Reading it
  back needs the vault open, and locking discards whatever the panel had loaded.

### Stored credentials

- The master password is stretched with **Argon2id** (64 MiB, 3 passes) into a
  256-bit key. Only the salt, the cost parameters, and an encrypted verifier are
  stored — never the password or the key.
- Each credential is sealed with **AES-256-GCM** under a fresh 96-bit nonce, and
  the connection's identity is authenticated alongside it, so a ciphertext moved
  to another record fails to open rather than decrypting under the wrong server.
- The derived key exists only in the running process. Locking discards it and
  closes every live pool and client.
- The master password is held as a `CharArray` and cleared after derivation, not
  left as a `String` for the GC to keep.
- Failed unlock attempts enter a widening cooldown.

This defends against someone reading the configuration file. It does not defend
against someone who already controls the running process.

## Layout

```
core/src/main/kotlin/dev/dbide/core/
  postgres/      pgjdbc adapter, pooling, error classification, redaction   [M0]
  result/        QueryResult, CellValue, DbError                            [M0]
  catalog/       schemas, objects, and columns, as the browser sees them    [M2]
  connections/   connection domain types, validation, ConnectionService     [M1]
  vault/         Argon2id derivation, AES-GCM sealing                       [M1]
  store/         SQLite configuration database and migrations               [M1]
  registry/      live HikariCP pools and Lettuce clients                    [M1]
  redis/         Lettuce adapter: bounded SCAN, paged values, command guard    [M3]
  appdata/       platform configuration directory                           [M1]
  sql/           statement splitter, editor execution rule, highlighting    [M2]
  policy/        what a statement may do, and what must be agreed to first  [M2]
  export/        CSV writing, export eligibility, bounded streaming         [M2]
  history/       what was executed, how it ended, and how much is kept   [M2, M4]

app/src/main/kotlin/dev/dbide/app/
  ui/            connection screens [M1]; schema tree, SQL editor, result grid [M2];
                 key browser, value viewers, INFO dashboard, console          [M3];
                 query history, theme and contrast tokens                     [M4]
  Main.kt        window, application lifecycle
```

Query history is written from M2 and read from M4. **History** in the shell opens
it: newest first, grouped by day, filtered by connection and by how each execution
ended, and paged further back on request. An entry can be copied or reopened into
the editor — reopening never runs anything, and it asks before replacing a script
you are in the middle of. Clearing names its own scope and never touches the
connections. Redis console commands are deliberately not recorded — a command's
arguments are where its secrets are, so the console's history lives in memory for
one session and is discarded when the vault locks.

Configuration lives in the platform application data directory
(`~/Library/Application Support/dbide` on macOS, `%AppData%\dbide` on Windows,
`$XDG_DATA_HOME/dbide` or `~/.local/share/dbide` on Linux), in a SQLite file
created owner-only. `DBIDE_DATA_DIR` overrides it.

## License

Apache-2.0 (see [`db-ide-mvp-plan.md`](db-ide-mvp-plan.md) §8; the `LICENSE`
file lands in M5).

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
> Kotlin **M0 and M1 are done**: a packaged window opens an encrypted connection
> manager, where PostgreSQL and Redis connections are saved under a master
> password, survive a restart, and reconnect on unlock. M2 — the PostgreSQL read
> path — is next. See [`docs/mvp-steps/`](docs/mvp-steps/README.md).

## The shape of the product

| | |
|---|---|
| **Delivery** | A native installer per platform — `.dmg`, `.msi`, `.deb`. Install, launch, connect. |
| **Interface** | A desktop window: connection sidebar, editor tabs, result grid, key browser. |
| **Engines** | PostgreSQL and Redis in the same workspace, not two separate tools. |
| **Data** | Everything is local. Credentials are encrypted on disk under a master password. |
| **Scope** | Read-only in v0.1, deliberately. See [`db-ide-mvp-plan.md`](db-ide-mvp-plan.md) §0. |

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
  not a security boundary.
- Production-tagged connections are visibly and behaviorally distinct.

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
  connections/   connection domain types, validation, ConnectionService     [M1]
  vault/         Argon2id derivation, AES-GCM sealing                       [M1]
  store/         SQLite configuration database and migrations               [M1]
  registry/      live HikariCP pools and Lettuce clients                    [M1]
  redis/         Lettuce adapter; SCAN and paged value reads arrive in M3  [M1]
  appdata/       platform configuration directory                           [M1]
  sql/           statement splitter                                         [M2]

app/src/main/kotlin/dev/dbide/app/
  ui/            Compose screens, grid, editor, key browser
  Main.kt        window, application lifecycle
```

Directories marked with an unshipped milestone are placeholders. `sql/` is the
exception: it is the first piece of M2 to land.

Configuration lives in the platform application data directory
(`~/Library/Application Support/dbide` on macOS, `%AppData%\dbide` on Windows,
`$XDG_DATA_HOME/dbide` or `~/.local/share/dbide` on Linux), in a SQLite file
created owner-only. `DBIDE_DATA_DIR` overrides it.

## License

Apache-2.0 (see [`db-ide-mvp-plan.md`](db-ide-mvp-plan.md) §8; the `LICENSE`
file lands in M5).

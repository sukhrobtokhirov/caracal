<p align="center">
  <img src="branding/caracal.png" alt="" width="128">
</p>

# Caracal

**A free desktop IDE for Postgres and Redis.**

One native window where both engines sit side by side — the daily workflow of
DataGrip or DBeaver without the license wall. No browser, no webview, no local web
server, and no JDK to install: the installer carries its own trimmed runtime.

<!--
  The demo GIF goes here, immediately under the pitch, before anything else:
  five seconds showing the connection list, a Redis key browsed, a SQL statement
  run, and the result grid — in the application window, with no browser chrome
  anywhere in frame, and with synthetic connection names, hostnames, and data.
  Recording it is a blocking step in docs/RELEASING.md; it is deliberately not a
  broken <img> until then.
-->

## What it does

- **PostgreSQL** — a schema browser read lazily from `pg_catalog`, a SQL editor
  that splits and runs scripts, cancel that actually cancels, a virtualized result
  grid that keeps `numeric` and `int8` at full precision, and CSV export.
- **Redis** — a bounded `SCAN` key browser with prefix grouping and type filters,
  paged viewers for all six value types with TTL, an `INFO` dashboard, and a
  command console that refuses the commands that stop a server.
- **Both at once** — one window, one connection list, one set of shortcuts. Not
  two tools that happen to be installed on the same machine.
- **Read-only until you say otherwise.** A connection is read-only when you create
  it. Marking it writable makes a write ask first — and on a production-tagged
  connection, ask you to type the connection's name.
- **Tabs and history.** SQL work lives in tabs that keep their own connection,
  query, and result, and will not close on unsaved work without asking. Every
  execution is recorded, searchable, and reopenable without running anything.
- **Local, and only local.** No account, no telemetry, no network listener.
  Credentials are sealed on disk under a master password.

## Download

Installers for the current release are on the
[releases page](https://github.com/sukhrobtokhirov/caracal/releases/latest).

| Platform | File | Size |
|---|---|---|
| macOS (Apple Silicon) | `caracal_0.1.0_macos_arm64.dmg` | ~105 MB |
| macOS (Intel) | `caracal_0.1.0_macos_x86_64.dmg` | ~105 MB |
| Linux (x86-64) | `caracal_0.1.0_linux_x86_64.deb` | ~100 MB |
| Linux (ARM64) | `caracal_0.1.0_linux_arm64.deb` | ~100 MB |
| Windows (x86-64) | `caracal_0.1.0_windows_x86_64.msi` | ~100 MB |

They are that size because each one contains a trimmed Java runtime, which is the
reason you do not have to install one. Windows on ARM has no build of its own yet;
the x86-64 installer runs there under emulation.

Every release also publishes `checksums.txt`. Verify before installing:

```sh
sha256sum --check --ignore-missing checksums.txt
```

On macOS that is `shasum -a 256 --check --ignore-missing checksums.txt`, and on
Windows `Get-FileHash caracal_0.1.0_windows_x86_64.msi -Algorithm SHA256`.

> v0.1.0 has not been tagged yet, so the links above are where it will be rather
> than where it is. Until then, [build from source](#build-from-source).

## Run it

The installers are **not signed**. Signing certificates cost money annually, and
this project has none yet, so macOS and Windows will both warn you the first time.
The steps below are the standard ones for unsigned software — they are not a way
around a security check, and you should be as suspicious of them as you would be
of any download.

**macOS.** Open the `.dmg`, drag Caracal to Applications, and launch it. Gatekeeper
will refuse it on the first attempt ("Apple could not verify…"). Open **System
Settings → Privacy & Security**, scroll to the message naming Caracal, and choose
**Open Anyway**. macOS asks once; every later launch is normal. Do not disable
Gatekeeper globally.

**Linux.** Install the package and launch Caracal from your applications menu:

```sh
sudo apt install ./caracal_0.1.0_linux_x86_64.deb
```

The `.deb` files are built on Ubuntu 24.04, so they need glibc 2.39 or newer —
Ubuntu 24.04, Debian 13, or anything more recent. Older distributions should
[build from source](#build-from-source).

**Windows.** Run the `.msi`. SmartScreen will show "Windows protected your PC"
because the installer is unsigned; choose **More info → Run anyway** if you are
satisfied the checksum matches what the release page publishes.

To check which build you have without opening the window:

```sh
caracal --version
```

On macOS that is `/Applications/Caracal.app/Contents/MacOS/Caracal --version`. The
same three facts are in **Settings → About** inside the application.

## Your first connection

1. Launch Caracal. The first thing the window asks for is a **master password**,
   which you choose now. It encrypts every database credential you save.
   **It is never stored anywhere and cannot be recovered** — if you forget it, the
   saved passwords are gone and the connections have to be entered again.
2. **New connection** → pick PostgreSQL or Redis, fill in host, port, database, and
   user, and tag the environment. **Test** tells you whether it works before you
   save it.
3. Save it, then open it. A PostgreSQL connection opens the schema browser and an
   editor tab; a Redis connection opens the key browser.
4. Run a statement with `Cmd/Ctrl+Enter`, or browse a key. `Cmd/Ctrl+K` switches
   connections; `Cmd/Ctrl+/` lists every shortcut.

New connections are read-only. Untick that in the connection dialog when you
actually need to write, and expect to be asked before each write.

## Security model

Caracal has no server, no network listener, no telemetry, and no account. What it
does have is live database credentials, which is what the following protects.

- Credentials are sealed on disk under a key derived from the master password.
- Connection strings, credentials, Redis command arguments, and parameterized
  query values never appear in logs or error messages.
- JDBC URLs are never assembled with a password in them; credentials are passed
  as `Properties` so they cannot leak into a stack trace.
- Read-only and production guards are enforced in the core, not in the UI. A
  disabled button is not a security boundary: a connection marked read-only opens
  its whole pool in a PostgreSQL `READ ONLY` transaction, so a write is refused by
  the server whether it arrived as an `UPDATE`, inside a CTE, or inside a function
  body compiled last year.
- The database user's own grants remain the real boundary. A read-only role is
  still the right way to browse production.
- Production-tagged connections are visibly and behaviorally distinct.
- Every execution is recorded in query history — the statement as submitted, how it
  ended, and a message that has already been redacted. It is kept in the same
  owner-only database as the sealed credentials, and never written to a log: a
  `WHERE email = '…'` is a record of a person as much as of a query. History is
  capped per connection and goes with the connection when it is deleted. Reading it
  back needs the vault open, and locking discards whatever the panel had loaded.
- Redis console commands are deliberately not recorded — a command's arguments are
  where its secrets are — so that transcript lives in memory for one session and is
  discarded when the vault locks.

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
against someone who already controls the running process. Vulnerability reports go
to [`SECURITY.md`](SECURITY.md), never to the issue tracker.

### Where your data lives

One SQLite file, created owner-only, in the platform's application data directory:

| | |
|---|---|
| macOS | `~/Library/Application Support/caracal` |
| Windows | `%AppData%\caracal` |
| Linux | `$XDG_DATA_HOME/caracal`, or `~/.local/share/caracal` |

`CARACAL_DATA_DIR` overrides it. Settings → About shows the path in use.

Back it up by copying that directory while the application is closed. Deleting it
resets Caracal to a first run — the connections and the sealed passwords go with
it, and nothing can bring them back.

## Build from source

You need **JDK 25** (21+ works; the Gradle toolchain resolves it), and Docker only
for the opt-in integration tests. Users of a released installer need neither.

```sh
./gradlew :app:run                              # launch from source
./gradlew :app:packageDistributionForCurrentOS  # installer for this platform
```

To keep development runs off your real configuration, point them at a scratch
directory:

```sh
CARACAL_DATA_DIR=/tmp/caracal ./gradlew :app:run
```

If the checkout lives in a folder synced by iCloud Drive, packaging needs an
output directory outside it — `codesign` rejects the xattr the sync attaches:

```sh
./gradlew :app:packageDistributionForCurrentOS -Pcaracal.distributionsDir=/tmp/caracal
```

`jpackage` cannot cross-compile. Each installer is built on its own platform; the
release workflow does that on five runners.

## Development

```sh
./gradlew :core:test     # headless — no display server, no window
./gradlew check          # everything, including Compose UI tests and the module-boundary check
```

`:core` has no Compose dependency and never will — `:core:assertNoComposeDependency`
fails the build if one appears, and `:core`'s tests run with AWT headless so a
window can never quietly become a requirement. Domain types, the vault, the
store, the connection registry, and both engine adapters are plain JVM code that
tests headlessly. If a test needs a window to run, it belongs in `:app`.

Integration tests use Testcontainers and are opt-in, because they need Docker:

```sh
CARACAL_INTEGRATION=1 ./gradlew :core:test
```

Opt-in locally, mandatory in CI. `.github/workflows/ci.yml` runs `check` on Linux,
macOS, and Windows, and sets `CARACAL_INTEGRATION=1` on the Linux runner — the only
one of the three with a Docker daemon. `.github/workflows/release.yml` builds the
five installers for a `v*` tag, or on request as a rehearsal.

[`CONTRIBUTING.md`](CONTRIBUTING.md) has the architecture, the test commands, and
the constraints a pull request must not weaken.

### Layout

```
core/src/main/kotlin/dev/caracal/core/
  postgres/      pgjdbc adapter, pooling, error classification, redaction   [M0]
  result/        QueryResult, CellValue, DbError                            [M0]
  catalog/       schemas, objects, and columns, as the browser sees them    [M2]
  connections/   connection domain types, validation, ConnectionService     [M1]
  vault/         Argon2id derivation, AES-GCM sealing                       [M1]
  store/         SQLite configuration database and migrations               [M1]
  registry/      live HikariCP pools and Lettuce clients                    [M1]
  redis/         Lettuce adapter: bounded SCAN, paged values, command guard    [M3]
  appdata/       platform configuration directory, and the pre-rename move  [M1]
  sql/           statement splitter, editor execution rule, highlighting    [M2]
  policy/        what a statement may do, and what must be agreed to first  [M2]
  export/        CSV writing, export eligibility, bounded streaming         [M2]
  history/       what was executed, how it ended, and how much is kept   [M2, M4]

app/src/main/kotlin/dev/caracal/app/
  ui/            connection screens [M1]; schema tree, SQL editor, result grid [M2];
                 key browser, value viewers, INFO dashboard, console          [M3];
                 query history, theme and contrast tokens                     [M4]
  Main.kt        window, application lifecycle, `--version`
  BuildInfo.kt   which build this is                                          [M5]
```

## Current limitations

- The result grid is read-only. Editing data is not in v0.1.
- Installers are unsigned; see [Run it](#run-it).
- Windows on ARM has no native build yet.
- PostgreSQL and Redis only. Tested against PostgreSQL 16 and Redis 7; other
  versions are likely fine and untested — reports welcome.
- A forgotten master password cannot be recovered.
- No cloud sync, no accounts, no plugins, no AI. Several of these are permanent.

What is planned, and what has been deliberately deferred, is in
[`db-ide-mvp-plan.md`](db-ide-mvp-plan.md) and the milestone guides under
[`docs/mvp-steps/`](docs/mvp-steps/README.md). Changes are recorded in
[`CHANGELOG.md`](CHANGELOG.md).

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

> **History.** The application was called Database IDE until M5 named it Caracal; an
> installation under the old name is migrated on first launch.

## Contributing

Bug reports and focused pull requests are welcome — start with
[`CONTRIBUTING.md`](CONTRIBUTING.md), and open an issue before anything large.
Participation is governed by the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

Apache-2.0. See [`LICENSE`](LICENSE), [`NOTICE`](NOTICE), and
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) for the components a Caracal
installer redistributes, including the GPL-with-Classpath-Exception Java runtime
that makes it possible to ship without asking you to install one.

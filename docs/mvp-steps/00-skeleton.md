# M0 — Skeleton

## Outcome

Produce the smallest end-to-end application that proves the chosen architecture: a Gradle build compiles a Kotlin application, Compose Desktop opens a native window, `:core` connects to one temporary hardcoded PostgreSQL database over JDBC, and the window renders the result of `SELECT 1`. `jpackage` turns all of it into an installer that runs on a machine with no JDK.

This milestone is about retiring architecture and packaging risk. It is not a miniature connection manager or SQL IDE.

> **This is a rewrite, not a fresh start.** The Go implementation of M0 and M1 is tagged `go-implementation`. Read it while working — the vault design, the error classification, and the M1 test cases are worth carrying over as *designs*, even though none of the code is. Resist translating Go line by line: coroutines replace `context.Context` plumbing, and `use {}` replaces `defer`.

## Why the window comes first

The previous stack spent M0–M4 in a browser and planned to acquire a native window at the end. Compose inverts that: you get a window in the first hour, and every milestone after this one is a real application. The packaging risk that used to be deferred to release week is retired here instead, in the milestone that exists specifically to retire risk.

That is why M0 ends at an *installer*, not at a `./gradlew run`. Running from Gradle proves nothing about whether a stranger can use this.

## User-visible behavior

When the user launches the application:

1. A native window opens, titled `Database IDE`, with the platform's own title bar.
2. A single **Run SELECT 1** button executes the query.
3. The window shows the returned column, value, duration, and any error.

The window must also make these states understandable: PostgreSQL unavailable, authentication failed, query running, query succeeded, and query failed. A failure must be a visible message in the window, never only a line in a log nobody reads.

## Scope

### Included

- Gradle multi-module build with the Kotlin and Compose plugins
- `:core` — JDBC connection, one fixed query, domain result types, zero Compose dependencies
- `:app` — Compose window, minimal result view, application entry point
- Coroutine-based execution on `Dispatchers.IO` with cancellation wired from the start
- Temporary PostgreSQL configuration for one developer database
- `jpackage` configuration producing an installer for the current platform
- Basic `:core` unit tests and one Compose UI test
- A Testcontainers-backed integration test, opt-in

### Not included

- SQLite, saved connections, or credential encryption
- Editable SQL or the statement splitter
- General result-grid behavior
- Redis
- Query history
- Native menus, window-state persistence, theming
- Code signing, notarization, or CI release automation

## Suggested project shape

Exact package names may change, but keep the module boundary visible from the first commit:

```text
settings.gradle.kts        includes :core and :app
build.gradle.kts           shared Kotlin/JVM toolchain config
core/
  build.gradle.kts         pgjdbc, HikariCP, coroutines — NO compose
  src/main/kotlin/
    postgres/              temporary PostgreSQL query adapter
    result/                QueryResult, CellValue, DbError
  src/test/kotlin/
app/
  build.gradle.kts         compose desktop plugin, depends on :core
  src/main/kotlin/
    Main.kt                window + application entry point
    ui/                    composables
  src/test/kotlin/
```

The Compose plugin must not appear in `:core`'s build file. If it ever does, the headless test guarantee is gone and nothing will tell you until CI has no display.

## Work packages

### 0.1 Set up the Gradle build

- Kotlin 2.x, JVM toolchain pinned to 25 (or 21+ if 25 is unavailable) via `kotlin { jvmToolchain(...) }` so the build does not depend on the developer's `JAVA_HOME`.
- Apply `org.jetbrains.compose` and the Compose compiler plugin to `:app` only.
- Use the Gradle version catalog (`gradle/libs.versions.toml`) from the start. Six dependencies is when it is easy to adopt; sixty is when you wish you had.
- Commit the Gradle wrapper and verify it — the wrapper jar is executable code.

Confirm the current Compose Multiplatform version rather than copying one from a tutorial, and record the version used in the implementation notes below.

### 0.2 Build the PostgreSQL adapter in `:core`

Keep it deliberately small, but establish the shape M2 will grow into:

```kotlin
class PostgresAdapter(private val dataSource: DataSource) {
    suspend fun selectOne(): QueryResult = withContext(Dispatchers.IO) { /* ... */ }
}
```

Requirements:

- HikariCP with a small pool and a short connection timeout. Do not open a raw `DriverManager` connection — the pool is where the timeout and validation settings live, and you want them from day one.
- Credentials passed as `Properties`, never concatenated into the JDBC URL.
- `use {}` on `Connection`, `Statement`, and `ResultSet`. There is no `defer`; leaks here are silent until the pool is exhausted.
- Read the temporary database configuration from an environment variable (`DBIDE_DEV_POSTGRES_*`). Nothing that resembles a credential enters the repository.

### 0.3 Classify errors safely

Map failure into the sealed `DbError` hierarchy from the [step rules](README.md):

- authentication failure, host unreachable, database missing, timeout, and cancellation each map to a distinct case;
- the message shown to the user must not contain the host, port, username, or JDBC URL;
- the raw `SQLException` may be logged at debug verbosity only, after scrubbing.

`SQLException.getSQLState()` is the stable signal. Do not branch on message text — it is localized and it changes between driver versions.

### 0.4 Wire cancellation from the start

Even for `SELECT 1`. This is the pattern the whole application depends on, and retrofitting it in M2 means retrofitting it everywhere at once.

- Execute inside `withContext(Dispatchers.IO)`.
- Hold the `Statement` and register a cancellation handler that calls `statement.cancel()` from a different thread — cancelling the coroutine alone will not interrupt a blocked JDBC call.
- Set `setQueryTimeout` as a backstop.
- Prove it with a test: run `SELECT pg_sleep(30)`, cancel the coroutine, assert the call returns promptly and the server-side query is gone.

That test is the most valuable thing in this milestone. Write it.

### 0.5 Build the Compose window

- `application { Window(...) }` with the title, a sensible initial size, and a minimum size.
- One button, one result area, one error area.
- State held in a `ViewModel`-style class or a `remember`ed holder — not in composable local state that a recomposition can lose.
- The button is disabled while a query is running, and a cancel affordance appears. Even here, the running state must be visible.
- Call `:core` from a coroutine scope tied to the window, so closing the window cancels in-flight work.

Do not start theming. Do not add a design system. Two states rendered honestly is the goal.

### 0.6 Package with jpackage

Configure `compose.desktop.application.nativeDistributions`:

- target formats for the current platform (`Dmg`, `Msi`, `Deb`);
- package name, vendor, and version;
- a `jlink`-trimmed runtime — check the resulting installer size and record it.

Then do the part that matters: `./gradlew :app:packageDistributionForCurrentOS`, install the artifact on a machine or account with **no JDK installed**, and launch it. An installer that only works where a JDK happens to exist has proven nothing.

Note the constraint now rather than at release: `jpackage` cannot cross-compile. Each platform's installer is built on that platform, which is a CI shape decision in M5.

### 0.7 Establish the test and run commands

```sh
./gradlew :core:test                              # headless, no display server
./gradlew test                                    # includes Compose UI tests
./gradlew :app:run                                # launch from source
./gradlew :app:packageDistributionForCurrentOS    # installer
```

`:core:test` passing with no display available is a hard requirement, not a nicety. Verify it deliberately — on Linux, run it with `DISPLAY` unset.

## Testing

### Automated

- `PostgresAdapter` maps success, authentication failure, host unreachable, timeout, and cancellation onto the correct `DbError` cases.
- Error messages contain no host, port, username, or JDBC URL. Assert this — it is the kind of rule that silently rots.
- Cancellation test: `pg_sleep` is interrupted promptly and leaves no running backend.
- Connections, statements, and result sets are returned to the pool on both the success and failure paths. Assert pool metrics rather than trusting the code.
- One Compose UI test: clicking the button moves through running to a rendered result.
- `:core` has no Compose dependency — assert it in the build, so a stray import fails the build rather than CI on a headless runner.

The PostgreSQL integration test uses Testcontainers and may be opt-in locally, but CI must run it.

### Manual acceptance scenario

1. Set the documented temporary PostgreSQL environment variables.
2. `./gradlew :app:packageDistributionForCurrentOS`.
3. Install the artifact on a clean account or machine with no JDK.
4. Launch it from the platform's normal launcher, not a terminal.
5. Click **Run SELECT 1** and confirm the value and duration appear.
6. Stop PostgreSQL and click again; confirm a clear, credential-free error in the window.
7. Start a long query and cancel it; confirm the UI recovers and the server-side query ends.
8. Close the window and confirm the process exits with no lingering JVM.

## Completion checklist

- [ ] `:core` compiles and tests with no Compose dependency and no display server.
- [ ] A native window opens; nothing about the application involves a browser.
- [ ] `SELECT 1` makes a real JDBC round trip and displays its result.
- [ ] Cancellation interrupts a blocking query and is covered by a test.
- [ ] Credentials are absent from logs, error messages, and the repository.
- [ ] Pooled resources are released on both success and failure paths.
- [ ] An installer built by `jpackage` runs on a machine with no JDK.
- [ ] Installer size and cold-start time are measured and recorded below.

## Exit criterion

An installer produced by `./gradlew :app:packageDistributionForCurrentOS`, installed on a machine with no JDK, opens a window and successfully round-trips `SELECT 1` to PostgreSQL.

---

## Implementation notes

Status: **complete** (Kotlin). Verified on 2026-08-20 on macOS 15 (arm64) against
PostgreSQL 16 in Docker. The Go implementation of this milestone is tagged
`go-implementation`.

### Versions used

| | |
|---|---|
| Kotlin | 2.3.21 |
| Compose Multiplatform | 1.11.1 |
| Compose Material 3 | 1.9.0 — released on its own cadence, so it does not track the Compose version |
| Gradle | 9.6.0, wrapper pinned by SHA-256 |
| JVM toolchain | 25 |
| pgjdbc / HikariCP | 42.7.13 / 7.1.0 |
| Testcontainers / JUnit | 1.21.4 / 5.14.2 |

### Measurements

| | |
|---|---|
| `.dmg` installer | 80 MB |
| Installed `.app` | 149 MB, of which 92 MB is the bundled runtime |
| `jlink` module trimming | Applied — 13 platform modules instead of the JDK's full set |
| Cold start to first frame | 1,511 ms on the first launch, 901 ms warm |

Cold start is measured from JVM start to the first rendered frame, so it excludes the
launcher's own process spawn. Set `DBIDE_LOG_STARTUP=1` to have the application report
it; the measurement stays in the code because M5 has to check this number again.

### Decisions this guide left open

- **Errors are thrown, not returned.** `PostgresAdapter` throws `DbException`, which
  carries the classified `DbError`. A `Result`-shaped return would have made every call
  site unwrap something that is exceptional in practice, and coroutine cancellation
  already travels as an exception.
- **Cancellation cannot use `Job.invokeOnCompletion`.** That handler fires when a job
  *completes*, and a job blocked in JDBC does not complete until the call returns — so
  it fires only after the query it was meant to interrupt has finished. `execute` instead
  launches an undispatched guard coroutine that sits in `awaitCancellation()` and calls
  `Statement.cancel()` from another thread the moment the scope is cancelled. The
  integration test proves it: a cancelled `pg_sleep(30)` returns in well under a second
  and leaves no backend behind.
- **Connection-level failures get fixed messages; query failures keep the server's.**
  A driver message about a connection names the host, port, user, and database, so none
  of it survives. A server message about a query is about the SQL, which is the useful
  part — it is passed through `Redaction` as defence in depth and keeps its SQLSTATE,
  position, detail, and hint.
- **The pool owns read-only mode.** `HikariConfig.isReadOnly` is set, so v0.1's
  read-only rule holds even for a caller that bypasses the UI.
- **`:core` exposes `PostgresSession`, not a `DataSource`.** The pool type never reaches
  `:app`; the window gets an adapter, a pool-metrics reading for tests, and `close()`.

### Intentional deviations

| Deviation | Reason |
|---|---|
| The macOS bundle is versioned `1.0.0`, not `0.1.0` | macOS rejects an app version whose first number is zero. The real version rides along as the bundle build version. M5 decides what the released version string is. |
| Packaging output can be redirected out of `build/` | `codesign` refuses to sign an app image carrying a `com.apple.FinderInfo` xattr, and iCloud Drive attaches one to everything it syncs — which breaks `jpackage` for any checkout inside a synced folder. `-Pdbide.distributionsDir=…` (or `DBIDE_DISTRIBUTIONS_DIR`) points the output somewhere local. Default behaviour is unchanged. |
| The installer was not yet installed on a machine with no JDK | The exit criterion's final step is a clean-machine install. The bundle carries its own 13-module runtime and was launched from the packaged binary, but a JDK exists on this machine, so that step is still owed. |

### Carried over from the Go implementation

These decisions were made once and still hold. Do not re-litigate them:

- Errors returned to the UI never contain the host, port, username, or connection string.
- Readiness and health checks are ordinary calls, not a privileged side channel.
- The temporary hardcoded connection is scaffolding and is removed in M1.

### No longer applicable

The Go M0 carried a loopback HTTP server, a session token, an `Origin` check, a CSP, and a browser opener. All of it existed to make an HTML UI safe to serve, and all of it is gone. Do not reintroduce any of it — an in-process Compose UI has no such boundary to defend.

### Verification record

- `./gradlew check`: 28 `:core` unit tests and 8 `:app` tests pass. `:core:test` runs
  with `java.awt.headless=true`, so a stray AWT or Compose dependency fails here rather
  than on a CI runner with no display.
- `:core:assertNoComposeDependency` was proven to fail by temporarily adding
  Material 3 to `:core`, then restored.
- `DBIDE_INTEGRATION=1 ./gradlew :core:test`: 8 Testcontainers tests against
  PostgreSQL 16 pass — `SELECT 1`, wrong password, unknown database, unreachable host,
  statement timeout, scope cancellation, and pool release on both the success and the
  failure path.
- The packaged `.app` was launched from its bundle: it opened its window, held an
  `idle` connection in `pg_stat_activity` under the application name `Database IDE`,
  and exited with no lingering JVM.

### Still owed before M1 is called done

- Install the `.dmg` on a machine or account with no JDK and click through the manual
  acceptance scenario there.
- Build the `.msi` and `.deb` on their own platforms; `jpackage` cannot cross-compile,
  which is the CI shape decision M5 has to make.

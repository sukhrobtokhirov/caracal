# Contributing to Caracal

Caracal is a desktop IDE for PostgreSQL and Redis, written in Kotlin with Compose
Multiplatform. It holds live database credentials and connects to servers people
cannot afford to lose, which shapes most of what follows.

Bug reports and small, focused pull requests are welcome. Before starting anything
large, open an issue — v0.1 has a deliberately narrow scope and the fastest way to
have work rejected is to widen it by surprise.

## Getting set up

You need **JDK 25** (21+ works; the Gradle toolchain resolves it) and, only for the
integration tests, **Docker**.

```sh
./gradlew :core:test     # headless — no display server, no window
./gradlew check          # everything: unit, Compose UI, and the module-boundary check
./gradlew :app:run       # launch from source
```

Point development runs at a scratch configuration directory so they stay off your
real installation:

```sh
CARACAL_DATA_DIR=/tmp/caracal ./gradlew :app:run
```

Integration tests use Testcontainers and are opt-in, because they need a Docker
daemon:

```sh
CARACAL_INTEGRATION=1 ./gradlew :core:test
```

CI runs `check` on Linux, macOS, and Windows, with the integration suites enabled
on Linux — the only runner of the three with Docker. A pull request is expected to
be green on all three.

## How the code is arranged

Two modules, and the boundary between them is enforced by the build rather than by
convention:

- **`:core`** — domain types, the vault, the SQLite store, the connection registry,
  and both engine adapters. Plain JVM code. Every test in it runs headlessly.
- **`:app`** — the Compose window, view models, and screens.

`:core` must never depend on Compose. `:core:assertNoComposeDependency` fails the
build if an artifact from `org.jetbrains.compose`, `androidx.compose`, or
`org.jetbrains.skiko` appears on its classpath, and its tests run with AWT headless
so a window can never quietly become a requirement. If a test needs a window, it
belongs in `:app`.

A `Connection`, `ResultSet`, `Statement`, or Lettuce command object must not escape
`:core`. The UI receives domain types.

## The constraints that are not negotiable

A pull request that weakens any of these will be declined regardless of what else
it does well.

- **Never persist or log a plaintext credential.** The master password is held as a
  `CharArray` and cleared after derivation; secrets are sealed with AES-256-GCM
  under an Argon2id-derived key that exists only in the running process.
- **Never assemble a JDBC URL containing a password.** Credentials are passed as
  `Properties` so they cannot leak into a URL, a log line, or a stack trace.
- **Scrub before surfacing.** `SQLException` messages and JDBC metadata both leak
  connection details; they go through `Redaction` before reaching a log or the UI.
- **Never log Redis command arguments** — a command's arguments are where its
  secrets are — or complete parameterized query data at normal verbosity.
- **Read-only and production guards are enforced in `:core`.** A disabled Compose
  button is not a security boundary. A read-only connection opens its pool in a
  PostgreSQL `READ ONLY` transaction, so the server refuses the write whether it
  arrived as an `UPDATE`, inside a CTE, or inside a function body.
- **PostgreSQL values keep their precision.** `numeric` and `int8` reach the cell
  renderer as `BigDecimal` and `Long`. Nothing may round-trip them through a
  `Double` or a string.
- **Redis browsing never uses `KEYS`.** Bounded `SCAN` with a cursor, always.
- **Everything is bounded** — result rows, scan iterations, value reads, memory,
  and execution time. Disable autocommit and set a fetch size before streaming a
  large result; pgjdbc otherwise buffers the entire result set into heap.
- **Cancellation must actually cancel.** Cancelling a coroutine does not stop a
  blocking JDBC call: install a handler that calls `Statement.cancel()` from
  another thread, and set `setQueryTimeout` as a backstop.
- **Close deterministically.** `use {}` for every `ResultSet`, `Statement`, and
  pooled `Connection`; never let a `Flow` outlive the connection it reads from.

## Scope

v0.1 is a local, single-user, read-first tool for PostgreSQL and Redis. These are
out of scope, and a pull request adding one will be asked to become an issue first:

- other database engines;
- editing data in the result grid;
- cloud sync, accounts, telemetry, or any network listener;
- AI query generation;
- a plugin system or a general-purpose command palette.

## Pull requests

- One concern per pull request. A refactor bundled with a fix is two reviews
  wearing one hat.
- Add the tests with the feature, not afterwards. New behaviour in `:core` gets a
  headless test; new UI gets a Compose test.
- Match the surrounding code. Comments here explain *why* something is the way it
  is — particularly where the obvious implementation was wrong — and that
  convention is worth keeping.
- Screenshots for UI changes, using synthetic data. Check every pixel for real
  hostnames, query text, and notification content before attaching one.
- Say what you ran. "`./gradlew check` on macOS, integration suites on Linux via
  CI" is a complete answer.
- Run `./gradlew check` before pushing.

## Reporting bugs

Use the issue templates. Include your OS and architecture, the output of
`--version`, and the engine and server version. Redact hostnames, usernames, and
query text before pasting anything — a stack trace from this application can carry
all three.

Security vulnerabilities do not go in the issue tracker. See
[`SECURITY.md`](SECURITY.md).

## License

By contributing, you agree that your contributions are licensed under the Apache
License 2.0, the same license as the project.

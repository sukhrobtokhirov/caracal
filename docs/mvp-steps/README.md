# Caracal MVP — Implementation Steps

This directory expands the milestones in [`db-ide-mvp-plan.md`](../../db-ide-mvp-plan.md) into implementation guides. Each milestone has its own scope, feature behavior, technical tasks, tests, and acceptance criteria.

The source plan remains the product and architecture contract. These guides explain how to deliver it without silently expanding the MVP.

The product is a native desktop IDE for PostgreSQL and Redis — the category of DataGrip, DBeaver, and TablePlus. It is built in **Kotlin with Compose Multiplatform for Desktop**. There is no browser, no webview, and no HTTP server anywhere in it: the UI calls suspend functions in the same process as the database code.

## How to use these documents

Work through the steps in order. A later step may rely on interfaces or decisions established by an earlier one. Do not begin the next milestone until the current milestone's acceptance scenario works from a clean build.

Within each guide:

1. Confirm the scope and non-goals.
2. Implement the work packages in order unless the document marks them as independent.
3. Add the listed automated tests as part of the feature, not as a cleanup task.
4. Run the manual acceptance scenario.
5. Record intentional deviations in the guide before moving on.

## Roadmap

| Step | Document | Primary outcome | Depends on |
|---|---|---|---|
| M0 | [Skeleton](00-skeleton.md) | A packaged desktop window runs `SELECT 1` over JDBC | Nothing |
| M1 | [Connection manager](01-connection-manager.md) | Encrypted PostgreSQL and Redis connections survive restart | M0 |
| M2 | [PostgreSQL read path](02-postgres-read-path.md) | Browse schemas and safely execute, inspect, cancel, and export queries | M1 |
| M3 | [Redis read path](03-redis-read-path.md) | Safely browse keys, inspect large values, and run guarded commands | M1; M0–M2 UI shell |
| M4 | [Product polish](04-product-polish.md) | Daily-use workflow with history, tabs, shortcuts, and clear errors | M2 and M3 |
| M5 | [Release](05-release.md) | Reproducible cross-platform v0.1.0 installers and contributor docs | M4 |

There is no desktop-shell milestone. Compose opens a real window in M0, so every milestone after it is a working application.

## Rules that apply to every step

### Module boundary

- **`:core` never depends on Compose.** Domain types, the vault, the store, the registry, and both engine adapters are plain JVM code.
- Every `:core` test runs headlessly — no window, no display server, no `@Composable`.
- A `Connection`, `ResultSet`, `Statement`, or Lettuce command object must never escape `:core`. The UI receives domain types only.
- Business rules live in `:core`. A Compose file that decides whether a connection may write is a bug.

### Security

- Never persist or log a plaintext credential.
- Never assemble a JDBC URL containing a password; pass credentials as `Properties`.
- Scrub `SQLException` messages and JDBC metadata before they reach a log or the UI — both leak connection details.
- Never log Redis command arguments that may contain secrets, or complete parameterized query data, at normal verbosity.
- Hold the master password as a `CharArray` and clear it after derivation.
- Treat production-tagged connections as visibly and behaviorally different from development connections.

### Data safety

- PostgreSQL result grids are read-only in v0.1.
- Enforce read-only mode in `:core`; a disabled Compose button is not a security boundary.
- Never use Redis `KEYS` for browsing.
- Bound result sizes, scan iterations, value reads, memory use, and execution time.
- Disable autocommit and set a fetch size before streaming a large PostgreSQL result — pgjdbc otherwise buffers the entire result set into heap. Set `setMaxRows` as a backstop.
- Close `ResultSet`, `Statement`, and pooled `Connection` deterministically. Use `use {}`; never let a `Flow` outlive the connection it reads from.

### Errors and cancellation

- Model failures as a sealed hierarchy in `:core`, with a stable machine-readable case and a human-readable message. The UI renders cases, never parses strings.
- Every database call is a `suspend fun` on `Dispatchers.IO`.
- Cancelling a coroutine does **not** cancel a blocking JDBC call. Install a cancellation handler that calls `Statement.cancel()` from another thread, and set `setQueryTimeout` as a backstop.
- Scope work to the UI element that owns it: closing an editor tab cancels its query, and nothing else.

Suggested error model:

```kotlin
sealed interface DbError {
    val message: String
    data class ConnectionUnavailable(override val message: String) : DbError
    data class QueryFailed(override val message: String, val sqlState: String?,
                           val position: Int?, val detail: String?, val hint: String?) : DbError
    data class Cancelled(override val message: String) : DbError
    data class ReadOnlyViolation(override val message: String) : DbError
    data class CommandNotAllowed(override val message: String, val command: String) : DbError
}
```

### Quality gate

Each milestone is complete only when all of the following are true:

- A clean checkout can build without undocumented local setup.
- Unit and integration tests for the milestone pass.
- `./gradlew :core:test` passes with no display server available.
- The manual acceptance scenario passes.
- Expected failure states are visible and actionable in the UI.
- No deferred feature from the source plan was added accidentally.

## MVP completion test

The final definition of done is behavioral: use the application for one full working day instead of `psql`, `redis-cli`, and DBeaver — as a window you leave open, not a tab you keep losing. Every reason to switch back becomes an evidence-based v0.2 backlog item.

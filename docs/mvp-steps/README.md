# Database IDE MVP — Implementation Steps

This directory expands the milestones in [`db-ide-mvp-plan.md`](../../db-ide-mvp-plan.md) into implementation guides. Each milestone has its own scope, feature behavior, technical tasks, tests, and acceptance criteria.

The source plan remains the product and architecture contract. These guides explain how to deliver it without silently expanding the MVP.

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
| M0 | [Skeleton](00-skeleton.md) | One local binary serves the UI and runs `SELECT 1` | Nothing |
| M1 | [Connection manager](01-connection-manager.md) | Encrypted PostgreSQL and Redis connections survive restart | M0 |
| M2 | [PostgreSQL read path](02-postgres-read-path.md) | Browse schemas and safely execute, inspect, cancel, and export queries | M1 |
| M3 | [Redis read path](03-redis-read-path.md) | Safely browse keys, inspect large values, and run guarded commands | M1; M0–M2 UI shell |
| M4 | [Product polish](04-product-polish.md) | Daily-use workflow with history, tabs, shortcuts, and clear errors | M2 and M3 |
| M5 | [Release](05-release.md) | Reproducible cross-platform v0.1.0 binaries and contributor docs | M4 |

## Rules that apply to every step

### Security

- Bind the HTTP server explicitly to `127.0.0.1`.
- Authenticate every API request with the per-process random session token.
- Validate `Origin` on mutating requests and reject unexpected origins.
- Never send stored secrets back to the frontend.
- Never log passwords, Redis command arguments that may contain secrets, or complete parameterized query data at normal verbosity.
- Treat production-tagged connections as visibly and behaviorally different from development connections.

### Data safety

- PostgreSQL result grids are read-only in v0.1.
- Enforce read-only mode on the server; a disabled frontend button is not a security boundary.
- Never use Redis `KEYS` for browsing.
- Bound result sizes, scan iterations, value reads, memory use, and execution time.
- Preserve exact database values on the wire. In particular, encode PostgreSQL `numeric` and `int8` as strings.

### API behavior

- Use JSON error responses with a stable machine-readable code and a human-readable message.
- Apply timeouts and context cancellation to database work.
- Validate connection ownership/type before dispatching an engine-specific request.
- Do not return internal errors, SQL connection strings, stack traces, or sealed credential bytes to the browser.

Suggested error envelope:

```json
{
  "error": {
    "code": "connection_unavailable",
    "message": "The connection is not open.",
    "details": {}
  }
}
```

### Quality gate

Each milestone is complete only when all of the following are true:

- A clean checkout can build without undocumented local setup.
- Unit and integration tests for the milestone pass.
- The manual acceptance scenario passes.
- Expected failure states are visible and actionable in the UI.
- No deferred feature from the source plan was added accidentally.

## MVP completion test

The final definition of done is behavioral: use the tool for one full working day instead of `psql`, `redis-cli`, and DBeaver. Every reason to switch back becomes an evidence-based v0.2 backlog item.

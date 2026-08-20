# M1 — Connection Manager

## Outcome

Replace the temporary M0 connection with a persistent, encrypted connection manager for PostgreSQL and Redis. Users can create, inspect, update, test, open, close, and delete named connections. Connections survive restart, but plaintext secrets never do.

## User-visible behavior

The application begins in one of two states:

- **Locked:** the user must enter the master password before stored credentials or database connections can be used.
- **Unlocked:** saved connection summaries are visible and connections can be opened.

For each connection, the UI shows name, engine, host and port, database/index, environment, read-only state, color, and open/closed/error status. It never displays a saved password. Editing a connection leaves the secret unchanged unless the user explicitly enters a replacement.

## Scope

### Included

- Pure-Go SQLite configuration store
- First-run master-password setup and later unlock flow
- Argon2id key derivation and AES-GCM secret sealing
- PostgreSQL and Redis connection CRUD
- Test, open, and close operations
- Environment tags (`dev`, `staging`, `prod`), colors, and read-only flag
- In-memory registry of PostgreSQL pools and Redis clients
- Safe status and validation errors

### Not included

- OS keychain integration
- Cloud sync, shared connections, or credential import
- SSH tunnels, proxies, PostgreSQL service files, Redis Cluster/Sentinel
- Automatic reconnection policy beyond client-library defaults
- Querying, schema browsing, or key browsing

## Data model

Implement the `connections` table from the source plan. Add a small metadata table so encryption parameters and schema version can be stored without overloading connection rows:

```sql
CREATE TABLE app_metadata (
  key   TEXT PRIMARY KEY,
  value BLOB NOT NULL
);
```

Required metadata includes:

- schema migration version;
- random Argon2id salt;
- Argon2id parameters used to derive the key;
- an encrypted verifier that distinguishes an incorrect master password from corrupted connection data.

Run migrations transactionally. Enable SQLite foreign keys on every connection and configure a reasonable busy timeout. Restrict database-file permissions where the operating system supports it.

## Connection representation

Use separate types for storage, API output, and runtime clients:

- **Stored connection:** contains sealed secret bytes and database fields.
- **Connection summary:** safe for JSON; contains no secret or encryption metadata.
- **Connection input:** accepts an optional new secret and validates engine-specific fields.
- **Runtime connection:** an open `pgxpool.Pool` or `redis.Client` held only in memory.

Never serialize a storage record directly into an HTTP response.

Engine-specific defaults and validation:

| Field | PostgreSQL | Redis |
|---|---|---|
| Default port | `5432` | `6379` |
| Database | Non-empty database name | Integer database index, default `0` |
| Username | Usually required; allow server-specific exceptions | Optional |
| Secret | Password | Password |
| TLS mode | `disable`, `require`, `verify-full` | `disable`, `require` for MVP |

Host must be a host name or IP without a URL scheme. Port must be `1..65535`. Normalize environment values instead of accepting arbitrary strings.

## Work packages

### 1.1 Add the SQLite store and migrations

- Create the database in the platform-appropriate application data directory, with a CLI override for development and tests.
- Create migrations for `app_metadata`, `connections`, and `query_history`. History is populated in M2/M4, but creating it now fixes the storage contract early.
- Keep SQL behind a store interface so handlers do not know about encryption blobs or SQLite details.
- Provide create, list, get, update, and delete operations using parameterized SQL.
- Sort connection summaries deterministically, preferably by environment then case-insensitive name.
- When deleting a connection, close its runtime client before removing it from storage. If closing fails, return a warning but do not leave a phantom open registry entry.

Store tests must use isolated temporary databases and cover migrations from an empty file, constraints, updates, deletion, ordering, and foreign-key cascades.

### 1.2 Implement master-password lifecycle

First run:

1. Generate a random salt.
2. Derive a 256-bit key with Argon2id using explicit, versioned parameters.
3. Encrypt a fixed random verifier with AES-GCM.
4. Persist only the salt, parameters, and sealed verifier.
5. Keep the derived key in memory for the unlocked process.

Later runs:

1. Read the stored salt and parameters.
2. Derive a candidate key from the entered password.
3. Attempt to open the verifier.
4. Unlock only if authenticated decryption succeeds.

Do not store the master password, derived key, or plaintext verifier. Clear references to sensitive byte slices when practical, understanding that Go does not guarantee perfect memory erasure.

Set a minimum master-password length for accidental weak-password prevention, but explain that the local threat model and Argon2id cost matter more than arbitrary composition rules. Rate-limit repeated unlock attempts within the process.

### 1.3 Seal connection secrets

- Use a fresh random 96-bit nonce for every AES-GCM seal operation.
- Store a versioned envelope containing algorithm version, nonce, and ciphertext.
- Authenticate stable connection identity fields as additional authenticated data, such as envelope version, connection ID, and engine. This prevents copying a ciphertext to a different record unnoticed.
- Represent the plaintext secret as a versioned structure so another sensitive field can be added later without inventing a second encryption format.
- On update, preserve the existing sealed secret when the secret input is omitted. Reseal when the connection ID/engine used by authenticated data changes.
- Treat authentication failure as corrupted or inaccessible data; never fall back to interpreting the bytes as plaintext.

Add tests for unique ciphertext, correct round trip, wrong key, modified nonce/ciphertext, wrong connection identity, and unchanged-secret update.

### 1.4 Build connection CRUD APIs

Implement the source-plan endpoints:

```text
POST   /api/connections
GET    /api/connections
PUT    /api/connections/:id
DELETE /api/connections/:id
POST   /api/connections/:id/test
POST   /api/connections/:id/open
POST   /api/connections/:id/close
```

Behavioral requirements:

- Create returns the safe summary and `201`.
- List and update never include secret fields, even as `null` or masked placeholders that imply storage shape.
- Update uses an explicit `secretChanged`/optional-secret contract so an empty password is not confused with "leave unchanged."
- Test decrypts the secret, creates a short-lived client, performs a lightweight round trip, reports sanitized server information, and always closes the client.
- Open is idempotent for an already-open healthy connection.
- Close is idempotent and removes the runtime entry.
- Delete requires a deliberate confirmation in the UI and returns no secret-bearing record.
- All mutating routes use token and Origin protection from M0.

Recommended test operations are PostgreSQL `Ping` and Redis `PING`. Apply short DNS, connect, TLS, and ping timeouts so a bad host does not freeze the form.

### 1.5 Add the runtime connection registry

- Key registry entries by stored connection UUID.
- Store the engine alongside the concrete client to prevent type confusion.
- Protect concurrent access with a mutex or a registry-owned command loop.
- Build the client from decrypted fields only during test/open; do not construct or log a URL containing the password.
- Set conservative pool sizes for a single-user desktop tool.
- Track status as `closed`, `opening`, `open`, or `error`, plus a safe last-error message.
- If a saved connection changes host, TLS, user, database, or secret, close and invalidate its old runtime client.
- Close all clients during application shutdown.

Do not write decrypted configuration to temporary files or store it in React state.

### 1.6 Build the connection UI

Create three main pieces:

- **Connection list:** grouped or marked by environment, with engine icon, color, and live status.
- **Connection form:** engine-aware fields, defaults, validation, test action, and create/update action.
- **Unlock/setup screen:** clear first-run and returning-user states, with generic incorrect-password feedback.

Production safety must be visible before M2:

- Use a persistent red treatment for `prod`, not color alone; include a `PROD` label.
- Show `READ ONLY` explicitly.
- Keep the selected connection's environment visible in the application shell.
- Do not prefill the saved password on edit. Show **Leave saved password unchanged** until the user chooses to replace it.

Prevent double submission, show progress for test/open, focus the first invalid field, and preserve non-secret form values after a failed test.

### 1.7 Remove the M0 scaffold

- Delete the temporary hardcoded connection endpoint.
- Remove the development connection-string dependency from normal startup.
- Route all future database work through a stored connection ID and the runtime registry.
- Keep an explicit test-only configuration path for integration tests if needed.

## Failure handling

Map common failures into safe, useful categories:

| Failure | User message |
|---|---|
| DNS/connect timeout | Could not reach the host before the timeout |
| Authentication failure | The server rejected the username or password |
| TLS verification | The server certificate could not be verified |
| Wrong master password | The master password is incorrect |
| Corrupt sealed secret | This saved credential cannot be decrypted |
| Unsupported engine/mode | This connection configuration is not supported |

Detailed driver errors may be logged only after redaction. Responses must not include connection strings, passwords, or raw sealed data.

## Testing

### Automated

- Migrations and store operations on a temporary SQLite database
- Encryption round trips and tamper detection
- Locked-state rejection of operations requiring secrets
- CRUD response serialization proving secrets are absent
- Engine-specific validation and defaults
- Registry concurrency, idempotent open/close, update invalidation, and shutdown
- PostgreSQL and Redis test/open failure mappings
- UI setup, unlock, create, edit-without-secret-change, test, delete, and production labeling

Use integration databases in CI where available. Unit-test transport and auth failures with controlled fakes so error paths remain deterministic.

### Manual acceptance scenario

1. Start with an empty application-data directory.
2. Create a master password.
3. Add one real PostgreSQL and one real Redis connection.
4. Mark one connection `prod` and `read_only`; confirm both warnings remain visible.
5. Test and open each connection.
6. Restart the application and unlock it.
7. Confirm both connections remain, their passwords are not displayed, and they open successfully.
8. Edit a color without entering a new secret and confirm the connection still works.
9. Enter a wrong master password and confirm no connection data is usable.
10. Delete a connection and confirm it disappears and any runtime client closes.

## Completion checklist

- [x] SQLite migrations create the source-plan schema and encryption metadata.
- [x] Secrets are sealed with Argon2id-derived AES-GCM keys and unique nonces.
- [x] No connection API response includes passwords or sealed bytes.
- [x] PostgreSQL and Redis connections can be created, tested, opened, closed, edited, and deleted.
- [x] Open clients are managed by a concurrency-safe in-memory registry.
- [x] Production and read-only connections are unambiguous in the UI.
- [x] Connections survive application restart and unlock.
- [x] The M0 hardcoded connection is removed.

## Exit criterion

Real PostgreSQL and Redis connections can be saved securely, survive a restart, and reconnect after the user unlocks the application.

---
## Implementation notes

Status: **complete** (Kotlin). Verified on 2026-08-20 on macOS 15 (arm64) against
PostgreSQL 16 and Redis 7 in Docker. The Go implementation of this milestone is
preserved under the `go-implementation` git tag; its designs — the vault, the
secret-change contract, the locked-state rules, the schema versioning — carried
over intact, and its HTTP shape did not.

### Versions used

| | |
|---|---|
| SQLite | `org.xerial:sqlite-jdbc` 3.53.2.1 |
| Redis | Lettuce 6-series API, `io.lettuce:lettuce-core` 7.7.0 |
| Argon2id | BouncyCastle `bcprov-jdk18on` 1.85.2 |

Everything else is unchanged from [M0](00-skeleton.md#versions-used).

### Decisions this guide left open

- **The secret-change contract is a sealed type, not a flag pair.** Go needed
  `{"changed": bool, "value": string}` because JSON has no sum type and an empty
  password field must not be read as "delete the credential". Kotlin has
  `SecretUpdate.Unchanged | Clear | Replace`, so the ambiguity cannot be
  expressed: a form that submits an empty password field sends `Unchanged`, and
  removing a credential takes a deliberate `Clear`.
- **Locked state covers listing, not just secrets.** Every `ConnectionService`
  operation requires an unlocked vault. Summaries carry no credentials, but they
  are still a map of where this user's databases live.
- **Locking closes clients.** `lock()` discards the derived key *and* closes every
  live pool and client, so a locked application is not still talking to production.
- **Schema version storage.** The applied migration version lives in
  `app_metadata` under `schema_version`. `app_metadata` itself is created outside
  the versioned steps, since it holds the version.
- **Name uniqueness.** A unique index on `connections.name` returns a
  `duplicate_name` conflict. Two connections with the same name would make the
  environment badges useless as a safety signal.
- **Redis TLS.** `disable` and `require` only; `require` verifies the certificate.
  PostgreSQL keeps all three modes, where `require` encrypts without verifying,
  matching libpq's meaning of the word.
- **`ConnectionService` is an interface.** The source plan's §4 already wrote it
  as one. `DefaultConnectionService` is the only real implementation; the interface
  exists so a Compose test that clicks **Create** does not need SQLite and an
  Argon2id derivation standing behind the button.
- **One failure vocabulary reaches the UI.** `:core` throws four hierarchies —
  `DbException`, `VaultException`, `StoreException`, `ValidationException` — and
  `Throwable.toFailure()` joins them into a `Failure(code, message, fields)`. The
  mapping lives in `:core`, so the UI renders cases and never classifies one.
  Anything unrecognised becomes a generic message, because an unexpected
  exception's text has not been through redaction.
- **The read-only flag is a label in v0.1.** Every PostgreSQL pool is opened with
  `isReadOnly = true` regardless, because v0.1 has no write path at all. The
  per-connection flag is the visible promise that survives into v0.2, when writes
  arrive and it acquires teeth.

### Intentional deviations

| Deviation | Reason |
|---|---|
| The configuration directory is overridden by `DBIDE_DATA_DIR`, not a CLI flag | The guide asked for "a CLI override for development and tests". A packaged desktop application is started by the platform's launcher, not a terminal, so a flag would be unreachable exactly where it is needed. Tests pass a `Path` directly; `./gradlew :app:run` passes the variable through. |
| A failed client close during delete is logged, not returned as a warning | The guide asks for a warning that does not leave a phantom registry entry. The entry is always dropped, which is the part that matters; surfacing "the connection was deleted, but closing its pool complained" is noise a user cannot act on. Revisit if it ever proves otherwise. |
| `jlink` keeps three more platform modules than M0 | Netty, under Lettuce, pulls in `java.instrument`, `java.security.jgss`, and `jdk.jfr`. `:app:suggestRuntimeModules` reports them; `jdk.crypto.ec` stays hand-added because TLS providers load reflectively and never appear in that analysis. |
| Registry state tests use Redis rather than PostgreSQL | HikariCP retries a refused connection for its whole five-second connect timeout, so eight concurrent failed dials took 41 seconds. Lettuce reports a refusal at once. The registry cannot tell the engines apart, so the cheap one proves the same state machine; PostgreSQL keeps the tests that are actually about pgjdbc's messages. |

### M0 scaffolding removed

- `DBIDE_DEV_POSTGRES_*` and `PostgresConnectionConfig.fromEnvironment`, along with
  the environment pass-through in `:app`'s build file.
- `BootstrapViewModel`, `BootstrapScreen`, and their tests. All database work now
  routes through a stored connection ID and the runtime registry.
- `PostgresConnectionConfig` survives as the *dialing* form, built by
  `PostgresConnectionConfig.of(config, password)` at the moment a client is opened
  or tested. Its `SslMode` enum was replaced by the domain's `TlsMode`, so both
  engines speak one TLS vocabulary.

### Verification record

- `./gradlew check`: 247 tests pass — 187 in `:core`, 60 in `:app` — with
  `:core:assertNoComposeDependency` and `java.awt.headless=true` both holding.
- `DBIDE_INTEGRATION=1 ./gradlew check`: the same 247 including the Testcontainers
  suites against PostgreSQL 16 and Redis 7. `ConnectionManagerIntegrationTest`
  runs the manual acceptance scenario end to end: create one connection per engine,
  mark one `prod` and `read_only`, test and open both, restart the process, unlock,
  confirm both survived with working passwords, edit a colour without touching the
  secret, change a host and watch the stale client close, and delete a connection.
- On disk, after a real packaged run: the database file is `0600` and its directory
  `0700`, journal mode is WAL, `app_metadata` holds only the salt, parameters,
  verifier, and schema version, and neither database password nor master password
  appears anywhere in the file or its WAL sidecar.
- The application was run against a scratch `DBIDE_DATA_DIR` and driven by hand:
  master password chosen, a PostgreSQL connection created, tested, opened, and
  deleted, then the window closed with no lingering JVM. First frame at 616 ms.
- `.dmg` installer: 105 MB, up from M0's 80 MB. Lettuce brings Netty, and
  BouncyCastle and sqlite-jdbc bring the rest.

### Bugs found by the manual run

Three, and they are the reason this section exists. The automated suites were green
and the packaged application still could not start.

1. **The configuration store was closed the instant it opened.** `main` held its
   resource in a `DisposableEffect` keyed on that resource, so the key changed as
   soon as it appeared, Compose disposed the previous effect, and `onDispose` closed
   the store it was meant to be guarding. Every screen afterwards read from a closed
   connection. The lifecycle moved into `rememberStartup`, whose `DisposableEffect`
   is keyed on `Unit`; reintroducing the old keying fails two of its four tests.
2. **A store failure reached the UI unclassified.** `ConfigStore` passed driver
   exceptions through, so the failure above surfaced as `unexpected_error` — the
   deliberate catch-all for text that has not been through redaction — and the
   window said only "Something went wrong". Every statement is now wrapped, and a
   test asserts a closed store still answers with a classified code.
3. **The PostgreSQL pool was built on the AWT event thread.** `RedisSession.open`
   switched to `Dispatchers.IO`; `PostgresSession.open` did not, so pool
   construction and, on the failure path, `HikariDataSource.close()` ran on whichever
   thread called them. The log said it outright:
   `[AWT-EventQueue-0] dbide-postgres - Starting...`. Found by reading the log of a
   successful run, not by anything failing.

The lesson for M2 is about the first one. It was reported as verified on the
evidence that the process stayed alive and the database file appeared — both of
which were true *because* of the bug, since opening and closing a store still
creates its file. Liveness is not correctness; the window has to be looked at.

### Bug found by the integration run

A wrong Redis password was classified as `host_unreachable` rather than
`authentication_failed`. Lettuce reports an authentication failure during connect
by wrapping it in a `RedisConnectionException` whose own message says only that it
could not connect; the classifier read the top-level message and never saw the
`WRONGPASS` reply underneath. It now reads the whole cause chain, with a unit-test
regression for the wrapped shape.

The same run also produced a false positive worth recording: the "no plaintext on
disk" assertion failed because Testcontainers' default PostgreSQL password is the
four-letter word `test`, which occurs by chance in any binary file. The containers
now use distinctive passwords, so that assertion means something.

### Still owed

- The manual scenario was driven far enough to find the three bugs above — setup,
  create, test, open, delete — but not to its end. The Redis half, the restart and
  unlock, and the production and read-only labelling have been exercised only by
  `ConnectionManagerIntegrationTest` and the Compose UI tests, not by hand.
- Re-run the manual pass against the *packaged* application rather than
  `./gradlew :app:run`. The three bugs above were all in code that packaging does
  not change, but that is an argument, not a check.
- The clean-machine install M0 still owes, now carrying M1's larger bundle.

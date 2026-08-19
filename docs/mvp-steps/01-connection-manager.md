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

- [ ] SQLite migrations create the source-plan schema and encryption metadata.
- [ ] Secrets are sealed with Argon2id-derived AES-GCM keys and unique nonces.
- [ ] No connection API response includes passwords or sealed bytes.
- [ ] PostgreSQL and Redis connections can be created, tested, opened, closed, edited, and deleted.
- [ ] Open clients are managed by a concurrency-safe in-memory registry.
- [ ] Production and read-only connections are unambiguous in the UI.
- [ ] Connections survive application restart and unlock.
- [ ] The M0 hardcoded connection is removed.

## Exit criterion

Real PostgreSQL and Redis connections can be saved securely, survive a restart, and reconnect after the user unlocks the application.

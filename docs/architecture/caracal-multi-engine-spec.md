# Caracal — Multi-Engine Support & Runtime Driver Provisioning

**Implementation spec for Claude Code.**
Target: Caracal v0.2.0. Prerequisite: v0.1.0 tagged and released from current `main`.

---

## 0. How to use this document

This spec is written to be executed **one phase per Claude Code session**. Each phase has
a goal, a file list, and acceptance criteria. Do not start a phase until the previous
phase's acceptance criteria pass and the work is committed.

Rules for the implementing agent:

1. **Phase 0 is not optional.** It captures current behaviour as tests. Every later phase
   is a refactor, and a refactor without characterization tests silently destroys the
   sharp edges (error position mapping, numeric precision, command classification) that
   make this tool worth using.
2. **Never change behaviour and structure in the same commit.** Move code, run tests,
   commit. Then change behaviour, run tests, commit.
3. **Read `§12 Do not abstract` before writing any interface.** Over-abstraction is the
   main failure mode of this refactor.
4. When this spec conflicts with what is actually in the repo, the repo wins — update the
   spec and say so in the commit message.

---

## 1. Discovery step (run first, every session)

This spec was written from a feature description, not from the source. Before editing,
establish ground truth:

```bash
# Module and build layout
fd -e kts -e gradle --max-depth 2
cat settings.gradle.kts

# Where the named files actually live
fd 'Vault.kt|Kdf.kt|DataSafetyPolicy.kt|Redaction.kt' 
fd 'PostgresCatalog.kt|StatementSplitter.kt|PostgresErrors.kt|PostgresValues.kt'
fd 'RedisKeyTree.kt|RedisCommandGuard.kt|RedisValueViewer.kt'

# How tightly the UI is bound to concrete engine types
rg -n 'Postgres|Redis' --type kotlin -g '!**/test/**' -l | head -50
rg -n 'when\s*\(.*[Ee]ngine' --type kotlin

# Current dependency surface
rg -n 'postgresql|lettuce|jedis|hikari' -g '*.gradle.kts'
```

Record the answers in `docs/architecture/current-state.md` before Phase 0. Specifically
note: is this a single Gradle module or already multi-module, and does the UI layer import
`PostgresCatalog` / `RedisKeyTree` directly or through some existing indirection?

> **Answered 2026-08-22** in [`current-state.md`](current-state.md), at commit `78fe44d`.
> Two modules — `:core` and `:app` — with **no `:ui` module**; the UI lives in `:app` and
> imports `dev.caracal.core.postgres` / `dev.caracal.core.redis` directly, through no
> indirection at all. Fourteen files do so, and there are nine `when (engine)` switches.
> The root package is **`dev.caracal`**, not `caracal`, so every package name below gains
> that prefix.

---

## 2. Target architecture

### 2.1 Modules

> **Repo check.** None of this exists yet: the build has `:core` and `:app` only, and
> `:app` holds both the Compose UI and the wiring the table below splits between `:ui`
> and `:app`. Read the table as the destination, and expect Phase 1 to create nine
> modules where there are two.

```
:core            vault, secrets, redaction, safety policy, connection registry,
                 environment tagging, build info.        No engine imports. No UI.
:engine-api      the SPI. Interfaces, capability model, value model, error model.
                 Depends on :core only.
:engine-sql      shared SQL machinery: statement splitting, result grid model,
                 query history, explain tree model, CSV/JSON export.
                 Depends on :engine-api.
:engine-postgres implements :engine-api. Bundled driver.
:engine-redis    implements :engine-api. Bundled driver.
:engine-sqlite   implements :engine-api. Bundled driver.
:engine-mysql    implements :engine-api. Driver provisioned at runtime.
:drivers         driver manifest, download, verification, classloading. (§8)
:ui              Compose. Depends on :engine-api, :engine-sql, :core. 
                 MUST NOT depend on any :engine-* implementation module.
:app             wiring, ServiceLoader registration, packaging. Depends on everything.
```

### 2.2 Dependency rule, enforced by test

The single most important structural invariant: **`:ui` must not compile against a
concrete engine.** Enforce it mechanically, not by discipline:

> **Repo check.** Until the module split lands there is no `:ui` to test, so this belongs
> in `app/src/test/` and reads `app/src/main/kotlin`. The forbidden imports are
> `dev.caracal.engine.postgres` and so on — mind the `dev.` prefix, without which the test
> passes by matching nothing.

```kotlin
// ui/src/test/kotlin/ArchitectureTest.kt
@Test
fun `ui does not depend on engine implementations`() {
    val forbidden = listOf("engine.postgres", "engine.redis", "engine.mysql", "engine.sqlite")
    val violations = Path("src/main/kotlin").walk()
        .filter { it.extension == "kt" }
        .flatMap { f -> f.readLines().mapIndexed { i, l -> f to (i + 1 to l) } }
        .filter { (_, line) -> forbidden.any { line.second.contains("import caracal.$it") } }
        .toList()
    assertTrue(violations.isEmpty(), "UI imports engine internals:\n$violations")
}
```

Add the equivalent for `:engine-api` (must not import `:ui` or any engine impl).

---

## 3. The SPI

Package `caracal.engine.api`.

### 3.1 Engine

```kotlin
@JvmInline value class EngineId(val value: String)   // "postgres", "redis", "mysql", "sqlite"

interface DatabaseEngine {
    val id: EngineId
    val displayName: String
    val capabilities: EngineCapabilities

    /** Declarative description of the connection dialog. See §5.2. */
    val connectionForm: ConnectionForm

    /** Which driver artifacts this engine needs, or null if bundled. See §8. */
    val driverRequirement: DriverRequirement?

    fun validate(descriptor: ConnectionDescriptor): List<ValidationIssue>

    suspend fun connect(
        descriptor: ConnectionDescriptor,
        secrets: SecretBundle,
        policy: DataSafetyPolicy,
        drivers: DriverProvider,
    ): DatabaseSession
}
```

`connect` receives the resolved policy so the driver can apply **server-side** read-only
enforcement at connection time (§7). It receives `DriverProvider` so it can obtain a
classloaded JDBC driver without knowing how it got there.

### 3.2 Session and facets

Do **not** build one god interface with twenty capability booleans. Use facets: an engine
either provides a facet or does not, and the UI asks.

```kotlin
interface DatabaseSession : AutoCloseable {
    val engineId: EngineId
    val capabilities: EngineCapabilities
    val serverVersion: ServerVersion
    val state: StateFlow<SessionState>          // Connecting, Ready, Broken(reason), Closed

    suspend fun ping(): Duration

    fun <F : Any> facet(type: KClass<F>): F?
}

inline fun <reified F : Any> DatabaseSession.facet(): F? = facet(F::class)
inline fun <reified F : Any> DatabaseSession.requireFacet(): F =
    facet(F::class) ?: error("${engineId.value} does not provide ${F::class.simpleName}")
```

Facet catalogue:

| Facet | Provided by | Purpose |
|---|---|---|
| `QueryFacet` | postgres, mysql, sqlite | execute statements, stream rows, cancel |
| `CatalogFacet` | postgres, mysql, sqlite | lazy object tree, DDL reconstruction |
| `TransactionFacet` | postgres, mysql, sqlite | explicit begin/commit/rollback + observable state |
| `MutationFacet` | postgres, mysql, sqlite | row identity, change planning for editable grid |
| `ExplainFacet` | postgres, mysql | plan capture |
| `KeyValueFacet` | redis | scan, key metadata, paged value reads |
| `CommandFacet` | redis | console execution |
| `MetricsFacet` | redis, postgres, mysql | INFO / pg_stat dashboards |

```kotlin
interface QueryFacet {
    /** Splitting is shared (:engine-sql) but dialect-aware; engine supplies the config. */
    val splitterConfig: SplitterConfig

    suspend fun execute(request: StatementRequest): StatementExecution
}

interface StatementExecution {
    val outcomes: Flow<StatementOutcome>   // one or more per submitted statement
    suspend fun cancel(): CancelResult
}

data class StatementRequest(
    val sql: String,
    val sourceOffset: Int,          // offset of this statement within the editor buffer
    val parameters: List<BoundParameter> = emptyList(),
    val fetchSize: Int = 500,
    val maxRows: Long? = null,
    val intent: WriteIntent,        // classified by the engine, approved by core policy (§7)
)

sealed interface StatementOutcome {
    data class Rows(val descriptor: ResultDescriptor, val rows: Flow<Row>) : StatementOutcome
    data class UpdateCount(val count: Long, val tag: String?) : StatementOutcome
    data class Notice(val severity: Severity, val text: String) : StatementOutcome
    data class Failed(val error: EngineError) : StatementOutcome
}
```

`Notice` exists so `RAISE NOTICE`, MySQL warnings, and SQLite `PRAGMA` output stop being
swallowed. The current Postgres implementation almost certainly discards
`SQLWarning`/`PSQLWarning`; wire it here.

### 3.3 Cancellation

Cancellation semantics differ enough that they must be declared, not assumed:

```kotlin
enum class CancellationSupport {
    OUT_OF_BAND,     // Postgres: PGConnection.cancelQuery() on a side channel
    SIDE_CONNECTION, // MySQL: KILL QUERY <id> from a second connection
    INTERRUPT,       // SQLite: sqlite3_interrupt via Statement.cancel()
    CLIENT_ABANDON,  // Redis: cannot cancel; we stop reading and drop the connection
    NONE,
}

sealed interface CancelResult {
    object ServerAcknowledged : CancelResult
    object ClientAbandoned : CancelResult          // UI must warn: statement may still run
    data class Unsupported(val reason: String) : CancelResult
}
```

The UI must render `ClientAbandoned` differently from `ServerAcknowledged` — "cancel that
actually cancels" is a claim in the README and it must not silently become false when a
new engine is added.

---

## 4. Capability model

```kotlin
data class EngineCapabilities(
    val family: EngineFamily,                  // SQL, KEY_VALUE, DOCUMENT
    val namespaceModel: NamespaceModel,        // NONE | DATABASE | SCHEMA | DATABASE_AND_SCHEMA
    val transactions: TransactionSupport,      // NONE | IMPLICIT | EXPLICIT
    val readOnlyEnforcement: ReadOnlyEnforcement,
    val cancellation: CancellationSupport,
    val rowIdentity: RowIdentitySupport,       // PRIMARY_KEY | PSEUDO_COLUMN | PRIMARY_KEY_OR_PSEUDO | NONE
    val identifierQuote: QuoteStyle,           // DOUBLE_QUOTE | BACKTICK | BRACKET
    val supportsMultipleResultSets: Boolean,
    val supportsExplain: Boolean,
    val supportsSchemaDiff: Boolean,
    val maxIdentifierLength: Int,
    val defaultPort: Int?,
)
```

**Usage rule.** The UI reads capabilities to decide what to *show*. It never switches on
`EngineId`. If you find yourself writing `when (session.engineId)` in `:ui`, the missing
thing is a capability or a facet — add it.

Example, correct:

```kotlin
if (session.capabilities.transactions == TransactionSupport.EXPLICIT) {
    TransactionToolbar(session.requireFacet<TransactionFacet>())
}
```

Example, wrong — do not do this:

```kotlin
if (session.engineId == EngineId("postgres")) { TransactionToolbar(...) }
```

---

## 5. Connections

### 5.1 Descriptor

The current descriptor is almost certainly host/port/database/user/password. SQLite breaks
that on day one (a file path, no credentials), so generalize now:

```kotlin
data class ConnectionDescriptor(
    val id: ConnectionId,
    val engineId: EngineId,
    val displayName: String,
    val environment: Environment,            // DEV | STAGING | PROD  (existing)
    val writable: Boolean,                   // existing
    val target: ConnectionTarget,
    val transport: Transport,
    val tls: TlsConfig,
    val secretRef: SecretRef?,               // null for SQLite and unauthenticated Redis
    val engineOptions: Map<String, String>,  // engine-specific, validated by the engine
)

sealed interface ConnectionTarget {
    data class Network(val host: String, val port: Int, val database: String?) : ConnectionTarget
    data class File(val path: Path, val createIfMissing: Boolean) : ConnectionTarget
    data class Url(val raw: String) : ConnectionTarget      // escape hatch; redact on display
    data class Cluster(val nodes: List<HostPort>) : ConnectionTarget  // Redis cluster, later
}

sealed interface Transport {
    object Direct : Transport
    data class SshTunnel(
        val host: String, val port: Int, val user: String,
        val auth: SshAuthRef, val localBindPort: Int?,
    ) : Transport
}
```

### 5.2 Declarative connection form

So the connection dialog does not grow a `when(engine)` block:

```kotlin
data class ConnectionForm(val sections: List<FormSection>)

sealed interface FormField {
    val key: String; val label: String; val required: Boolean
    data class Text(...) : FormField
    data class Number(..., val default: Int?) : FormField
    data class FilePath(..., val extensions: List<String>, val mustExist: Boolean) : FormField
    data class Choice(..., val options: List<Pair<String, String>>) : FormField
    data class Toggle(..., val default: Boolean) : FormField
    data class Secret(..., val kind: SecretKind) : FormField
}
```

Each engine declares its form; the dialog renders it generically and hands back a
`Map<String, String>` that `engine.validate()` checks. Validation messages come from the
engine, so "port must be between 1 and 65535" and "database file is not readable" are both
first-class.

### 5.3 Secrets and vault migration

```kotlin
sealed interface SecretBundle {
    object None : SecretBundle
    data class Password(val password: CharArray) : SecretBundle
    data class UserPassword(val user: String, val password: CharArray) : SecretBundle
    data class ClientCertificate(val keyStore: ByteArray, val passphrase: CharArray) : SecretBundle
    data class ConnectionString(val value: CharArray) : SecretBundle
    data class Token(val value: CharArray, val expiresAt: Instant?) : SecretBundle  // RDS IAM, later
}
```

The vault currently stores one shape. Adding these breaks old records unless you version
the payload **now**:

```kotlin
// Sealed, versioned envelope stored inside the AES-256-GCM ciphertext.
@Serializable
data class VaultRecord(
    val schemaVersion: Int,           // bump to 2
    val kind: String,                 // discriminator for SecretBundle
    val payload: JsonObject,
)
```

Migration in `Vault.kt`:

- On unlock, read `schemaVersion`. If `1`, map the legacy `{user, password}` shape to
  `kind = "user_password"`, re-encrypt, write back, log the count at info level.
- Migration must be idempotent and must run inside the same unlock transaction, so a crash
  mid-migration leaves every record readable by either version.
- **Test:** write a fixture vault file in v1 format, check it into
  `core/src/test/resources/vault/v1-legacy.bin`, and assert it unlocks and migrates. This
  fixture must never be regenerated — it is the proof that shipped users can upgrade.

Do not touch `Kdf.kt` parameters during this work. Argon2id parameter changes are a
separate, independently-tested change with its own migration path.

---

## 6. Values and errors — the precision-critical parts

### 6.1 Value model

`PostgresValues.kt` keeps `numeric` and `int8` at full precision. Preserve that guarantee
across all engines by making it impossible to express a lossy value:

```kotlin
sealed interface CellValue {
    object Null : CellValue
    data class Text(val value: String) : CellValue
    data class Integer(val value: BigInteger) : CellValue
    data class Decimal(val value: BigDecimal) : CellValue
    data class Floating(val value: Double) : CellValue   // ONLY for float4/float8/REAL
    data class Bool(val value: Boolean) : CellValue
    data class Bytes(val value: ByteArray, val truncated: Boolean) : CellValue
    data class Json(val raw: String) : CellValue
    data class Temporal(val kind: TemporalKind, val raw: String, val parsed: Any?) : CellValue
    data class Array(val elements: List<CellValue>, val elementType: String) : CellValue
    /** Engine-specific type we can display but not interpret. Never lossy: raw is verbatim. */
    data class Opaque(val typeName: String, val display: String) : CellValue
}
```

**Rule:** no engine may construct `Floating` for an exact numeric type, and no code path
may route a value through `Double` or `toString()` of a JDBC object. Read `numeric` with
`getBigDecimal`, `int8` with `getLong`/`getBigDecimal`, MySQL `DECIMAL` and `BIGINT
UNSIGNED` likewise (`BIGINT UNSIGNED` exceeds `Long.MAX_VALUE` — it must become
`BigInteger`, and there is a test for it in §10).

### 6.2 Error model

```kotlin
data class EngineError(
    val message: String,              // already redacted (Redaction.kt) before construction
    val code: String?,                // SQLSTATE, MySQL errno, Redis error prefix
    val position: SourcePosition?,    // 0-based char offset into the ORIGINAL editor buffer
    val detail: String?,
    val hint: String?,
    val internalQuery: String?,
    val cause: Throwable?,
)

data class SourcePosition(val offset: Int, val length: Int = 1)
```

`position` is the feature that makes this tool feel precise, and it is the easiest thing to
lose in a refactor. Two hazards:

1. Postgres reports a **1-based position in characters — code points — within the
   statement it received**. The editor needs a **0-based UTF-16 offset within the whole
   buffer**. That mapping involves the statement's own start offset and a code-point-aware
   conversion.

   > **Repo check — this is not in `PostgresErrors.kt`.** `PostgresErrors` only lifts the
   > server's raw `position` onto `DbError.QueryFailed`. The mapping is
   > `Statement.documentIndex` in **`core/sql/StatementSplitter.kt`**, called from
   > `app/EditorViewModel.kt`. Move *that*, do not rewrite it, and keep its tests — which
   > means the mapping is shared SQL machinery bound for `:engine-sql`, not something that
   > goes into `:engine-postgres`.
   >
   > `Statement.isIntactIn` sits beside it and matters just as much: the underline is only
   > drawn when the statement's text still sits at the same offset, so an edit made while
   > the statement was running clears the marker instead of underlining an innocent word.
   > Nothing else enforces that. `ErrorPositionTest` pins both down.
2. MySQL does **not** give a position. `EngineError.position` will be null and the UI must
   degrade to underlining the whole statement — implement that fallback in the UI once,
   not per engine.

---

## 7. Safety across engines

Today `DataSafetyPolicy.kt` gates writes and `RedisCommandGuard.kt` classifies commands.
Split the responsibility cleanly:

- **Classification is per-engine.** Only the Postgres driver knows that `SELECT ... FOR
  UPDATE` takes locks; only the Redis driver knows `FLUSHALL` is server-stopping.
- **Policy is core.** Given an intent plus environment plus writable flag, core decides:
  allow / confirm / type-the-name / refuse.

```kotlin
// engine-api
enum class WriteIntent { READ_ONLY, WRITE, DDL, DESTRUCTIVE, SERVER_AFFECTING, CONNECTION_AFFECTING, UNKNOWN }

interface IntentClassifier { fun classify(statement: String): WriteIntent }

// core — unchanged decision table, now engine-independent
enum class Gate { ALLOW, CONFIRM, CONFIRM_TYPED_NAME, REFUSE }
fun DataSafetyPolicy.gate(intent: WriteIntent, env: Environment, writable: Boolean): Gate
```

`UNKNOWN` must be treated as at least `WRITE`. Any classifier that cannot parse a statement
returns `UNKNOWN`, never `READ_ONLY`.

### Server-side enforcement matrix

Client-side classification is a UX affordance, not a security control. Enforce read-only at
the server wherever the engine allows it, at connect time:

| Engine | Mechanism |
|---|---|
| PostgreSQL | `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` (or `default_transaction_read_only=on` in connection params) |
| MySQL | `SET SESSION transaction_read_only = ON` (5.7.20+); verify by reading it back |
| SQLite | open with `?mode=ro` on the file URI, plus `PRAGMA query_only = ON` |
| Redis | no session-level equivalent — `RedisCommandGuard` stays the sole control |

```kotlin
enum class ReadOnlyEnforcement { SESSION_SETTING, CONNECTION_URI, COMMAND_GUARD_ONLY }
```

Where enforcement is `COMMAND_GUARD_ONLY`, the connection list must say so. Do not present
Redis and Postgres read-only connections with identical UI affordances when one is
server-enforced and the other is not.

---

## 8. Runtime driver provisioning

### 8.1 The decision, stated honestly

Downloading drivers at runtime resolves the licensing problem (GPL/LGPL/OTN drivers cannot
be bundled in a permissively-licensed installer) at the cost of the "no network" property
and by introducing a remote-code-loading path into a security-sensitive app.

The design below narrows both costs. It differs from DBeaver in one deliberate way:

> **Bundle every driver whose licence permits it. Download only what you legally cannot ship.**

| Engine | Driver | Licence | Decision |
|---|---|---|---|
| PostgreSQL | `org.postgresql:postgresql` | BSD-2-Clause | **bundle** |
| Redis | `io.lettuce:lettuce-core` | Apache-2.0 | **bundle** |
| SQLite | `org.xerial:sqlite-jdbc` | Apache-2.0 | **bundle** |
| MySQL | `com.mysql:mysql-connector-j` | GPL-2.0 + FOSS exception | **download** |
| MariaDB | `org.mariadb.jdbc:mariadb-java-client` | LGPL-2.1 | **download** |
| Oracle | `com.oracle.database.jdbc:ojdbc11` | OTN (click-through) | **download + explicit acceptance** |
| SQL Server | `com.microsoft.sqlserver:mssql-jdbc` | MIT | **bundle** (if added) |

Consequence: a default install still connects to PostgreSQL, Redis and SQLite **fully
offline**, which is the promise most users care about. Network access happens only when a
user deliberately adds a MySQL connection.

I am not a lawyer and this table is a starting point, not legal advice — have the licence
column reviewed against Caracal's own licence before the release that ships it.

### 8.2 Constraint: JDBC only

Runtime provisioning applies **only to JDBC drivers**, because `java.sql.Driver` is a
stable API surface that can be reached reflectively across a classloader boundary. Non-JDBC
clients (Lettuce, a future Mongo driver) must be compile-time dependencies. Encode this:
`DriverRequirement` is only expressible for engines whose `family == SQL`.

### 8.3 Data model

```kotlin
@Serializable
data class DriverManifest(
    val manifestVersion: Int,
    val appVersion: String,          // manifest ships with the app; this is a sanity check
    val drivers: List<DriverSpec>,
)

@Serializable
data class DriverSpec(
    val id: String,                  // "mysql-connector-j"
    val engineId: String,
    val displayName: String,         // "MySQL Connector/J"
    val version: String,             // pinned, exact
    val driverClass: String,         // "com.mysql.cj.jdbc.Driver"
    val licence: LicenceInfo,        // spdxId, name, url, requiresExplicitAcceptance
    val artifacts: List<DriverArtifact>,   // main jar + every transitive dep, all pinned
    val totalBytes: Long,
)

@Serializable
data class DriverArtifact(
    val coordinates: String,         // "com.mysql:mysql-connector-j:9.1.0"
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,              // lowercase hex, mandatory
    val urls: List<String>,          // https only, primary + mirrors
)
```

The manifest is **generated at build time and shipped inside the app jar**. Do not fetch a
manifest over the network at runtime: a downloadable manifest means an attacker who
controls it controls which code you load, and the checksum pinning becomes worthless.
Driver updates ship with app updates. Write this rationale into
`docs/architecture/drivers.md` so it is not "simplified" away later.

Generate the manifest with a Gradle task that resolves a detached configuration and hashes
the artifacts, so versions and checksums can never drift from what was tested:

```kotlin
// buildSrc or build-logic
abstract class GenerateDriverManifest : DefaultTask() {
    @get:Input abstract val driverCoordinates: MapProperty<String, String>
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction fun run() {
        // for each entry: project.configurations.detachedConfiguration(dependency)
        //   .resolvedConfiguration.resolvedArtifacts -> file, sha256, size, coordinates
        // emit JSON, sorted deterministically
    }
}
```

Check the generated manifest into the repo and **fail the build if regeneration produces a
diff** — that turns a silent supply-chain change into a reviewable one.

### 8.4 On-disk layout

```
${appDataDir}/drivers/
    cache/<sha256>/<fileName>.jar        content-addressed
    installed.json                        which specs are resolved, and how
    imports/<sha256>/<fileName>.jar       user-supplied jars (§8.8)
```

Content addressing means verification is structural: a file at path `cache/<h>/x.jar` whose
content does not hash to `<h>` is deleted on sight, and a partially-written file (always
written as `*.part` then atomically renamed) can never be mistaken for a good one.

`appDataDir`: `%LOCALAPPDATA%\Caracal` on Windows, `~/Library/Application Support/Caracal`
on macOS, `${XDG_DATA_HOME:-~/.local/share}/caracal` on Linux.

### 8.5 Provisioning service

```kotlin
interface DriverProvider {
    /** Returns a ready bundle, or null if not installed. Never downloads. */
    fun resolve(spec: DriverSpec): DriverBundle?
}

interface DriverProvisioner : DriverProvider {
    fun status(spec: DriverSpec): DriverStatus
    /** Requires prior user consent; caller must have shown the consent dialog. */
    fun install(spec: DriverSpec, consent: DriverConsent): Flow<InstallProgress>
    suspend fun importLocal(spec: DriverSpec, jars: List<Path>): ImportResult
    suspend fun remove(spec: DriverSpec)
}

sealed interface DriverStatus {
    object Installed : DriverStatus
    object NotInstalled : DriverStatus
    data class PartiallyInstalled(val missing: List<DriverArtifact>) : DriverStatus
    data class Corrupt(val artifact: DriverArtifact, val expected: String, val actual: String) : DriverStatus
    data class ImportedUnverified(val sha256: String) : DriverStatus
}

sealed interface InstallProgress {
    data class Downloading(val artifact: String, val bytesDone: Long, val bytesTotal: Long) : InstallProgress
    data class Verifying(val artifact: String) : InstallProgress
    data class Done(val bundle: DriverBundle) : InstallProgress
    data class Failed(val reason: InstallFailure) : InstallProgress
}
```

Download rules:

- **HTTPS only.** Reject any manifest URL not starting with `https://` at parse time.
- **Host allowlist**, compiled in: `repo1.maven.org` plus any mirror the user configures
  explicitly in Settings. A user-configured mirror does not relax checksum verification.
- **Single-flight**: a `Mutex` keyed by sha256, so two connection attempts do not race on
  the same artifact.
- **Verify before use, always**: stream the download through a `MessageDigest`, compare to
  the manifest, and only then rename `.part` into place. On mismatch: delete, do not retry
  automatically, surface `InstallFailure.ChecksumMismatch` with both hashes.
- **Size guard**: refuse a response whose `Content-Length` or actual bytes exceed
  `sizeBytes` by more than a small tolerance.
- **Timeouts**: 15 s connect, 60 s idle. Resume with `Range` if a `.part` exists and the
  server supports it; otherwise start over.
- **Proxy**: honour `HTTPS_PROXY`/`https_proxy`, JVM `https.proxyHost`, and an explicit app
  setting. Corporate networks are exactly where MySQL connections live.
- **No telemetry.** The download is a plain GET for a jar. Send no identifying headers
  beyond a `User-Agent` of `Caracal/<version>`.

### 8.6 Classloading

```kotlin
class DriverBundle(
    val spec: DriverSpec,
    private val loader: URLClassLoader,
) : AutoCloseable {
    fun newDriver(): java.sql.Driver {
        val cls = Class.forName(spec.driverClass, true, loader)
        return cls.getDeclaredConstructor().newInstance() as java.sql.Driver
    }
    override fun close() = loader.close()
}
```

Use a **child-first** loader so the driver's bundled dependencies (protobuf, etc.) do not
collide with the app's, but keep platform and API classes parent-delegated:

```kotlin
class DriverClassLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
    private val parentFirstPrefixes = listOf("java.", "javax.", "jdk.", "sun.", "caracal.engine.api.")

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            if (parentFirstPrefixes.any { name.startsWith(it) }) return super.loadClass(name, resolve)
            return try {
                findClass(name).also { if (resolve) resolveClass(it) }
            } catch (e: ClassNotFoundException) {
                super.loadClass(name, resolve)
            }
        }
    }
}
```

Critical details:

- **Never call `DriverManager.registerDriver`.** `DriverManager` will not see a class from
  a foreign loader anyway. Call `driver.connect(url, props)` directly. This also means the
  driver never becomes globally visible, which is what you want.
- **Close the loader** when the last connection using it closes, and on app shutdown.
  MySQL Connector/J starts an abandoned-connection cleanup thread; call
  `com.mysql.cj.jdbc.AbandonedConnectionCleanupThread.checkedShutdown()` reflectively (in a
  `try`/`catch`, ignoring absence) before closing, or you leak a thread per bundle.
- Set the thread context classloader to the driver loader around `connect()`; some drivers
  use it for ServiceLoader lookups internally.
- One loader per `DriverSpec` **version**, cached, not one per connection.

### 8.7 Consent

Before the first download of a given spec, show a dialog containing: driver name and exact
version, licence name with a link, total download size, the source URLs, and the expected
SHA-256. Buttons: **Download**, **Import jar from disk…**, **Cancel**.

Persist acceptance keyed by `(spec.id, spec.version)` in `installed.json`. A version bump
re-prompts — that is intentional, since it is a new artifact.

For `requiresExplicitAcceptance` licences (Oracle OTN), the licence text must be shown and
the accept button disabled until the user scrolls it. Do not fake this.

### 8.8 Offline and air-gapped path

Every download dialog offers **Import jar from disk**. On import:

1. Hash the file. If it matches a manifest artifact → store under `cache/<sha256>/` and
   mark `Installed`. This is the air-gap workflow: download on a connected machine, copy
   over, and the app treats it as fully verified.
2. If it does not match → store under `imports/<sha256>/`, mark `ImportedUnverified`, and
   show a persistent badge on every connection using it: *"driver not verified against the
   bundled manifest"*. Allow it; this is how users run a vendor-patched or older driver.
   Never let unverified silently look like verified.

Also support `--driver-dir <path>` on the CLI and a `CARACAL_DRIVER_DIR` env var so
administrators can pre-seed the cache for a whole fleet.

### 8.9 Failure messages

Follow the existing house style — name the actual failure, as the connection test button
already does:

| Condition | Message |
|---|---|
| No network | `Couldn't reach repo1.maven.org. Check your connection or proxy settings, or import the jar from disk.` |
| Checksum mismatch | `Downloaded file doesn't match the expected checksum and was discarded. Expected <a>, got <b>. This can mean a corrupted download or a tampered mirror.` |
| Driver class missing | `<jar> doesn't contain <driverClass>. This jar may be the wrong artifact.` |
| Proxy auth required | `The HTTP proxy requires authentication (407). Configure proxy credentials in Settings → Network.` |

### 8.10 Documentation honesty

The README currently claims *"Local only — no network listener, no telemetry, no account."*
All three remain true. Add a fourth line rather than quietly leaving the impression intact:

> Caracal makes no network requests of its own. The only exception is downloading a
> database driver, which happens only when you add a connection for an engine whose driver
> we can't legally bundle, only after you approve it, and only from Maven Central over
> HTTPS with a pinned SHA-256 checksum. PostgreSQL, Redis and SQLite drivers ship with the
> app and never require a download.

Add `docs/architecture/drivers.md` with the threat model: what pinning protects against (a
compromised mirror, a hijacked coordinate), what it does not (a compromised Caracal
release, malware already on the machine), and why the manifest is not remotely refreshable.

---

## 9. Phase plan

### Phase 0 — Characterization tests (no production code changes)

Lock in the behaviour that must survive. Write tests against the **current** code:

- `PostgresErrors`: at least 12 cases mapping a server error to a character offset,
  including multi-statement buffers, statements preceded by comments, and a statement
  containing non-ASCII text before the error position.
- `PostgresValues`: `numeric(38,10)` round-trip, `int8` at `Long.MAX_VALUE`, `numeric` with
  more digits than `Double` can hold, `NaN`, `Infinity`, `NULL` in every type.
- `StatementSplitter`: dollar-quoted bodies, nested `$tag$`, semicolons in strings,
  comments, `E''` escapes, unterminated literal at EOF.
- `RedisCommandGuard`: every currently-refused and currently-prompted command, plus casing
  and whitespace variants.
- `CsvExport`: the formula-injection neutralization cases.
- `DataSafetyPolicy`: the full decision table as a parameterized test.

**Acceptance:** these tests pass on unmodified `main`, and coverage of the four named files
is above 85%. Commit as `test: characterize engine behaviour before multi-engine refactor`.

> **Done 2026-08-22.** Most of this was already there — `PostgresErrorsTest` (23),
> `StatementSplitterTest` (37), `RedisCommandGuardTest` (20), `CsvWriterTest` (25, formula
> neutralization included). Three things were not:
>
> - **`PostgresValues` had no test that runs without a server.** It is `internal` and reads
>   from a `ResultSet`, so its only exercise was the container-gated
>   `PostgresTypesIntegrationTest`, which does not run on an ordinary `check`. The
>   precision guarantee §6.1 is built around had nothing standing behind it.
>   `PostgresValuesTest` now covers it with a proxy `ResultSet` that answers only the
>   getter each type actually calls — so a change from `getString` to `getBigDecimal`
>   fails, which is the change that breaks `NaN`.
> - **The error position mapping had five assertions, not twelve.** `ErrorPositionTest`
>   takes it to 21, across multi-statement buffers, leading line and block comments, CRLF,
>   dollar-quoted bodies, BMP and non-BMP text either side of the error, and every
>   out-of-range position.
> - **No coverage tooling was configured at all**, so the 85% criterion could not be
>   measured. Kover is now on `:core`, and `assertCharacterizationCoverage` holds each
>   named class to 85% individually — and fails if one vanishes from the report, which is
>   what Phase 1 will do to every one of them.
>
> Measured after: `PostgresErrors` 92%, everything else 100%.

### Phase 1 — Extract `:engine-api`, no behaviour change

Create the module and the interfaces in §3–§6. Make `PostgresSession` and `RedisSession`
implement them by delegating to existing code. Do not move logic yet — wrap it.

**Acceptance:** Phase 0 tests unchanged and green. `:ui` untouched. Architecture test from
§2.2 added but allowed to fail (mark `@Disabled` with a TODO naming Phase 2).

### Phase 2 — Flip the UI onto the SPI

Replace direct engine imports in `:ui` with `DatabaseSession` + facets + capabilities.
Every `when (engine)` becomes a capability or facet check.

**Acceptance:** §2.2 architecture test enabled and green. Manual smoke: connect to
Postgres and Redis, run a query, browse keys, cancel a statement, trigger an error and
confirm the underline is still on the exact character.

### Phase 3 — Engine registry

`ServiceLoader<DatabaseEngine>` registration in `:app`; engine picker in the connection
dialog; declarative `ConnectionForm` rendering.

**Acceptance:** adding a `DatabaseEngine` implementation to the classpath makes it appear
in the UI with zero UI code changes. Prove it with a fake `:engine-test` module used only
in tests.

### Phase 4 — Vault v2

`SecretBundle`, versioned records, migration, the checked-in v1 fixture test (§5.3).

**Acceptance:** v1 fixture unlocks, migrates, and is readable after migration. Kill the
process mid-migration in a test (inject a failure after N records) and assert the vault
still unlocks.

### Phase 5 — Driver provisioning

Everything in §8, but wired to a **local test repository** (a temp directory served by a
test HTTP server) rather than Maven Central, so the whole thing is testable offline.

**Acceptance:** tests cover happy path, checksum mismatch, truncated download, resume,
missing driver class, unverified import, allowlist rejection of an `http://` URL,
concurrent installs of the same artifact.

### Phase 6 — SQLite engine

Chosen deliberately as the first new engine: no host, no port, no credentials, file target,
`INTERRUPT` cancellation, `NONE` namespace model. It stress-tests the generalizations of
Phases 3–4 while being cheap to implement and requiring no download path.

**Acceptance:** the conformance suite (§10) passes for SQLite. Read-only enforcement
verified by attempting a write on a `mode=ro` connection and asserting the server refuses.

### Phase 7 — MySQL engine

First engine using the download path. Exercises: `information_schema` catalog, backtick
quoting, `KILL QUERY` cancellation from a side connection, `BIGINT UNSIGNED`, no error
positions, databases-not-schemas.

**Acceptance:** conformance suite passes against MySQL 8.0 and 8.4 and MariaDB 11 in
Testcontainers, with the driver provisioned through the real manifest against a local
mirror.

### Phase 8 — Release engineering

Manifest generation task wired into CI, licence table reviewed, docs updated,
`--driver-dir` implemented, driver management screen in Settings.

---

## 10. Conformance suite

The thing that stops five engines becoming five rotting codebases. Every engine module runs
the same abstract test class; engines declare capabilities, and tests that do not apply are
skipped **explicitly by capability**, never by engine name.

```kotlin
abstract class EngineConformanceTest {
    abstract fun engine(): DatabaseEngine
    abstract fun connectFixture(): ConnectionFixture   // Testcontainers or temp file

    @Test fun `connects and reports server version`()
    @Test fun `ping round trips`()
    @Test fun `failed connection names the actual failure`()
    @Test fun `credentials never appear in error messages`()      // greps for the fixture password
    @Test fun `credentials never appear in logs`()
    @Test fun `read-only connection refuses a write at the server`()
    @Test fun `exact numeric types survive round trip`()          // skipIf family != SQL
    @Test fun `large integers do not lose precision`()
    @Test fun `cancel behaves per declared CancellationSupport`()
    @Test fun `catalog lists objects lazily`()
    @Test fun `notices and warnings are surfaced`()
    @Test fun `identifiers are quoted correctly for round trip`() // table named `weird "name`
    @Test fun `session closes cleanly and frees threads`()        // thread count before/after
    @Test fun `intent classifier never returns READ_ONLY for a write`()
}
```

The redaction tests are the highest-value ones: they mechanically enforce a security
property across every future engine, and they are the tests most likely to catch a new
contributor's driver that stuffs the password into a JDBC URL.

Version matrix in CI: PostgreSQL 13/15/17, Redis 6.2/7.2/8, MySQL 8.0/8.4, MariaDB 11,
SQLite in-process. Run the full matrix nightly and a single version per engine on PRs.

---

## 11. Definition of done for v0.2.0

- [ ] `:ui` compiles without any engine implementation on its classpath
- [ ] Conformance suite green for postgres, redis, sqlite, mysql
- [ ] v1 vault fixture migrates
- [ ] Error position underlining verified unchanged for Postgres by Phase 0 tests
- [ ] Driver download tested offline against a local repo; checksum mismatch refuses
- [ ] Air-gap path documented and tested (`--driver-dir`, import from disk)
- [ ] README network claim updated; `docs/architecture/drivers.md` written
- [ ] Licence table reviewed against Caracal's own licence
- [ ] Manifest regeneration produces no diff in CI

---

## 12. Do not abstract

Pulling these into shared code will make the product worse. They belong in engine modules,
duplicated if necessary:

- **Error parsing and position mapping.** Character-precise underlining is a differentiator
  and it is per-protocol. A shared `getMessage()` path destroys it.
- **Type mapping and precision handling.** Every engine's edge cases are its own.
- **Catalog queries.** `pg_catalog` and `information_schema` are not the same shape, and
  pretending otherwise produces a lowest-common-denominator browser.
- **DDL reconstruction.** Fully dialect-specific.
- **Identifier quoting and escaping.** Declared as a capability, applied by the engine.
- **The Redis command guard.** It is not a generic "dangerous statement" classifier and
  should not become one.
- **Connection URL construction.** Redaction correctness depends on knowing exactly how the
  URL is built (`Redaction.kt`); a generic URL builder is how credentials end up in logs.

Shared, correctly: statement splitting *configuration-driven*, result grid model, query
history, export, transaction state UI, safety policy decision table, driver provisioning,
vault, keyboard shortcuts, theming.

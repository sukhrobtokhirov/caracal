# Current state — ground truth before the multi-engine refactor

Recorded per §1 of `caracal-multi-engine-spec.md`, from the source rather than from
the spec's description. Where the two disagree, this file records what is actually
in the repository; the spec is corrected to match.

Established at commit `78fe44d`, version `0.1.0` (untagged). Sections marked
**Phase 1** below record what changed when the SPI was extracted at `66ef6a2`; the
rest still describes the repository as it stands.

---

## 1. Module and build layout

**Two modules, not nine.** `settings.gradle.kts` includes exactly `:core` and `:app`.

> **Phase 1.** Three now: `:engine-api`, `:core`, `:app`. `:engine-api` holds the SPI
> and depends on nothing but Kotlin and coroutines —
> `assertSpiHasNoDependencies` fails its `check` if anything else reaches its runtime
> classpath, in the same shape as `:core`'s `assertNoComposeDependency`. Both `:core`
> and `:app` depend on it; `:core` does so with `api`, since the SPI is in the
> signatures it hands out rather than an implementation detail behind them.
>
> The PostgreSQL and Redis implementations of the SPI live in `:core`, under the
> packages `dev.caracal.engine.postgres` and `dev.caracal.engine.redis` — the names
> they will have once `:engine-postgres` and `:engine-redis` are carved out, so that
> split is a file move rather than an import churn.

| Module | Contains |
|---|---|
| `:core` | vault, config store, connections, registry, policy, SQL machinery, postgres, redis, export, history. Kotlin/JVM only — no Compose. |
| `:app` | Compose Desktop UI, view models, packaging, `BuildInfo`. Depends on `:core`. |

There is **no `:ui` module**. The UI lives in `:app` under `dev.caracal.app` and
`dev.caracal.app.ui`. The spec's §2.2 architecture test therefore has to be written
against `:app`, not `:ui`, until the module split actually happens.

`:core` already enforces one structural invariant mechanically, and it is the model
to copy for §2.2: `assertNoComposeDependency` resolves the runtime and test
classpaths and fails the build if any `org.jetbrains.compose`, `androidx.compose`,
or `org.jetbrains.skiko` artifact appears. It is wired into `check`.

Root `build.gradle.kts` sets `jvmToolchain(25)` and JUnit Platform for every
subproject, and promotes JUnit discovery issues to failures
(`junit.platform.discovery.issue.severity.critical=INFO`) — a test method that
returns a value is a build error here, not a silently skipped test.

**No coverage tooling is configured.** Neither JaCoCo nor Kover is on the build.
Phase 0's ">85% coverage" acceptance criterion cannot be measured as the repo
stands; adding it is part of Phase 0. *(Done: Kover is on `:core` and
`:engine-api`, and `assertCharacterizationCoverage` holds the eight named classes to
85% individually.)*

## 2. Package naming

The spec writes `caracal.engine.api`. The repository root package is
**`dev.caracal`** (`dev.caracal.core.*`, `dev.caracal.app.*`), and the macOS bundle
ID is `dev.caracal.app`. New SPI packages must therefore be
**`dev.caracal.engine.api`**, and the §2.2 forbidden-import list must read
`dev.caracal.engine.postgres` and so on.

> **Phase 1.** The forbidden list needs both spellings, not just that one. The engines
> are still in `dev.caracal.core.postgres` and `dev.caracal.core.redis`, so a list
> containing only the `engine` packages passes today by matching nothing — the same
> vacuous pass the missing `dev.` prefix would have caused, one layer down.
> `ArchitectureTest` forbids both, and a second, enabled test asserts the scan finds
> sources at all.

## 3. Where the named files actually live

Every file the spec names exists, under `dev.caracal.core`:

| Spec name | Actual path |
|---|---|
| `Vault.kt`, `Kdf.kt`, `Seal.kt` | `core/…/core/vault/` |
| `DataSafetyPolicy.kt` | `core/…/core/policy/` |
| `Redaction.kt` | `core/…/core/text/` |
| `PostgresCatalog.kt`, `PostgresErrors.kt`, `PostgresValues.kt` | `core/…/core/postgres/` |
| `StatementSplitter.kt` | `core/…/core/sql/` |
| `RedisCommandGuard.kt` | `core/…/core/redis/` |
| `RedisKeyTree.kt` | `app/…/app/` — a **UI** concern, not core |
| `RedisValueViewer.kt` | `app/…/app/ui/` |

Note the last two: the spec lists `RedisKeyTree.kt` among core engine files, but the
key-tree grouping is presentation and lives in `:app`. The engine-side Redis code is
`RedisSession`/`RedisAdapter`/`RedisModel` in `:core`.

## 4. How tightly the UI is bound to concrete engines

**Tightly.** Fourteen files under `app/src/main` import `dev.caracal.core.redis.*` or
`dev.caracal.core.postgres.*`:

```
RedisBrowserViewModel  RedisConsoleViewModel  RedisFormat  RedisInfoViewModel
RedisKeyTree  RedisValueViewModel  RedisWorkspace
ui/Badges  ui/CommandConfirmation  ui/Glyphs  ui/RedisConsole
ui/RedisInfoDashboard  ui/RedisKeyBrowser  ui/RedisValueViewer
```

There is **no existing indirection** — no session interface, no facet, no capability
object. View models hold a `RedisSession`/`PostgresSession` directly.

> **Phase 1.** Still true of `:app`, deliberately: the phase's acceptance criterion is
> that `:ui` is untouched. What changed is that the indirection now exists and is
> tested — `DatabaseSession`, `QueryFacet`, `CatalogFacet`, `EngineCapabilities` — so
> Phase 2 has somewhere to move the view models *to*. Thirteen files still import the
> concrete engines, and `ArchitectureTest` is the disabled test that will say when
> none do.
>
> **Phase 2. No longer true: none do.** `ArchitectureTest` is enabled and green. The
> view models never held a session — they go through `ConnectionService` and always
> did — so what actually bound them was the *vocabulary*: a key, a paged value, a
> reply tree, a server summary, all named `dev.caracal.core.redis.*`. Those moved
> into `:engine-api`, losing the engine's name where the concept was general
> (`RedisKey` → `KeyRef`, `RedisText` → `TextValue`, `RedisReply` → `CommandReply`,
> `RedisCursor` → `ScanCursor`, `RedisLimits` → `KeyValueLimits`), and `KeyValueFacet`,
> `CommandFacet` and `MetricsFacet` are declared in terms of them. `CommandClearance`
> and `CommandConfirmationRequired` moved to `dev.caracal.core.policy`, next to the
> SQL `Clearance` they are the analogue of — the guard that produces them stays in the
> engine, per §12.

`when (engine)` switches, all on the `Engine` enum:

| Site | Decides |
|---|---|
| `app/ui/WorkspaceScreen.kt:80` | which workspace tabs exist |
| `app/ui/WorkspaceScreen.kt:529` | which workspace body to render |
| `app/ui/Glyphs.kt:65` | engine glyph |
| `app/ui/EngineLogo.kt:55,88` | engine colour and mark |
| `app/ui/SettingsDialog.kt:189,196` | engine-specific settings copy |
| `core/connections/Connection.kt:85` | which TLS modes are offered |
| `core/connections/ConnectionDraft.kt:96` | which database-field validation runs |
| `core/connections/ConnectionService.kt:366` | which probe the test button runs |
| `core/registry/ConnectionRegistry.kt:109` | which session type to open |

Each of these is a Phase 2 target. The three UI presentation switches (glyph, logo,
tabs) become engine-declared metadata; the two `:core` switches become
`DatabaseEngine` methods; `ConnectionDraft`'s becomes `engine.validate()`;
`Connection.kt`'s becomes a capability.

> **Phase 2 took the behavioural ones and left the rest, on purpose.**
>
> Gone: the workspace tab list, the workspace body, and which pane the keyboard lands
> in all read `EngineCapabilities.family` now; the settings and connection-dialog copy
> reads `DatabaseEngine.displayName` and `capabilities.defaultPort`; the registry
> dials through `DatabaseEngine.connect` and has one accessor, `session(id)`, instead
> of two typed ones.
>
> Still switching, and deliberately: the connection **form** — which fields a dialog
> draws, what they are called, which are optional, what a Redis database field
> defaults to. Every one of those facts is already declared in
> `DatabaseEngine.connectionForm`, and rendering that declaration is Phase 3's stated
> job. Converting them by hand now would be writing the same table twice.
>
> Also still switching, and permanently: **artwork**. `Glyphs.of` and `EngineLogo` key
> on the engine because a logo cannot be declared in `:engine-api` — putting a drawing
> in the module every other module depends on drags a UI toolkit in behind it. Phase 3
> gives them an `EngineId` key and a fallback mark, so an engine with no artwork gets
> a generic one rather than a blank.
>
> The three `:core` switches that remain — `TlsMode.supportedBy`,
> `ConnectionDraft`'s database validation, and the test-button probe — are `:core`'s
> to make and are not what the rule is about. `Engines` in
> `dev.caracal.core.engines` is now the one place that knows which engines exist, and
> Phase 3 replaces its body with a `ServiceLoader` without anything above it changing.
>
> **Phase 3. None of the nine are left, and neither are the three.** `Engines` reads
> `ServiceLoader<DatabaseEngine>`; the connection dialog draws the engine's declared
> sections and fields; `TlsMode.supportedBy` is gone, replaced by reading the options
> off the engine's own `tls` choice; `ConnectionDraft`'s database validation is the
> generic declared-rule half plus whatever `engine.validate` says; and the test button
> dials through `DatabaseEngine.connect`, which deleted `PostgresProbe` and
> `RedisSession.test` outright. What is still keyed on an engine's name is the
> artwork, with a generic mark behind it for the engines this build has never seen.

`ConnectionRegistry` already has the shape the SPI wants: a private sealed
`RuntimeClient` with `Postgres`/`Redis` arms, and typed accessors `postgres(id)` /
`redis(id)` that throw `WrongEngineException` on a mismatch. That sealed hierarchy is
what `DatabaseSession` + facets replaces.

> **Phase 1 left it untouched.** `DatabaseEngine.connect` is a second, parallel way to
> open a session; the registry still opens them the old way. Repointing it at
> `DatabaseSession` is what makes the `:ui` flip possible, so it belongs with that
> flip rather than a phase before it.
>
> **Phase 2 repointed it.** `RuntimeClient` is gone; an entry holds a
> `DatabaseSession`, opened by `Engines.of(engine).connect(...)`. `postgres(id)` and
> `redis(id)` are gone with it, replaced by `session(id)` — and by one named escape
> hatch, `postgresAdapter(id)`, which `execute` and `exportCsv` still use because
> `QueryFacet` streams outcomes and `:core` returns a whole `QueryResult`.
> Reconciling those is a change to the result model, to the error position mapping
> that rides on it, and to every grid that reads one; it is not a change to make in
> the same phase as a module boundary, and Phase 2 stops there and says so.
>
> The translation between `ConnectionConfig` and `ConnectionDescriptor` lives in
> `core/registry/Descriptors.kt`. One thing in it is not mechanical: **`require`
> means two different things**. PostgreSQL's `require` encrypts and accepts any
> certificate; Redis's `require` verifies, because the Redis client has always been
> built with `verifyPeer` and that is the only secure mode it offers. The SPI has no
> encrypt-but-do-not-verify arm, so the stored word is translated per engine rather
> than flattened, and `verify-full` on Redis is refused rather than quietly
> downgraded. `DescriptorsTest` is that table.

## 5. Engine model today

`core/connections/Connection.kt:40`:

```kotlin
enum class Engine(val wire: String, val defaultPort: Int) { POSTGRES, REDIS }
```

The connection descriptor is `ConnectionConfig`, host/port/database/user shaped, with
`environment`, `readOnly`, and a TLS mode. It has no target sum type, so §5.1's
`ConnectionTarget` (needed for SQLite's file path) is a real change, not a rename.

> **Phase 1.** `ConnectionDescriptor` with its `ConnectionTarget` sum type now exists
> in `:engine-api`, and both engines map from it. `ConnectionConfig` is unchanged and
> is still what the store, the registry and the UI use; the engines convert at their
> own edge. `ConnectionId` and `Environment` moved into `:engine-api` — a descriptor
> names both — with typealiases left in `dev.caracal.core.connections` so nothing else
> had to move. The `Engine` enum survives untouched and is replaced by `EngineId` plus
> a registry in Phase 3.
>
> **Phase 3 did exactly that, and took the shape with it.** The enum is gone.
> `ConnectionConfig` holds an `EngineId`, a `ConnectionTarget` — so a file is
> expressible and not only a host and a port — and a `Map<String, String>` of whatever
> the engine's form declared, stored in a `connection_settings` table that schema 4
> adds. `host`, `port`, `database`, `username` and `tlsMode` survive as accessors over
> those two, because they are what the well-known keys in `FormKeys` mean, and because
> forty call sites reading `config.host` are forty call sites that do not care.
>
> Reading a stored engine name can no longer fail. A connection naming an engine this
> build does not have lists, shows what it points at, and refuses to open with a
> sentence — where `Engine.from` used to fail the entire store read.

## 6. Dependency surface

`:core` — `postgresql` 42.7.13, `lettuce` 7.7.0, `hikaricp` 7.1.0, `sqlite-jdbc`
3.53.2.1, `bouncycastle` 1.85.2, coroutines, slf4j.

**`sqlite-jdbc` is already a dependency**, but not as a database engine: it backs
`core/store/ConfigStore.kt`, the local configuration and history store. Phase 6 gets
the driver for free, and must not assume the artifact's presence means engine support
exists.

Testing: JUnit 5.14.2, Testcontainers 1.21.4 (`postgresql` module only — MySQL and
MariaDB modules must be added for Phase 7). Integration tests are opt-in behind
`CARACAL_INTEGRATION`, declared as a task input so flipping the flag re-runs them.
`:core` tests run with `java.awt.headless=true`.

## 7. Error position mapping — the spec is wrong about where it is

§6.2 says the 1-based-position-to-buffer-offset mapping "is currently correct in
`PostgresErrors.kt`". **It is not there.** `PostgresErrors` only lifts the server's
raw `position` (1-based, within the statement it received) onto
`DbError.QueryFailed.position`.

The mapping lives in **`core/sql/StatementSplitter.kt`**, on `Statement`:

```kotlin
fun documentIndex(postgresPosition: Int): Int?   // 1-based chars -> 0-based UTF-16 offset
fun isIntactIn(document: String): Boolean
```

It already handles the code-point conversion the spec warns about
(`offsetByCodePoints`, so a non-BMP character before the error does not shift the
underline) and the end-of-input position (`characters + 1` maps to `end`). It is
called from `app/EditorViewModel.kt:416`.

`isIntactIn` is a second guarantee the spec does not mention and Phase 2 must not
lose: the underline is only drawn if the statement's text still sits at the same
offset, so an edit made while the statement was running clears the marker rather than
underlining an innocent word.

Consequence for the phase plan: "move it, do not rewrite it, and keep its tests"
applies to `StatementSplitter.kt`, and that file is shared SQL machinery
(`:engine-sql`), not Postgres-specific.

## 8. Existing test coverage in the Phase 0 areas

| File under test | Test file | Tests |
|---|---|---|
| `PostgresErrors` | `PostgresErrorsTest` | 23 |
| `StatementSplitter` / `Statement` | `StatementSplitterTest` | 37 |
| `RedisCommandGuard` | `RedisCommandGuardTest` | 20 |
| `DataSafetyPolicy` | `DataSafetyPolicyTest` | 9 |
| `CsvWriter` (formula neutralization) | `CsvWriterTest` | 25 |
| `CsvExport` | `CsvExportTest` | 14 |
| **`PostgresValues`** | **none** | **0** |

`PostgresValues` is `internal` and reads from a `java.sql.ResultSet`, so its only
current exercise is `PostgresTypesIntegrationTest`, which is container-gated and
therefore does not run on an ordinary `./gradlew check`. **This is the largest Phase 0
gap** and the one guarding the precision guarantee §6.1 is built around.

## 9. Release prerequisite

The spec's prerequisite is "v0.1.0 tagged and released from current `main`". Neither
holds: the only tag is `go-implementation` (from the pre-Kotlin implementation), and
the working branch is `master`, not `main`. `gradle.properties` says `version=0.1.0`
and the release workflow refuses a tag that disagrees with it.

Phase 0 is test-only and safe to do ahead of the tag. Phase 1 onward should wait for
it, since v0.1.0 is the baseline every migration test is written against.

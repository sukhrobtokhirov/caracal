package dev.dbide.app

import dev.dbide.core.catalog.CatalogObject
import dev.dbide.core.catalog.ColumnInfo
import dev.dbide.core.catalog.Listing
import dev.dbide.core.catalog.ObjectKind
import dev.dbide.core.catalog.SchemaInfo
import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionDraft
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionService
import dev.dbide.core.connections.ConnectionSummary
import dev.dbide.core.connections.ConnectionView
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.RuntimeState
import dev.dbide.core.connections.RuntimeStatus
import dev.dbide.core.connections.Secret
import dev.dbide.core.connections.SecretUpdate
import dev.dbide.core.connections.TestResult
import dev.dbide.core.connections.TlsMode
import dev.dbide.core.connections.ValidationException
import dev.dbide.core.export.CsvExportReport
import dev.dbide.core.export.CsvOptions
import dev.dbide.core.export.ExportLimits
import dev.dbide.core.redis.CommandClearance
import dev.dbide.core.redis.CommandConfirmationRequired
import dev.dbide.core.redis.CommandConsent
import dev.dbide.core.redis.CommandResult
import dev.dbide.core.redis.KeyMetadata
import dev.dbide.core.redis.KeyType
import dev.dbide.core.redis.MemoryEstimate
import dev.dbide.core.redis.RedisCommand
import dev.dbide.core.redis.RedisCursor
import dev.dbide.core.redis.RedisKey
import dev.dbide.core.redis.RedisReply
import dev.dbide.core.redis.ScanPage
import dev.dbide.core.redis.ScanStop
import dev.dbide.core.redis.ServerInfo
import dev.dbide.core.redis.Ttl
import dev.dbide.core.redis.ValuePage
import dev.dbide.core.redis.ValueRequest
import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column
import dev.dbide.core.result.ColumnFormat
import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import dev.dbide.core.result.QueryResult
import dev.dbide.core.store.ConnectionNotFoundException
import dev.dbide.core.vault.VaultLockedException
import dev.dbide.core.vault.VaultState
import dev.dbide.core.vault.WrongPasswordException
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred

/**
 * A stand-in for the real service.
 *
 * The rules `:core` enforces are tested against `:core`. What the UI needs from a
 * double is the shape of the answers and the ability to pause one mid-flight, which
 * is how a "busy" state gets asserted at all.
 */
open class FakeConnectionService(
    private var state: VaultState = VaultState.SETUP_REQUIRED,
    private val masterPassword: String = "correct-horse",
) : ConnectionService {

    val stored = linkedMapOf<ConnectionId, ConnectionView>()

    /** Names of the calls made, in order. Tests assert on what was, and was not, called. */
    val calls = mutableListOf<String>()

    /** The draft each save was given, so a test can prove what the form sent. */
    val drafts = mutableListOf<ConnectionDraft>()

    /** Set to hold the next operation open until the test completes it. */
    var gate: CompletableDeferred<Unit>? = null

    /** Set to make the next operation fail. */
    var nextFailure: Throwable? = null

    var testResult = TestResult(Engine.POSTGRES, "16.2", latencyMillis = 12)

    private var counter = 0

    override suspend fun vaultState(): VaultState = state

    override suspend fun setUp(password: Secret) {
        calls += "setUp"
        await()
        state = VaultState.UNLOCKED
    }

    override suspend fun unlock(password: Secret) {
        calls += "unlock"
        await()
        if (password.expose() != masterPassword) throw WrongPasswordException()
        state = VaultState.UNLOCKED
    }

    override suspend fun lock() {
        calls += "lock"
        state = VaultState.LOCKED
    }

    override suspend fun list(): List<ConnectionView> {
        calls += "list"
        await()
        requireUnlocked()
        return stored.values.sortedWith(
            compareBy({ it.config.environment.severity }, { it.config.name.lowercase() }),
        )
    }

    override suspend fun get(id: ConnectionId): ConnectionView {
        requireUnlocked()
        return stored[id] ?: throw ConnectionNotFoundException(id)
    }

    override suspend fun create(draft: ConnectionDraft): ConnectionView {
        calls += "create"
        drafts += draft
        await()
        requireUnlocked()
        val normalized = draft.normalized()
        normalized.validate().takeIf { it.isNotEmpty() }?.let { throw ValidationException(it) }
        val id = ConnectionId("id-${++counter}")
        return view(normalized.toConfig(id, CREATED_AT), draft.secret.storesSecret())
            .also { stored[id] = it }
    }

    override suspend fun update(id: ConnectionId, draft: ConnectionDraft): ConnectionView {
        calls += "update"
        drafts += draft
        await()
        requireUnlocked()
        val existing = stored[id] ?: throw ConnectionNotFoundException(id)
        val normalized = draft.normalized()
        normalized.validate().takeIf { it.isNotEmpty() }?.let { throw ValidationException(it) }
        val hasSecret = when (draft.secret) {
            SecretUpdate.Unchanged -> existing.hasSecret
            else -> draft.secret.storesSecret()
        }
        return view(normalized.toConfig(id, existing.config.createdAt), hasSecret, existing.runtime)
            .also { stored[id] = it }
    }

    override suspend fun delete(id: ConnectionId) {
        calls += "delete"
        await()
        requireUnlocked()
        stored.remove(id) ?: throw ConnectionNotFoundException(id)
    }

    override suspend fun test(id: ConnectionId): TestResult {
        calls += "test"
        await()
        requireUnlocked()
        return testResult
    }

    override suspend fun open(id: ConnectionId): ConnectionView {
        calls += "open"
        await()
        requireUnlocked()
        val existing = stored.getValue(id)
        return existing.copy(runtime = RuntimeState(RuntimeStatus.OPEN, openedAt = CREATED_AT))
            .also { stored[id] = it }
    }

    override suspend fun close(id: ConnectionId): ConnectionView {
        calls += "close"
        await()
        requireUnlocked()
        val existing = stored.getValue(id)
        return existing.copy(runtime = RuntimeState.CLOSED).also { stored[id] = it }
    }

    // --- Catalog -------------------------------------------------------------

    /** What the catalog answers with. Tests set the parts they care about. */
    var schemas: Listing<SchemaInfo> = Listing(listOf(SchemaInfo("public", owner = "dbide")))

    val objects = mutableMapOf<Pair<String, ObjectKind>, Listing<CatalogObject>>()

    val columns = mutableMapOf<Pair<String, String>, List<ColumnInfo>>()

    /** Set to fail one node's read without failing every other node's. */
    var failingSchema: String? = null

    override suspend fun schemas(id: ConnectionId, includeSystem: Boolean): Listing<SchemaInfo> {
        calls += "schemas(system=$includeSystem)"
        await()
        requireUnlocked()
        return if (includeSystem) schemas else Listing(schemas.items.filterNot { it.system })
    }

    override suspend fun objects(
        id: ConnectionId,
        schema: String,
        kind: ObjectKind,
    ): Listing<CatalogObject> {
        calls += "objects($schema, $kind)"
        await()
        requireUnlocked()
        refuse(schema)
        return objects[schema to kind] ?: Listing(emptyList())
    }

    override suspend fun columns(
        id: ConnectionId,
        schema: String,
        relation: String,
    ): List<ColumnInfo> {
        calls += "columns($schema, $relation)"
        await()
        requireUnlocked()
        refuse(schema)
        return columns[schema to relation].orEmpty()
    }

    /** Seeds one schema's objects and, for a relation, its columns. */
    fun seedObject(
        schema: String,
        name: String,
        kind: ObjectKind = ObjectKind.TABLE,
        columns: List<ColumnInfo> = emptyList(),
        signature: String? = null,
    ) {
        val key = schema to kind
        objects[key] = Listing(
            objects[key]?.items.orEmpty() + CatalogObject(schema, name, kind, signature = signature),
        )
        if (columns.isNotEmpty()) this.columns[schema to name] = columns
    }

    // --- Queries -------------------------------------------------------------

    /** The statements the editor sent, in order. */
    val executed = mutableListOf<String>()

    /** What every execution returns, unless [nextFailure] is set first. */
    var queryResult: QueryResult = QueryResult(
        columns = listOf(Column("one", "int4", ColumnFormat.NUMBER)),
        rows = listOf(listOf(CellValue.Integer(1))),
        duration = 3.milliseconds,
    )

    override suspend fun execute(id: ConnectionId, sql: String): QueryResult {
        calls += "execute"
        executed += sql
        await()
        requireUnlocked()
        return queryResult
    }

    /** Every export the editor asked for: the statement, and where it was written. */
    val exports = mutableListOf<Pair<String, Path>>()

    /** What every export reports, unless [nextFailure] is set first. */
    var exportReport = CsvExportReport(rows = 3, bytes = 64, duration = 5.milliseconds)

    /** What a completed export leaves in the file. */
    var exportContent: String = "one\r\n1\r\n"

    /**
     * Records the export and, on success, writes [exportContent].
     *
     * Deliberately *not* routed through `CsvExport.writeToFile`. That function opens
     * its writer on `Dispatchers.IO`, which a `TestScope` cannot advance past, and
     * what it guarantees — that a failed or cancelled export deletes its partial file
     * — is `:core`'s and is asserted against the real thing in `CsvExportTest` and
     * `PostgresCsvExportIntegrationTest`. A double that reimplemented it here would
     * only be a test of the double.
     */
    override suspend fun exportCsv(
        id: ConnectionId,
        sql: String,
        destination: Path,
        options: CsvOptions,
        limits: ExportLimits,
    ): CsvExportReport {
        calls += "exportCsv"
        exports += sql to destination
        await()
        requireUnlocked()
        destination.writeText(exportContent)
        return exportReport
    }

    private fun refuse(schema: String) {
        if (schema == failingSchema) {
            throw DbException(DbError.QueryFailed("permission denied for schema $schema"))
        }
    }

    // --- Redis, M3 -----------------------------------------------------------

    /** What [redisScan] hands back. A test that cares sets it; most do not. */
    var scanPage: ScanPage = ScanPage(
        cursor = RedisCursor.START,
        keys = emptyList(),
        iterations = 1,
        stopped = ScanStop.COMPLETE,
    )

    /** What [redisInfo] hands back. Restricted by default, which is the harder case. */
    var serverInfo: ServerInfo = ServerInfo(restricted = true)

    /** What [redisValue] hands back, or `null` to have it report a missing key. */
    var valuePage: ValuePage? = null

    /** What [redisCommand] hands back when the guard is satisfied. */
    var commandResult: CommandResult = CommandResult(
        command = "PING",
        reply = RedisReply.Status("PONG"),
        duration = 1.milliseconds,
        truncated = false,
    )

    /**
     * The consent each console command arrived with.
     *
     * §3.10's single-use rule is a UI property — the toggle resets after one execution
     * and is never saved — so it can only be asserted by recording what the UI actually
     * sent, command by command.
     */
    val consents = mutableListOf<CommandConsent>()

    override suspend fun redisInfo(id: ConnectionId): ServerInfo {
        calls += "redisInfo"
        await()
        requireUnlocked()
        return serverInfo
    }

    override suspend fun redisScan(
        id: ConnectionId,
        cursor: RedisCursor,
        match: String?,
        type: KeyType?,
        pageSize: Int?,
    ): ScanPage {
        calls += "redisScan($cursor, $match, ${type?.wire})"
        await()
        requireUnlocked()
        return scanPage
    }

    override suspend fun redisKey(id: ConnectionId, key: RedisKey): KeyMetadata {
        calls += "redisKey"
        await()
        requireUnlocked()
        return scanPage.keys.firstOrNull { it.key == key }
            ?: KeyMetadata(key = key, type = null, ttl = Ttl.Gone, memory = MemoryEstimate.Absent)
    }

    override suspend fun redisValue(id: ConnectionId, request: ValueRequest): ValuePage {
        calls += "redisValue(${request.type.wire})"
        await()
        requireUnlocked()
        return valuePage
            ?: throw DbException(DbError.KeyTypeChanged(expected = request.type.wire, actual = null))
    }

    override suspend fun redisCommand(
        id: ConnectionId,
        command: RedisCommand,
        consent: CommandConsent,
    ): CommandResult {
        calls += "redisCommand(${command.label})"
        consents += consent
        await()
        requireUnlocked()
        // The real guard lives in `:core` and is tested there. What a UI test needs is
        // the one behaviour it has to react to: a command that asks a question.
        confirmations.remove(command.label)?.let { throw CommandConfirmationRequired(it) }
        return commandResult
    }

    /** Commands this double will demand an acknowledgement for, once each. */
    val confirmations = mutableMapOf<String, CommandClearance.Confirm>()

    override suspend fun shutdown() {
        calls += "shutdown"
    }

    /** Seeds a saved connection without going through the form. */
    fun seed(
        name: String = "Local",
        engine: Engine = Engine.POSTGRES,
        environment: Environment = Environment.DEV,
        readOnly: Boolean = false,
        hasSecret: Boolean = true,
        status: RuntimeStatus = RuntimeStatus.CLOSED,
    ): ConnectionView {
        val id = ConnectionId("id-${++counter}")
        val config = ConnectionConfig(
            id = id,
            name = name,
            engine = engine,
            host = "localhost",
            port = engine.defaultPort,
            database = if (engine == Engine.REDIS) "0" else "dbide",
            username = "dbide",
            tlsMode = TlsMode.DISABLE,
            environment = environment,
            readOnly = readOnly,
            color = null,
            createdAt = CREATED_AT,
        )
        return view(config, hasSecret, RuntimeState(status)).also { stored[id] = it }
    }

    private fun requireUnlocked() {
        if (state != VaultState.UNLOCKED) throw VaultLockedException()
    }

    private suspend fun await() {
        gate?.await()
        nextFailure?.let {
            nextFailure = null
            throw it
        }
    }

    private fun view(
        config: ConnectionConfig,
        hasSecret: Boolean,
        runtime: RuntimeState = RuntimeState.CLOSED,
    ) = ConnectionView(ConnectionSummary(config, hasSecret), runtime)

    private fun SecretUpdate.storesSecret(): Boolean = this is SecretUpdate.Replace && !secret.isEmpty()

    companion object {
        val CREATED_AT: Instant = Instant.parse("2026-08-20T10:00:00Z")
    }
}

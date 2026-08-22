package dev.caracal.app

import dev.caracal.core.catalog.CatalogObject
import dev.caracal.core.catalog.ColumnInfo
import dev.caracal.core.catalog.Listing
import dev.caracal.core.catalog.ObjectKind
import dev.caracal.core.catalog.SchemaInfo
import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionDraft
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionService
import dev.caracal.core.connections.ConnectionSummary
import dev.caracal.core.connections.ConnectionView
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.RuntimeState
import dev.caracal.core.connections.RuntimeStatus
import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.SecretUpdate
import dev.caracal.core.connections.TestResult
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.connections.ValidationException
import dev.caracal.core.export.CsvExportReport
import dev.caracal.core.history.ExecutionRecord
import dev.caracal.core.history.HistoryPage
import dev.caracal.core.history.HistoryQuery
import dev.caracal.core.history.HistoryScope
import dev.caracal.core.history.cursor
import dev.caracal.core.export.CsvOptions
import dev.caracal.core.export.ExportLimits
import dev.caracal.core.redis.CommandClearance
import dev.caracal.core.redis.CommandConfirmationRequired
import dev.caracal.engine.api.CommandConsent
import dev.caracal.engine.api.CommandResult
import dev.caracal.engine.api.KeyMetadata
import dev.caracal.engine.api.KeyType
import dev.caracal.engine.api.MemoryEstimate
import dev.caracal.engine.api.RawCommand
import dev.caracal.engine.api.ScanCursor
import dev.caracal.engine.api.KeyRef
import dev.caracal.engine.api.CommandReply
import dev.caracal.engine.api.ScanPage
import dev.caracal.engine.api.ScanStop
import dev.caracal.engine.api.ServerInfo
import dev.caracal.engine.api.Ttl
import dev.caracal.engine.api.ValuePage
import dev.caracal.engine.api.ValueRequest
import dev.caracal.core.result.CellValue
import dev.caracal.core.result.Column
import dev.caracal.core.result.ColumnFormat
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.result.QueryResult
import dev.caracal.core.store.ConnectionNotFoundException
import dev.caracal.core.vault.VaultLockedException
import dev.caracal.core.vault.VaultState
import dev.caracal.core.vault.WrongPasswordException
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
    var schemas: Listing<SchemaInfo> = Listing(listOf(SchemaInfo("public", owner = "caracal")))

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

    // --- Query history, M4 ---------------------------------------------------

    /**
     * What history holds, newest last — the order the store writes rows in.
     *
     * A mutable list rather than a fixed answer, because the flows worth asserting
     * here are the ones where a call changes it: clearing, and the reload that
     * follows.
     */
    val history = mutableListOf<ExecutionRecord>()

    override suspend fun history(request: HistoryQuery): HistoryPage {
        calls += "history"
        await()
        requireUnlocked()
        // Newest first, then the filters, then the keyset — the same order and the
        // same meaning as the SQL, so a view model that pages correctly against this
        // pages correctly against SQLite.
        val matching = history.asReversed()
            .filter { request.connectionId == null || it.connectionId == request.connectionId }
            .filter { request.outcome == null || it.outcome == request.outcome }
        val from = request.olderThan
            ?.let { cursor -> matching.indexOfFirst { it.cursor() == cursor } + 1 }
            ?: 0
        val remaining = matching.drop(from)
        val page = remaining.take(request.limit)
        return HistoryPage(
            items = page,
            next = if (remaining.size > page.size) page.last().cursor() else null,
        )
    }

    override suspend fun clearHistory(scope: HistoryScope): Int {
        calls += "clearHistory"
        await()
        requireUnlocked()
        val going = history.filter {
            scope is HistoryScope.Everything ||
                (scope as HistoryScope.OneConnection).id == it.connectionId
        }
        history.removeAll(going)
        return going.size
    }

    private fun refuse(schema: String) {
        if (schema == failingSchema) {
            throw DbException(DbError.QueryFailed("permission denied for schema $schema"))
        }
    }

    // --- Redis, M3 -----------------------------------------------------------

    /** What [redisScan] hands back. A test that cares sets it; most do not. */
    var scanPage: ScanPage = ScanPage(
        cursor = ScanCursor.START,
        keys = emptyList(),
        iterations = 1,
        stopped = ScanStop.COMPLETE,
    )

    /**
     * Pages [redisScan] hands back in order, one per call, before falling back to
     * [scanPage].
     *
     * A queue rather than one page, because the behaviour §3.2 is most emphatic about
     * — an empty batch is not the end of a traversal — can only be asserted across a
     * sequence of pages. One page cannot express "and then another one".
     */
    val scanPages = mutableListOf<ScanPage>()

    /** The metadata [redisKey] answers with, for keys not on the current scan page. */
    val keyMetadata = mutableMapOf<KeyRef, KeyMetadata>()

    /**
     * Metadata [redisKey] answers with in order, one per call, before [keyMetadata].
     *
     * For the one sequence that cannot be expressed as a fixed answer: a key that is a
     * string when it is opened and a list when the viewer looks again.
     */
    val metadataPages = mutableListOf<KeyMetadata>()

    /** What [redisInfo] hands back. Restricted by default, which is the harder case. */
    var serverInfo: ServerInfo = ServerInfo(restricted = true)

    /** What [redisValue] hands back, or `null` to have it report a missing key. */
    var valuePage: ValuePage? = null

    /** Pages [redisValue] hands back in order, for asserting continuation. */
    val valuePages = mutableListOf<ValuePage>()

    /** Every value request made, so a test can prove which continuation was sent. */
    val valueRequests = mutableListOf<ValueRequest>()

    /** Set to fail the next [redisValue] alone, leaving the metadata read intact. */
    var nextValueFailure: Throwable? = null

    /** What [redisCommand] hands back when the guard is satisfied. */
    var commandResult: CommandResult = CommandResult(
        command = "PING",
        reply = CommandReply.Status("PONG"),
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
        cursor: ScanCursor,
        match: String?,
        type: KeyType?,
        pageSize: Int?,
    ): ScanPage {
        calls += "redisScan($cursor, $match, ${type?.wire})"
        await()
        requireUnlocked()
        return if (scanPages.isEmpty()) scanPage else scanPages.removeAt(0)
    }

    override suspend fun redisKey(id: ConnectionId, key: KeyRef): KeyMetadata {
        calls += "redisKey"
        await()
        requireUnlocked()
        if (metadataPages.isNotEmpty()) return metadataPages.removeAt(0)
        return keyMetadata[key]
            ?: scanPage.keys.firstOrNull { it.key == key }
            ?: KeyMetadata(key = key, type = null, ttl = Ttl.Gone, memory = MemoryEstimate.Absent)
    }

    override suspend fun redisValue(id: ConnectionId, request: ValueRequest): ValuePage {
        calls += "redisValue(${request.type.wire})"
        valueRequests += request
        await()
        requireUnlocked()
        nextValueFailure?.let {
            nextValueFailure = null
            throw it
        }
        if (valuePages.isNotEmpty()) return valuePages.removeAt(0)
        return valuePage
            ?: throw DbException(DbError.KeyTypeChanged(expected = request.type.wire, actual = null))
    }

    override suspend fun redisCommand(
        id: ConnectionId,
        command: RawCommand,
        consent: CommandConsent,
    ): CommandResult {
        calls += "redisCommand(${command.label})"
        consents += consent
        await()
        requireUnlocked()
        // The real guard lives in `:core` and is tested there. What a UI test needs is
        // the one behaviour it has to react to: a command that asks a question.
        confirmations.remove(command.label)?.let { throw CommandConfirmationRequired(it) }
        // The reply is whatever the test set, but the name on it is always the command
        // that was actually sent — as `:core` guarantees, and as the transcript relies
        // on to label an entry with something other than the last test's fixture.
        return commandResult.copy(command = command.label)
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
            database = if (engine == Engine.REDIS) "0" else "caracal",
            username = "caracal",
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

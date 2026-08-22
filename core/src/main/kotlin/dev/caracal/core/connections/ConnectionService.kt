package dev.caracal.core.connections

import dev.caracal.core.catalog.CatalogObject
import dev.caracal.core.catalog.ColumnInfo
import dev.caracal.core.catalog.Listing
import dev.caracal.core.catalog.ObjectKind
import dev.caracal.core.catalog.SchemaInfo
import dev.caracal.core.export.CsvExport
import dev.caracal.core.export.CsvExportReport
import dev.caracal.core.export.CsvOptions
import dev.caracal.core.export.ExportLimits
import dev.caracal.core.history.ExecutionOutcome
import dev.caracal.core.history.ExecutionRecord
import dev.caracal.core.history.HistoryPage
import dev.caracal.core.history.HistoryQuery
import dev.caracal.core.history.HistoryScope
import dev.caracal.core.postgres.PostgresCatalog
import dev.caracal.engine.api.CommandConsent
import dev.caracal.engine.api.CommandResult
import dev.caracal.engine.api.KeyMetadata
import dev.caracal.engine.api.KeyType
import dev.caracal.core.redis.RedisAdapter
import dev.caracal.engine.api.RawCommand
import dev.caracal.engine.api.ScanCursor
import dev.caracal.engine.api.KeyRef
import dev.caracal.engine.api.ScanPage
import dev.caracal.engine.api.ServerInfo
import dev.caracal.engine.api.ValuePage
import dev.caracal.engine.api.ValueRequest
import dev.caracal.core.postgres.PostgresConnectionConfig
import dev.caracal.core.postgres.PostgresProbe
import dev.caracal.core.redis.RedisSession
import dev.caracal.core.registry.ConnectionRegistry
import dev.caracal.core.result.QueryResult
import dev.caracal.core.result.toFailure
import dev.caracal.core.store.ConfigStore
import dev.caracal.core.vault.SecretIdentity
import dev.caracal.core.vault.Vault
import dev.caracal.core.vault.VaultException
import dev.caracal.core.vault.VaultLockedException
import dev.caracal.core.vault.VaultState
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Everything the UI can do to connections, as the source plan's §4 defines it.
 *
 * It is an interface so the UI can be driven against a stand-in: a Compose test
 * that clicks "Create" should not need SQLite and an Argon2id derivation behind it.
 * [DefaultConnectionService] is the one real implementation.
 */
interface ConnectionService {
    /** Whether setup is required, and whether the vault is open. */
    suspend fun vaultState(): VaultState

    /** Chooses the master password on first run. */
    suspend fun setUp(password: Secret)

    /** Opens the vault with the master password. */
    suspend fun unlock(password: Secret)

    /** Discards the master key and closes every live client. */
    suspend fun lock()

    /** Every saved connection with its runtime state. Never returns secrets. */
    suspend fun list(): List<ConnectionView>

    /** One saved connection with its runtime state. */
    suspend fun get(id: ConnectionId): ConnectionView

    /** Saves a new connection and seals its secret. */
    suspend fun create(draft: ConnectionDraft): ConnectionView

    /** Replaces a connection's fields, leaving its secret alone unless told otherwise. */
    suspend fun update(id: ConnectionId, draft: ConnectionDraft): ConnectionView

    /** Closes a connection's client and removes it. */
    suspend fun delete(id: ConnectionId)

    /** Dials, authenticates, and disconnects without touching the registry. */
    suspend fun test(id: ConnectionId): TestResult

    /** Establishes a live client. Idempotent for a healthy connection. */
    suspend fun open(id: ConnectionId): ConnectionView

    /** Releases a connection's client. Idempotent. */
    suspend fun close(id: ConnectionId): ConnectionView

    /**
     * The schemas of an open PostgreSQL connection, system schemas included only if
     * asked for.
     *
     * The three catalog reads are PostgreSQL's: a Redis connection is refused rather
     * than answered with an empty list, because an empty list is a claim about a
     * server and this one was never asked.
     */
    suspend fun schemas(id: ConnectionId, includeSystem: Boolean = false): Listing<SchemaInfo>

    /** The objects of one kind in one schema of an open PostgreSQL connection. */
    suspend fun objects(id: ConnectionId, schema: String, kind: ObjectKind): Listing<CatalogObject>

    /** The columns of one relation of an open PostgreSQL connection. */
    suspend fun columns(id: ConnectionId, schema: String, relation: String): List<ColumnInfo>

    /**
     * Runs one statement on an open PostgreSQL connection and returns its bounded
     * result.
     *
     * One statement, already chosen: splitting a script is
     * [dev.caracal.core.sql.EditorExecution]'s job and happens before anything is sent.
     * Cancelling the calling coroutine cancels the statement on the server, so
     * closing the editor that started it stops the work rather than orphaning it.
     *
     * Every attempt is recorded in query history — succeeded, failed, or cancelled
     * — because the query worth finding again later is rarely the one that worked.
     * The panel that reads it back is M4's.
     */
    suspend fun execute(id: ConnectionId, sql: String): QueryResult

    /**
     * Runs [sql] **again** and streams its whole result to [destination] as CSV.
     *
     * Again is the word a caller has to pass on to the user: this is a second
     * execution against the live server, so a table that has changed since the grid
     * was drawn exports as it is now. The alternative — holding every completed
     * result in case someone exports it — is how an IDE ends up carrying a gigabyte
     * per tab.
     *
     * Refused before a connection is taken when
     * [dev.caracal.core.export.ExportEligibility] says the statement does not qualify.
     * A returned [CsvExportReport] that is not `complete` means a limit ended the
     * file early and the caller must say so; cancellation and failure throw instead,
     * and leave no file behind.
     *
     * Not recorded in query history. The statement is already there from the run
     * that filled the grid, and a second identical row would say that the user ran
     * something twice when what they did was save it once.
     */
    suspend fun exportCsv(
        id: ConnectionId,
        sql: String,
        destination: Path,
        options: CsvOptions = CsvOptions(),
        limits: ExportLimits = ExportLimits(),
    ): CsvExportReport

    /**
     * One page of query history, newest first.
     *
     * Reading it needs the vault open, for the same reason listing connections does:
     * a statement is a map of a database, and often of a person — `WHERE email =
     * '…'` is a record of someone whether or not it ever returned a row.
     *
     * The connection a row names is not resolved here. Every row's connection still
     * exists — the schema cascades history away with it — and the caller already
     * holds the list it would be joined against, so a name read from that list is the
     * connection's *current* name rather than one copied at some point in the past.
     */
    suspend fun history(request: HistoryQuery = HistoryQuery()): HistoryPage

    /**
     * Forgets history, for one connection or for all of them. Returns how many rows
     * went.
     *
     * Never removes a connection. The cascade runs the other way, and a user asking
     * to forget an afternoon of queries is not asking to lose the servers they ran
     * them against.
     */
    suspend fun clearHistory(scope: HistoryScope): Int

    /**
     * The `INFO` summary of an open Redis connection.
     *
     * Never fails for want of permission. A server that refuses `INFO` returns a
     * [ServerInfo] marked restricted, because §3.8 requires the key tools to keep
     * working when the dashboard cannot be drawn — and an ACL that grants read access
     * and withholds `INFO` is the ordinary way to hand someone a production cache.
     */
    suspend fun redisInfo(id: ConnectionId): ServerInfo

    /**
     * One page of an open Redis connection's keyspace, with each key's metadata.
     *
     * Bounded on every axis by [dev.caracal.core.redis.KeyValueLimits], and never `KEYS`.
     * An empty page is a successful result: `MATCH` filters on the server, so a
     * selective pattern produces empty batches while the cursor advances, and only
     * [ScanPage.complete] means the traversal is over.
     */
    suspend fun redisScan(
        id: ConnectionId,
        cursor: ScanCursor = ScanCursor.START,
        match: String? = null,
        type: KeyType? = null,
        pageSize: Int? = null,
    ): ScanPage

    /** One key's type, TTL, and size estimate, read fresh. */
    suspend fun redisKey(id: ConnectionId, key: KeyRef): KeyMetadata

    /**
     * One page of one Redis value, in the shape its type has.
     *
     * The key's type is re-read before anything else, so a key that has been deleted
     * and recreated as something else reports
     * [dev.caracal.core.result.DbError.KeyTypeChanged] with the type it is now, rather
     * than a bare `WRONGTYPE`.
     */
    suspend fun redisValue(id: ConnectionId, request: ValueRequest): ValuePage

    /**
     * Runs a raw Redis command, once [dev.caracal.core.redis.RedisCommandGuard] is
     * satisfied.
     *
     * [consent] is what the user has agreed to for *this* command and is never
     * remembered past it. A command that needs an acknowledgement it has not been
     * given throws [dev.caracal.core.redis.CommandConfirmationRequired] carrying the
     * question to put on screen; the guard is consulted inside `:core`, so a caller
     * cannot reach the server by declining to ask.
     */
    suspend fun redisCommand(
        id: ConnectionId,
        command: RawCommand,
        consent: CommandConsent = CommandConsent.None,
    ): CommandResult

    /** Releases every client. Called during application shutdown. */
    suspend fun shutdown()
}

/**
 * The connection manager: it joins the configuration store, the secret vault, and
 * the runtime registry into the operations the UI calls.
 *
 * It is the only class that holds a decrypted password, and it holds one only for
 * the duration of a single operation.
 */
class DefaultConnectionService(
    private val store: ConfigStore,
    private val vault: Vault,
    private val registry: ConnectionRegistry,
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> ConnectionId = { ConnectionId(UUID.randomUUID().toString()) },
) : ConnectionService {

    private val log = LoggerFactory.getLogger(DefaultConnectionService::class.java)

    // --- Vault lifecycle -----------------------------------------------------

    /** Whether setup is required, and whether the vault is open. */
    override suspend fun vaultState(): VaultState = vault.state()

    /**
     * Chooses the master password on first run.
     *
     * The vault refuses to write a new salt over an existing one, because doing so
     * would orphan every sealed credential. Whether there are any to orphan is a
     * question only this layer can answer: no stored connection holds a sealed
     * secret means an interrupted first run, which re-keying costs nothing, and the
     * user gets the first-run screen they expected rather than an error about a file
     * that has nothing in it.
     */
    override suspend fun setUp(password: Secret) {
        val nothingSealed = store.list().none { it.sealedSecret?.isNotEmpty() == true }
        vault.setUp(password, replaceOrphanedMetadata = nothingSealed)
    }

    /** Opens the vault with the master password. */
    override suspend fun unlock(password: Secret) = vault.unlock(password)

    /**
     * Discards the master key and closes every live client: a locked application
     * must not still be talking to production.
     */
    override suspend fun lock() {
        vault.lock()
        registry.closeAll()
    }

    // --- Connection CRUD -----------------------------------------------------

    /**
     * Every saved connection with its runtime state.
     *
     * Listing needs the vault open. Connection summaries carry no secrets, but they
     * are still a map of where this user's databases live.
     */
    override suspend fun list(): List<ConnectionView> {
        requireUnlocked()
        val states = registry.states()
        return store.list().map { record ->
            ConnectionView(
                summary = record.summarize(),
                // A connection the registry has never seen — every connection after a
                // restart — is closed, not statusless.
                runtime = states[record.id] ?: RuntimeState.CLOSED,
            )
        }
    }

    /** One saved connection with its runtime state. */
    override suspend fun get(id: ConnectionId): ConnectionView = view(record(id))

    /** Saves a new connection and seals its secret. */
    override suspend fun create(draft: ConnectionDraft): ConnectionView {
        requireUnlocked()
        val normalized = draft.normalized().validated()

        val config = normalized.toConfig(newId(), clock())
        val sealed = sealFor(config, normalized.secret, existing = null)
        val record = ConnectionRecord(config, sealed)

        store.create(record)
        return view(record)
    }

    /**
     * Replaces a connection's fields. The stored secret survives unless the caller
     * explicitly supplies a replacement.
     */
    override suspend fun update(id: ConnectionId, draft: ConnectionDraft): ConnectionView {
        val existing = record(id)
        val normalized = draft.normalized().validated()

        // created_at is immutable: an edit is not a new connection.
        val config = normalized.toConfig(id, existing.config.createdAt)
        val sealed = sealFor(config, normalized.secret, existing)
        val updated = ConnectionRecord(config, sealed)

        store.update(updated)

        // An open client built from the old settings must not survive them.
        try {
            withPassword(updated) { password -> registry.invalidateIfChanged(config, password) }
        } catch (unreadable: VaultException) {
            // The stored secret cannot be read back, so no client built from it can be
            // trusted either.
            registry.close(id)
            throw unreadable
        }
        return view(updated)
    }

    /**
     * Closes a connection's client and removes it. The client is released before the
     * record, so no runtime entry outlives its configuration.
     */
    override suspend fun delete(id: ConnectionId) {
        requireUnlocked()
        store.get(id)
        registry.forget(id)
        store.delete(id)
    }

    // --- Runtime operations --------------------------------------------------

    /** Dials, authenticates, and disconnects without touching the registry. */
    override suspend fun test(id: ConnectionId): TestResult {
        val record = record(id)
        return withPassword(record) { password ->
            when (record.config.engine) {
                Engine.POSTGRES -> PostgresProbe.test(PostgresConnectionConfig.of(record.config, password))
                Engine.REDIS -> RedisSession.test(record.config, password)
            }
        }
    }

    /**
     * Establishes a live client. Idempotent for a connection that is already healthy.
     *
     * A failure throws, but the registry has already recorded the status as `error`
     * with a safe message, so the list reflects it even if the caller only refreshes.
     */
    override suspend fun open(id: ConnectionId): ConnectionView {
        val record = record(id)
        withPassword(record) { password -> registry.open(record.config, password) }
        return view(record)
    }

    /** Releases a connection's client. Idempotent. */
    override suspend fun close(id: ConnectionId): ConnectionView {
        val record = record(id)
        registry.close(id)
        return view(record)
    }

    // --- Catalog -------------------------------------------------------------

    /**
     * The schemas of an open PostgreSQL connection.
     *
     * Browsing needs no decrypted password: the pool is already authenticated, so
     * these three go straight to the live session. A connection that is not open
     * throws rather than being opened implicitly — the user closed it, and a click on
     * a stale tree is not permission to dial production again.
     */
    override suspend fun schemas(id: ConnectionId, includeSystem: Boolean): Listing<SchemaInfo> =
        catalog(id).schemas(includeSystem)

    /** The objects of one kind in one schema of an open PostgreSQL connection. */
    override suspend fun objects(
        id: ConnectionId,
        schema: String,
        kind: ObjectKind,
    ): Listing<CatalogObject> = catalog(id).objects(schema, kind)

    /** The columns of one relation of an open PostgreSQL connection. */
    override suspend fun columns(
        id: ConnectionId,
        schema: String,
        relation: String,
    ): List<ColumnInfo> = catalog(id).columns(schema, relation)

    /**
     * Runs one statement on an open PostgreSQL connection.
     *
     * Like the catalog reads, this needs no decrypted password and refuses a
     * connection that is not open rather than dialing one. What the statement is
     * allowed to do is not decided here: the pool's read-only transaction decides it,
     * on the server, where a write hidden in a function body is still a write.
     */
    override suspend fun execute(id: ConnectionId, sql: String): QueryResult {
        requireUnlocked()
        val adapter = registry.postgres(id).adapter
        val executedAt = clock()
        val started = TimeSource.Monotonic.markNow()
        try {
            val result = adapter.execute(sql)
            // Rows returned, or rows affected for a statement that produced no result
            // set. `rowsAffected` is null for a result-producing statement, so the two
            // never both apply.
            record(id, sql, ExecutionOutcome.OK, executedAt, started, result.rowsAffected ?: result.rows.size.toLong())
            return result
        } catch (cancellation: CancellationException) {
            record(id, sql, ExecutionOutcome.CANCELLED, executedAt, started)
            throw cancellation
        } catch (problem: Throwable) {
            // toFailure() is the redaction boundary the UI already renders through, so
            // the sentence stored here is exactly the one the user was shown — and it
            // carries no host, user name, or driver text, whatever was thrown.
            record(id, sql, ExecutionOutcome.ERROR, executedAt, started, error = problem.toFailure().message)
            throw problem
        }
    }

    /**
     * Re-runs [sql] and streams it to [destination] as CSV.
     *
     * The file is the unit of failure, not the row: [CsvExport.writeToFile] deletes
     * a partial file rather than leaving one behind, because a CSV that simply stops
     * is not a partial document — it is a complete-looking one with no way for
     * anything reading it to know the rows ran out.
     */
    override suspend fun exportCsv(
        id: ConnectionId,
        sql: String,
        destination: Path,
        options: CsvOptions,
        limits: ExportLimits,
    ): CsvExportReport {
        requireUnlocked()
        val adapter = registry.postgres(id).adapter
        return CsvExport.writeToFile(destination) { out -> adapter.exportCsv(sql, out, options, limits) }
    }

    // --- Query history, M4 ---------------------------------------------------

    override suspend fun history(request: HistoryQuery): HistoryPage {
        requireUnlocked()
        return store.history(request)
    }

    override suspend fun clearHistory(scope: HistoryScope): Int {
        requireUnlocked()
        return store.clearHistory(scope)
    }

    /** Releases every client. Called during application shutdown. */
    override suspend fun shutdown() = registry.closeAll()

    // --- Internals -----------------------------------------------------------

    /**
     * Writes one history row, and never lets doing so change what execute() did.
     *
     * [NonCancellable], because the cancellation path is precisely the one that must
     * still be recorded: a user who stops a query at 2am is exactly the user who
     * comes back looking for it. A coroutine that has already been cancelled would
     * otherwise be cancelled again at the first suspension inside the store.
     *
     * A store failure is swallowed to a debug line. History is a convenience; a full
     * disk must not turn a successful query into a failed one, and the log line
     * deliberately names neither the statement nor the connection.
     */
    private suspend fun record(
        id: ConnectionId,
        sql: String,
        outcome: ExecutionOutcome,
        executedAt: Instant,
        started: TimeSource.Monotonic.ValueTimeMark,
        rowCount: Long? = null,
        error: String? = null,
    ) {
        val entry = ExecutionRecord(
            connectionId = id,
            statement = sql,
            outcome = outcome,
            executedAt = executedAt,
            // Measured around the whole call rather than taken from QueryResult, so
            // that a failure and a cancellation are timed the same way a success is.
            duration = started.elapsedNow(),
            rowCount = rowCount,
            error = error,
        )
        withContext(NonCancellable) {
            runCatching { store.record(entry) }
                .onFailure { log.debug("an execution could not be recorded in history") }
        }
    }

    // --- Redis, M3 -----------------------------------------------------------
    //
    // Like the PostgreSQL catalog reads, none of these needs a decrypted password and
    // all of them refuse a connection that is not open rather than dialing one: a
    // stale key list is not permission to reconnect to production.

    override suspend fun redisInfo(id: ConnectionId): ServerInfo = redis(id).info()

    override suspend fun redisScan(
        id: ConnectionId,
        cursor: ScanCursor,
        match: String?,
        type: KeyType?,
        pageSize: Int?,
    ): ScanPage = redis(id).scan(cursor = cursor, match = match, type = type, pageSize = pageSize)

    override suspend fun redisKey(id: ConnectionId, key: KeyRef): KeyMetadata = redis(id).metadata(key)

    override suspend fun redisValue(id: ConnectionId, request: ValueRequest): ValuePage =
        redis(id).value(request)

    /**
     * Runs a console command.
     *
     * Not recorded in query history, and §3.9 is explicit about why: a Redis command's
     * arguments are where its secrets are — `AUTH`, `CONFIG SET requirepass`, a session
     * token being written to a key — and a history that stored them would be a file of
     * credentials on disk. The console's own history stays in memory for the session.
     */
    override suspend fun redisCommand(
        id: ConnectionId,
        command: RawCommand,
        consent: CommandConsent,
    ): CommandResult = redis(id).execute(command, consent)

    private suspend fun redis(id: ConnectionId): RedisAdapter {
        requireUnlocked()
        return registry.redis(id).adapter
    }

    private suspend fun catalog(id: ConnectionId): PostgresCatalog {
        requireUnlocked()
        return registry.postgres(id).catalog
    }

    private fun requireUnlocked() {
        if (!vault.isUnlocked) throw VaultLockedException()
    }

    private suspend fun record(id: ConnectionId): ConnectionRecord {
        requireUnlocked()
        return store.get(id)
    }

    private suspend fun view(record: ConnectionRecord) =
        ConnectionView(record.summarize(), registry.state(record.id))

    private fun ConnectionDraft.validated(): ConnectionDraft {
        val errors = validate()
        if (errors.isNotEmpty()) throw ValidationException(errors)
        return this
    }

    /**
     * Runs [body] with the connection's decrypted password, then clears it. A
     * connection with no stored secret dials with an empty password, which is what
     * Redis and trust-authenticated PostgreSQL expect.
     */
    private suspend fun <T> withPassword(record: ConnectionRecord, body: suspend (Secret) -> T): T {
        val sealed = record.sealedSecret
        if (sealed == null || sealed.isEmpty()) return body(Secret.EMPTY)
        val password = vault.open(SecretIdentity.of(record.config), sealed)
        try {
            return body(password)
        } finally {
            password.clear()
        }
    }

    /**
     * Resolves the sealed secret for a create or update:
     *
     * - [SecretUpdate.Unchanged] keeps the stored bytes;
     * - [SecretUpdate.Clear] removes them;
     * - [SecretUpdate.Replace] seals the new password.
     *
     * When nothing changes but the connection's authenticated identity does — the
     * engine, in practice — the existing secret is opened and resealed so it stays
     * bound to its own record.
     */
    private suspend fun sealFor(
        config: ConnectionConfig,
        update: SecretUpdate,
        existing: ConnectionRecord?,
    ): ByteArray? = when (update) {
        is SecretUpdate.Clear -> null
        is SecretUpdate.Replace ->
            if (update.secret.isEmpty()) null else vault.seal(SecretIdentity.of(config), update.secret)

        is SecretUpdate.Unchanged -> {
            val stored = existing?.sealedSecret
            when {
                stored == null || stored.isEmpty() -> null
                SecretIdentity.of(existing.config) == SecretIdentity.of(config) -> stored
                else -> {
                    val password = vault.open(SecretIdentity.of(existing.config), stored)
                    try {
                        vault.seal(SecretIdentity.of(config), password)
                    } finally {
                        password.clear()
                    }
                }
            }
        }
    }
}

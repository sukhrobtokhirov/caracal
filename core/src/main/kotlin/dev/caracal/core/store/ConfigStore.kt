/**
 * The local configuration database: a SQLite file holding connection records,
 * encryption metadata, and (from M2) query history.
 *
 * Callers work with domain types. Nothing outside this package needs to know about
 * SQL, column names, or that sealed secrets are stored as BLOBs.
 */
package dev.caracal.core.store

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionRecord
import dev.caracal.core.connections.EngineId
import dev.caracal.core.connections.Environment
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.core.history.DEFAULT_HISTORY_RETENTION
import dev.caracal.core.history.ExecutionOutcome
import dev.caracal.core.history.ExecutionRecord
import dev.caracal.core.history.HistoryCursor
import dev.caracal.core.history.HistoryPage
import dev.caracal.core.history.HistoryQuery
import dev.caracal.core.history.HistoryScope
import dev.caracal.core.history.MAX_HISTORY_PAGE
import dev.caracal.core.vault.MetadataStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/**
 * Owns the SQLite handle.
 *
 * One connection, serialized by a mutex: this is a single-user desktop tool, so
 * write contention is not a problem worth a pool.
 */
class ConfigStore private constructor(
    private val connection: Connection,
    val path: Path,
    private val dispatcher: CoroutineDispatcher,
    private val historyRetention: Int,
) : MetadataStore, AutoCloseable {

    private val mutex = Mutex()

    override suspend fun getMetadata(key: String): ByteArray? = query { db ->
        db.prepareStatement("SELECT value FROM app_metadata WHERE key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getBytes(1) else null }
        }
    }

    override suspend fun putMetadata(key: String, value: ByteArray) = query { db ->
        db.prepareStatement(
            """
            INSERT INTO app_metadata(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, key)
            statement.setBytes(2, value)
            statement.executeUpdate()
        }
        Unit
    }

    /**
     * Inserts a new connection record and the settings its engine declared.
     *
     * Two statements, in one transaction: a connection whose settings did not arrive
     * is a connection with no user and no TLS mode, which would dial — and dial
     * differently from the one the user described.
     */
    suspend fun create(record: ConnectionRecord) = transaction { db ->
        db.prepareStatement(
            "INSERT INTO connections ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, record.id.value)
            statement.bindFields(record, from = 2)
            statement.setString(13, record.config.createdAt.toString())
            statement.executeUpdate()
        }
        db.writeSettings(record)
        Unit
    }

    /** Replaces every mutable field of an existing connection. `created_at` is immutable. */
    suspend fun update(record: ConnectionRecord) = transaction { db ->
        db.prepareStatement(
            """
            UPDATE connections SET name = ?, engine = ?, target_kind = ?, host = ?, port = ?,
                "database" = ?, file_path = ?, secret_sealed = ?, environment = ?,
                read_only = ?, color = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            // The UPDATE names no id column, so the fields start at 1 and the id
            // becomes the trailing WHERE term.
            statement.bindFields(record, from = 1)
            statement.setString(12, record.id.value)
            if (statement.executeUpdate() == 0) throw ConnectionNotFoundException(record.id)
        }
        // Replaced rather than merged: a field the engine no longer declares, or one
        // the user cleared, has to leave the store with the same edit that cleared it.
        db.prepareStatement("DELETE FROM connection_settings WHERE connection_id = ?").use { statement ->
            statement.setString(1, record.id.value)
            statement.executeUpdate()
        }
        db.writeSettings(record)
        Unit
    }

    /** Reads one connection, including its sealed secret. */
    suspend fun get(id: ConnectionId): ConnectionRecord = query { db ->
        val settings = db.readSettings(id)
        db.prepareStatement("SELECT $COLUMNS FROM connections WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw ConnectionNotFoundException(id)
                rows.toRecord(settings)
            }
        }
    }

    /**
     * Every connection, ordered by environment severity — production first, so the
     * dangerous ones are never buried — then by name, case-insensitively.
     */
    suspend fun list(): List<ConnectionRecord> = query { db ->
        db.prepareStatement(
            """
            SELECT $COLUMNS FROM connections
            ORDER BY CASE environment WHEN 'prod' THEN 0 WHEN 'staging' THEN 1 ELSE 2 END,
                     name COLLATE NOCASE, id
            """.trimIndent(),
        ).use { statement ->
            // One query for every connection's settings rather than one per row: a
            // list of thirty connections is thirty round trips through a mutex.
            val settings = db.readAllSettings()
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val id = ConnectionId(rows.getString("id"))
                        add(rows.toRecord(settings[id].orEmpty()))
                    }
                }
            }
        }
    }

    /** Removes a connection. Its query history cascades away. */
    suspend fun delete(id: ConnectionId) = query { db ->
        db.prepareStatement("DELETE FROM connections WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            if (statement.executeUpdate() == 0) throw ConnectionNotFoundException(id)
        }
        Unit
    }

    // --- Query history -------------------------------------------------------

    /**
     * Records one attempted execution, and drops whatever falls off the end.
     *
     * The insert and the prune are one statement each against the same serialized
     * connection, so a caller never observes the intermediate state where a
     * connection holds one row more than its retention allows.
     *
     * Nothing here logs [ExecutionRecord.statement]. §2.11 is explicit that history
     * is sensitive local data, and a query text that reaches a log file has left the
     * owner-only database that was protecting it.
     */
    suspend fun record(entry: ExecutionRecord): Unit = query { db ->
        db.prepareStatement(
            """
            INSERT INTO query_history
                (connection_id, statement, duration_ms, row_count, status, error, executed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, entry.connectionId.value)
            statement.setString(2, entry.statement)
            statement.setLongOrNull(3, entry.duration?.inWholeMilliseconds)
            statement.setLongOrNull(4, entry.rowCount)
            statement.setString(5, entry.outcome.stored)
            statement.setString(6, entry.error)
            statement.setString(7, entry.executedAt.toString())
            statement.executeUpdate()
        }
        db.prune(entry.connectionId)
    }

    /**
     * One page of history, newest first.
     *
     * Ordered by `id` alone, and that is a decision rather than a shortcut. The
     * obvious key is `executed_at`, and it does not work: the column holds
     * `Instant.toString()`, which writes a fractional second only when there is one,
     * so `…09:00:00.500Z` sorts *before* `…09:00:00Z` under the text comparison
     * SQLite applies to it. A clock that steps backwards over NTP is the second
     * reason. `id` is `INTEGER PRIMARY KEY AUTOINCREMENT`, assigned in the order rows
     * were written, and every row has one — which is what a keyset needs and what a
     * timestamp, here, is not.
     *
     * The page is read with one row more than [HistoryQuery.limit] asks for and the
     * extra one is discarded. That is what makes [HistoryPage.next] a fact rather
     * than a guess: a Show more that appears at the end of a connection's history and
     * then produces nothing is a control that has taught the user to distrust it.
     */
    suspend fun history(request: HistoryQuery): HistoryPage = query { db ->
        val limit = request.limit.coerceIn(1, MAX_HISTORY_PAGE)
        // Each filter arrives as its condition and its value together, so a term can
        // never reach the SQL without the bind that fills it. Nothing here is
        // interpolated — not the connection id, and not the outcome that came from a
        // closed enum and would have been safe.
        val filters = buildList<Pair<String, Any>> {
            request.connectionId?.let { add("connection_id = ?" to it.value) }
            request.outcome?.let { add("status = ?" to it.stored) }
            request.olderThan?.let { add("id < ?" to it.id) }
        }
        val where = if (filters.isEmpty()) {
            ""
        } else {
            filters.joinToString(" AND ", prefix = "WHERE ") { it.first }
        }

        db.prepareStatement(
            """
            SELECT id, connection_id, statement, duration_ms, row_count, status, error, executed_at
            FROM query_history $where ORDER BY id DESC LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            filters.forEachIndexed { index, (_, value) -> statement.setObject(index + 1, value) }
            statement.setInt(filters.size + 1, limit + 1)
            statement.executeQuery().use { rows ->
                val read = buildList { while (rows.next()) add(rows.toExecutionRecord()) }
                val items = read.take(limit)
                HistoryPage(
                    items = items,
                    next = if (read.size > limit) items.last().id?.let(::HistoryCursor) else null,
                )
            }
        }
    }

    /**
     * Forgets history, for one connection or for all of them.
     *
     * The connections themselves are never touched. Deleting a connection deletes its
     * history through the schema's cascade; this is the other direction, and it has to
     * stay the other direction — a user clearing an afternoon of queries is not asking
     * to lose the servers they ran them against.
     */
    suspend fun clearHistory(scope: HistoryScope): Int = query { db ->
        when (scope) {
            HistoryScope.Everything ->
                db.prepareStatement("DELETE FROM query_history").use { it.executeUpdate() }

            is HistoryScope.OneConnection ->
                db.prepareStatement("DELETE FROM query_history WHERE connection_id = ?").use { statement ->
                    statement.setString(1, scope.id.value)
                    statement.executeUpdate()
                }
        }
    }

    /**
     * Keeps the newest [historyRetention] rows for one connection and deletes the rest.
     *
     * Scoped to the connection that was just written to: a prune that ranged over
     * the whole table would make an afternoon in staging quietly evict last week's
     * production queries.
     */
    private fun Connection.prune(id: ConnectionId) {
        prepareStatement(
            """
            DELETE FROM query_history
            WHERE connection_id = ? AND id NOT IN (
                SELECT id FROM query_history WHERE connection_id = ?
                ORDER BY id DESC LIMIT ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id.value)
            statement.setString(2, id.value)
            statement.setInt(3, historyRetention)
            statement.executeUpdate()
        }
    }

    /** Binds [value], or SQL NULL where there is no number to record. */
    private fun PreparedStatement.setLongOrNull(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.INTEGER) else setLong(index, value)
    }

    private fun PreparedStatement.setIntOrNull(index: Int, value: Int?) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }

    private fun PreparedStatement.setStringOrNull(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    /**
     * One history row.
     *
     * A status this build does not recognise is a read failure rather than a
     * silently dropped row: it means a newer version wrote the file, and pretending
     * the execution never happened is the worse of the two answers.
     */
    private fun ResultSet.toExecutionRecord(): ExecutionRecord {
        val stored = getString("status")
        val outcome = ExecutionOutcome.of(stored)
            ?: throw StoreReadException("A history entry records an outcome this build does not know.")
        val executedAt = runCatching { Instant.parse(getString("executed_at")) }.getOrElse {
            throw StoreReadException("A history entry has an unreadable execution time.")
        }
        return ExecutionRecord(
            id = getLong("id"),
            connectionId = ConnectionId(getString("connection_id")),
            statement = getString("statement"),
            outcome = outcome,
            executedAt = executedAt,
            // getLong returns 0 for SQL NULL, so wasNull is the only way to tell a
            // query that took no measurable time from one that was never timed.
            duration = getLong("duration_ms").takeUnless { wasNull() }?.milliseconds,
            rowCount = getLong("row_count").takeUnless { wasNull() },
            error = getString("error"),
        )
    }

    /** The applied migration version. Reported in the About view from M5. */
    suspend fun schemaVersion(): Int =
        getMetadata(META_SCHEMA_VERSION)?.let { String(it, Charsets.US_ASCII).trim().toIntOrNull() } ?: 0

    override fun close() {
        runCatching { connection.close() }
            .onFailure { log.debug("closing the configuration database failed") }
    }

    /**
     * Runs one statement against the single connection.
     *
     * Driver exceptions are wrapped here rather than at each call site, so a store
     * failure always reaches the UI as a classified message. Without this, a closed
     * or corrupted database surfaces as an unrecognised exception and the user is
     * told only that something went wrong.
     */
    private suspend fun <T> query(body: (Connection) -> T): T = withContext(dispatcher) {
        mutex.withLock {
            try {
                body(connection)
            } catch (failure: SQLException) {
                throw failure.asStoreFailure()
            }
        }
    }

    /**
     * Runs several statements as one unit.
     *
     * A connection and its settings are two tables and one fact, and the halfway
     * state — a row with no settings — is a connection that dials with no user and
     * no TLS. The connection is serialized by the same mutex [query] uses, so
     * nothing else can be inside a transaction at the same time.
     */
    private suspend fun <T> transaction(body: (Connection) -> T): T = query { db ->
        db.autoCommit = false
        try {
            val result = body(db)
            db.commit()
            result
        } catch (failure: Throwable) {
            runCatching { db.rollback() }
            throw failure
        } finally {
            db.autoCommit = true
        }
    }

    /**
     * Binds the eleven mutable fields in a fixed order, starting at [from]. Insert
     * and update differ only in what surrounds them: an id first, or an id last.
     */
    private fun PreparedStatement.bindFields(record: ConnectionRecord, from: Int) {
        val config = record.config
        val target = config.target
        var index = from
        setString(index++, config.name)
        setString(index++, config.engineId.value)
        setString(index++, target.storedKind)
        setStringOrNull(index++, (target as? ConnectionTarget.Network)?.host)
        setIntOrNull(index++, (target as? ConnectionTarget.Network)?.port)
        setStringOrNull(index++, (target as? ConnectionTarget.Network)?.database)
        setStringOrNull(index++, (target as? ConnectionTarget.File)?.path?.toString())
        setBytes(index++, record.sealedSecret)
        setString(index++, config.environment.wire)
        setInt(index++, if (config.readOnly) 1 else 0)
        setString(index, config.color)
    }

    /** Writes one connection's declared settings. The caller owns the transaction. */
    private fun Connection.writeSettings(record: ConnectionRecord) {
        if (record.config.settings.isEmpty()) return
        prepareStatement(
            "INSERT INTO connection_settings(connection_id, key, value) VALUES (?, ?, ?)",
        ).use { statement ->
            record.config.settings.forEach { (key, value) ->
                statement.setString(1, record.id.value)
                statement.setString(2, key)
                statement.setString(3, value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.readSettings(id: ConnectionId): Map<String, String> =
        prepareStatement("SELECT key, value FROM connection_settings WHERE connection_id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
            }
        }

    private fun Connection.readAllSettings(): Map<ConnectionId, Map<String, String>> =
        prepareStatement("SELECT connection_id, key, value FROM connection_settings").use { statement ->
            statement.executeQuery().use { rows ->
                buildMap<ConnectionId, MutableMap<String, String>> {
                    while (rows.next()) {
                        getOrPut(ConnectionId(rows.getString(1))) { mutableMapOf() }[rows.getString(2)] =
                            rows.getString(3)
                    }
                }
            }
        }

    /**
     * Rebuilds a stored connection.
     *
     * An engine name is read and not resolved: a connection naming an engine this
     * build does not have still lists, still shows what it points at, and still
     * refuses to open with a sentence about the engine. Refusing to *read* it would
     * mean one missing engine module makes the whole store unopenable, which is the
     * failure the [EngineId] change was for.
     */
    private fun ResultSet.toRecord(settings: Map<String, String>): ConnectionRecord {
        val id = ConnectionId(getString("id"))
        val createdAt = runCatching { Instant.parse(getString("created_at")) }.getOrElse {
            throw StoreReadException("Connection $id has an unreadable creation time.")
        }
        val config = ConnectionConfig(
            id = id,
            name = getString("name"),
            engineId = EngineId(getString("engine")),
            target = readTarget(id),
            settings = settings,
            environment = Environment.from(getString("environment")) ?: Environment.DEFAULT,
            readOnly = getInt("read_only") != 0,
            color = getString("color")?.takeIf { it.isNotBlank() },
            createdAt = createdAt,
        )
        return ConnectionRecord(config, getBytes("secret_sealed"))
    }

    private fun ResultSet.readTarget(id: ConnectionId): ConnectionTarget = when (getString("target_kind")) {
        TARGET_FILE -> {
            val path = getString("file_path")
                ?: throw StoreReadException("Connection $id opens a file but names none.")
            ConnectionTarget.File(runCatching { Path.of(path) }.getOrElse {
                throw StoreReadException("Connection $id names a file path that cannot be read.")
            })
        }

        else -> ConnectionTarget.Network(
            host = getString("host").orEmpty(),
            port = getInt("port"),
            database = getString("database"),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConfigStore::class.java)

        private const val COLUMNS =
            """id, name, engine, target_kind, host, port, "database", file_path,
               secret_sealed, environment, read_only, color, created_at"""

        /** The stored spelling of a [ConnectionTarget.File]. Everything else is a network. */
        private const val TARGET_FILE = "file"

        private const val TARGET_NETWORK = "network"

        private val ConnectionTarget.storedKind: String
            get() = if (this is ConnectionTarget.File) TARGET_FILE else TARGET_NETWORK

        private const val BUSY_TIMEOUT_MS = 5_000

        /**
         * Creates or opens the configuration database and migrates it.
         *
         * The file and its directory are created with owner-only permissions where the
         * operating system supports it: this file holds sealed credentials.
         */
        suspend fun open(
            path: Path,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            historyRetention: Int = DEFAULT_HISTORY_RETENTION,
        ): ConfigStore = withContext(dispatcher) {
            path.parent?.let { directory ->
                runCatching { Files.createDirectories(directory) }.getOrElse { failure ->
                    throw StoreOpenException("The configuration directory could not be created.", failure)
                }
                restrictPermissions(directory, "rwx------")
            }

            val config = SQLiteConfig().apply {
                enforceForeignKeys(true)
                busyTimeout = BUSY_TIMEOUT_MS
                setJournalMode(SQLiteConfig.JournalMode.WAL)
            }
            val dataSource = SQLiteDataSource(config).apply { url = "jdbc:sqlite:$path" }

            val connection = try {
                dataSource.connection
            } catch (failure: SQLException) {
                throw StoreOpenException("The configuration database could not be opened.", failure)
            }
            try {
                restrictPermissions(path, "rw-------")
                migrate(connection)
            } catch (failure: Throwable) {
                runCatching { connection.close() }
                throw failure
            }
            ConfigStore(connection, path, dispatcher, historyRetention)
        }

        /**
         * Tightens a path to owner-only. Windows and some filesystems have no POSIX
         * permissions, so a failure is logged rather than treated as fatal.
         */
        private fun restrictPermissions(path: Path, permissions: String) {
            runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions)) }
                .onFailure { log.debug("could not restrict permissions on the configuration path") }
        }
    }
}

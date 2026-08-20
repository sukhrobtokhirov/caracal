/**
 * The local configuration database: a SQLite file holding connection records,
 * encryption metadata, and (from M2) query history.
 *
 * Callers work with domain types. Nothing outside this package needs to know about
 * SQL, column names, or that sealed secrets are stored as BLOBs.
 */
package dev.dbide.core.store

import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionRecord
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.TlsMode
import dev.dbide.core.history.DEFAULT_HISTORY_RETENTION
import dev.dbide.core.history.ExecutionOutcome
import dev.dbide.core.history.ExecutionRecord
import dev.dbide.core.vault.MetadataStore
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

    /** Inserts a new connection record. */
    suspend fun create(record: ConnectionRecord) = query { db ->
        db.prepareStatement(
            "INSERT INTO connections ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, record.id.value)
            statement.bindFields(record, from = 2)
            statement.setString(13, record.config.createdAt.toString())
            statement.executeUpdate()
        }
        Unit
    }

    /** Replaces every mutable field of an existing connection. `created_at` is immutable. */
    suspend fun update(record: ConnectionRecord) = query { db ->
        db.prepareStatement(
            """
            UPDATE connections SET name = ?, engine = ?, host = ?, port = ?, "database" = ?,
                username = ?, secret_sealed = ?, tls_mode = ?, environment = ?, read_only = ?, color = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            // The UPDATE names no id column, so the fields start at 1 and the id
            // becomes the trailing WHERE term.
            statement.bindFields(record, from = 1)
            statement.setString(12, record.id.value)
            if (statement.executeUpdate() == 0) throw ConnectionNotFoundException(record.id)
        }
        Unit
    }

    /** Reads one connection, including its sealed secret. */
    suspend fun get(id: ConnectionId): ConnectionRecord = query { db ->
        db.prepareStatement("SELECT $COLUMNS FROM connections WHERE id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw ConnectionNotFoundException(id)
                rows.toRecord()
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
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toRecord()) }
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
     * The most recent [limit] executions on one connection, newest first.
     *
     * The `id` tiebreak matters more than it looks: two statements run in the same
     * millisecond are ordered by insertion and not arbitrarily, so a history panel
     * paging through this cannot show a row twice or skip one.
     *
     * This is what M4's history panel reads. It exists now because a record that
     * nothing can read back is a record nothing has tested.
     */
    suspend fun history(id: ConnectionId, limit: Int = DEFAULT_HISTORY_RETENTION): List<ExecutionRecord> =
        query { db ->
            db.prepareStatement(
                """
                SELECT id, connection_id, statement, duration_ms, row_count, status, error, executed_at
                FROM query_history WHERE connection_id = ?
                ORDER BY executed_at DESC, id DESC LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id.value)
                statement.setInt(2, limit)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.toExecutionRecord()) }
                }
            }
        }

    /** Forgets one connection's history without touching the connection itself. */
    suspend fun clearHistory(id: ConnectionId): Int = query { db ->
        db.prepareStatement("DELETE FROM query_history WHERE connection_id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeUpdate()
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
                ORDER BY executed_at DESC, id DESC LIMIT ?
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
     * Binds the eleven mutable fields in a fixed order, starting at [from]. Insert
     * and update differ only in what surrounds them: an id first, or an id last.
     */
    private fun PreparedStatement.bindFields(record: ConnectionRecord, from: Int) {
        val config = record.config
        var index = from
        setString(index++, config.name)
        setString(index++, config.engine.wire)
        setString(index++, config.host)
        setInt(index++, config.port)
        setString(index++, config.database)
        setString(index++, config.username)
        setBytes(index++, record.sealedSecret)
        setString(index++, config.tlsMode.wire)
        setString(index++, config.environment.wire)
        setInt(index++, if (config.readOnly) 1 else 0)
        setString(index, config.color)
    }

    private fun ResultSet.toRecord(): ConnectionRecord {
        val id = ConnectionId(getString("id"))
        val engine = Engine.from(getString("engine"))
            ?: throw StoreReadException("Connection $id names an engine this build does not support.")
        val createdAt = runCatching { Instant.parse(getString("created_at")) }.getOrElse {
            throw StoreReadException("Connection $id has an unreadable creation time.")
        }
        val config = ConnectionConfig(
            id = id,
            name = getString("name"),
            engine = engine,
            host = getString("host"),
            port = getInt("port"),
            database = getString("database").orEmpty(),
            username = getString("username").orEmpty(),
            tlsMode = TlsMode.from(getString("tls_mode")) ?: TlsMode.DEFAULT,
            environment = Environment.from(getString("environment")) ?: Environment.DEFAULT,
            readOnly = getInt("read_only") != 0,
            color = getString("color")?.takeIf { it.isNotBlank() },
            createdAt = createdAt,
        )
        return ConnectionRecord(config, getBytes("secret_sealed"))
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConfigStore::class.java)

        private const val COLUMNS =
            """id, name, engine, host, port, "database", username, secret_sealed,
               tls_mode, environment, read_only, color, created_at"""

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

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
import dev.dbide.core.vault.MetadataStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
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
            ConfigStore(connection, path, dispatcher)
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

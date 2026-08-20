package dev.dbide.core.store

import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionRecord
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.TlsMode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import kotlin.io.path.exists
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ConfigStoreTest {
    @TempDir
    lateinit var directory: Path

    private val databasePath: Path get() = directory.resolve("config").resolve("dbide.db")

    private suspend fun open() = ConfigStore.open(databasePath)

    private suspend fun <T> withStore(body: suspend (ConfigStore) -> T): T =
        open().use { body(it) }

    private fun record(
        id: String = "id-1",
        name: String = "Local",
        engine: Engine = Engine.POSTGRES,
        environment: Environment = Environment.DEV,
        sealed: ByteArray? = byteArrayOf(1, 2, 3),
        createdAt: Instant = Instant.parse("2026-08-20T10:00:00Z"),
        readOnly: Boolean = false,
    ) = ConnectionRecord(
        ConnectionConfig(
            id = ConnectionId(id),
            name = name,
            engine = engine,
            host = "localhost",
            port = 5432,
            database = "dbide",
            username = "dbide",
            tlsMode = TlsMode.DISABLE,
            environment = environment,
            readOnly = readOnly,
            color = "#4c8dff",
            createdAt = createdAt,
        ),
        sealed,
    )

    @Test
    fun `an empty directory becomes a migrated database`() = runTest {
        withStore { store ->
            assertTrue(databasePath.exists())
            assertEquals(LATEST_SCHEMA_VERSION, store.schemaVersion())
            assertEquals(emptyList(), store.list())
        }
    }

    @Test
    fun `opening an existing database again does not re-run migrations`() = runTest {
        withStore { it.create(record()) }

        withStore { store ->
            assertEquals(LATEST_SCHEMA_VERSION, store.schemaVersion())
            assertEquals(1, store.list().size)
        }
    }

    @Test
    fun `a connection survives a round trip with its sealed bytes intact`() = runTest {
        val original = record(sealed = byteArrayOf(7, 8, 9))

        withStore { store ->
            store.create(original)
            val read = store.get(ConnectionId("id-1"))

            assertEquals(original.config, read.config)
            assertContentEquals(byteArrayOf(7, 8, 9), read.sealedSecret)
        }
    }

    @Test
    fun `a connection with no stored secret reads back without one`() = runTest {
        withStore { store ->
            store.create(record(sealed = null))

            val read = store.get(ConnectionId("id-1"))

            assertTrue(read.sealedSecret?.isEmpty() != false)
            assertEquals(false, read.summarize().hasSecret)
        }
    }

    @Test
    fun `a duplicate name is refused`() = runTest {
        withStore { store ->
            store.create(record(id = "id-1", name = "Local"))

            assertThrows<DuplicateNameException> { store.create(record(id = "id-2", name = "Local")) }
        }
    }

    @Test
    fun `renaming onto another connection's name is refused`() = runTest {
        withStore { store ->
            store.create(record(id = "id-1", name = "Local"))
            store.create(record(id = "id-2", name = "Staging"))

            assertThrows<DuplicateNameException> { store.update(record(id = "id-2", name = "Local")) }
        }
    }

    @Test
    fun `an update replaces every mutable field`() = runTest {
        withStore { store ->
            store.create(record())
            val changed = ConnectionRecord(
                record().config.copy(
                    name = "Renamed",
                    host = "db.internal",
                    port = 6432,
                    environment = Environment.PROD,
                    readOnly = true,
                    color = null,
                ),
                byteArrayOf(4, 5),
            )

            store.update(changed)

            val read = store.get(ConnectionId("id-1"))
            assertEquals("Renamed", read.config.name)
            assertEquals("db.internal", read.config.host)
            assertEquals(6432, read.config.port)
            assertEquals(Environment.PROD, read.config.environment)
            assertTrue(read.config.readOnly)
            assertNull(read.config.color)
            assertContentEquals(byteArrayOf(4, 5), read.sealedSecret)
        }
    }

    @Test
    fun `an update cannot rewrite the creation time`() = runTest {
        val created = Instant.parse("2026-08-20T10:00:00Z")
        withStore { store ->
            store.create(record(createdAt = created))

            store.update(record(createdAt = Instant.parse("2020-01-01T00:00:00Z")))

            assertEquals(created, store.get(ConnectionId("id-1")).config.createdAt)
        }
    }

    @Test
    fun `reading, updating, or deleting an unknown connection reports that`() = runTest {
        withStore { store ->
            assertThrows<ConnectionNotFoundException> { store.get(ConnectionId("nope")) }
            assertThrows<ConnectionNotFoundException> { store.update(record(id = "nope")) }
            assertThrows<ConnectionNotFoundException> { store.delete(ConnectionId("nope")) }
        }
    }

    @Test
    fun `deleting removes the connection`() = runTest {
        withStore { store ->
            store.create(record())

            store.delete(ConnectionId("id-1"))

            assertEquals(emptyList(), store.list())
        }
    }

    @Test
    fun `the list puts production first, then staging, then development`() = runTest {
        withStore { store ->
            store.create(record(id = "a", name = "zeta-dev", environment = Environment.DEV))
            store.create(record(id = "b", name = "alpha-prod", environment = Environment.PROD))
            store.create(record(id = "c", name = "beta-staging", environment = Environment.STAGING))

            assertEquals(
                listOf("alpha-prod", "beta-staging", "zeta-dev"),
                store.list().map { it.config.name },
            )
        }
    }

    @Test
    fun `within an environment the list sorts by name, ignoring case`() = runTest {
        withStore { store ->
            store.create(record(id = "a", name = "beta"))
            store.create(record(id = "b", name = "Alpha"))
            store.create(record(id = "c", name = "charlie"))

            assertEquals(listOf("Alpha", "beta", "charlie"), store.list().map { it.config.name })
        }
    }

    @Test
    fun `deleting a connection cascades its query history away`() = runTest {
        withStore { store ->
            store.create(record())
            insertHistoryRow(ConnectionId("id-1"))
            assertEquals(1, historyRowCount())

            store.delete(ConnectionId("id-1"))

            assertEquals(0, historyRowCount())
        }
    }

    @Test
    fun `query history cannot name a connection that does not exist`() = runTest {
        withStore {
            assertThrows<SQLException> { insertHistoryRow(ConnectionId("ghost")) }
        }
    }

    @Test
    fun `metadata round trips and overwrites in place`() = runTest {
        withStore { store ->
            assertNull(store.getMetadata("kdf_salt"))

            store.putMetadata("kdf_salt", byteArrayOf(1, 2))
            store.putMetadata("kdf_salt", byteArrayOf(3, 4))

            assertContentEquals(byteArrayOf(3, 4), store.getMetadata("kdf_salt"))
        }
    }

    @Test
    fun `a store that can no longer be reached reports it rather than leaking the driver`() = runTest {
        val store = open()
        store.create(record())
        store.close()

        // Whatever went wrong underneath, the caller gets a classified message: the
        // UI has no way to render a raw SQLException except as "something went wrong".
        listOf<suspend () -> Any?>(
            { store.list() },
            { store.get(ConnectionId("id-1")) },
            { store.create(record(id = "id-2", name = "Other")) },
            { store.getMetadata("kdf_salt") },
        ).forEach { operation ->
            val failure = assertThrows<StoreException> { operation() }
            assertEquals("store_unavailable", failure.code)
        }
    }

    @Test
    fun `a database from a newer build refuses to open rather than being downgraded`() = runTest {
        withStore { it.putMetadata(META_SCHEMA_VERSION, "999".toByteArray()) }

        val failure = assertThrows<StoreOpenException> { open() }

        assertTrue(failure.safeMessage.contains("newer version"), failure.safeMessage)
    }

    @Test
    fun `the database file and its directory are owner-only`() = runTest {
        withStore {
            val supportsPosix = Files.getFileStore(databasePath).supportsFileAttributeView(
                java.nio.file.attribute.PosixFileAttributeView::class.java,
            )
            if (!supportsPosix) return@withStore

            assertEquals("rw-------", posixPermissions(databasePath))
            assertEquals("rwx------", posixPermissions(databasePath.parent))
        }
    }

    private fun posixPermissions(path: Path) =
        java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    /**
     * M2 owns query history, so there is no store API for it yet. These two helpers
     * reach the table directly to prove the foreign key is real now, rather than
     * discovering in M4 that it never was.
     */
    private fun insertHistoryRow(connectionId: ConnectionId) {
        connect().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO query_history (connection_id, statement, status, executed_at)
                VALUES (?, 'SELECT 1', 'ok', '2026-08-20T10:00:00Z')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, connectionId.value)
                statement.executeUpdate()
            }
        }
    }

    private fun historyRowCount(): Int = connect().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM query_history").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun connect() = DriverManager.getConnection("jdbc:sqlite:$databasePath").also { connection ->
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
    }

    @Test
    fun `the read-only migration turns every connection saved before it read only`() = runTest {
        // The one migration in this project that changes data rather than shape, and
        // it changes it in the direction that cannot hurt. Until M2 §2.4 the pool
        // opened every connection read-only whatever this column said, so a row
        // holding 0 recorded a box that did nothing — not a decision to allow writes.
        // Leaving those rows alone would have turned every connection saved before
        // this release into a writable one, silently.
        writeSchemaVersionOneDatabase()

        val migrated = withStore { store -> store.list().associate { it.config.name to it.config } }

        assertTrue(migrated.getValue("Writable").readOnly, "a pre-migration row stayed writable")
        assertTrue(migrated.getValue("Restricted").readOnly)
    }

    @Test
    fun `a connection saved after the migration can still be writable`() = runTest {
        // The migration sets a floor for what already exists; it does not weld the
        // column shut. A connection the user deliberately marks writable stays that way
        // across a reopen, or the flag would be a one-way door.
        writeSchemaVersionOneDatabase()
        withStore { it.create(record(id = "id-3", name = "New", readOnly = false)) }

        val reopened = withStore { store -> store.get(ConnectionId("id-3")).config }

        assertFalse(reopened.readOnly)
    }

    /**
     * A database at schema version 1, with two connections in it, one of which has
     * `read_only = 0`.
     *
     * Written with raw SQL rather than through the store, because the store applies
     * every migration on open — which is the thing under test, and cannot be used to
     * set up its own starting state.
     */
    private fun writeSchemaVersionOneDatabase() {
        Files.createDirectories(databasePath.parent)
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE connections (
                        id            TEXT PRIMARY KEY,
                        name          TEXT NOT NULL,
                        engine        TEXT NOT NULL,
                        host          TEXT NOT NULL,
                        port          INTEGER NOT NULL,
                        "database"    TEXT,
                        username      TEXT,
                        secret_sealed BLOB,
                        tls_mode      TEXT,
                        environment   TEXT NOT NULL DEFAULT 'dev',
                        read_only     INTEGER NOT NULL DEFAULT 0,
                        color         TEXT,
                        created_at    TIMESTAMP NOT NULL
                    )
                    """,
                )
                statement.execute("CREATE UNIQUE INDEX idx_connections_name ON connections(name)")
                statement.execute(
                    """
                    CREATE TABLE query_history (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
                        statement     TEXT NOT NULL,
                        duration_ms   INTEGER,
                        row_count     INTEGER,
                        status        TEXT NOT NULL,
                        error         TEXT,
                        executed_at   TIMESTAMP NOT NULL
                    )
                    """,
                )
                statement.execute(
                    "CREATE INDEX idx_history_conn_time ON query_history(connection_id, executed_at DESC)",
                )
                statement.execute(
                    """
                    CREATE TABLE app_metadata (key TEXT PRIMARY KEY, value BLOB NOT NULL)
                    """,
                )
                statement.execute("INSERT INTO app_metadata (key, value) VALUES ('schema_version', '1')")
                listOf("id-1" to "Writable", "id-2" to "Restricted").forEachIndexed { index, (id, name) ->
                    statement.execute(
                        """
                        INSERT INTO connections
                            (id, name, engine, host, port, "database", username, tls_mode,
                             environment, read_only, created_at)
                        VALUES ('$id', '$name', 'postgres', 'localhost', 5432, 'dbide', 'dbide',
                                'disable', 'dev', $index, '2026-08-20T10:00:00Z')
                        """,
                    )
                }
            }
        }
    }

}

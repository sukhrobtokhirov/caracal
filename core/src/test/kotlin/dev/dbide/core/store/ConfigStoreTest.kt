package dev.dbide.core.store

import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionRecord
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.TlsMode
import dev.dbide.core.history.ExecutionOutcome
import dev.dbide.core.history.ExecutionRecord
import dev.dbide.core.history.HistoryQuery
import dev.dbide.core.history.HistoryScope
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    /**
     * One connection's history as a plain list.
     *
     * The store returns a page and a cursor because that is what a panel needs. Most
     * of these tests are about what was written rather than about paging, and reading
     * `.items` in twelve of them would be twelve reminders of a fact none of them is
     * asserting. The paging tests use the real signature.
     */
    private suspend fun ConfigStore.history(id: ConnectionId) =
        history(HistoryQuery(connectionId = id)).items

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
            store.record(execution())
            assertEquals(1, historyRowCount())

            store.delete(ConnectionId("id-1"))

            assertEquals(0, historyRowCount())
        }
    }

    @Test
    fun `query history cannot name a connection that does not exist`() = runTest {
        withStore { store ->
            assertThrows<StoreException> { store.record(execution(id = ConnectionId("ghost"))) }
        }
    }

    @Test
    fun `an execution round trips through history`() = runTest {
        withStore { store ->
            store.create(record())
            store.record(
                execution(
                    statement = "SELECT * FROM \"Mixed Case\" WHERE note = 'quoted, comma'",
                    duration = 1_250.milliseconds,
                    rowCount = 42,
                ),
            )

            val entry = store.history(ConnectionId("id-1")).single()

            assertEquals("SELECT * FROM \"Mixed Case\" WHERE note = 'quoted, comma'", entry.statement)
            assertEquals(ExecutionOutcome.OK, entry.outcome)
            assertEquals(1_250.milliseconds, entry.duration)
            assertEquals(42L, entry.rowCount)
            assertNull(entry.error)
            assertEquals(Instant.parse("2026-08-21T09:00:00Z"), entry.executedAt)
            assertNotNull(entry.id)
        }
    }

    @Test
    fun `a failed execution keeps its message and a cancelled one keeps no numbers`() = runTest {
        withStore { store ->
            store.create(record())
            store.record(
                execution(
                    outcome = ExecutionOutcome.ERROR,
                    at = Instant.parse("2026-08-21T09:00:00Z"),
                    error = "relation \"invoice\" does not exist",
                ),
            )
            store.record(
                execution(outcome = ExecutionOutcome.CANCELLED, at = Instant.parse("2026-08-21T09:01:00Z")),
            )

            val (cancelled, failed) = store.history(ConnectionId("id-1"))

            assertEquals(ExecutionOutcome.CANCELLED, cancelled.outcome)
            assertNull(cancelled.rowCount)
            assertNull(cancelled.duration)
            assertNull(cancelled.error)
            assertEquals("relation \"invoice\" does not exist", failed.error)
        }
    }

    @Test
    fun `history is newest first, and same-millisecond executions keep their order`() = runTest {
        withStore { store ->
            store.create(record())
            val moment = Instant.parse("2026-08-21T09:00:00Z")
            store.record(execution(statement = "first", at = moment))
            store.record(execution(statement = "second", at = moment))
            store.record(execution(statement = "third", at = moment.plusSeconds(1)))

            assertEquals(
                listOf("third", "second", "first"),
                store.history(ConnectionId("id-1")).map { it.statement },
            )
        }
    }

    @Test
    fun `retention keeps the newest entries, and only for the connection written to`() = runTest {
        ConfigStore.open(databasePath, historyRetention = 2).use { store ->
            store.create(record(id = "id-1", name = "Alpha"))
            store.create(record(id = "id-2", name = "Beta"))
            val moment = Instant.parse("2026-08-21T09:00:00Z")
            repeat(3) { index ->
                store.record(execution(statement = "alpha-$index", at = moment.plusSeconds(index.toLong())))
            }
            repeat(2) { index ->
                store.record(
                    execution(
                        id = ConnectionId("id-2"),
                        statement = "beta-$index",
                        at = moment.plusSeconds(index.toLong()),
                    ),
                )
            }

            assertContentEquals(
                listOf("alpha-2", "alpha-1"),
                store.history(ConnectionId("id-1")).map { it.statement },
            )
            // An afternoon in staging must not evict last week's production queries.
            assertContentEquals(
                listOf("beta-1", "beta-0"),
                store.history(ConnectionId("id-2")).map { it.statement },
            )
        }
    }

    @Test
    fun `history reads back only the connection it was asked for`() = runTest {
        withStore { store ->
            store.create(record(id = "id-1", name = "Alpha"))
            store.create(record(id = "id-2", name = "Beta"))
            store.record(execution(statement = "alpha"))
            store.record(execution(id = ConnectionId("id-2"), statement = "beta"))

            assertEquals(listOf("alpha"), store.history(ConnectionId("id-1")).map { it.statement })
        }
    }

    @Test
    fun `a page carries a cursor only while there is another page behind it`() = runTest {
        withStore { store ->
            store.create(record())
            repeat(5) { store.record(execution(statement = "q$it")) }

            val first = store.history(HistoryQuery(connectionId = ConnectionId("id-1"), limit = 2))
            assertEquals(listOf("q4", "q3"), first.items.map { it.statement })
            assertNotNull(first.next)

            val second = store.history(
                HistoryQuery(connectionId = ConnectionId("id-1"), limit = 2, olderThan = first.next),
            )
            assertEquals(listOf("q2", "q1"), second.items.map { it.statement })
            assertNotNull(second.next)

            val last = store.history(
                HistoryQuery(connectionId = ConnectionId("id-1"), limit = 2, olderThan = second.next),
            )
            assertEquals(listOf("q0"), last.items.map { it.statement })
            // The page is short, so there is provably nothing behind it. A cursor here
            // would draw a Show more that produces nothing when it is pressed.
            assertNull(last.next)
        }
    }

    @Test
    fun `paging is not shifted by executions recorded while it is under way`() = runTest {
        withStore { store ->
            store.create(record())
            repeat(4) { store.record(execution(statement = "q$it")) }

            val first = store.history(HistoryQuery(connectionId = ConnectionId("id-1"), limit = 2))
            // History grows at exactly the end paging starts from, which is what an
            // offset cannot survive: OFFSET 2 would now return q2 again and skip q1.
            store.record(execution(statement = "later"))

            val second = store.history(
                HistoryQuery(connectionId = ConnectionId("id-1"), limit = 2, olderThan = first.next),
            )

            assertEquals(listOf("q1", "q0"), second.items.map { it.statement })
        }
    }

    @Test
    fun `a page can be read across every connection at once, or filtered to one ending`() = runTest {
        withStore { store ->
            store.create(record(id = "id-1", name = "Alpha"))
            store.create(record(id = "id-2", name = "Beta"))
            store.record(execution(statement = "alpha-ok"))
            store.record(execution(id = ConnectionId("id-2"), statement = "beta-ok"))
            store.record(execution(statement = "alpha-failed", outcome = ExecutionOutcome.ERROR))

            assertEquals(
                listOf("alpha-failed", "beta-ok", "alpha-ok"),
                store.history(HistoryQuery()).items.map { it.statement },
            )
            assertEquals(
                listOf("alpha-failed"),
                store.history(HistoryQuery(outcome = ExecutionOutcome.ERROR)).items.map { it.statement },
            )
            assertEquals(
                listOf("alpha-ok"),
                store.history(
                    HistoryQuery(connectionId = ConnectionId("id-1"), outcome = ExecutionOutcome.OK),
                ).items.map { it.statement },
            )
        }
    }

    @Test
    fun `a page is never larger than the ceiling, whatever it is asked for`() = runTest {
        withStore { store ->
            store.create(record())
            repeat(3) { store.record(execution(statement = "q$it")) }

            // The caller's own number is a request, not an instruction: history is
            // capped per connection at a thousand statements, and a single call that
            // read all of them would hand the panel a thousand strings to hold.
            assertEquals(3, store.history(HistoryQuery(limit = Int.MAX_VALUE)).items.size)
            assertEquals(1, store.history(HistoryQuery(limit = 0)).items.size)
        }
    }

    @Test
    fun `clearing everything empties every connection and keeps all of them`() = runTest {
        withStore { store ->
            store.create(record(id = "id-1", name = "Alpha"))
            store.create(record(id = "id-2", name = "Beta"))
            store.record(execution(statement = "alpha"))
            store.record(execution(id = ConnectionId("id-2"), statement = "beta"))

            assertEquals(2, store.clearHistory(HistoryScope.Everything))

            assertTrue(store.history(HistoryQuery()).isEmpty)
            assertEquals(2, store.list().size)
        }
    }

    @Test
    fun `clearing history leaves the connection alone`() = runTest {
        withStore { store ->
            store.create(record())
            store.record(execution())
            store.record(execution())

            assertEquals(2, store.clearHistory(HistoryScope.OneConnection(ConnectionId("id-1"))))

            assertTrue(store.history(ConnectionId("id-1")).isEmpty())
            assertEquals("Local", store.get(ConnectionId("id-1")).config.name)
        }
    }

    @Test
    fun `an outcome this build does not know is a read failure, not a skipped row`() = runTest {
        withStore { store ->
            store.create(record())
            // What a newer version writing an outcome this one has never heard of
            // would leave behind. Silently dropping the row would report a history
            // that is missing an execution and say nothing about it.
            insertHistoryRow(ConnectionId("id-1"), status = "rolled_back")

            assertThrows<StoreReadException> { store.history(ConnectionId("id-1")) }
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

    private fun execution(
        id: ConnectionId = ConnectionId("id-1"),
        statement: String = "SELECT 1",
        outcome: ExecutionOutcome = ExecutionOutcome.OK,
        at: Instant = Instant.parse("2026-08-21T09:00:00Z"),
        duration: Duration? = null,
        rowCount: Long? = null,
        error: String? = null,
    ) = ExecutionRecord(
        connectionId = id,
        statement = statement,
        outcome = outcome,
        executedAt = at,
        duration = duration,
        rowCount = rowCount,
        error = error,
    )

    /**
     * Writes a history row the store's own API would refuse to write, which is the
     * only way to model a file written by a version this one does not have.
     */
    private fun insertHistoryRow(connectionId: ConnectionId, status: String) {
        connect().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO query_history (connection_id, statement, status, executed_at)
                VALUES (?, 'SELECT 1', ?, '2026-08-20T10:00:00Z')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, connectionId.value)
                statement.setString(2, status)
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

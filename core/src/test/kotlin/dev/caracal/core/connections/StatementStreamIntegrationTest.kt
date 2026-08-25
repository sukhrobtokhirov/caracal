package dev.caracal.core.connections

import dev.caracal.core.export.ExportLimits
import dev.caracal.core.export.ExportStop
import dev.caracal.core.registry.ConnectionRegistry
import dev.caracal.core.result.CellValue
import dev.caracal.core.result.DbException
import dev.caracal.core.result.Truncation
import dev.caracal.core.result.toFailure
import dev.caracal.core.sql.StatementSplitter
import dev.caracal.core.store.ConfigStore
import dev.caracal.core.vault.KdfParams
import dev.caracal.core.vault.Vault
import dev.caracal.engine.ServerImage
import dev.caracal.engine.postgres.PostgresEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * What `:core` gets back now that it reads the engine's stream, against a real
 * server.
 *
 * Issue #4 replaced `ConnectionRegistry.postgresAdapter` — the last place the SPI was
 * bypassed — with `QueryFacet` for both of the calls that used it. The route changed
 * for every statement the editor runs and every export it writes, and the two things
 * a route change can quietly break are the ones this file is about: the character an
 * error underlines, and whether an export is streamed or assembled first.
 *
 * The underline is the sharper of the two. Its mapping is unit-tested in
 * `ErrorPositionTest` and `PostgresEngineErrorsTest`, and both of those would still
 * pass with `:core` handed the wrong number to feed them — so the case below runs the
 * whole chain the editor runs, from a script through the splitter and the service to
 * `Statement.documentIndex`, and asserts the index of a specific character. §11's
 * "error position underlining verified unchanged for Postgres", by test rather than
 * by eye.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class StatementStreamIntegrationTest {
    @TempDir
    lateinit var directory: Path

    // --- The underline ---------------------------------------------------------

    @Test
    fun `a server error lands on the exact character of the statement that was run`() = runBlocking<Unit> {
        // Two statements, so that the offset of the second one is doing real work: a
        // mapping that ignored it would land inside the first statement and the
        // assertion below would say so rather than passing by coincidence.
        val script = "SELECT 1;\nSELECT totl FROM invoices;"
        val target = StatementSplitter.split(script).statements.last()

        val failure = service { service, id ->
            assertThrows<DbException> { runBlocking { service.execute(id, target.text) } }
        }.toFailure()

        val position = assertNotNull(failure.query?.position, "the server's position did not survive the stream")
        assertEquals(
            script.indexOf("totl"),
            target.documentIndex(position),
            "the underline moved off the misspelled column",
        )
    }

    @Test
    fun `a character outside the BMP earlier in the statement does not shift the underline`() = runBlocking<Unit> {
        // The bug this guards is invisible until it happens: PostgreSQL counts code
        // points and Kotlin counts UTF-16 units, so one emoji before the error puts
        // every later underline one character out.
        val sql = "SELECT '🐍' AS snake, totl FROM invoices"
        val target = StatementSplitter.split(sql).statements.single()

        val failure = service { service, id ->
            assertThrows<DbException> { runBlocking { service.execute(id, sql) } }
        }.toFailure()

        val position = assertNotNull(failure.query?.position)
        assertEquals(sql.indexOf("totl"), target.documentIndex(position))
    }

    @Test
    fun `everything the error report carries survives the crossing`() = runBlocking<Unit> {
        // The SPI's own error type has no room for a severity or for the object a
        // server named, and both are on screen in the error banner: "duplicate key
        // value violates unique constraint" is only actionable once you know which
        // one. They reach the UI because the engine's classified failure travels with
        // the outcome rather than being rebuilt from it, and this is what says so.
        val failure = service(readOnly = false) { service, id ->
            assertThrows<DbException> {
                runBlocking { service.execute(id, "INSERT INTO ledger (note) VALUES ('opening')") }
            }
        }.toFailure()

        val query = assertNotNull(failure.query)
        assertEquals("23505", query.sqlState)
        assertNotNull(query.severity, "the severity did not survive the stream")
        assertEquals("ledger_pkey", query.subject?.constraint, "the constraint the server named was dropped")
    }

    @Test
    fun `a read-only violation keeps its own code rather than becoming a query failure`() = runBlocking<Unit> {
        val thrown = service { service, id ->
            assertThrows<DbException> {
                runBlocking { service.execute(id, "INSERT INTO invoices (total) VALUES (1)") }
            }
        }

        assertEquals("read_only_violation", thrown.error.code)
    }

    @Test
    fun `a failed statement is a failure and not an empty result`() = runBlocking<Unit> {
        val thrown = service { service, id ->
            assertThrows<DbException> { runBlocking { service.execute(id, "SELECT * FROM no_such_table") } }
        }
        assertEquals("query_failed", thrown.error.code)
    }

    // --- What the stream assembles ---------------------------------------------

    @Test
    fun `a result comes back with its columns, its values and its notices`() = runBlocking<Unit> {
        val result = service { service, id ->
            service.execute(id, "SELECT total FROM invoices ORDER BY total")
        }

        assertEquals(listOf("total"), result.columns.map { it.name })
        assertEquals(listOf("numeric"), result.columns.map { it.typeName })
        assertEquals(3, result.rows.size)
        assertEquals(CellValue.Decimal(java.math.BigDecimal("10")), result.rows.first().single())
        assertEquals(Truncation.NONE, result.truncation)
    }

    @Test
    fun `a statement with no result set reports its count rather than an empty grid`() = runBlocking<Unit> {
        val result = service(readOnly = false) { service, id ->
            service.execute(id, "UPDATE invoices SET total = total")
        }

        assertEquals(3L, result.rowsAffected)
        assertTrue(result.columns.isEmpty())
    }

    @Test
    fun `a notice is the whole output of some statements and is not dropped`() = runBlocking<Unit> {
        val result = service(readOnly = false) { service, id ->
            service.execute(id, "DO $$ BEGIN RAISE NOTICE 'checked 3 tables'; END $$")
        }

        assertTrue(
            result.notices.any { it.message.contains("checked 3 tables") },
            "the notice was swallowed: ${result.notices}",
        )
    }

    @Test
    fun `a result past the row limit says so rather than looking complete`() = runBlocking<Unit> {
        val result = service { service, id ->
            service.execute(id, "SELECT i FROM generate_series(1, 5000) i")
        }

        assertEquals(Truncation.ROW_LIMIT, result.truncation)
        assertTrue(result.truncated)
        assertEquals(1_000, result.rows.size, "the row limit is what bounds a grid, and it still does")
    }

    // --- Export ----------------------------------------------------------------

    @Test
    fun `an export writes every row, past the limit that bounds a grid`() = runBlocking<Unit> {
        // The point of an export streaming rather than materializing: five thousand
        // rows is five times what the grid retains, and the file holds all of them.
        val path = directory.resolve("series.csv")

        val report = service { service, id ->
            service.exportCsv(id, "SELECT i FROM generate_series(1, 5000) i", path)
        }

        assertEquals(5_000, report.rows)
        assertEquals(ExportStop.COMPLETE, report.stopped)
        assertEquals(Files.size(path), report.bytes)
        assertEquals(5_001, Files.readAllLines(path).size, "a header and one record per row")
    }

    @Test
    fun `an export that reaches its row budget says which budget it was`() = runBlocking<Unit> {
        val path = directory.resolve("bounded.csv")

        val report = service { service, id ->
            service.exportCsv(
                id,
                "SELECT i FROM generate_series(1, 5000) i",
                path,
                limits = ExportLimits(rows = 10),
            )
        }

        assertEquals(10, report.rows)
        assertEquals(ExportStop.ROW_LIMIT, report.stopped)
    }

    @Test
    fun `an export of a statement that modifies data is refused before anything runs`() = runBlocking<Unit> {
        val path = directory.resolve("refused.csv")

        val thrown = service { service, id ->
            assertThrows<DbException> {
                runBlocking { service.exportCsv(id, "UPDATE invoices SET total = 0", path) }
            }
        }

        assertEquals("export_unavailable", thrown.error.code)
        assertTrue(Files.notExists(path), "left a file behind for an export that never ran")
    }

    // --- Fixtures --------------------------------------------------------------

    /**
     * Opens a vault, creates one connection against the container, and runs [body].
     *
     * A fresh store per case, because the vault is set up once per store and these
     * cases care about statements rather than about sharing one.
     */
    private suspend fun <T> service(readOnly: Boolean = true, body: suspend (ConnectionService, ConnectionId) -> T): T {
        val store = ConfigStore.open(directory.resolve("caracal-${counter++}.db"))
        val registry = ConnectionRegistry()
        // Argon2id at production cost would add a second to every case here.
        val service = DefaultConnectionService(store, Vault(store, store, params = KdfParams.TESTING), registry)
        try {
            service.setUp(Secret("correct-horse-battery"))
            val view = service.create(
                networkDraft(
                    engineId = PostgresEngine.ID,
                    name = "Postgres",
                    host = postgres.host,
                    port = postgres.firstMappedPort,
                    database = postgres.databaseName,
                    username = postgres.username,
                    secret = SecretUpdate.Replace(Secret(postgres.password)),
                    readOnly = readOnly,
                ),
            )
            service.open(view.id)
            return body(service, view.id)
        } finally {
            registry.closeAll()
            store.close()
        }
    }

    private var counter = 0

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(ServerImage.postgres)
                .withDatabaseName("caracal_stream")
                .withUsername("caracal_reader")
                .withPassword("pg-secret-password")
                .also { it.start() }

        @JvmStatic
        @BeforeAll
        fun seed() {
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE invoices (total numeric)")
                    statement.execute("INSERT INTO invoices (total) VALUES (10), (20), (30)")
                    // A named constraint, so that a failure has an object to name.
                    statement.execute("CREATE TABLE ledger (note text PRIMARY KEY)")
                    statement.execute("INSERT INTO ledger (note) VALUES ('opening')")
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}

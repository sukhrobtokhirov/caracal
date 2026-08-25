package dev.caracal.core.postgres

import dev.caracal.core.connections.Secret
import dev.caracal.core.export.CsvExport
import dev.caracal.core.export.CsvExportReport
import dev.caracal.core.export.CsvOptions
import dev.caracal.core.export.ExportLimits
import dev.caracal.core.export.ExportRefusal
import dev.caracal.core.export.ExportStop
import dev.caracal.core.result.CellValue
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.result.QueryResult
import dev.caracal.engine.ServerImage
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Whether an exported file holds what the database holds.
 *
 * A CSV is read by tools that will not ask what was meant, and usually on another
 * machine and another day. So these are asked of a real server: the quoting a
 * value needs to survive, the difference between NULL and an empty string, a
 * `bytea` PostgreSQL will still accept, and a `timestamptz` that means the same
 * instant in the file as it does in the grid.
 *
 * The rest is about what an export costs. It streams — nothing is retained, so
 * the budgets are on the file rather than on memory — and a budget that ends it
 * early has to say so, because a CSV gives its reader no way to tell that the
 * rows simply stopped.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class PostgresCsvExportIntegrationTest {

    @TempDir
    lateinit var directory: Path

    // --- What the file says ----------------------------------------------------

    @Test
    fun `a result is a header and one record per row`() {
        val export = export("SELECT id, name FROM invoices ORDER BY id")

        assertEquals(
            "id,name\r\n" +
                "1,Ada\r\n" +
                // A comma inside a value would otherwise be a column boundary.
                "2,\"Grace, Hopper\"\r\n" +
                // The line break is the value's, kept as it was; the record still ends CRLF.
                "3,\"two\nlines \"\"quoted\"\"\"\r\n",
            export.csv,
        )
        assertEquals(3, export.report.rows)
        assertTrue(export.report.complete)
    }

    @Test
    fun `NULL, an empty string, and the word NULL are three different things`() {
        // The distinction the grid draws in italics has to survive into a file that
        // has no italics. An empty field is an empty string; the sentinel is bare; a
        // value that spells the sentinel is quoted.
        val export = export("SELECT note FROM invoices ORDER BY id")

        assertEquals("note\r\nNULL\r\n\r\n\"  padded  \"\r\n", export.csv)
        assertEquals(
            "spelled,missing\r\n\"NULL\",NULL\r\n",
            export("SELECT 'NULL'::text AS spelled, NULL::text AS missing").csv,
        )
    }

    @Test
    fun `numbers keep every digit and their scale`() {
        val export = export("SELECT total, big, paid FROM invoices ORDER BY id")

        assertEquals(
            "total,big,paid\r\n" +
                // 2^53 + 1: the value a JSON wire would have rounded.
                "1234.5000,9007199254740993,true\r\n" +
                "0.0001,-1,false\r\n" +
                "0.0000,0,NULL\r\n",
            export.csv,
        )
    }

    @Test
    fun `a timestamptz means the same instant in the file as in the grid`() {
        val shown = query("SELECT sent FROM invoices WHERE id = 3").rows.single().single()

        assertEquals("sent\r\n${(shown as CellValue.Text).value}\r\n", export("SELECT sent FROM invoices WHERE id = 3").csv)
    }

    @Test
    fun `bytea exports in the form PostgreSQL reads back`() {
        val exported = export("SELECT receipt FROM invoices WHERE id = 1").csv.removePrefix("receipt\r\n").trim()

        val same = query("SELECT '$exported'::bytea = '\\xdeadbeef'::bytea AS same").rows.single().single()
        assertEquals(CellValue.Bool(true), same)
    }

    @Test
    fun `the null representation can be the one the destination expects`() {
        // PostgreSQL's own `COPY ... WITH (FORMAT csv)` writes an empty field for NULL
        // by default and `\N` on request. Whatever a file is going to be read by, the
        // sentinel is the one thing about it that cannot have a right answer here.
        val export = export("SELECT note FROM invoices ORDER BY id", options = CsvOptions(nullText = "\\N"))

        assertEquals("note\r\n\\N\r\n\r\n\"  padded  \"\r\n", export.csv)
    }

    // --- What the file costs ---------------------------------------------------

    @Test
    fun `a result far larger than the grid's own limit exports whole`() {
        // Fifty times the grid's thousand-row bound, and none of it is ever held:
        // this is the reason export re-runs the query instead of keeping the result.
        val export = export("SELECT i, repeat('x', 100) FROM generate_series(1, 50000) i")

        assertEquals(50_000, export.report.rows)
        assertTrue(export.report.complete)
        assertEquals(50_001, export.csv.split("\r\n").count { it.isNotEmpty() })
    }

    @Test
    fun `an export that reaches the row budget stops and says which budget it was`() {
        val export = export("SELECT i FROM generate_series(1, 100) i", limits = ExportLimits(rows = 10))

        assertEquals(ExportStop.ROW_LIMIT, export.report.stopped)
        assertEquals(10, export.report.rows)
        assertFalse(export.report.complete)
        assertEquals(11, export.csv.split("\r\n").count { it.isNotEmpty() })
    }

    @Test
    fun `an export that reaches the byte budget stops at the end of a record`() {
        val budget = ExportLimits(bytes = 100)

        val export = export("SELECT repeat('x', 40) FROM generate_series(1, 100)", limits = budget)

        assertEquals(ExportStop.SIZE_LIMIT, export.report.stopped)
        assertTrue(export.report.rows in 1..4, "wrote ${export.report.rows} rows")
        // Checked between rows, so the file passes the budget by at most one record
        // and never stops inside one.
        assertTrue(export.report.bytes >= budget.bytes, "stopped early at ${export.report.bytes}")
        assertTrue(export.report.bytes < budget.bytes + 64, "overshot to ${export.report.bytes}")
        assertTrue(export.csv.endsWith("\r\n"))
    }

    @Test
    fun `an export past its time budget stops`() {
        // The clock starts when the export does, not when the first row arrives, so
        // the budget is already spent by the time there is anything to write.
        //
        // How *much* gets written before the check first fires is a fact about the
        // machine and not about this code — the row is what an earlier version of this
        // test asserted, and it held only for as long as no other container in the
        // suite had warmed Docker up first. What the budget promises is that the export
        // stops far short of the query, says which budget stopped it, and leaves a file
        // that ends at a record boundary; those are what is asserted.
        val rows = 2_000_000
        val export = export(
            "SELECT i FROM generate_series(1, $rows) i",
            limits = ExportLimits(duration = 1.milliseconds),
        )

        assertEquals(ExportStop.TIME_LIMIT, export.report.stopped)
        assertFalse(export.report.complete)
        assertTrue(export.report.rows < rows / 10, "wrote ${export.report.rows} of $rows rows")
        assertTrue(export.csv.startsWith("i\r\n"), "the header is missing from: ${export.csv.take(20)}")
        assertTrue(export.csv.endsWith("\r\n"), "stopped inside a record")
    }

    @Test
    fun `the report's byte count is the size of the file`() = runBlocking {
        val path = directory.resolve(CsvExport.fileName("invoices"))

        val report = PostgresSession.open(config()).use { session ->
            CsvExport.writeToFile(path) { out -> session.adapter.exportCsv("SELECT * FROM invoices ORDER BY id", out) }
        }

        assertEquals(3, report.rows)
        assertEquals(Files.size(path), report.bytes)
    }

    // --- What is refused -------------------------------------------------------

    @Test
    fun `a statement that modifies data is refused, and the table is untouched`() {
        val error = assertThrows<DbException> { export("UPDATE invoices SET total = 0") }.error

        assertIs<DbError.ExportUnavailable>(error)
        assertEquals(ExportRefusal.MODIFIES_DATA.message, error.message)
        assertEquals(
            CellValue.Decimal(BigDecimal("1234.5000")),
            query("SELECT total FROM invoices WHERE id = 1").rows.single().single(),
        )
    }

    @Test
    fun `a write the classifier cannot see is still refused by the server`() {
        // The classifier is not the boundary and this is what says so: the statement
        // is a SELECT, the write is inside a function, and the read-only transaction
        // refuses it on the way to the file exactly as it would on the way to the grid.
        val error = assertThrows<DbException> { export("SELECT add_invoice(99)") }.error

        assertIs<DbError.ReadOnlyViolation>(error)
        assertEquals(3, (query("SELECT count(*) AS n FROM invoices").rows.single().single() as CellValue.Integer).value)
    }

    @Test
    fun `cancelling an export ends the query on the server and leaves no file`() = runBlocking {
        val path = directory.resolve("cancelled.csv")

        PostgresSession.open(config()).use { session ->
            PostgresSession.open(config()).use { observer ->
                val writing = CompletableDeferred<Unit>()
                val export = launch(Dispatchers.IO) {
                    writing.complete(Unit)
                    CsvExport.writeToFile(path) { out -> session.adapter.exportCsv(SLEEPING_ROWS, out) }
                }
                writing.await()
                awaitBackend(observer)

                export.cancelAndJoin()

                assertBackendIsGone(observer)
                assertEquals(0, session.activeConnections)
                // A CSV that stops where the user changed their mind is indistinguishable
                // from one that ran out of rows.
                assertFalse(Files.exists(path), "left a partial file behind")
            }
        }
    }

    // --- Fixtures --------------------------------------------------------------

    private data class Export(val csv: String, val report: CsvExportReport)

    private fun export(
        sql: String,
        options: CsvOptions = CsvOptions(),
        limits: ExportLimits = ExportLimits(),
    ): Export = runBlocking {
        PostgresSession.open(config()).use { session ->
            val out = StringBuilder()
            val report = session.adapter.exportCsv(sql, out, options, limits)
            Export(out.toString(), report)
        }
    }

    private fun query(sql: String): QueryResult = runBlocking {
        PostgresSession.open(config()).use { session -> session.adapter.execute(sql) }
    }

    private suspend fun awaitBackend(observer: PostgresSession) =
        withTimeout(10.seconds) {
            while (sleepingBackends(observer) == 0L) delay(50)
        }

    private suspend fun assertBackendIsGone(observer: PostgresSession) =
        withTimeout(10.seconds) {
            while (sleepingBackends(observer) > 0L) delay(50)
        }

    private suspend fun sleepingBackends(observer: PostgresSession): Long {
        val result = observer.adapter.execute(
            "SELECT count(*) AS running FROM pg_stat_activity WHERE query LIKE 'SELECT i, pg_sleep%'",
        )
        return (result.rows.single().single() as CellValue.Integer).value
    }

    private fun config() = PostgresConnectionConfig(
        host = postgres.host,
        port = postgres.firstMappedPort,
        database = postgres.databaseName,
        user = postgres.username,
        password = Secret(postgres.password),
    )

    companion object {
        /** A read that produces rows slowly enough to be cancelled mid-export. */
        private const val SLEEPING_ROWS = "SELECT i, pg_sleep(1) FROM generate_series(1, 60) i"

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(ServerImage.postgres).also { it.start() }

        @JvmStatic
        @BeforeAll
        fun seed() {
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE invoices (
                            id      int PRIMARY KEY,
                            name    text,
                            total   numeric(20,4),
                            big     int8,
                            paid    bool,
                            receipt bytea,
                            sent    timestamptz,
                            note    text
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO invoices VALUES
                            (1, 'Ada', 1234.5000, 9007199254740993, true, '\xdeadbeef',
                             '2026-08-20 07:30:00+00', NULL),
                            (2, 'Grace, Hopper', 0.0001, -1, false, NULL, NULL, ''),
                            (3, e'two\nlines "quoted"', 0, 0, NULL, '\x',
                             '2026-01-01 00:00:00+05', '  padded  ')
                        """.trimIndent(),
                    )
                    // The write no keyword scan can see, for the same reason the read-only
                    // suite has one: enforcement belongs to the transaction.
                    statement.execute(
                        """
                        CREATE FUNCTION add_invoice(n int) RETURNS int LANGUAGE sql AS $$
                            INSERT INTO invoices (id, name) VALUES (n, 'written') RETURNING id
                        $$
                        """.trimIndent(),
                    )
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}

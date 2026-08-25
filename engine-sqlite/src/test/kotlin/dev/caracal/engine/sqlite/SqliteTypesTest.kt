package dev.caracal.engine.sqlite

import dev.caracal.engine.api.CellValue
import dev.caracal.engine.api.ColumnFormat
import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionId
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.DatabaseSession
import dev.caracal.engine.api.QueryFacet
import dev.caracal.engine.api.ResultDescriptor
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.api.SessionPolicy
import dev.caracal.engine.api.StatementOutcome
import dev.caracal.engine.api.StatementRequest
import java.math.BigInteger
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What SQLite's dynamic typing does to a result, asserted against a real file.
 *
 * The conformance suite cannot ask any of this. It asks the questions every engine has
 * an answer to, and "what happens when a column declared `INTEGER` holds the string
 * `n/a`" is a question only one engine in this repository has — the other two would
 * have rejected the write.
 *
 * That is the whole of what makes this engine's value mapping different, so it is
 * worth being precise about the property under test: **a value's type is the value's,
 * and the column's declared type is only a label.** Every case below is one way of
 * getting those two to disagree.
 */
class SqliteTypesTest {

    @TempDir
    lateinit var directory: Path

    private val opened = mutableListOf<DatabaseSession>()

    @AfterEach
    fun closeSessions() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    @Test
    fun `an INTEGER column holding text renders as text`() = runBlocking<Unit> {
        // The line from the issue, as a test. SQLite applies INTEGER affinity as a
        // *preference*: `'42'` converts because the conversion is lossless, and `'n/a'`
        // does not because it is not a number, so one row of this column is an integer
        // and the next is a string. A mapping driven by the column's declared type
        // would render the second as a number — or, worse, as a zero, which is what
        // `getLong` returns for a string it cannot parse.
        val session = open(
            "CREATE TABLE mixed (n INTEGER)",
            "INSERT INTO mixed (n) VALUES ('42')",
            "INSERT INTO mixed (n) VALUES ('n/a')",
            "INSERT INTO mixed (n) VALUES (NULL)",
        )

        val cells = column(session, "SELECT n FROM mixed ORDER BY rowid")

        assertEquals(
            listOf(CellValue.Integer(BigInteger.valueOf(42)), CellValue.Text("n/a"), CellValue.Null),
            cells,
            "the column's declared type decided a value's type",
        )
    }

    @Test
    fun `the declared type still decides how the column is presented`() = runBlocking<Unit> {
        // The other half, and the reason the two are not the same field. A column of
        // mixed values is still a column of numbers as far as the schema is concerned,
        // and the grid has to align it one way for all its rows. So the *format* comes
        // from the declared type even where a value contradicts it.
        val session = open("CREATE TABLE mixed (n INTEGER)", "INSERT INTO mixed (n) VALUES ('n/a')")

        val descriptor = descriptor(session, "SELECT n FROM mixed")

        assertEquals("INTEGER", descriptor.columns.single().typeName)
        assertEquals(ColumnFormat.NUMBER, descriptor.columns.single().format)
    }

    @Test
    fun `a declared type is read by SQLite's affinity rules, not by an exact name`() = runBlocking<Unit> {
        // SQLite accepts any type name at all and decides what it means by looking for
        // five substrings in a fixed order. `UNSIGNED BIG INT` is a number because it
        // contains INT; `wibble` is NUMERIC affinity because it contains none of them;
        // `INTCHAR` is a number because INT is tested before CHAR. Getting the order
        // wrong means two spellings of the same type align differently in one table.
        val session = open(
            """
            CREATE TABLE shapes (
                a "UNSIGNED BIG INT", b "VARYING CHARACTER(20)", c "wibble",
                d "INTCHAR", e "BLOB", f "DOUBLE PRECISION"
            )
            """.trimIndent(),
        )

        val formats = descriptor(session, "SELECT a, b, c, d, e, f FROM shapes").columns.map { it.format }

        assertEquals(
            listOf(
                ColumnFormat.NUMBER,
                ColumnFormat.TEXT,
                ColumnFormat.NUMBER,
                ColumnFormat.NUMBER,
                ColumnFormat.BINARY,
                ColumnFormat.NUMBER,
            ),
            formats,
        )
    }

    @Test
    fun `a real is a float and never a decimal`() = runBlocking<Unit> {
        // The declaration `exactNumerics = false` made concrete. A `DECIMAL(30,10)`
        // column converts what it is given to an IEEE double, so what comes back is
        // approximate — and [CellValue.Floating] is the only arm that says so.
        // Rendering it as a Decimal would claim a precision the bytes do not have,
        // which is the guarantee `CellValue` exists to keep.
        val session = open(
            "CREATE TABLE money (amount DECIMAL(30,10))",
            "INSERT INTO money (amount) VALUES ('12345678901234567890.1234567890')",
        )

        val cell = column(session, "SELECT amount FROM money").single()

        val floating = assertIs<CellValue.Floating>(cell, "a SQLite DECIMAL arrived as something exact")
        assertTrue(
            floating.value != 0.0 && floating.value.toString().startsWith("1.2345678901234"),
            "the value was not the double SQLite stored: ${floating.value}",
        )
    }

    @Test
    fun `a blob arrives as bounded hexadecimal with its true size`() = runBlocking<Unit> {
        val session = open(
            "CREATE TABLE files (body BLOB)",
            "INSERT INTO files (body) VALUES (x'00ff10')",
        )

        val cell = column(session, "SELECT body FROM files").single()

        assertEquals(CellValue.Bytes(preview = "\\x00ff10", byteCount = 3, truncated = false), cell)
    }

    @Test
    fun `a JSON column's text is labelled and a temporal column's is too`() = runBlocking<Unit> {
        // Labelling, not conversion: both hold exactly the characters SQLite stored.
        // It is what lets the grid offer a pretty-printer for one and a date renderer
        // for the other without either of them changing the value.
        val session = open(
            "CREATE TABLE documents (body JSON, seen DATETIME)",
            "INSERT INTO documents VALUES ('{\"a\":1}', '2026-08-25 10:00:00')",
        )

        val cells = row(session, "SELECT body, seen FROM documents")

        assertEquals(CellValue.Json("{\"a\":1}"), cells[0])
        val temporal = assertIs<CellValue.Temporal>(cells[1])
        assertEquals("2026-08-25 10:00:00", temporal.raw)
    }

    @Test
    fun `a DATETIME column holding an integer is an integer`() = runBlocking<Unit> {
        // Storing a Unix epoch in a `DATETIME` column is ordinary SQLite. The label
        // applies to text and only to text, because an integer that has been called a
        // timestamp is still an integer and rendering it as a date would be inventing
        // a time zone and an epoch nobody declared.
        val session = open(
            "CREATE TABLE events (at DATETIME)",
            "INSERT INTO events (at) VALUES (1756108800)",
        )

        val cell = column(session, "SELECT at FROM events").single()

        assertEquals(CellValue.Integer(BigInteger.valueOf(1756108800)), cell)
    }

    // ---------------------------------------------------------------- helpers

    /** A read-only session over a fresh database seeded with [statements]. */
    private suspend fun open(vararg statements: String): DatabaseSession {
        val path = directory.resolve("types.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach { statement.executeUpdate(it) }
            }
        }
        return SqliteEngine().connect(
            descriptor = ConnectionDescriptor(
                id = ConnectionId("types"),
                engineId = SqliteEngine.ID,
                displayName = "Types",
                target = ConnectionTarget.File(path),
            ),
            secrets = SecretBundle.None,
            policy = SessionPolicy(readOnly = true, statementTimeout = 10.seconds),
        ).also { opened += it }
    }

    private suspend fun descriptor(session: DatabaseSession, sql: String): ResultDescriptor =
        rowsOutcome(session, sql).first

    private suspend fun row(session: DatabaseSession, sql: String): List<CellValue> =
        rowsOutcome(session, sql).second.single()

    private suspend fun column(session: DatabaseSession, sql: String): List<CellValue> =
        rowsOutcome(session, sql).second.map { it.single() }

    /**
     * The descriptor and every row of a statement, read inside the emission that
     * carried them.
     *
     * Inside, because [StatementOutcome.Rows] is live only while it is being
     * delivered — the cursor, the transaction and the lock on the file are all
     * released the moment this returns.
     */
    private suspend fun rowsOutcome(
        session: DatabaseSession,
        sql: String,
    ): Pair<ResultDescriptor, List<List<CellValue>>> {
        var result: Pair<ResultDescriptor, List<List<CellValue>>>? = null
        val facet = session.facet(QueryFacet::class)!!
        facet.execute(StatementRequest(sql = sql)).outcomes.collect { outcome ->
            when (outcome) {
                is StatementOutcome.Rows ->
                    result = outcome.descriptor to outcome.rows.toList().map { it.cells }

                is StatementOutcome.Failed -> error("'$sql' failed: ${outcome.error.message}")
                else -> Unit
            }
        }
        return checkNotNull(result) { "'$sql' produced no rows" }
    }
}

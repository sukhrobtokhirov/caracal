package dev.caracal.core.postgres

import dev.caracal.core.connections.Secret
import dev.caracal.core.result.DbError
import dev.caracal.core.text.Redaction
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.sql.SQLWarning
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLWarning
import org.postgresql.util.ServerErrorMessage

class PostgresErrorsTest {
    private val config = PostgresConnectionConfig(
        host = "db.internal.example",
        port = 6432,
        database = "payments",
        user = "reporting",
        password = Secret("hunter2"),
    )
    private val redaction = Redaction(config.secrets())

    @ParameterizedTest(name = "SQLSTATE {0} is {1}")
    @CsvSource(
        "28P01, authentication_failed",
        "28000, authentication_failed",
        "3D000, database_not_found",
        "08001, host_unreachable",
        "08S01, host_unreachable",
        "08006, connection_failed",
        "08004, connection_failed",
        "57P03, connection_failed",
        "57014, cancelled",
        "25006, read_only_violation",
        "42P01, query_failed",
    )
    fun `maps SQLSTATE onto a distinct case`(sqlState: String, expectedCode: String) {
        val error = PostgresErrors.classify(SQLException("driver text", sqlState), redaction)

        assertEquals(expectedCode, error.code)
    }

    @Test
    fun `a pool timeout inherits the state of the failure underneath it`() {
        val poolTimeout = SQLTransientConnectionException(
            "Connection is not available, request timed out after 5000ms",
            null as String?,
            SQLException("connection refused", "08001"),
        )

        assertIs<DbError.HostUnreachable>(PostgresErrors.classify(poolTimeout, redaction))
    }

    @Test
    fun `an exception wrapped by another library is still classified`() {
        val wrapped = RuntimeException("pool failed", SQLException("bad password", "28P01"))

        assertIs<DbError.AuthenticationFailed>(PostgresErrors.classify(wrapped, redaction))
    }

    @Test
    fun `a timeout beats the cancellation state it arrives as`() {
        val cancelled = SQLException("ERROR: canceling statement due to statement timeout", "57014")

        assertIs<DbError.Timeout>(PostgresErrors.classify(cancelled, redaction, timedOut = true))
        assertIs<DbError.Timeout>(PostgresErrors.classify(SQLTimeoutException("timed out"), redaction))
    }

    @Test
    fun `a timeout says which limit it passed, and a cancellation does not`() {
        // §2.4: the two arrive as the same SQLSTATE and must not read the same. A
        // user who pressed Stop knows why their query ended; a user whose query hit a
        // limit they have never seen needs the number.
        val cancelled = SQLException("canceling statement due to user request", "57014")

        val timedOut = PostgresErrors.classify(cancelled, redaction, timedOut = true, limit = 30.seconds)
        val stopped = PostgresErrors.classify(cancelled, redaction)

        assertIs<DbError.Timeout>(timedOut)
        assertEquals(30.seconds, timedOut.limit)
        assertTrue(timedOut.message.contains("30s"), "the limit is missing from: ${timedOut.message}")

        assertIs<DbError.Cancelled>(stopped)
        assertNotEquals(timedOut.message, stopped.message)
    }

    // --- §2.9: the server's own report ---------------------------------------

    @Test
    fun `the whole server error report is carried across`() {
        val error = assertIs<DbError.QueryFailed>(
            PostgresErrors.classify(psqlException(SYNTAX_ERROR), redaction),
        )

        assertEquals("42601", error.sqlState)
        assertEquals("ERROR", error.severity)
        assertEquals(8, error.position)
        assertEquals("This is the detail.", error.detail)
        assertEquals("Perhaps you meant \"total\".", error.hint)
    }

    @Test
    fun `the object a constraint violation names is carried across`() {
        val error = assertIs<DbError.QueryFailed>(
            PostgresErrors.classify(psqlException(CONSTRAINT_VIOLATION), redaction),
        )

        val subject = assertNotNull(error.subject)
        assertEquals("sales", subject.schema)
        assertEquals("orders", subject.table)
        assertEquals("orders_total_check", subject.constraint)
        assertEquals("sales.orders, constraint orders_total_check", subject.describe())
    }

    @Test
    fun `an error that names no object carries no subject at all`() {
        // Rather than an ErrorSubject of five nulls, which the UI would then have to
        // ask whether it is empty.
        val error = assertIs<DbError.QueryFailed>(
            PostgresErrors.classify(psqlException(SYNTAX_ERROR), redaction),
        )

        assertNull(error.subject)
    }

    @Test
    fun `the server file, line, and routine never leave the adapter`() {
        // §2.9's exclusion list. They name the C source that raised the error, which
        // helps nobody outside the PostgreSQL source tree and turns a screenshot of
        // an error into a statement about the server build.
        val error = assertIs<DbError.QueryFailed>(
            PostgresErrors.classify(psqlException(SYNTAX_ERROR), redaction),
        )

        val everything = listOf(error.message, error.detail, error.hint, error.severity)
            .joinToString(" ")
        listOf("scan.l", "1176", "scanner_yyerror", "PL/pgSQL function").forEach {
            assertFalse(everything.contains(it), "leaked \"$it\" in: $everything")
        }
    }

    @Test
    fun `a position inside a generated query is reported and not mistaken for a real one`() {
        // An error raised inside a function body counts its position into a query the
        // server generated, which is not in the user's document. Reported as itself so
        // the editor can decline to point at anything.
        val error = assertIs<DbError.QueryFailed>(
            PostgresErrors.classify(psqlException(INTERNAL_POSITION), redaction),
        )

        assertNull(error.position)
        assertEquals(19, error.internalPosition)
    }

    @Test
    fun `every part of a server report goes through redaction`() {
        val error = assertIs<DbError.QueryFailed>(
            PostgresErrors.classify(psqlException(LEAKY), redaction),
        )

        listOf(error.message, error.detail, error.hint, error.subject?.table).forEach {
            assertNoConnectionIdentity(it.orEmpty())
        }
    }

    @Test
    fun `a failure with no SQLException anywhere is a connection failure`() {
        assertIs<DbError.ConnectionFailed>(PostgresErrors.classify(IllegalStateException("no idea")))
    }

    @Test
    fun `connection errors never carry the connection identity`() {
        val leaky = SQLException(
            "FATAL: password authentication failed for user \"reporting\" " +
                "(jdbc:postgresql://db.internal.example:6432/payments)",
            "28P01",
        )

        val message = PostgresErrors.classify(leaky, redaction).message

        assertNoConnectionIdentity(message)
    }

    @Test
    fun `query errors keep the server text but lose the connection identity`() {
        val leaky = SQLException(
            "relation \"orders\" does not exist on db.internal.example as user reporting",
            "42P01",
        )

        val error = PostgresErrors.classify(leaky, redaction)

        assertIs<DbError.QueryFailed>(error)
        assertEquals("42P01", error.sqlState)
        assertNoConnectionIdentity(error.message)
        assertEquals(true, error.message.contains("relation \"orders\" does not exist"))
    }

    // --- Notices --------------------------------------------------------------

    @Test
    fun `a notice keeps everything the server put in it`() {
        val notice = PostgresErrors.notices(psqlWarning(NOTICE), redaction).single()

        assertEquals("table \"invoices\" does not exist, skipping", notice.message)
        assertEquals("NOTICE", notice.severity)
        assertEquals("00000", notice.sqlState)
        assertEquals("Nothing was dropped.", notice.detail)
        assertEquals("Use IF EXISTS deliberately.", notice.hint)
    }

    @Test
    fun `a chain of notices arrives in the order the server raised them`() {
        val chain = psqlWarning(NOTICE).apply {
            nextWarning = psqlWarning(WARNING)
            nextWarning.nextWarning = SQLWarning("a warning with no server report")
        }

        val notices = PostgresErrors.notices(chain, redaction)

        assertEquals(3, notices.size)
        assertEquals(listOf("NOTICE", "WARNING", null), notices.map { it.severity })
        assertEquals("a warning with no server report", notices.last().message)
    }

    @Test
    fun `every part of a notice goes through redaction`() {
        // A notice is server-authored text like any error, and `RAISE NOTICE '%', ...`
        // will happily interpolate whatever the function was given.
        val notice = PostgresErrors.notices(psqlWarning(LEAKY_NOTICE), redaction).single()

        listOf(notice.message, notice.detail, notice.hint).forEach {
            assertNoConnectionIdentity(it.orEmpty())
        }
    }

    @Test
    fun `a chain longer than the cap is cut, and says so`() {
        // A loop that raises a notice per iteration is ordinary PL/pgSQL, and keeping
        // every one of them is the unbounded retention ResultLimits exists to prevent.
        val head = psqlWarning(NOTICE)
        var tail: SQLWarning = head
        repeat(PostgresErrors.MAX_NOTICES + 20) {
            val next = psqlWarning(NOTICE)
            tail.nextWarning = next
            tail = next
        }

        val notices = PostgresErrors.notices(head, redaction)

        assertEquals(PostgresErrors.MAX_NOTICES + 1, notices.size)
        assertTrue(
            notices.last().message.contains("were not kept"),
            "a cut list must not look complete: ${notices.last().message}",
        )
    }

    @Test
    fun `a warning chained to itself terminates instead of hanging`() {
        // pgjdbc does not build one. This walk is over driver-owned state, and a hang
        // here would be indistinguishable from a hung query.
        val loop = SQLWarning("round and round")
        loop.nextWarning = loop

        assertEquals(1, PostgresErrors.notices(loop, redaction).size)
    }

    @Test
    fun `no warning is no notices`() {
        assertEquals(emptyList(), PostgresErrors.notices(null, redaction))
    }

    private fun assertNoConnectionIdentity(message: String) {
        listOf("db.internal.example", "6432", "payments", "reporting", "hunter2", "jdbc:").forEach {
            assertFalse(message.contains(it, ignoreCase = true), "leaked \"$it\" in: $message")
        }
    }

    /**
     * A [PSQLException] carrying a real server error report.
     *
     * Built from the wire format pgjdbc parses — field-tagged, NUL-separated — because
     * `ServerErrorMessage` has no other public constructor, and because a hand-built
     * stub would prove that the mapping copies fields rather than that it copies the
     * right ones.
     */
    private fun psqlException(fields: Map<Char, String>): PSQLException =
        PSQLException(serverMessage(fields))

    /** The same, for the warning half of PostgreSQL's identical message format. */
    private fun psqlWarning(fields: Map<Char, String>): PSQLWarning =
        PSQLWarning(serverMessage(fields))

    private fun serverMessage(fields: Map<Char, String>): ServerErrorMessage =
        ServerErrorMessage(fields.entries.joinToString("") { (tag, value) -> "$tag$value\u0000" })

    private companion object {
        /** `select ordr from invoices` — a typo, with everything the server says about it. */
        val SYNTAX_ERROR = mapOf(
            'S' to "ERROR",
            'C' to "42601",
            'M' to "column \"ordr\" does not exist",
            'D' to "This is the detail.",
            'H' to "Perhaps you meant \"total\".",
            'P' to "8",
            // The three §2.9 excludes, present in the input so their absence from the
            // output means something.
            'F' to "scan.l",
            'L' to "1176",
            'R' to "scanner_yyerror",
        )

        val CONSTRAINT_VIOLATION = mapOf(
            'S' to "ERROR",
            'C' to "23514",
            'M' to "new row for relation \"orders\" violates check constraint \"orders_total_check\"",
            's' to "sales",
            't' to "orders",
            'n' to "orders_total_check",
        )

        val INTERNAL_POSITION = mapOf(
            'S' to "ERROR",
            'C' to "42P01",
            'M' to "relation \"missing\" does not exist",
            'p' to "19",
            'q' to "SELECT count(*) FROM missing",
            'W' to "PL/pgSQL function add_invoice(numeric) line 3 at SQL statement",
        )

        /** `DROP TABLE IF EXISTS invoices` against a database that has none. */
        val NOTICE = mapOf(
            'S' to "NOTICE",
            'C' to "00000",
            'M' to "table \"invoices\" does not exist, skipping",
            'D' to "Nothing was dropped.",
            'H' to "Use IF EXISTS deliberately.",
            'F' to "tablecmds.c",
            'R' to "DropErrorMsgNonExistent",
        )

        val WARNING = mapOf(
            'S' to "WARNING",
            'C' to "01000",
            'M' to "there is no transaction in progress",
        )

        val LEAKY_NOTICE = mapOf(
            'S' to "NOTICE",
            'C' to "00000",
            'M' to "copied 12 rows from db.internal.example",
            'D' to "The user reporting owns them.",
            'H' to "Run this on jdbc:postgresql://db.internal.example:6432/payments instead.",
        )

        val LEAKY = mapOf(
            'S' to "ERROR",
            'C' to "42P01",
            'M' to "relation \"orders\" does not exist on db.internal.example",
            'D' to "The user reporting has no such relation.",
            'H' to "Connect to jdbc:postgresql://db.internal.example:6432/payments instead.",
            't' to "payments",
        )
    }

    @Test
    fun `a slow statement that failed on its own terms keeps its SQLSTATE`() {
        // `timedOut` is wall clock, measured from before the connection was even
        // acquired, so it exceeds the limit routinely while the statement itself
        // never timed out. Letting it short-circuit threw away the SQLSTATE, the
        // server's message, the hint, and the position the editor highlights — a
        // constraint violation at 29.8s of a 30s limit was reported as a timeout.
        val violation = PSQLException(
            ServerErrorMessage("SERROR\u0000C23505\u0000Mduplicate key value\u0000northers_pkey\u0000"),
        )

        val error = PostgresErrors.classify(violation, redaction, timedOut = true, limit = 30.seconds)

        val failed = assertIs<DbError.QueryFailed>(error)
        assertEquals("23505", failed.sqlState)
    }

    @Test
    fun `a timeout still needs the cancellation the server actually performed`() {
        val cancelled = PSQLException(ServerErrorMessage("SERROR\u0000C57014\u0000Mcanceling statement\u0000"))

        assertIs<DbError.Timeout>(
            PostgresErrors.classify(cancelled, redaction, timedOut = true, limit = 30.seconds),
        )
    }

    @Test
    fun `a saturated pool is a connection failure, not a failed query`() {
        // Hikari raises this with no SQLSTATE anywhere in the chain when the pool is
        // simply full of healthy connections. Falling through told the user their SQL
        // was wrong and showed them pool internals; nothing ever reached the server.
        val exhausted = SQLTransientConnectionException(
            "caracal-postgres - Connection is not available, request timed out after 5000ms.",
        )

        assertIs<DbError.ConnectionFailed>(PostgresErrors.classify(exhausted, redaction))
    }
}

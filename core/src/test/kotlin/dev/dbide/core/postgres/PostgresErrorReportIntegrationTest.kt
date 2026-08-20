package dev.dbide.core.postgres

import dev.dbide.core.connections.Secret
import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import dev.dbide.core.sql.StatementSplitter
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §2.9 against a real server, because this is a section that cannot be tested any
 * other way.
 *
 * [PostgresErrorsTest] proves the mapping copies the right fields out of a report it
 * was handed. What it cannot prove is that PostgreSQL fills those fields in, that it
 * counts `Position` the way the documentation says, or that it counts characters
 * rather than bytes when the statement contains something outside ASCII. Those are
 * claims about the server, and only the server can settle them.
 *
 * The last one is the reason this file exists. An off-by-one in the position
 * conversion is invisible in every test written in English and wrong in every
 * script that is not.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DBIDE_INTEGRATION", matches = "1")
class PostgresErrorReportIntegrationTest {

    @Test
    fun `a syntax error carries its SQLSTATE, severity, and position`(): Unit = runBlocking {
        val error = failure("SELECT * FRM invoices")

        assertEquals("42601", error.sqlState)
        assertEquals("ERROR", error.severity)
        assertNotNull(error.position, "the server reported no position for a syntax error")
    }

    @Test
    fun `an unknown column brings the hint that answers it`() = runBlocking {
        // The reason §2.9 asks for the hint at all. The message says the column does
        // not exist; the hint says which one was meant, and only one of those two is
        // an answer.
        val error = failure("SELECT totl FROM invoices")

        assertEquals("42703", error.sqlState)
        val hint = assertNotNull(error.hint, "the server offered no hint for a near-miss column")
        assertTrue(hint.contains("total"), "the hint did not name the column: $hint")
    }

    @Test
    fun `a constraint violation names the object it is about`() = runBlocking {
        val error = failure("INSERT INTO invoices (total) VALUES (-1)")

        val subject = assertNotNull(error.subject, "a check violation named no object")
        assertEquals("invoices", subject.table)
        assertEquals("invoices_total_check", subject.constraint)
    }

    @Test
    fun `the position lands on the offending character`() = runBlocking {
        val sql = "SELECT totl FROM invoices"
        val error = failure(sql)

        val statement = StatementSplitter.split(sql).statements.single()
        val index = assertNotNull(statement.documentIndex(assertNotNull(error.position)))

        // 1-based into the statement, so the mapped index is where `totl` starts.
        assertEquals(sql.indexOf("totl"), index)
    }

    @Test
    fun `the position still lands correctly after non-ASCII text`() = runBlocking {
        // The case the conversion exists for. PostgreSQL counts characters and Kotlin
        // counts UTF-16 units, and the two agree exactly until something outside the
        // BMP appears — one emoji before the error and every highlight after it is off
        // by one, drifting further with each.
        val sql = "SELECT '🧾 receipt' AS note, totl FROM invoices"
        val error = failure(sql)

        val statement = StatementSplitter.split(sql).statements.single()
        val index = assertNotNull(statement.documentIndex(assertNotNull(error.position)))

        assertEquals(sql.indexOf("totl"), index)
    }

    @Test
    fun `an error inside a function body reports an internal position and no real one`(): Unit = runBlocking {
        // There is nothing in the user's document to point at: the position counts
        // into a query the server generated from a function body. §2.9's rule is to
        // show the message and not guess, and this is the shape that makes that
        // possible — a null position, and the internal one reported as itself.
        val error = failure("SELECT broken()")

        assertNull(error.position, "a position inside a generated query was reported as a real one")
        assertNotNull(error.internalPosition)
    }

    @Test
    fun `a statement that outlives its timeout says so, and says it differently`() = runBlocking {
        val session = PostgresSession(config(), statementTimeout = 1.seconds)
        session.use {
            val error = assertThrows<DbException> {
                runBlocking { it.adapter.execute("SELECT pg_sleep(30)") }
            }.error

            // Not `Cancelled`, though the server reported the same SQLSTATE for both.
            val timeout = assertIs<DbError.Timeout>(error)
            assertEquals(1.seconds, timeout.limit)
            assertTrue(timeout.message.contains("1s"), "the limit is missing from: ${timeout.message}")
        }
    }

    @Test
    fun `a timed-out statement returns its connection to the pool`() = runBlocking {
        // A second, not less: JDBC's query timeout has one-second granularity, and a
        // sub-second limit is rounded up to it rather than truncated to "no limit".
        val session = PostgresSession(config(), statementTimeout = 800.milliseconds)
        session.use {
            assertThrows<DbException> { runBlocking { it.adapter.execute("SELECT pg_sleep(30)") } }

            assertEquals(0, it.activeConnections)
            assertEquals(1, it.adapter.execute("SELECT 1").rows.size)
        }
    }

    @Test
    fun `no server error carries the connection identity`() = runBlocking {
        val error = failure("SELECT * FROM nowhere")

        val everything = listOfNotNull(
            error.message,
            error.detail,
            error.hint,
            error.subject?.describe(),
        ).joinToString(" ")
        listOf(postgres.host, postgres.username, postgres.password, "jdbc:").forEach {
            assertTrue(!everything.contains(it), "leaked \"$it\" in: $everything")
        }
    }

    // --- Fixtures -------------------------------------------------------------

    /** Runs [sql], expecting it to fail, and returns the server's report. */
    private suspend fun failure(sql: String): DbError.QueryFailed = session().use { session ->
        val error = assertThrows<DbException> { runBlocking { session.adapter.execute(sql) } }.error
        assertIs<DbError.QueryFailed>(error)
    }

    private fun session() = PostgresSession(config())

    private fun config() = PostgresConnectionConfig(
        host = postgres.host,
        port = postgres.firstMappedPort,
        database = postgres.databaseName,
        user = postgres.username,
        password = Secret(postgres.password),
        // The constraint violation needs the server to get far enough to check the
        // constraint, which a read-only transaction refuses before it does.
        readOnly = false,
    )

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        @JvmStatic
        @BeforeAll
        fun seed() {
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE invoices (total numeric CONSTRAINT invoices_total_check CHECK (total >= 0))",
                    )
                    statement.execute(
                        """
                        CREATE FUNCTION broken() RETURNS int AS ${'$'}${'$'}
                        BEGIN
                          RETURN (SELECT count(*) FROM does_not_exist);
                        END;
                        ${'$'}${'$'} LANGUAGE plpgsql
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

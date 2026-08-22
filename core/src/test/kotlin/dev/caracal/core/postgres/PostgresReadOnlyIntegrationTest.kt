package dev.caracal.core.postgres

import dev.caracal.core.connections.Secret
import dev.caracal.core.result.CellValue
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.engine.ServerImage
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Whether v0.1's read-only rule actually holds, asked of a real server.
 *
 * The rule is the source plan's (§0, and the risk table: a data-loss bug destroys
 * trust permanently), and M0 put it in the pool rather than the UI. What these
 * tests are really checking is that it does not depend on recognizing a write:
 * the cases no keyword scan could catch — a write inside a function, a write
 * inside a CTE — are refused by the same mechanism as an obvious `INSERT`, and
 * all of them arrive as [DbError.ReadOnlyViolation] rather than as raw server
 * text.
 *
 * Seeding therefore cannot go through the adapter. It uses a direct connection,
 * which is also the point: the guarantee belongs to this application's pool, not
 * to the database user, so a read-only role remains the recommendation for
 * production browsing.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class PostgresReadOnlyIntegrationTest {

    @Test
    fun `an obvious write is refused`() = runBlocking {
        session().use { session ->
            val error = assertThrows<DbException> {
                runBlocking { session.adapter.execute("INSERT INTO invoices (total) VALUES (1)") }
            }.error

            assertIs<DbError.ReadOnlyViolation>(error)
            assertInvoiceCount(2)
        }
    }

    @Test
    fun `a write hidden in a function is refused`() = runBlocking {
        // No keyword scan can see this one: the statement is a SELECT, and the INSERT
        // is inside a function body the server compiled long before. This is why
        // enforcement is the transaction's job and not the classifier's.
        session().use { session ->
            val error = assertThrows<DbException> {
                runBlocking { session.adapter.execute("SELECT add_invoice(99)") }
            }.error

            assertIs<DbError.ReadOnlyViolation>(error)
            assertInvoiceCount(2)
        }
    }

    @Test
    fun `a write hidden in a CTE is refused`() = runBlocking {
        session().use { session ->
            val error = assertThrows<DbException> {
                runBlocking {
                    session.adapter.execute(
                        "WITH gone AS (DELETE FROM invoices RETURNING *) SELECT count(*) FROM gone",
                    )
                }
            }.error

            assertIs<DbError.ReadOnlyViolation>(error)
            assertInvoiceCount(2)
        }
    }

    @Test
    fun `DDL is refused`(): Unit = runBlocking {
        session().use { session ->
            val error = assertThrows<DbException> {
                runBlocking { session.adapter.execute("CREATE TABLE sneaky (a int)") }
            }.error

            assertIs<DbError.ReadOnlyViolation>(error)
        }
    }

    @Test
    fun `reads still work`() = runBlocking {
        session().use { session ->
            val result = session.adapter.execute("SELECT total FROM invoices ORDER BY total")

            assertEquals(listOf("total"), result.columns.map { it.name })
            assertEquals(2, result.rows.size)
            assertNull(result.rowsAffected, "a row-returning statement has no affected count")
        }
    }

    @Test
    fun `a statement that returns no result set is not an error`() = runBlocking {
        // `executeQuery` throws "No results were returned by the query" for these, so
        // reading the result of a statement has to ask which kind came back first.
        session().use { session ->
            val result = session.adapter.execute("SET search_path TO public")

            assertTrue(result.columns.isEmpty())
            assertTrue(result.rows.isEmpty())
        }
    }

    @Test
    fun `a session setting does not survive the statement that set it`() = runBlocking {
        // Each statement gets its own transaction and that transaction is rolled
        // back, so a SET succeeds and is gone. Worth asserting rather than assuming:
        // it is why the editor warns about session statements instead of running
        // them hopefully.
        session().use { session ->
            session.adapter.execute("SET search_path TO pg_catalog")

            val after = session.adapter.execute("SHOW search_path").rows.single().single()

            // Asserting what it is not, rather than the exact default, which differs
            // between server versions and is not the point.
            assertNotEquals(
                CellValue.Text("pg_catalog"),
                after,
                "the search_path set by the previous statement outlived its transaction",
            )
        }
    }

    @Test
    fun `a refused write returns its connection to the pool`() = runBlocking {
        session().use { session ->
            assertThrows<DbException> { runBlocking { session.adapter.execute("DELETE FROM invoices") } }

            assertEquals(0, session.activeConnections)
            assertEquals(2, session.adapter.execute("SELECT * FROM invoices").rows.size)
        }
    }

    private suspend fun assertInvoiceCount(expected: Long) {
        session().use { session ->
            val result = session.adapter.execute("SELECT count(*) FROM invoices")

            assertEquals(expected, (result.rows.single().single() as CellValue.Integer).value)
        }
    }

    private fun session() = PostgresSession(
        PostgresConnectionConfig(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
            user = postgres.username,
            password = Secret(postgres.password),
        ),
    )

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(ServerImage.postgres).also { it.start() }

        @JvmStatic
        @BeforeAll
        fun seed() {
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE invoices (total numeric)")
                    statement.execute("INSERT INTO invoices (total) VALUES (10), (20)")
                    statement.execute(
                        """
                        CREATE FUNCTION add_invoice(amount numeric) RETURNS numeric AS ${'$'}${'$'}
                        BEGIN
                          INSERT INTO invoices (total) VALUES (amount);
                          RETURN amount;
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

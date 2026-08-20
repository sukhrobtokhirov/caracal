package dev.dbide.core.postgres

import dev.dbide.core.connections.Secret
import dev.dbide.core.result.CellValue
import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The other half of §2.4, asked of a real server: that a connection which is *not*
 * marked read only can actually write, and that what it writes is still there
 * afterwards.
 *
 * [PostgresReadOnlyIntegrationTest] proves the refusal. This proves the permission,
 * and the two together are what make the `Read only` checkbox mean something — a
 * flag that refused everything would be safe and useless, and a flag that permitted
 * everything would be neither.
 *
 * The assertion that matters most here is the dullest one: that a committed row is
 * visible from a *different* connection. Every statement runs in its own
 * transaction, and a transaction that is rolled back after reporting "1 row
 * affected" would pass any test that only looked at the count.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DBIDE_INTEGRATION", matches = "1")
class PostgresWriteIntegrationTest {

    @BeforeEach
    fun reset() {
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE ledger")
                statement.execute("INSERT INTO ledger (note, amount) VALUES ('opening', 100)")
            }
        }
    }

    @Test
    fun `an insert on a writable connection is committed, not rolled back`() = runBlocking {
        writable().use { session ->
            val result = session.adapter.execute("INSERT INTO ledger (note, amount) VALUES ('rent', 42)")

            // A command-only result: no columns, a count instead. §2.5's shape.
            assertTrue(result.columns.isEmpty())
            assertTrue(result.rows.isEmpty())
            assertEquals(1L, result.rowsAffected)
        }

        // Asked of a connection that has never seen the transaction that wrote it.
        assertEquals(2L, ledgerCount())
    }

    @Test
    fun `an update on a writable connection survives its transaction`() = runBlocking {
        writable().use { session ->
            val result = session.adapter.execute("UPDATE ledger SET amount = 7 WHERE note = 'opening'")

            assertEquals(1L, result.rowsAffected)
        }

        assertEquals("7", amountOf("opening"))
    }

    @Test
    fun `DDL on a writable connection persists`(): Unit = runBlocking {
        writable().use { session ->
            session.adapter.execute("CREATE TABLE scratch (a int)")
            session.adapter.execute("DROP TABLE scratch")
        }
        // Both statements committing is what makes this pass: had the CREATE been
        // rolled back, the DROP would have failed.
    }

    @Test
    fun `a failed write leaves nothing behind and returns its connection to the pool`() = runBlocking {
        writable().use { session ->
            assertThrows<DbException> {
                runBlocking { session.adapter.execute("INSERT INTO ledger (note, amount) VALUES ('bad', 'x')") }
            }

            // An aborted transaction has to be ended, or PostgreSQL refuses every
            // subsequent statement on that connection.
            assertEquals(0, session.activeConnections)
            assertEquals(1L, session.adapter.count("SELECT count(*) FROM ledger"))
        }
        assertEquals(1L, ledgerCount())
    }

    @Test
    fun `a write is still refused when the same server is opened read only`() = runBlocking {
        // The same database and the same user. The only difference is the flag, which
        // is the point: this is the application's guarantee, not the role's.
        readOnly().use { session ->
            val error = assertThrows<DbException> {
                runBlocking { session.adapter.execute("INSERT INTO ledger (note, amount) VALUES ('no', 1)") }
            }.error

            assertIs<DbError.ReadOnlyViolation>(error)
        }

        assertEquals(1L, ledgerCount())
    }

    @Test
    fun `a session setting persists on a writable connection and not on a read-only one`() = runBlocking {
        // The cost of committing, stated rather than discovered. A `SET` is
        // transactional: rolling back undoes it, committing does not. This is why
        // `StatementClassifier` keeps session statements in a case of their own
        // instead of calling them reads.
        readOnly().use { session ->
            session.adapter.execute("SET search_path TO pg_catalog")

            assertNotEquals(
                CellValue.Text("pg_catalog"),
                session.adapter.execute("SHOW search_path").rows.single().single(),
            )
        }

        // A pool of exactly one, so the second statement is guaranteed the same
        // physical connection the first one set. With the default pool it would be
        // the same connection nearly always, and "nearly always" is not an assertion.
        PostgresDataSources.create(config(readOnly = false), poolSize = 1).use { pool ->
            val adapter = PostgresAdapter(pool, readOnly = false)
            adapter.execute("SET search_path TO pg_catalog")

            assertEquals(
                CellValue.Text("pg_catalog"),
                adapter.execute("SHOW search_path").rows.single().single(),
            )
        }
    }

    @Test
    fun `reads still work on a writable connection`() = runBlocking {
        writable().use { session ->
            val result = session.adapter.execute("SELECT note FROM ledger")

            assertEquals(listOf("note"), result.columns.map { it.name })
            assertNull(result.rowsAffected, "a row-returning statement has no affected count")
        }
    }

    // --- Fixtures -------------------------------------------------------------

    private suspend fun PostgresAdapter.count(sql: String): Long =
        (execute(sql).rows.single().single() as CellValue.Integer).value

    private fun ledgerCount(): Long = postgres.createConnection("").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM ledger").use {
                it.next()
                it.getLong(1)
            }
        }
    }

    private fun amountOf(note: String): String = postgres.createConnection("").use { connection ->
        connection.prepareStatement("SELECT amount::text FROM ledger WHERE note = ?").use { statement ->
            statement.setString(1, note)
            statement.executeQuery().use {
                it.next()
                it.getString(1)
            }
        }
    }

    private fun writable() = PostgresSession(config(readOnly = false))

    private fun readOnly() = PostgresSession(config(readOnly = true))

    private fun config(readOnly: Boolean) = PostgresConnectionConfig(
        host = postgres.host,
        port = postgres.firstMappedPort,
        database = postgres.databaseName,
        user = postgres.username,
        password = Secret(postgres.password),
        readOnly = readOnly,
    )

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        @JvmStatic
        @BeforeAll
        fun seed() {
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE ledger (note text, amount numeric)")
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}

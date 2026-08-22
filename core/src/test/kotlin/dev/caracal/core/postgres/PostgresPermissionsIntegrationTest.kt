package dev.caracal.core.postgres

import dev.caracal.core.connections.Secret
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.engine.api.ObjectKind
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §2.5's permission-limited user, and §2.4's closing sentence: the database user's
 * grants are the protection underneath everything this application does.
 *
 * The read-only pool and the statement classifier are both this side of the wire and
 * both can be wrong. A role with `SELECT` on one table and nothing on the next is the
 * boundary that holds when they are — which makes it worth proving that the boundary
 * is really there, and that hitting it produces a sentence a person can act on rather
 * than something that reads like the connection died.
 *
 * The role is the one the plan recommends for production browsing, so this doubles as
 * a test that the recommended setup is usable: the browser still works, the grants
 * still allow the reads they were granted for, and only the denied statement fails.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class PostgresPermissionsIntegrationTest {

    @Test
    fun `a table the role was granted reads normally`() = runBlocking {
        // First, so that a failure below means "denied" and not "this role cannot do
        // anything at all".
        reader().use { session ->
            assertEquals(2, session.adapter.execute("SELECT * FROM published").rows.size)
        }
    }

    @Test
    fun `a table the role was not granted is refused by the server`() = runBlocking {
        reader().use { session ->
            val error = assertThrows<DbException> {
                runBlocking { session.adapter.execute("SELECT * FROM restricted") }
            }.error

            val failure = assertIs<DbError.QueryFailed>(error)
            assertEquals("42501", failure.sqlState)
            assertEquals("ERROR", failure.severity)
            assertTrue(
                failure.message.contains("restricted"),
                "the message must name what was denied: ${failure.message}",
            )
        }
    }

    @Test
    fun `a denied read is a query failure and not a broken connection`() = runBlocking {
        // The distinction the user acts on. "Permission denied for table X" means ask
        // for a grant; "the connection failed" means check the network — and an
        // application that reports the second when it meant the first sends people to
        // debug the wrong thing. It is also why the session must stay usable.
        reader().use { session ->
            assertThrows<DbException> {
                runBlocking { session.adapter.execute("SELECT * FROM restricted") }
            }

            assertEquals(0, session.activeConnections)
            assertEquals(2, session.adapter.execute("SELECT * FROM published").rows.size)
        }
    }

    @Test
    fun `a denied read names the relation and not the connection`() = runBlocking {
        reader().use { session ->
            val error = assertThrows<DbException> {
                runBlocking { session.adapter.execute("SELECT * FROM restricted") }
            }.error

            val everything = assertIs<DbError.QueryFailed>(error).let {
                listOfNotNull(it.message, it.detail, it.hint, it.subject?.describe()).joinToString(" ")
            }
            listOf(postgres.host, postgres.databaseName, READER, READER_PASSWORD, "jdbc:").forEach {
                assertTrue(!everything.contains(it), "leaked \"$it\" in: $everything")
            }
        }
    }

    @Test
    fun `a write is refused whether or not the application asked first`() = runBlocking {
        // Two boundaries, one statement. The pool's READ ONLY transaction refuses this
        // before the server checks the grant, so the SQLSTATE is 25006 rather than
        // 42501 — and the point of the test is that the role has no INSERT either, so
        // the statement has nowhere to succeed even if this application were wrong
        // about everything.
        reader().use { session ->
            val error = assertThrows<DbException> {
                runBlocking { session.adapter.execute("INSERT INTO published VALUES (3, 'new')") }
            }.error

            assertIs<DbError.ReadOnlyViolation>(error)
        }

        writableReader().use { session ->
            val error = assertThrows<DbException> {
                runBlocking { session.adapter.execute("INSERT INTO published VALUES (3, 'new')") }
            }.error

            assertEquals("42501", assertIs<DbError.QueryFailed>(error).sqlState)
        }
    }

    @Test
    fun `the object browser still describes tables the role may not read`() = runBlocking {
        // `pg_catalog` is readable by any role that can connect, so the tree does not
        // go blank for a limited user — it shows the database as it is and the refusal
        // arrives when a query runs, which is where §2.8 wants it: on the statement,
        // not in place of the browser.
        reader().use { session ->
            val tables = session.catalog.objects("public", ObjectKind.TABLE).items.map { it.name }

            assertTrue("published" in tables, "the granted table is missing: $tables")
            assertTrue("restricted" in tables, "the denied table is missing: $tables")
            assertEquals(
                listOf("id", "secret"),
                session.catalog.columns("public", "restricted").map { it.name },
            )
        }
    }

    @Test
    fun `a schema the role has no USAGE on is listed as unusable`() = runBlocking {
        // §4.7's distinction, and the reason it needs the server to answer it: both
        // schemas list from `pg_catalog` identically, and only `has_schema_privilege`
        // separates "nothing in it" from "nothing you may look at".
        reader().use { session ->
            val schemas = session.catalog.schemas().items.associateBy { it.name }

            assertEquals(true, schemas.getValue("public").usable)
            assertEquals(
                false,
                schemas.getValue(WALLED).usable,
                "a schema this role was never granted USAGE on reported as usable",
            )
        }
    }

    // --- Fixtures -------------------------------------------------------------

    private fun reader() = PostgresSession(readerConfig())

    /** The same role with the pool's own read-only transaction switched off. */
    private fun writableReader() = PostgresSession(readerConfig().copy(readOnly = false))

    private fun readerConfig() = PostgresConnectionConfig(
        host = postgres.host,
        port = postgres.firstMappedPort,
        database = postgres.databaseName,
        user = READER,
        password = Secret(READER_PASSWORD),
    )

    companion object {
        private const val READER = "limited_reader"

        /** A schema the reader is never granted `USAGE` on. It has a table, so it is not empty. */
        private const val WALLED = "walled"
        private const val READER_PASSWORD = "reader-secret"

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        init {
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE published (id int, title text)")
                    statement.execute("INSERT INTO published VALUES (1, 'one'), (2, 'two')")
                    statement.execute("CREATE TABLE restricted (id int, secret text)")
                    statement.execute("INSERT INTO restricted VALUES (1, 'do not read')")

                    statement.execute("CREATE ROLE $READER LOGIN PASSWORD '$READER_PASSWORD'")
                    statement.execute("GRANT USAGE ON SCHEMA public TO $READER")
                    // One table, one privilege. Everything else is denied by omission,
                    // which is how a real read-only role is built.
                    statement.execute("GRANT SELECT ON published TO $READER")

                    // Deliberately never granted to the reader: it is what
                    // `has_schema_privilege` has to notice.
                    statement.execute("CREATE SCHEMA $WALLED")
                    statement.execute("CREATE TABLE $WALLED.hidden (id int)")
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}

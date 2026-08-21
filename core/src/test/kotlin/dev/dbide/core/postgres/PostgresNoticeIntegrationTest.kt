package dev.dbide.core.postgres

import dev.dbide.core.connections.Secret
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §2.9's other half: what the server says when nothing went wrong.
 *
 * A notice is PostgreSQL volunteering something on a statement that succeeded, and
 * it is the only output some statements have — a `DO` block written to report what
 * it found returns no rows, no count, and nothing at all if notices are dropped. An
 * application that drops them shows a blank grid and calls it success.
 *
 * Against a real server because every claim here is about the server and the driver
 * between it and us: which JDBC chain pgjdbc hangs a notice on, whether a notice
 * survives its statement, and whether `RAISE ... USING` fields arrive as the fields
 * they were raised as.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DBIDE_INTEGRATION", matches = "1")
class PostgresNoticeIntegrationTest {

    @Test
    fun `a statement whose only output is a notice still has output`() = runBlocking {
        session().use { session ->
            val result = session.adapter.execute(raise("NOTICE", "checked 3 tables"))

            assertTrue(result.columns.isEmpty())
            assertTrue(result.rows.isEmpty())
            assertEquals("checked 3 tables", result.notices.single().message)
        }
    }

    @Test
    fun `a notice raised alongside rows arrives with them`() = runBlocking {
        session().use { session ->
            val result = session.adapter.execute("SELECT * FROM noisy()")

            assertEquals(2, result.rows.size)
            assertEquals("about to return 2 rows", result.notices.single().message)
        }
    }

    @Test
    fun `severity, SQLSTATE, detail, and hint all survive the trip`() = runBlocking {
        session().use { session ->
            val notice = session.adapter.execute(
                raise("NOTICE", "nothing was dropped", detail = "The table was not there.", hint = "Check the schema."),
            ).notices.single()

            assertEquals("NOTICE", notice.severity)
            assertEquals("00000", notice.sqlState)
            assertEquals("The table was not there.", notice.detail)
            assertEquals("Check the schema.", notice.hint)
        }
    }

    @Test
    fun `a warning is kept as a warning and not flattened into a notice`() = runBlocking {
        // The two differ only by severity on the wire, and the difference is the
        // whole reason to show one: WARNING is the server saying this went through
        // and you probably did not mean it.
        session().use { session ->
            val notice = session.adapter.execute(raise("WARNING", "this column is deprecated"))
                .notices.single()

            assertEquals("WARNING", notice.severity)
            assertEquals("01000", notice.sqlState)
        }
    }

    @Test
    fun `notices arrive in the order the server raised them`() = runBlocking {
        session().use { session ->
            val messages = session.adapter.execute(
                "DO $TAG BEGIN " +
                    "RAISE NOTICE 'first'; RAISE WARNING 'second'; RAISE NOTICE 'third'; " +
                    "END $TAG",
            ).notices.map { it.message }

            assertEquals(listOf("first", "second", "third"), messages)
        }
    }

    @Test
    fun `a notice does not follow its pooled connection onto the next statement`() = runBlocking {
        // The failure this rules out is the quiet one: a pool of a few connections
        // hands the same one back within a session, and a notice left on it would be
        // reported against whatever ran next — attributing one statement's news to
        // another, with no way for the user to tell.
        session().use { session ->
            repeat(4) { session.adapter.execute(raise("NOTICE", "leftover")) }

            repeat(4) {
                assertEquals(emptyList(), session.adapter.execute("SELECT 1").notices)
            }
        }
    }

    @Test
    fun `more notices than the cap are cut, and the result says so`() = runBlocking {
        // A loop raising one per iteration is ordinary PL/pgSQL. Keeping all of them
        // would be the unbounded retention ResultLimits exists to prevent, one field
        // over.
        session().use { session ->
            val overflow = PostgresErrors.MAX_NOTICES + 50
            val notices = session.adapter.execute(
                "DO $TAG BEGIN FOR i IN 1..$overflow LOOP RAISE NOTICE 'notice %', i; END LOOP; END $TAG",
            ).notices

            assertEquals(PostgresErrors.MAX_NOTICES + 1, notices.size)
            assertTrue(
                notices.last().message.contains("were not kept"),
                "a cut list must not look complete: ${notices.last().message}",
            )
        }
    }

    @Test
    fun `a notice that quotes the connection is redacted like any other server text`() = runBlocking {
        // `RAISE NOTICE '%', ...` interpolates whatever the function was given, and a
        // notice is no less server-authored than an error.
        session().use { session ->
            val quoted = "connected to ${postgres.databaseName} as ${postgres.username}"
            val notice = session.adapter.execute(raise("NOTICE", quoted)).notices.single()

            listOf(postgres.databaseName, postgres.username, postgres.password).forEach {
                assertTrue(!notice.message.contains(it), "leaked \"$it\" in: ${notice.message}")
            }
            assertTrue(notice.message.startsWith("connected to"), "redaction ate the message")
        }
    }

    @Test
    fun `a statement that says nothing carries no notices`() = runBlocking {
        session().use { session ->
            assertEquals(emptyList(), session.adapter.selectOne().notices)
        }
    }

    // --- Fixtures -------------------------------------------------------------

    /** A `DO` block that raises one message, with whichever `USING` fields are given. */
    private fun raise(
        severity: String,
        message: String,
        detail: String? = null,
        hint: String? = null,
    ): String {
        val using = listOfNotNull(
            detail?.let { "DETAIL = '$it'" },
            hint?.let { "HINT = '$it'" },
        ).joinToString(", ")
        val clause = if (using.isEmpty()) "" else " USING $using"
        return "DO $TAG BEGIN RAISE $severity '%', '$message'$clause; END $TAG"
    }

    private fun session() = PostgresSession(config())

    private fun config() = PostgresConnectionConfig(
        host = postgres.host,
        port = postgres.firstMappedPort,
        database = postgres.databaseName,
        user = postgres.username,
        password = Secret(postgres.password),
    )

    companion object {
        /** `$$`, which a Kotlin string literal will not say plainly. */
        private const val TAG = "\$\$"

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        init {
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    // A function so that the notice is raised while rows are being
                    // produced, rather than by a statement that produces none.
                    statement.execute(
                        "CREATE FUNCTION noisy() RETURNS SETOF int AS $TAG " +
                            "BEGIN RAISE NOTICE '%', 'about to return 2 rows'; " +
                            "RETURN QUERY SELECT * FROM (VALUES (1), (2)) AS v(n); END; " +
                            "$TAG LANGUAGE plpgsql",
                    )
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}

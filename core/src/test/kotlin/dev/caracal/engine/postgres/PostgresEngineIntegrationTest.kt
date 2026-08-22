package dev.caracal.engine.postgres

import dev.caracal.core.connections.Secret
import dev.caracal.core.postgres.PostgresConnectionConfig
import dev.caracal.core.postgres.PostgresSession
import dev.caracal.core.result.CellValue as CoreCellValue
import dev.caracal.engine.ServerImage
import dev.caracal.engine.api.CancelResult
import dev.caracal.engine.api.CatalogFacet
import dev.caracal.engine.api.CellValue
import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionId
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.DatabaseSession
import dev.caracal.engine.api.ObjectKind
import dev.caracal.engine.api.QueryFacet
import dev.caracal.engine.api.Row
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.api.SessionPolicy
import dev.caracal.engine.api.SessionState
import dev.caracal.engine.api.StatementOutcome
import dev.caracal.engine.api.StatementRequest
import dev.caracal.engine.api.facet
import dev.caracal.engine.api.requireFacet
import java.math.BigInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * PostgreSQL reached only through the SPI, against a real server.
 *
 * The unit tests above check each crossing in isolation, which is what makes them
 * fast and what makes them insufficient: they all agree with each other about a
 * `ResultSet` none of them has seen. This runs the whole path — descriptor, connect,
 * facet, statement, outcome — and asserts that what comes out the far end is what
 * the concrete session produces today, because Phase 1 is a refactor and anything
 * else is a regression.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class PostgresEngineIntegrationTest {

    @Test
    fun `an engine connects, pings, and names the server`() = runBlocking {
        connect().use { session ->
            assertEquals(PostgresEngine.ID, session.engineId)
            assertEquals(SessionState.Ready, session.state.value)
            assertTrue(session.ping() > Duration.ZERO)

            val version = session.serverVersion
            assertNotNull(version.raw, "the server did not name itself")
            assertEquals(ServerImage.postgresMajor, version.major)
        }
    }

    @Test
    fun `closing a session says so, and releases the pool`() = runBlocking {
        val session = connect()
        session.close()

        assertEquals(SessionState.Closed, session.state.value)
    }

    @Test
    fun `a select arrives as rows, described before the first one`() = runBlocking {
        connect().use { session ->
            val outcomes = run(session, "SELECT 1 AS one, 'two' AS two")

            val rows = assertIs<StatementOutcome.Rows>(outcomes.single())
            assertEquals(listOf("one", "two"), rows.descriptor.columns.map { it.name })
            assertEquals(listOf("int4", "text"), rows.descriptor.columns.map { it.typeName })

            val cells = rows.rows.toList().single().cells
            assertEquals(CellValue.Integer(BigInteger.ONE), cells[0])
            assertEquals(CellValue.Text("two"), cells[1])
        }
    }

    @Test
    fun `an int8 at the top of its range crosses the SPI intact`() = runBlocking {
        connect().use { session ->
            val outcomes = run(session, "SELECT 9223372036854775807::int8 AS big")

            val rows = assertIs<StatementOutcome.Rows>(outcomes.single())
            assertEquals(
                CellValue.Integer(BigInteger("9223372036854775807")),
                rows.rows.toList().single().cells.single(),
            )
        }
    }

    @Test
    fun `a numeric keeps every digit and its scale`() = runBlocking {
        connect().use { session ->
            val outcomes = run(session, "SELECT 12345678901234567890.1234567890::numeric AS exact")

            val rows = assertIs<StatementOutcome.Rows>(outcomes.single())
            val value = assertIs<CellValue.Decimal>(rows.rows.toList().single().cells.single())
            assertEquals("12345678901234567890.1234567890", value.value.toPlainString())
        }
    }

    @Test
    fun `NaN in a numeric column is not turned into a number`() = runBlocking {
        connect().use { session ->
            val outcomes = run(session, "SELECT 'NaN'::numeric AS nope")

            val rows = assertIs<StatementOutcome.Rows>(outcomes.single())
            assertEquals(CellValue.Text("NaN"), rows.rows.toList().single().cells.single())
        }
    }

    @Test
    fun `a server error lands on the exact character of the second statement`() = runBlocking {
        connect().use { session ->
            // What the editor does: the buffer holds two statements, the second one
            // fails, and the underline has to be under `nope` and not under `SELECT`.
            val buffer = "SELECT 1;\nSELECT nope FROM pg_class;"
            val statement = buffer.substring(10)
            val outcomes = run(
                session,
                StatementRequest(sql = statement, sourceOffset = 10),
            )

            val failed = assertIs<StatementOutcome.Failed>(outcomes.single())
            assertEquals("42703", failed.error.code)
            val position = assertNotNull(failed.error.position, "no position on a column error")
            assertEquals(buffer.indexOf("nope"), position.offset)
        }
    }

    @Test
    fun `a statement with no result set reports a count rather than nothing`() = runBlocking {
        connect(readOnly = false).use { session ->
            run(session, "CREATE TEMP TABLE spi_counts (id int)")
            val outcomes = run(session, "INSERT INTO spi_counts VALUES (1), (2), (3)")

            assertEquals(StatementOutcome.UpdateCount(3), outcomes.single())
        }
    }

    @Test
    fun `a notice is surfaced rather than swallowed`() = runBlocking {
        connect(readOnly = false).use { session ->
            val outcomes = run(
                session,
                "DO $$ BEGIN RAISE NOTICE 'the whole output of this statement'; END $$",
            )

            val notice = outcomes.filterIsInstance<StatementOutcome.Notice>().single()
            assertEquals("the whole output of this statement", notice.text)
        }
    }

    @Test
    fun `a read-only session is refused a write by the server, not by a keyword`() = runBlocking {
        connect(readOnly = true).use { session ->
            val outcomes = run(session, "CREATE TABLE spi_should_not_exist (id int)")

            val failed = assertIs<StatementOutcome.Failed>(outcomes.single())
            // The statement reached the server and the server refused it with 25006 —
            // which is the point, because a keyword classifier can be walked past by a
            // function body compiled last year.
            //
            // The code is `read_only_violation` and not `25006`, and that is worth
            // stating rather than asserting around. `EngineError.code` carries the
            // SQLSTATE only for a failure that stayed a raw server error; a failure
            // `PostgresErrors` recognized and named carries the name, because the name
            // is what the UI branches on and the SQLSTATE it came from is not kept.
            // Section 6.2 of the spec describes `code` as the SQLSTATE alone; the
            // repository wins for now, and Phase 2 is where the two namespaces should
            // stop sharing one field.
            assertEquals("read_only_violation", failed.error.code)
            assertNull(failed.error.position)
        }
    }

    @Test
    fun `cancelling a running statement is acknowledged by the server`() = runBlocking {
        connect().use { session ->
            val execution = session.requireFacet<QueryFacet>()
                .execute(StatementRequest(sql = SLEEP, sourceOffset = 0))

            val collected = mutableListOf<StatementOutcome>()
            val collector = launch(Dispatchers.IO) {
                execution.outcomes.collect { collected += it }
            }
            awaitBackend()

            val result = execution.cancel()
            withTimeout(10.seconds) { collector.join() }

            // Not ClientAbandoned: pgjdbc opens a side channel and the server fails
            // the statement, which arrives back through the same flow.
            assertEquals(CancelResult.ServerAcknowledged, result)
        }
    }

    @Test
    fun `cancelling a statement that is not running says so rather than lying`() = runBlocking {
        connect().use { session ->
            val execution = session.requireFacet<QueryFacet>()
                .execute(StatementRequest(sql = "SELECT 1", sourceOffset = 0))

            assertTrue(execution.cancel() is CancelResult.Unsupported)
        }
    }

    @Test
    fun `the catalog facet lists schemas and the objects in them`() = runBlocking {
        connect().use { session ->
            val catalog = session.requireFacet<CatalogFacet>()

            val schemas = catalog.schemas()
            assertTrue(schemas.items.any { it.name == "public" }, "public was not listed")

            val objects = catalog.objects("pg_catalog", ObjectKind.TABLE)
            assertTrue(objects.items.isNotEmpty(), "pg_catalog listed no tables")
        }
    }

    @Test
    fun `a facet this engine does not provide is absent rather than thrown`() = runBlocking {
        connect().use { session ->
            assertNotNull(session.facet<QueryFacet>())
            assertNotNull(session.facet<CatalogFacet>())
            assertNull(session.facet<Unrelated>())
        }
    }

    private interface Unrelated

    private suspend fun run(session: DatabaseSession, sql: String): List<StatementOutcome> =
        run(session, StatementRequest(sql = sql, sourceOffset = 0))

    private suspend fun run(session: DatabaseSession, request: StatementRequest): List<StatementOutcome> =
        session.requireFacet<QueryFacet>().execute(request).outcomes.toList()

    /** Waits until the sleeping statement is actually on the server. */
    private suspend fun awaitBackend() = withTimeout(10.seconds) {
        PostgresSession(config()).use { observer ->
            while (sleepingBackends(observer) == 0L) delay(50)
        }
    }

    private suspend fun sleepingBackends(observer: PostgresSession): Long {
        val result = observer.adapter.execute(
            "SELECT count(*) AS running FROM pg_stat_activity WHERE query LIKE 'SELECT pg_sleep%'",
        )
        return (result.rows.single().single() as CoreCellValue.Integer).value
    }

    private suspend fun connect(readOnly: Boolean = true): DatabaseSession = PostgresEngine().connect(
        descriptor = descriptor(),
        secrets = SecretBundle.UserPassword(postgres.username, postgres.password.toCharArray()),
        policy = SessionPolicy(readOnly = readOnly, statementTimeout = 30.seconds),
    )

    private fun descriptor() = ConnectionDescriptor(
        id = ConnectionId("spi-fixture"),
        engineId = PostgresEngine.ID,
        displayName = "SPI fixture",
        target = ConnectionTarget.Network(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
        ),
        engineOptions = mapOf(PostgresEngine.OPTION_USER to postgres.username),
    )

    private fun config() = PostgresConnectionConfig(
        host = postgres.host,
        port = postgres.firstMappedPort,
        database = postgres.databaseName,
        user = postgres.username,
        password = Secret(postgres.password),
    )

    companion object {
        private const val SLEEP = "SELECT pg_sleep(30)"

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(ServerImage.postgres).also { it.start() }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}

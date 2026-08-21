package dev.dbide.core.postgres

import dev.dbide.core.connections.Secret
import dev.dbide.core.result.CellValue
import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The tests that decide whether M0 actually works. They need Docker, so they are
 * opt-in locally (`DBIDE_INTEGRATION=1`) and mandatory in CI.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DBIDE_INTEGRATION", matches = "1")
class PostgresAdapterIntegrationTest {

    @Test
    fun `SELECT 1 makes a real round trip`() = runBlocking {
        session().use { session ->
            val result = session.adapter.selectOne()

            assertEquals(1, result.columns.size)
            assertEquals("one", result.columns.single().name)
            assertEquals(listOf(listOf(CellValue.Integer(1))), result.rows)
            assertTrue(result.duration > 0.milliseconds)
            assertFalse(result.truncated)
        }
    }

    @Test
    fun `the pooled connection goes back after a success`() = runBlocking {
        session().use { session ->
            session.adapter.selectOne()
            session.adapter.selectOne()

            assertEquals(0, session.activeConnections)
        }
    }

    @Test
    fun `the pooled connection goes back after a failure`() = runBlocking {
        session().use { session ->
            assertThrows<DbException> { runBlocking { session.adapter.execute("SELECT * FROM nope") } }

            assertEquals(0, session.activeConnections)
        }
    }

    @Test
    fun `a wrong password is an authentication failure and says nothing else`() = runBlocking {
        PostgresSession(config().copy(password = Secret("not-the-password"))).use { session ->
            val error = assertThrows<DbException> { runBlocking { session.adapter.selectOne() } }.error

            assertIs<DbError.AuthenticationFailed>(error)
            assertNoConnectionIdentity(error.message)
        }
    }

    @Test
    fun `an unknown database is reported as such`() = runBlocking {
        PostgresSession(config().copy(database = "no_such_database")).use { session ->
            val error = assertThrows<DbException> { runBlocking { session.adapter.selectOne() } }.error

            assertIs<DbError.DatabaseNotFound>(error)
            assertNoConnectionIdentity(error.message)
        }
    }

    @Test
    fun `an unreachable server is reported without naming it`() = runBlocking {
        // Port 1 is reserved and closed: the connection is refused rather than hanging.
        PostgresSession(config().copy(port = 1)).use { session ->
            val error = assertThrows<DbException> { runBlocking { session.adapter.selectOne() } }.error

            assertIs<DbError.ConnectionUnavailable>(error)
            assertNoConnectionIdentity(error.message)
        }
    }

    @Test
    fun `a query past the timeout is stopped and reported as a timeout`() = runBlocking {
        PostgresSession(config()).use { session ->
            val impatient = PostgresAdapter(
                dataSource = PostgresDataSources.create(config()),
                redaction = Redaction(config().secrets()),
                queryTimeout = 1.seconds,
            )

            val error = assertThrows<DbException> { runBlocking { impatient.execute(SLEEP) } }.error

            assertIs<DbError.Timeout>(error)
            assertBackendIsGone(session)
        }
    }

    @Test
    fun `cancelling the scope interrupts the blocked query and ends it server-side`() = runBlocking {
        session().use { session ->
            val observer = session()
            observer.use {
                val running = CompletableDeferred<Unit>()
                val query = launch(Dispatchers.IO) {
                    running.complete(Unit)
                    session.adapter.execute(SLEEP)
                }
                running.await()
                awaitBackend(observer)

                val elapsed = TimeSource.Monotonic.markNow()
                query.cancelAndJoin()

                // Cancelling a coroutine does not interrupt JDBC; only Statement.cancel does.
                assertTrue(
                    elapsed.elapsedNow() < 5.seconds,
                    "cancellation took ${elapsed.elapsedNow()}, so the JDBC call was not interrupted",
                )
                assertBackendIsGone(observer)
                assertEquals(0, session.activeConnections)
            }
        }
    }

    @Test
    fun `a backend killed mid-statement is a connection failure, and the pool recovers`() = runBlocking {
        // §2.5's last fixture: the server going away underneath a running statement.
        // It is worth its own test because it is the one failure that arrives while a
        // connection is checked out and half-used — the interesting part is not the
        // message but what the pool does next, since a session that survives one
        // killed backend and then hands out dead connections forever has failed in a
        // way the user reads as "the application broke".
        session().use { session ->
            session().use { observer ->
                val query = async(Dispatchers.IO) { runCatching { session.adapter.execute(SLEEP) } }
                awaitBackend(observer)
                terminateSleepingBackends(observer)

                val failure = query.await().exceptionOrNull()
                val error = assertIs<DbException>(failure).error
                assertIs<DbError.ConnectionUnavailable>(error)
                assertNoConnectionIdentity(error.message)

                // The killed connection is discarded rather than returned, and the next
                // statement gets a working one.
                assertEquals(0, session.activeConnections)
                assertEquals(listOf(listOf(CellValue.Integer(1))), session.adapter.selectOne().rows)
            }
        }
    }

    /** Ends every `pg_sleep` backend but the one asking. Allowed in a read-only transaction. */
    private suspend fun terminateSleepingBackends(observer: PostgresSession) {
        observer.adapter.execute(
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                "WHERE query LIKE 'SELECT pg_sleep%' AND pid <> pg_backend_pid()",
        )
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
            "SELECT count(*) AS running FROM pg_stat_activity WHERE query LIKE 'SELECT pg_sleep%'",
        )
        return (result.rows.single().single() as CellValue.Integer).value
    }

    private fun assertNoConnectionIdentity(message: String) {
        val config = config()
        listOf(config.host, config.database, config.user, config.password.expose(), "jdbc:").forEach {
            assertFalse(message.contains(it, ignoreCase = true), "leaked \"$it\" in: $message")
        }
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
        private const val SLEEP = "SELECT pg_sleep(30)"

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}

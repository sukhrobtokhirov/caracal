package dev.dbide.core.history

import dev.dbide.core.connections.ConnectionDraft
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionService
import dev.dbide.core.connections.DefaultConnectionService
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Secret
import dev.dbide.core.connections.SecretUpdate
import dev.dbide.core.registry.ConnectionRegistry
import dev.dbide.core.result.DbException
import dev.dbide.core.store.ConfigStore
import dev.dbide.core.vault.KdfParams
import dev.dbide.core.vault.Vault
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §2.11, against a real server: one history row per attempted execution, whatever
 * became of it.
 *
 * *Whatever became of it* is the whole requirement and the reason this needs a
 * server rather than a stub. A success is the easy case. The two that matter are
 * the query that failed — which is the one someone comes back looking for — and
 * the query that was cancelled, where the recording has to survive the very
 * cancellation that is being recorded.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DBIDE_INTEGRATION", matches = "1")
class QueryHistoryIntegrationTest {
    @TempDir
    lateinit var directory: Path

    private class Session(
        val store: ConfigStore,
        val registry: ConnectionRegistry,
        val service: ConnectionService,
    ) : AutoCloseable {
        override fun close() {
            runBlocking { registry.closeAll() }
            store.close()
        }
    }

    private suspend fun session(retention: Int = DEFAULT_HISTORY_RETENTION): Session {
        val store = ConfigStore.open(directory.resolve("dbide.db"), historyRetention = retention)
        val registry = ConnectionRegistry()
        // Argon2id at production cost would add a second to every test here.
        val vault = Vault(store, params = KdfParams.TESTING)
        return Session(store, registry, DefaultConnectionService(store, vault, registry))
    }

    /** One connection's history as a plain list; these tests are not about paging. */
    private suspend fun ConfigStore.history(id: ConnectionId) =
        history(HistoryQuery(connectionId = id)).items

    private fun draft(name: String = "Postgres", readOnly: Boolean = true) = ConnectionDraft(
        name = name,
        engine = Engine.POSTGRES,
        host = postgres.host,
        port = postgres.firstMappedPort,
        database = postgres.databaseName,
        username = postgres.username,
        secret = SecretUpdate.Replace(Secret(postgres.password)),
        readOnly = readOnly,
    )

    @Test
    fun `a successful query is recorded with its returned row count`(): Unit = runBlocking {
        session().use { session ->
            session.service.setUp(Secret("correct-horse-battery"))
            val view = session.service.create(draft())
            session.service.open(view.id)

            session.service.execute(view.id, "SELECT total FROM invoices ORDER BY total")

            val entry = session.store.history(view.id).single()
            assertEquals(ExecutionOutcome.OK, entry.outcome)
            assertEquals("SELECT total FROM invoices ORDER BY total", entry.statement)
            assertEquals(2L, entry.rowCount)
            assertNull(entry.error)
            assertNotNull(entry.duration)
        }
    }

    @Test
    fun `a statement that returns no rows records the count the server affected`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret("correct-horse-battery"))
            // Writable, so the statement reaches the server rather than being refused
            // by the read-only transaction — the affected count is the point here.
            val view = session.service.create(draft(readOnly = false))
            session.service.open(view.id)

            session.service.execute(view.id, "UPDATE invoices SET total = total")

            val entry = session.store.history(view.id).single()
            assertEquals(ExecutionOutcome.OK, entry.outcome)
            assertEquals(2L, entry.rowCount)
        }
    }

    @Test
    fun `a failed query is recorded with the sentence the user was shown`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret("correct-horse-battery"))
            val view = session.service.create(draft())
            session.service.open(view.id)

            val thrown = assertThrows<DbException> {
                runBlocking { session.service.execute(view.id, "SELECT * FROM no_such_table") }
            }

            val entry = session.store.history(view.id).single()
            assertEquals(ExecutionOutcome.ERROR, entry.outcome)
            assertEquals(thrown.error.message, entry.error)
            assertNull(entry.rowCount)
            // The message came through toFailure(), which is the same redaction the
            // banner renders: a history panel must not become the place the host name
            // finally shows up.
            assertNoIdentity(entry.error.orEmpty())
        }
    }

    @Test
    fun `a cancelled query is still recorded`(): Unit = runBlocking {
        session().use { session ->
            session.service.setUp(Secret("correct-horse-battery"))
            val view = session.service.create(draft())
            session.service.open(view.id)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val running = scope.async { session.service.execute(view.id, "SELECT pg_sleep(30)") }
            // Long enough for the statement to be on the server, so what is cancelled
            // is a running query rather than a coroutine that never started one.
            delay(500)
            running.cancel()
            runCatching { running.await() }

            val entry = session.store.history(view.id).single()
            assertEquals(ExecutionOutcome.CANCELLED, entry.outcome)
            assertEquals("SELECT pg_sleep(30)", entry.statement)
            assertNull(entry.rowCount)
            assertNotNull(entry.duration)
        }
    }

    @Test
    fun `history survives a restart and stays bounded by its retention`() = runBlocking {
        session(retention = 2).use { session ->
            session.service.setUp(Secret("correct-horse-battery"))
            val view = session.service.create(draft())
            session.service.open(view.id)
            repeat(3) { index -> session.service.execute(view.id, "SELECT $index") }
        }

        session(retention = 2).use { session ->
            session.service.unlock(Secret("correct-horse-battery"))
            val id = session.service.list().single().id

            assertEquals(
                listOf("SELECT 2", "SELECT 1"),
                session.store.history(id).map { it.statement },
            )
        }
    }

    @Test
    fun `exporting does not record a second execution`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret("correct-horse-battery"))
            val view = session.service.create(draft())
            session.service.open(view.id)
            val sql = "SELECT total FROM invoices ORDER BY total"
            session.service.execute(view.id, sql)

            session.service.exportCsv(view.id, sql, directory.resolve("invoices.csv"))

            // Export re-runs the statement, but the user ran it once and saved it once.
            // Two identical rows would tell M4's panel a story that did not happen.
            assertEquals(1, session.store.history(view.id).size)
        }
    }

    @Test
    fun `deleting a connection takes its history with it`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret("correct-horse-battery"))
            val view = session.service.create(draft())
            session.service.open(view.id)
            session.service.execute(view.id, "SELECT 1")

            session.service.delete(view.id)

            assertTrue(session.store.history(view.id).isEmpty())
        }
    }

    private fun assertNoIdentity(message: String) {
        listOf(postgres.host, postgres.databaseName, postgres.username, postgres.password, "jdbc:")
            .filter { it.length > 2 }
            .forEach { secret ->
                assertFalse(message.contains(secret, ignoreCase = true), "leaked \"$secret\" in: $message")
            }
    }

    companion object {
        // Testcontainers defaults every one of these to the four-letter word "test",
        // which occurs by chance in half the strings a database produces. Distinctive
        // values are what make the "nothing leaked" assertion mean anything.
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("dbide_history")
                .withUsername("dbide_reader")
                .withPassword("pg-secret-password")
                .also { it.start() }

        @JvmStatic
        @BeforeAll
        fun seed() {
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE invoices (total numeric)")
                    statement.execute("INSERT INTO invoices (total) VALUES (10), (20)")
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}

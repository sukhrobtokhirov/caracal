package dev.dbide.core.connections

import dev.dbide.core.registry.ConnectionRegistry
import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import dev.dbide.core.store.ConfigStore
import dev.dbide.core.vault.KdfParams
import dev.dbide.core.vault.Vault
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M1's acceptance scenario, against real servers.
 *
 * These need Docker, so they are opt-in locally (`DBIDE_INTEGRATION=1`) and
 * mandatory in CI. Everything they assert — that a secret survives a restart, that
 * a wrong password is classified rather than leaked — is the milestone's exit
 * criterion in executable form.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DBIDE_INTEGRATION", matches = "1")
class ConnectionManagerIntegrationTest {
    @TempDir
    lateinit var directory: Path

    private val master = "correct-horse-battery"

    private val databasePath: Path get() = directory.resolve("dbide.db")

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

    /** One process's worth of collaborators. Building a second models a restart. */
    private suspend fun session(): Session {
        val store = ConfigStore.open(databasePath)
        val registry = ConnectionRegistry()
        // Argon2id at production cost would add a second to every test here.
        val vault = Vault(store, params = KdfParams.TESTING)
        return Session(store, registry, DefaultConnectionService(store, vault, registry))
    }

    private fun postgresDraft(
        name: String = "Postgres",
        host: String = postgres.host,
        port: Int = postgres.firstMappedPort,
        database: String = postgres.databaseName,
        username: String = postgres.username,
        password: String = postgres.password,
    ) = ConnectionDraft(
        name = name,
        engine = Engine.POSTGRES,
        host = host,
        port = port,
        database = database,
        username = username,
        secret = SecretUpdate.Replace(Secret(password)),
    )

    private fun redisDraft(
        name: String = "Redis",
        host: String = redis.host,
        port: Int = redis.firstMappedPort,
        database: String = "0",
        password: String = REDIS_PASSWORD,
    ) = ConnectionDraft(
        name = name,
        engine = Engine.REDIS,
        host = host,
        port = port,
        database = database,
        secret = SecretUpdate.Replace(Secret(password)),
    )

    @Test
    fun `a PostgreSQL connection can be tested, opened, and closed`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            val view = session.service.create(postgresDraft())

            val result = session.service.test(view.id)
            assertEquals(Engine.POSTGRES, result.engine)
            assertNotNull(result.serverVersion)

            assertTrue(session.service.open(view.id).runtime.isOpen)
            // Open is idempotent for a healthy connection.
            assertTrue(session.service.open(view.id).runtime.isOpen)

            assertEquals(RuntimeStatus.CLOSED, session.service.close(view.id).runtime.status)
            assertEquals(RuntimeStatus.CLOSED, session.service.close(view.id).runtime.status)
        }
    }

    @Test
    fun `a Redis connection can be tested, opened, and closed`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            val view = session.service.create(redisDraft())

            val result = session.service.test(view.id)
            assertEquals(Engine.REDIS, result.engine)
            assertNotNull(result.serverVersion)

            assertTrue(session.service.open(view.id).runtime.isOpen)
            assertEquals(RuntimeStatus.CLOSED, session.service.close(view.id).runtime.status)
        }
    }

    @Test
    fun `both engines are open at once, and neither is confused for the other`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            val pg = session.service.create(postgresDraft())
            val rd = session.service.create(redisDraft())

            session.service.open(pg.id)
            session.service.open(rd.id)

            assertTrue(session.registry.postgres(pg.id).activeConnections >= 0)
            assertEquals("PONG", session.registry.redis(rd.id).ping())
            assertThrows<DbException> { runBlocking { session.registry.redis(pg.id) } }
            assertThrows<DbException> { runBlocking { session.registry.postgres(rd.id) } }
        }
    }

    // --- Failure classification ----------------------------------------------

    @Test
    fun `a wrong PostgreSQL password is an authentication failure and names nothing`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            val view = session.service.create(postgresDraft(password = "not-the-password"))

            val error = assertThrows<DbException> { runBlocking { session.service.test(view.id) } }.error

            assertIs<DbError.AuthenticationFailed>(error)
            assertNoIdentity(error.message)
        }
    }

    @Test
    fun `a wrong Redis password is an authentication failure and names nothing`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            val view = session.service.create(redisDraft(password = "not-the-password"))

            val error = assertThrows<DbException> { runBlocking { session.service.test(view.id) } }.error

            assertIs<DbError.AuthenticationFailed>(error)
            assertNoIdentity(error.message)
        }
    }

    @Test
    fun `an unknown PostgreSQL database is reported as such`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            val view = session.service.create(postgresDraft(database = "no_such_database"))

            val error = assertThrows<DbException> { runBlocking { session.service.test(view.id) } }.error

            assertIs<DbError.DatabaseNotFound>(error)
            assertNoIdentity(error.message)
        }
    }

    @Test
    fun `an unreachable host is reported without naming it`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            // Port 1 is reserved and closed, so this is refused rather than hanging.
            val view = session.service.create(postgresDraft(port = 1))

            val error = assertThrows<DbException> { runBlocking { session.service.test(view.id) } }.error

            assertIs<DbError.ConnectionUnavailable>(error)
            assertNoIdentity(error.message)
        }
    }

    @Test
    fun `a failed open leaves a status of error with a safe message`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            val view = session.service.create(postgresDraft(password = "not-the-password"))

            assertThrows<DbException> { runBlocking { session.service.open(view.id) } }

            val listed = session.service.list().single()
            assertEquals(RuntimeStatus.ERROR, listed.runtime.status)
            assertNoIdentity(assertNotNull(listed.runtime.lastError))
        }
    }

    // --- The acceptance scenario ---------------------------------------------

    @Test
    fun `connections survive a restart, unlock, and open again`() = runBlocking {
        val (pgId, redisId) = session().use { session ->
            session.service.setUp(Secret(master))
            val pg = session.service.create(
                postgresDraft().copy(environment = Environment.PROD, readOnly = true),
            )
            val rd = session.service.create(redisDraft())
            session.service.open(pg.id)
            session.service.open(rd.id)
            pg.id to rd.id
        }

        session().use { restarted ->
            restarted.service.unlock(Secret(master))

            val views = restarted.service.list()
            assertEquals(2, views.size)
            // Production first, and both closed in a process that has just started.
            assertEquals(Environment.PROD, views.first().config.environment)
            assertTrue(views.all { it.runtime.status == RuntimeStatus.CLOSED })
            assertTrue(views.all { it.hasSecret })

            // The saved passwords still work, which is the milestone's exit criterion.
            assertTrue(restarted.service.open(pgId).runtime.isOpen)
            assertTrue(restarted.service.open(redisId).runtime.isOpen)
        }
    }

    @Test
    fun `an edit that does not touch the password leaves the connection working`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            val view = session.service.create(postgresDraft())
            session.service.open(view.id)

            session.service.update(
                view.id,
                ConnectionDraft.of(view.config).copy(color = "#ff8800"),
            )

            // A cosmetic edit changes nothing about dialing, so the client stays up.
            assertTrue(session.service.get(view.id).runtime.isOpen)
            session.service.test(view.id)
        }
    }

    @Test
    fun `changing the host closes the client built from the old one`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            val view = session.service.create(postgresDraft())
            session.service.open(view.id)

            session.service.update(view.id, ConnectionDraft.of(view.config).copy(port = 1))

            assertEquals(RuntimeStatus.CLOSED, session.service.get(view.id).runtime.status)
        }
    }

    @Test
    fun `deleting a connection closes its client`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            val view = session.service.create(postgresDraft())
            session.service.open(view.id)

            session.service.delete(view.id)

            assertEquals(emptyList(), session.service.list())
            assertEquals(emptyMap(), session.registry.states())
        }
    }

    @Test
    fun `locking closes the live clients as well as discarding the key`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            val view = session.service.create(postgresDraft())
            session.service.open(view.id)

            session.service.lock()

            assertEquals(RuntimeStatus.CLOSED, session.registry.state(view.id).status)
            assertThrows<DbException> { runBlocking { session.registry.postgres(view.id) } }
        }
    }

    @Test
    fun `no plaintext password ever reaches the configuration file`() = runBlocking {
        session().use { session ->
            session.service.setUp(Secret(master))
            session.service.create(postgresDraft())
            session.service.create(redisDraft())
        }

        // WAL mode keeps recent writes in a sidecar file, so check that too.
        listOf(databasePath, Path.of("$databasePath-wal")).forEach { path ->
            if (!path.toFile().exists()) return@forEach
            val contents = String(path.readBytes(), Charsets.ISO_8859_1)
            listOf(postgres.password, REDIS_PASSWORD, master).forEach { secret ->
                assertFalse(contents.contains(secret), "$secret appears in ${path.fileName}")
            }
        }
    }

    /** No classified message may name the server, the database, or the credential. */
    private fun assertNoIdentity(message: String) {
        listOf(postgres.host, postgres.databaseName, postgres.username, postgres.password, "jdbc:")
            .filter { it.length > 2 }
            .forEach { assertFalse(message.contains(it, ignoreCase = true), "leaked \"$it\" in: $message") }
    }

    companion object {
        private const val REDIS_PASSWORD = "redis-password"

        // Testcontainers' default password is the four-letter word "test", which
        // occurs by chance in any binary file. A distinctive one makes the
        // "no plaintext on disk" assertion mean something.
        private const val POSTGRES_PASSWORD = "pg-secret-password"

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withPassword(POSTGRES_PASSWORD)
                .also { it.start() }

        private val redis: GenericContainer<*> =
            GenericContainer("redis:7-alpine")
                .withExposedPorts(6379)
                .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
                .also { it.start() }

        @JvmStatic
        @AfterAll
        fun stopContainers() {
            postgres.stop()
            redis.stop()
        }
    }
}

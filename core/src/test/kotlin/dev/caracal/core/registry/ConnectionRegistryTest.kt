package dev.caracal.core.registry

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.EngineId
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.RuntimeStatus
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.connections.networkConfig
import dev.caracal.core.result.DbException
import dev.caracal.core.vault.password
import dev.caracal.engine.postgres.PostgresEngine
import dev.caracal.engine.redis.RedisEngine
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The registry's behaviour without a database behind it: identity, idempotence, and
 * what a failed dial leaves behind. The engine adapters have their own integration
 * tests; these run headlessly and offline.
 */
class ConnectionRegistryTest {
    private val registry = ConnectionRegistry()

    /**
     * Port 1 is reserved and closed, so a dial there is refused rather than hanging
     * until the connect timeout.
     *
     * Redis is the default engine for the state-machine tests purely for speed:
     * Lettuce reports a refused connection at once, while HikariCP keeps retrying
     * for its whole five-second connect timeout. The registry cannot tell the
     * difference, so the cheaper engine proves the same behaviour.
     */
    private fun unreachable(
        id: String = "id-1",
        engineId: EngineId = RedisEngine.ID,
    ) = config(id = id, engineId = engineId, port = 1)

    private fun config(
        id: String = "id-1",
        engineId: EngineId = PostgresEngine.ID,
        host: String = "127.0.0.1",
        port: Int = 5432,
        database: String = "caracal",
        username: String = "caracal",
        tlsMode: TlsMode = TlsMode.DISABLE,
    ) = networkConfig(
        id = ConnectionId(id),
        name = "Connection $id",
        engineId = engineId,
        host = host,
        port = port,
        database = database,
        username = username,
        tlsMode = tlsMode,
        environment = Environment.DEV,
        readOnly = false,
        color = null,
        createdAt = Instant.parse("2026-08-20T10:00:00Z"),
    )

    @Test
    fun `a connection the registry has never seen is closed, not statusless`() = runTest {
        assertEquals(RuntimeStatus.CLOSED, registry.state(ConnectionId("unknown")).status)
        assertNull(registry.state(ConnectionId("unknown")).lastError)
        assertEquals(emptyMap(), registry.states())
    }

    @Test
    fun `closing and forgetting an unknown connection is not an error`() = runTest {
        registry.close(ConnectionId("unknown"))
        registry.forget(ConnectionId("unknown"))

        assertEquals(emptyMap(), registry.states())
    }

    @Test
    fun `a failed dial leaves the status in error with a safe message`() = runBlocking {
        // PostgreSQL specifically: its driver messages are the ones that name the
        // host, port, user, and database, so this is where redaction has to hold.
        val config = unreachable(engineId = PostgresEngine.ID)
        assertThrows<DbException> { runBlocking { registry.open(config, password("")) } }

        val state = registry.state(ConnectionId("id-1"))
        assertEquals(RuntimeStatus.ERROR, state.status)
        assertNull(state.openedAt)
        val message = assertNotNull(state.lastError)
        // The message is classified, not the driver's: no host, port, or user in it.
        listOf("127.0.0.1", "caracal", "jdbc:").forEach {
            assertFalse(message.contains(it), "leaked \"$it\" in: $message")
        }
    }

    @Test
    fun `a failed PostgreSQL dial is classified too`() = runBlocking {
        assertThrows<DbException> {
            runBlocking { registry.open(unreachable(engineId = PostgresEngine.ID), password("")) }
        }

        assertEquals(RuntimeStatus.ERROR, registry.state(ConnectionId("id-1")).status)
    }

    @Test
    fun `closing after a failed dial returns the entry to closed`() = runBlocking {
        assertThrows<DbException> { runBlocking { registry.open(unreachable(), password("")) } }

        registry.close(ConnectionId("id-1"))

        val state = registry.state(ConnectionId("id-1"))
        assertEquals(RuntimeStatus.CLOSED, state.status)
        assertNull(state.lastError)
    }

    @Test
    fun `forgetting drops the entry so no phantom survives the record`() = runBlocking {
        assertThrows<DbException> { runBlocking { registry.open(unreachable(), password("")) } }

        registry.forget(ConnectionId("id-1"))

        assertEquals(emptyMap(), registry.states())
    }

    @Test
    fun `a client that is not open cannot be borrowed`() = runTest {
        assertThrows<NotOpenException> { registry.session(ConnectionId("id-1")) }
    }

    @Test
    fun `closing everything is safe when nothing is open`() = runTest {
        registry.closeAll()

        assertEquals(emptyMap(), registry.states())
    }

    @Test
    fun `nothing is invalidated when the connection was never opened`() = runTest {
        assertFalse(registry.invalidateIfChanged(config(), password("hunter2")))
    }

    @Test
    fun `concurrent opens against the same connection do not corrupt its state`() = runBlocking {
        withContext(Dispatchers.IO) {
            (1..8).map {
                async {
                    runCatching { registry.open(unreachable(), password("")) }
                }
            }.awaitAll()
        }

        // Whatever order they finished in, exactly one entry exists and it is in a
        // terminal state — never left mid-dial.
        val states = registry.states()
        assertEquals(1, states.size)
        assertTrue(states.values.single().status in setOf(RuntimeStatus.ERROR, RuntimeStatus.CLOSED))
    }

    @Test
    fun `concurrent opens and closes across connections stay independent`() = runBlocking {
        withContext(Dispatchers.IO) {
            (1..6).map { index ->
                async {
                    val id = "id-$index"
                    runCatching { registry.open(unreachable(id = id), password("")) }
                    if (index % 2 == 0) registry.close(ConnectionId(id))
                }
            }.awaitAll()
        }

        assertEquals(6, registry.states().size)
        (1..6).forEach { index ->
            val expected = if (index % 2 == 0) RuntimeStatus.CLOSED else RuntimeStatus.ERROR
            assertEquals(expected, registry.state(ConnectionId("id-$index")).status, "id-$index")
        }
    }

    @Test
    fun `the fingerprint changes when any dialing field changes`() {
        val base = config()
        val secret = password("hunter2")
        val original = ConnectionRegistry.fingerprint(base, secret)

        listOf(
            "host" to config(host = "db.internal"),
            "port" to config(port = 6432),
            "database" to config(database = "other"),
            "user" to config(username = "someone"),
            "tls" to config(tlsMode = TlsMode.REQUIRE),
            "engine" to config(engineId = RedisEngine.ID),
            // A field no engine here declares. The fingerprint covers whatever the
            // settings map holds, so an engine that declares its own field gets the
            // same protection without this test knowing what the field is.
            "an engine's own field" to base.copy(settings = base.settings + ("region" to "eu-west-1")),
        ).forEach { (changed, config) ->
            assertFalse(
                original == ConnectionRegistry.fingerprint(config, secret),
                "a change to $changed went unnoticed",
            )
        }
    }

    @Test
    fun `the fingerprint changes when only the secret changes`() {
        assertFalse(
            ConnectionRegistry.fingerprint(config(), password("hunter2")) ==
                ConnectionRegistry.fingerprint(config(), password("hunter3")),
        )
    }

    @Test
    fun `the fingerprint ignores fields that do not affect dialing`() {
        val cosmetic = config().copy(name = "Renamed", color = "#ff0000")

        assertEquals(
            ConnectionRegistry.fingerprint(config(), password("hunter2")),
            ConnectionRegistry.fingerprint(cosmetic, password("hunter2")),
        )
    }

    @Test
    fun `the fingerprint changes when the read-only flag does`() {
        // Not cosmetic, and this is the assertion that says so. `readOnly` decides
        // whether the pool opens its connections in a PostgreSQL READ ONLY
        // transaction, so an open pool built under the old answer has to be
        // invalidated — otherwise ticking Read only leaves a connection that still
        // accepts writes, which is the direction that matters.
        val restricted = config().copy(readOnly = true)

        assertNotEquals(
            ConnectionRegistry.fingerprint(config(), password("hunter2")),
            ConnectionRegistry.fingerprint(restricted, password("hunter2")),
        )
    }

    @Test
    fun `the fingerprint cannot be fooled by shifting a boundary between fields`() {
        // Without a separator, host "a" + database "bc" and host "ab" + database "c"
        // would hash identically.
        assertFalse(
            ConnectionRegistry.fingerprint(config(host = "a", database = "bc"), password("")) ==
                ConnectionRegistry.fingerprint(config(host = "ab", database = "c"), password("")),
        )
    }

    @Test
    fun `the fingerprint does not contain the password`() {
        val fingerprint = ConnectionRegistry.fingerprint(config(), password("hunter2"))

        assertFalse(fingerprint.contains("hunter2"))
        assertEquals(64, fingerprint.length)
    }
}

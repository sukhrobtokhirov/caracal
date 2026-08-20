package dev.dbide.core.registry

import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.RuntimeStatus
import dev.dbide.core.connections.Secret
import dev.dbide.core.connections.TlsMode
import dev.dbide.core.result.DbException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
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
        engine: Engine = Engine.REDIS,
    ) = config(id = id, engine = engine, port = 1)

    private fun config(
        id: String = "id-1",
        engine: Engine = Engine.POSTGRES,
        host: String = "127.0.0.1",
        port: Int = 5432,
        database: String = "dbide",
        username: String = "dbide",
        tlsMode: TlsMode = TlsMode.DISABLE,
    ) = ConnectionConfig(
        id = ConnectionId(id),
        name = "Connection $id",
        engine = engine,
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
        val config = unreachable(engine = Engine.POSTGRES)
        assertThrows<DbException> { runBlocking { registry.open(config, Secret("")) } }

        val state = registry.state(ConnectionId("id-1"))
        assertEquals(RuntimeStatus.ERROR, state.status)
        assertNull(state.openedAt)
        val message = assertNotNull(state.lastError)
        // The message is classified, not the driver's: no host, port, or user in it.
        listOf("127.0.0.1", "dbide", "jdbc:").forEach {
            assertFalse(message.contains(it), "leaked \"$it\" in: $message")
        }
    }

    @Test
    fun `a failed PostgreSQL dial is classified too`() = runBlocking {
        assertThrows<DbException> {
            runBlocking { registry.open(unreachable(engine = Engine.POSTGRES), Secret("")) }
        }

        assertEquals(RuntimeStatus.ERROR, registry.state(ConnectionId("id-1")).status)
    }

    @Test
    fun `closing after a failed dial returns the entry to closed`() = runBlocking {
        assertThrows<DbException> { runBlocking { registry.open(unreachable(), Secret("")) } }

        registry.close(ConnectionId("id-1"))

        val state = registry.state(ConnectionId("id-1"))
        assertEquals(RuntimeStatus.CLOSED, state.status)
        assertNull(state.lastError)
    }

    @Test
    fun `forgetting drops the entry so no phantom survives the record`() = runBlocking {
        assertThrows<DbException> { runBlocking { registry.open(unreachable(), Secret("")) } }

        registry.forget(ConnectionId("id-1"))

        assertEquals(emptyMap(), registry.states())
    }

    @Test
    fun `a client that is not open cannot be borrowed`() = runTest {
        assertThrows<NotOpenException> { registry.postgres(ConnectionId("id-1")) }
        assertThrows<NotOpenException> { registry.redis(ConnectionId("id-1")) }
    }

    @Test
    fun `closing everything is safe when nothing is open`() = runTest {
        registry.closeAll()

        assertEquals(emptyMap(), registry.states())
    }

    @Test
    fun `nothing is invalidated when the connection was never opened`() = runTest {
        assertFalse(registry.invalidateIfChanged(config(), Secret("hunter2")))
    }

    @Test
    fun `concurrent opens against the same connection do not corrupt its state`() = runBlocking {
        withContext(Dispatchers.IO) {
            (1..8).map {
                async {
                    runCatching { registry.open(unreachable(), Secret("")) }
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
                    runCatching { registry.open(unreachable(id = id), Secret("")) }
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
        val password = Secret("hunter2")
        val original = ConnectionRegistry.fingerprint(base, password)

        listOf(
            base.copy(host = "db.internal"),
            base.copy(port = 6432),
            base.copy(database = "other"),
            base.copy(username = "someone"),
            base.copy(tlsMode = TlsMode.REQUIRE),
            base.copy(engine = Engine.REDIS),
        ).forEach { changed ->
            assertFalse(
                original == ConnectionRegistry.fingerprint(changed, password),
                "a change to ${changed.host}/${changed.port}/${changed.engine} went unnoticed",
            )
        }
    }

    @Test
    fun `the fingerprint changes when only the secret changes`() {
        assertFalse(
            ConnectionRegistry.fingerprint(config(), Secret("hunter2")) ==
                ConnectionRegistry.fingerprint(config(), Secret("hunter3")),
        )
    }

    @Test
    fun `the fingerprint ignores fields that do not affect dialing`() {
        val cosmetic = config().copy(name = "Renamed", color = "#ff0000")

        assertEquals(
            ConnectionRegistry.fingerprint(config(), Secret("hunter2")),
            ConnectionRegistry.fingerprint(cosmetic, Secret("hunter2")),
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
            ConnectionRegistry.fingerprint(config(), Secret("hunter2")),
            ConnectionRegistry.fingerprint(restricted, Secret("hunter2")),
        )
    }

    @Test
    fun `the fingerprint cannot be fooled by shifting a boundary between fields`() {
        // Without a separator, host "a" + database "bc" and host "ab" + database "c"
        // would hash identically.
        assertFalse(
            ConnectionRegistry.fingerprint(config(host = "a", database = "bc"), Secret("")) ==
                ConnectionRegistry.fingerprint(config(host = "ab", database = "c"), Secret("")),
        )
    }

    @Test
    fun `the fingerprint does not contain the password`() {
        val fingerprint = ConnectionRegistry.fingerprint(config(), Secret("hunter2"))

        assertFalse(fingerprint.contains("hunter2"))
        assertEquals(64, fingerprint.length)
    }
}

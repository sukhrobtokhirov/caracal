package dev.caracal.core.registry

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.EngineId
import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.connections.networkConfig
import dev.caracal.core.result.DbException
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.Environment
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.api.TlsConfig
import dev.caracal.engine.postgres.PostgresEngine
import dev.caracal.engine.redis.RedisEngine
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The translation the registry now dials through, and the one thing in it that is
 * not mechanical.
 *
 * Every field here used to be handed to `PostgresSession.open` or
 * `RedisSession.open` directly. They are handed to `DatabaseEngine.connect` now, by
 * way of a descriptor, and a descriptor that loses a field loses it *silently* —
 * the connection still opens. Three of them would be a security regression rather
 * than a bug report: the TLS mode, the environment, and the read-only flag.
 */
class DescriptorsTest {

    // --- TLS, where the two engines disagree about one word -------------------

    @Test
    fun `PostgreSQL's require encrypts without checking the certificate`() {
        // Which is what pgjdbc has always done for `require`, and what the servers
        // people have saved this against expect. Reading it as verify-full would
        // break every connection to a self-signed server on upgrade.
        val tls = config(PostgresEngine.ID, TlsMode.REQUIRE).toDescriptor(PostgresEngine()).tls

        assertEquals(TlsConfig.Required(verifyHostname = false), tls)
    }

    @Test
    fun `PostgreSQL's verify-full checks it`() {
        assertEquals(
            TlsConfig.Required(verifyHostname = true),
            config(PostgresEngine.ID, TlsMode.VERIFY_FULL).toDescriptor(PostgresEngine()).tls,
        )
    }

    @Test
    fun `Redis's require checks it, because Redis has no weaker on`() {
        // The same stored word, the other meaning. The Redis client is built with
        // verifyPeer and always has been, and `RedisEngine.validate` refuses a
        // descriptor that asks for encryption without verification — so reading this
        // as PostgreSQL's `require` would not weaken the connection, it would refuse
        // to open one that works today.
        val tls = config(RedisEngine.ID, TlsMode.REQUIRE).toDescriptor(RedisEngine()).tls

        assertEquals(TlsConfig.Required(verifyHostname = true), tls)
        assertTrue(RedisEngine().validate(config(RedisEngine.ID, TlsMode.REQUIRE).toDescriptor(RedisEngine())).isEmpty())
    }

    @Test
    fun `verify-full on Redis is refused rather than quietly downgraded`() {
        // Not reachable through the forms — `TlsMode.supportedBy` does not offer it —
        // so arriving here means a configuration file edited by hand. The one answer
        // that must not happen is a connection that opens with weaker transport
        // security than the file asked for.
        val failure = assertThrows<DbException> { config(RedisEngine.ID, TlsMode.VERIFY_FULL).toDescriptor(RedisEngine()) }

        assertEquals("Redis supports the disable and require TLS modes.", failure.error.message)
    }

    @Test
    fun `disabled stays disabled on both`() {
        assertEquals(TlsConfig.Disabled, config(PostgresEngine.ID, TlsMode.DISABLE).toDescriptor(PostgresEngine()).tls)
        assertEquals(TlsConfig.Disabled, config(RedisEngine.ID, TlsMode.DISABLE).toDescriptor(RedisEngine()).tls)
    }

    // --- The rest of the record ----------------------------------------------

    @Test
    fun `the fields an engine dials from all survive the translation`() {
        val descriptor = config(PostgresEngine.ID, TlsMode.DISABLE).toDescriptor(PostgresEngine())

        assertEquals(PostgresEngine.ID, descriptor.engineId)
        assertEquals(ConnectionId("id-1"), descriptor.id)
        assertEquals("Warehouse", descriptor.displayName)
        assertEquals(ConnectionTarget.Network("db.example", 5432, "analytics"), descriptor.target)
        assertEquals("reader", descriptor.engineOptions[PostgresEngine.OPTION_USER])
    }

    @Test
    fun `the production tag reaches the descriptor, because the guard reads it`() {
        // The Redis command guard reads the environment off the connection it
        // captured at open. A descriptor that dropped it would turn a typed phrase
        // into a click, on the connection where that matters most.
        val descriptor = config(RedisEngine.ID, TlsMode.DISABLE, environment = Environment.PROD).toDescriptor(RedisEngine())

        assertEquals(Environment.PROD, descriptor.environment)
    }

    @Test
    fun `writable is the label, and it is the opposite of the stored flag`() {
        assertEquals(false, config(PostgresEngine.ID, TlsMode.DISABLE, readOnly = true).toDescriptor(PostgresEngine()).writable)
        assertEquals(true, config(PostgresEngine.ID, TlsMode.DISABLE, readOnly = false).toDescriptor(PostgresEngine()).writable)
    }

    // --- Secrets --------------------------------------------------------------

    @Test
    fun `a user and a password travel as a pair`() {
        val bundle = config(PostgresEngine.ID, TlsMode.DISABLE).secretBundle(Secret("hunter2"))

        val pair = assertIs<SecretBundle.UserPassword>(bundle)
        assertEquals("reader", pair.user)
        assertEquals("hunter2", String(pair.password))
    }

    @Test
    fun `a password with no user travels alone`() {
        val bundle = config(RedisEngine.ID, TlsMode.DISABLE, username = "").secretBundle(Secret("hunter2"))

        assertEquals("hunter2", String(assertIs<SecretBundle.Password>(bundle).password))
    }

    @Test
    fun `neither is a statement, not a missing lookup`() {
        // SecretBundle.None is what an unauthenticated Redis and a SQLite file get.
        val bundle = config(RedisEngine.ID, TlsMode.DISABLE, username = "").secretBundle(Secret.EMPTY)

        assertEquals(SecretBundle.None, bundle)
    }

    @Test
    fun `the bundle keeps its own copy, so clearing the caller's secret is safe`() {
        // The registry clears the password the moment the dial returns, and the
        // bundle outlives that call by exactly as long as the dial takes.
        val password = Secret("hunter2")
        val bundle = assertIs<SecretBundle.UserPassword>(
            config(PostgresEngine.ID, TlsMode.DISABLE).secretBundle(password),
        )

        password.clear()

        assertEquals("hunter2", String(bundle.password))
    }

    private fun config(
        engine: EngineId,
        tlsMode: TlsMode,
        environment: Environment = Environment.DEV,
        readOnly: Boolean = true,
        username: String = "reader",
    ) = networkConfig(
        id = ConnectionId("id-1"),
        name = "Warehouse",
        engineId = engine,
        host = "db.example",
        port = if (engine == PostgresEngine.ID) 5432 else 6379,
        database = if (engine == PostgresEngine.ID) "analytics" else "0",
        username = username,
        tlsMode = tlsMode,
        environment = environment,
        readOnly = readOnly,
        color = null,
        createdAt = Instant.EPOCH,
    )
}

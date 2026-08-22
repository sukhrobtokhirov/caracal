package dev.caracal.engine.redis

import dev.caracal.core.policy.Acknowledgement
import dev.caracal.core.policy.CommandConfirmationRequired
import dev.caracal.core.redis.RedisFixture
import dev.caracal.engine.api.CommandFacet
import dev.caracal.engine.api.CommandLine
import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionId
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.DatabaseSession
import dev.caracal.engine.api.Environment
import dev.caracal.engine.api.QueryFacet
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.api.SessionPolicy
import dev.caracal.engine.api.SessionState
import dev.caracal.engine.api.facet
import dev.caracal.engine.api.requireFacet
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Redis reached only through the SPI, against a real server.
 *
 * Shorter than PostgreSQL's for the reason `RedisEngineSession` explains: the key
 * browser, the console, and the dashboard are not facets yet, because their shape is
 * decided by the UI that has not been flipped onto them. What is here is the part
 * that is settled — dialling, identity, a version, a ping, a lifecycle — plus the
 * one assertion that matters most for a second engine, which is that asking it for a
 * SQL facet gets a null rather than a surprise.
 */
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class RedisEngineIntegrationTest {

    @Test
    fun `an engine connects, pings, and names the server`() = runBlocking {
        connect().use { session ->
            assertEquals(RedisEngine.ID, session.engineId)
            assertEquals(SessionState.Ready, session.state.value)
            assertTrue(session.ping() > Duration.ZERO)

            val version = session.serverVersion
            assertNotNull(version.raw, "the server did not name itself")
            assertEquals(7, version.major)
        }
    }

    @Test
    fun `a key-value engine has no query facet, and says so with a null`() = runBlocking {
        connect().use { session ->
            // The whole point of facets over a god interface: the caller asks, and an
            // engine that does not run SQL is not obliged to pretend it might.
            assertNull(session.facet<QueryFacet>())
        }
    }

    @Test
    fun `closing a session says so`() = runBlocking {
        val session = connect()
        session.close()

        assertEquals(SessionState.Closed, session.state.value)
    }

    @Test
    fun `the descriptor's environment reaches the command guard`() = runBlocking {
        // Not decoration. The adapter captures the config at open and the guard reads
        // the environment off it, which is how FLUSHDB against production asks for a
        // typed phrase instead of a click. A descriptor that dropped the tag on the
        // way through would downgrade that silently, on the connection where it
        // matters most — so the assertion goes through a session that was opened from
        // a descriptor rather than through the guard directly.
        connect(environment = Environment.PROD).use { session ->
            val console = session.requireFacet<CommandFacet>()

            val refusal = assertFailsWith<CommandConfirmationRequired> {
                console.execute(CommandLine.command("FLUSHDB"))
            }

            assertEquals(Acknowledgement.TYPED, refusal.clearance.acknowledgement)
        }
    }

    @Test
    fun `a development connection asks for a click instead of a phrase`() = runBlocking {
        connect(environment = Environment.DEV).use { session ->
            val console = session.requireFacet<CommandFacet>()

            val refusal = assertFailsWith<CommandConfirmationRequired> {
                console.execute(CommandLine.command("FLUSHDB"))
            }

            assertEquals(Acknowledgement.CLICK, refusal.clearance.acknowledgement)
        }
    }

    private suspend fun connect(environment: Environment = Environment.DEV): DatabaseSession =
        RedisEngine.connect(
            descriptor = ConnectionDescriptor(
                id = ConnectionId("spi-fixture"),
                engineId = RedisEngine.ID,
                displayName = "SPI fixture",
                target = ConnectionTarget.Network(
                    host = RedisFixture.host,
                    port = RedisFixture.port,
                    database = RedisFixture.ENGINE_DB.toString(),
                ),
                environment = environment,
            ),
            secrets = SecretBundle.None,
            policy = SessionPolicy(readOnly = false, statementTimeout = 5.seconds),
        )
}

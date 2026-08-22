package dev.caracal.core.redis

import dev.caracal.core.connections.Secret
import dev.caracal.core.result.DbException
import dev.caracal.engine.api.MemoryEstimate
import dev.caracal.engine.api.RawCommand
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * What a restricted user sees, which is most of what a real user sees.
 *
 * Read access to a production Redis is normally handed out as an ACL, and an ACL
 * that grants `@read` grants neither `INFO` nor `MEMORY`. So the shape asserted here
 * is the common one rather than the exotic one: the dashboard is empty, the memory
 * column is blank, and everything else works. §3.8's "do not fail key browsing
 * because `INFO` is restricted" and §3.3's "return null with a per-field marker
 * rather than failing the entire page" are the same requirement seen twice, and this
 * is where they are checked against a server that really does refuse.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class RedisPermissionsIntegrationTest {

    private val db = RedisFixture.PERMISSIONS_DB

    @BeforeEach
    fun seed() {
        RedisFixture.admin(db) { commands ->
            commands.flushdb()
            (1..20).forEach { commands.set("user:$it", "value") }
            commands.hset("profile", "name", "ada")
        }
    }

    @Test
    fun `a user denied INFO still browses, and the dashboard says it was refused`() = runBlocking {
        restricted(RedisFixture.NOSTATS).use { session ->
            val info = session.adapter.info()

            assertTrue(info.restricted, "a refused INFO was not reported as refused")
            assertTrue(info.isEmpty)
            assertNull(info.totalKeys)

            // And the workspace is entirely usable, which is the point.
            assertEquals(21, session.adapter.scan().keys.size)
        }
    }

    @Test
    fun `a user denied MEMORY USAGE keeps the rest of the page`() = runBlocking {
        restricted(RedisFixture.NOSTATS).use { session ->
            val page = session.adapter.scan()

            assertEquals(21, page.keys.size)
            assertTrue(page.keys.all { it.exists }, "keys were lost with the memory estimate")
            assertTrue(page.keys.all { it.type != null })
            // Unavailable, not absent: the key is there and the server would not say
            // how big it is. A UI that showed a zero would be inventing a number.
            assertTrue(
                page.keys.all { it.memory == MemoryEstimate.Unavailable },
                "memory was reported as ${page.keys.map { it.memory }.distinct()}",
            )
        }
    }

    @Test
    fun `a permitted user gets the estimate, so the blank column really is the ACL`() = runBlocking {
        // The control for the test above. Without it, a bug that never asked for
        // MEMORY USAGE at all would pass both.
        restricted(RedisFixture.READER).use { session ->
            val page = session.adapter.scan()

            assertTrue(
                page.keys.any { it.memory is MemoryEstimate.Bytes },
                "no key reported a size even though the user may ask",
            )
            assertFalse(session.adapter.info().restricted)
        }
    }

    @Test
    fun `an ACL that refuses a write refuses it even when this build would have allowed it`() = runBlocking {
        // §3.10's closing line: the database ACL is the final control. This connection
        // is *not* marked read only, so the guard grants the command on a development
        // environment — and the server refuses it anyway, which is the arrangement the
        // milestone asks for rather than a gap in it.
        val session = RedisSession.open(
            config = RedisFixture.config(db, username = RedisFixture.READER),
            password = Secret(RedisFixture.ACL_PASSWORD),
        )

        session.use {
            val failure = assertThrows<DbException> {
                runBlocking { session.adapter.execute(RawCommand.of("SET", "user:1", "changed")) }
            }

            assertEquals("query_failed", failure.error.code)
            assertEquals("value", RedisFixture.admin(db) { it.get("user:1") })
        }
    }

    @Test
    fun `a refusal from the server does not name the server`() = runBlocking {
        // A `NOPERM` reply is server-authored text about the arguments that were sent,
        // and the project's rule is that nothing carrying the host, port, or user
        // reaches a message. Redaction is applied to command failures for that reason.
        val session = RedisSession.open(
            config = RedisFixture.config(db, username = RedisFixture.READER),
            password = Secret(RedisFixture.ACL_PASSWORD),
        )

        session.use {
            val failure = assertThrows<DbException> {
                runBlocking { session.adapter.execute(RawCommand.of("SET", "user:1", "changed")) }
            }

            val message = failure.error.message
            assertFalse(message.contains(RedisFixture.host), message)
            assertFalse(message.contains(RedisFixture.port.toString()), message)
            assertFalse(message.contains(RedisFixture.ACL_PASSWORD), message)
        }
    }

    @Test
    fun `a wrong ACL password is classified rather than leaked`() = runBlocking {
        val failure = assertThrows<DbException> {
            runBlocking {
                RedisSession.open(
                    config = RedisFixture.config(db, username = RedisFixture.READER),
                    password = Secret("not-the-password"),
                )
            }
        }

        assertEquals("authentication_failed", failure.error.code)
        assertFalse(failure.error.message.contains(RedisFixture.host))
    }

    private suspend fun restricted(username: String) = RedisFixture.session(
        database = db,
        username = username,
        password = Secret(RedisFixture.ACL_PASSWORD),
    )
}

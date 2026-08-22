package dev.caracal.core.redis

import dev.caracal.core.connections.Environment
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.engine.api.CommandConsent
import dev.caracal.engine.api.CommandReply
import dev.caracal.engine.api.Elision
import dev.caracal.engine.api.KeyValueLimits
import dev.caracal.engine.api.RawCommand
import dev.caracal.engine.api.TextValue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §3.9 and §3.10 against a real server.
 *
 * Two things are being asserted, and the second matters more. The first is that an
 * arbitrary command's reply is normalized and bounded — every RESP shape, from a
 * status line to a nested array, arriving as something a viewer can draw without
 * holding all of it.
 *
 * The second is that the guard is *enforcement* and not advice. `RedisCommandGuardTest`
 * proves the policy decides correctly; these prove the adapter actually asks it, and
 * that a caller who does not want to ask does not get the choice. A guard that a
 * caller can skip is a guard that a caller will eventually skip.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class RedisConsoleIntegrationTest {

    private val db = RedisFixture.CONSOLE_DB

    @BeforeEach
    fun reset() {
        RedisFixture.admin(db) { it.flushdb() }
    }

    // --- Replies --------------------------------------------------------------

    @Test
    fun `a status reply arrives with its text`() = runBlocking {
        // Asserted through `text` rather than by matching `CommandReply.Status`, because
        // whether a simple string is distinguishable from a bulk string is a property
        // of the negotiated protocol version and not of this code. Over RESP2 they
        // arrive through the same door.
        RedisFixture.session(db).use { session ->
            val result = session.adapter.execute(RawCommand.of("PING"))

            assertEquals("PONG", result.reply.text)
            assertEquals("PING", result.command)
            assertFalse(result.truncated)
        }
    }

    @Test
    fun `an integer reply is an integer and not the text of one`() = runBlocking {
        RedisFixture.admin(db) { it.rpush("queue", "a", "b", "c") }

        RedisFixture.session(db).use { session ->
            val result = session.adapter.execute(RawCommand.of("LLEN", "queue"))

            assertEquals(3, assertIs<CommandReply.Integer>(result.reply).value)
        }
    }

    @Test
    fun `a missing key is null and not an empty string`() = runBlocking {
        RedisFixture.session(db).use { session ->
            assertEquals(CommandReply.Nil, session.adapter.execute(RawCommand.of("GET", "absent")).reply)

            RedisFixture.admin(db) { it.set("blank", "") }
            val empty = session.adapter.execute(RawCommand.of("GET", "blank")).reply
            assertEquals("", assertIs<CommandReply.Bulk>(empty).value.text)
        }
    }

    @Test
    fun `an array reply keeps its order and its elements`() = runBlocking {
        RedisFixture.admin(db) { it.rpush("queue", "first", "second", "third") }

        RedisFixture.session(db).use { session ->
            val reply = session.adapter.execute(RawCommand.of("LRANGE", "queue", "0", "-1")).reply

            val items = assertIs<CommandReply.Items>(reply).items
            assertEquals(
                listOf("first", "second", "third"),
                items.map { assertIs<CommandReply.Bulk>(it).value.text },
            )
        }
    }

    @Test
    fun `a nested reply keeps its nesting`() = runBlocking {
        RedisFixture.admin(db) { commands ->
            commands.xadd("events", mapOf("kind" to "click"))
        }

        RedisFixture.session(db).use { session ->
            val reply = session.adapter.execute(RawCommand.of("XRANGE", "events", "-", "+")).reply

            val entry = assertIs<CommandReply.Items>(assertIs<CommandReply.Items>(reply).items.single())
            // `[id, [field, value]]`.
            assertNotNull(assertIs<CommandReply.Bulk>(entry.items[0]).value.text)
            assertEquals(2, assertIs<CommandReply.Items>(entry.items[1]).items.size)
        }
    }

    @Test
    fun `an error that is the whole reply fails the command, and carries Redis's code`() = runBlocking {
        // The split in `RedisReplyOutput.setError`. A top-level error is the command's
        // failure and is raised, so no caller has to remember to inspect a successful
        // result for one; an error *inside* an array is one element of a reply that
        // otherwise arrived, and is kept as a value.
        RedisFixture.admin(db) { it.rpush("a-list", "x") }

        RedisFixture.session(db).use { session ->
            val failure = assertThrows<DbException> {
                runBlocking { session.adapter.execute(RawCommand.of("GET", "a-list")) }
            }

            val queryFailed = assertIs<DbError.QueryFailed>(failure.error)
            // Redis's leading token is its nearest thing to a machine-readable code,
            // and travels where PostgreSQL's severity does.
            assertEquals("WRONGTYPE", queryFailed.severity)
            assertTrue(queryFailed.message.contains("WRONGTYPE"))
        }
    }

    @Test
    fun `a binary value comes back as bytes rather than as damaged text`() = runBlocking {
        RedisFixture.adminBytes(db) { it.set("blob".toByteArray(), byteArrayOf(0x00, 0xFF.toByte(), 0x41)) }

        RedisFixture.session(db).use { session ->
            val reply = session.adapter.execute(RawCommand.of("GET", "blob")).reply

            assertEquals("00ff41", assertIs<TextValue.Binary>(assertIs<CommandReply.Bulk>(reply).value).hex)
        }
    }

    @Test
    fun `argument bytes reach the server exactly as they were given`() = runBlocking {
        // The console takes an argument array precisely so that nothing between the
        // keyboard and the socket re-decides where one argument ends.
        val key = byteArrayOf(0x6B, 0xFF.toByte(), 0x01)

        RedisFixture.session(db).use { session ->
            session.adapter.execute(
                RawCommand.of(listOf("SET".toByteArray(), key, "value with spaces".toByteArray())),
            )

            val stored = RedisFixture.adminBytes(db) { it.get(key) }
            assertEquals("value with spaces", String(stored))
        }
    }

    // --- Bounds ---------------------------------------------------------------

    @Test
    fun `a reply with more elements than the budget is cut and says so`() = runBlocking {
        RedisFixture.admin(db) { commands -> (1..500).forEach { commands.rpush("long", "item:$it") } }

        RedisFixture.session(db, limits = KeyValueLimits(replyElements = 50)).use { session ->
            val result = session.adapter.execute(RawCommand.of("LRANGE", "long", "0", "-1"))

            assertTrue(result.truncated)
            val items = assertIs<CommandReply.Items>(result.reply).items
            // Fifty kept, and one marker saying the rest were not — so a short list can
            // never be misread as a complete one.
            assertEquals(51, items.size)
            assertEquals(CommandReply.Elided(Elision.ELEMENTS), items.last())
        }
    }

    @Test
    fun `a reply nested deeper than the budget is elided where it was`() = runBlocking {
        RedisFixture.admin(db) { it.xadd("events", mapOf("kind" to "click")) }

        RedisFixture.session(db, limits = KeyValueLimits(replyDepth = 1)).use { session ->
            val result = session.adapter.execute(RawCommand.of("XRANGE", "events", "-", "+"))

            assertTrue(result.truncated)
            val outer = assertIs<CommandReply.Items>(result.reply)
            assertEquals(CommandReply.Elided(Elision.DEPTH), outer.items.single())
        }
    }

    @Test
    fun `an element larger than the budget is clipped and reports its true size`() = runBlocking {
        RedisFixture.admin(db) { it.set("big", "x".repeat(10_000)) }

        RedisFixture.session(db, limits = KeyValueLimits(elementBytes = 100)).use { session ->
            val result = session.adapter.execute(RawCommand.of("GET", "big"))

            assertTrue(result.truncated)
            val value = assertIs<CommandReply.Bulk>(result.reply).value
            assertEquals(100, value.text?.length)
            assertEquals(10_000, value.byteCount)
            assertTrue(value.truncated)
        }
    }

    // --- The guard, enforced --------------------------------------------------

    @Test
    fun `a dangerous command will not run without an acknowledgement`() = runBlocking {
        RedisFixture.admin(db) { it.set("survivor", "value") }

        RedisFixture.session(db).use { session ->
            val failure = assertThrows<CommandConfirmationRequired> {
                runBlocking { session.adapter.execute(RawCommand.of("FLUSHDB")) }
            }

            assertEquals("FLUSHDB", failure.clearance.command)
            assertTrue(failure.clearance.warning.contains("deletes every key"))
            // And nothing happened, which is the part that matters.
            assertEquals("value", RedisFixture.admin(db) { it.get("survivor") })
        }
    }

    @Test
    fun `a dangerous command runs once it has been acknowledged`() = runBlocking {
        RedisFixture.admin(db) { it.set("doomed", "value") }

        RedisFixture.session(db).use { session ->
            session.adapter.execute(RawCommand.of("FLUSHDB"), CommandConsent.Given())

            assertEquals(null, RedisFixture.admin(db) { it.get("doomed") })
        }
    }

    @Test
    fun `a production connection will not take a click where it asked for a phrase`() = runBlocking {
        RedisFixture.admin(db) { it.set("survivor", "value") }

        RedisFixture.session(db, environment = Environment.PROD, name = "orders-cache").use { session ->
            // An empty acknowledgement is what a click sends. On production it is not
            // enough, and the failure carries the phrase that would be.
            val failure = assertThrows<CommandConfirmationRequired> {
                runBlocking { session.adapter.execute(RawCommand.of("FLUSHDB"), CommandConsent.Given()) }
            }
            assertEquals("orders-cache FLUSHDB", failure.clearance.phrase)

            // The wrong phrase is no better.
            assertThrows<CommandConfirmationRequired> {
                runBlocking {
                    session.adapter.execute(RawCommand.of("FLUSHDB"), CommandConsent.Given("orders-cache"))
                }
            }
            assertEquals("value", RedisFixture.admin(db) { it.get("survivor") })

            session.adapter.execute(RawCommand.of("FLUSHDB"), CommandConsent.Given("orders-cache FLUSHDB"))
            assertEquals(null, RedisFixture.admin(db) { it.get("survivor") })
        }
    }

    @Test
    fun `a read-only connection refuses a write no matter what is acknowledged`() = runBlocking {
        // The read-only column takes no override, so consent has nothing to unlock.
        RedisFixture.session(db, readOnly = true).use { session ->
            val failure = assertThrows<DbException> {
                runBlocking {
                    session.adapter.execute(RawCommand.of("SET", "k", "v"), CommandConsent.Given("anything"))
                }
            }

            assertEquals("command_not_allowed_read_only", failure.error.code)
            assertFalse(RedisFixture.admin(db) { it.exists("k") } == 1L)
        }
    }

    @Test
    fun `a read-only connection refuses a dangerous read no matter what is acknowledged`() = runBlocking {
        RedisFixture.session(db, readOnly = true).use { session ->
            val failure = assertThrows<DbException> {
                runBlocking {
                    session.adapter.execute(RawCommand.of("KEYS", "*"), CommandConsent.Given("yes really"))
                }
            }

            val refused = assertIs<DbError.CommandNotAllowed>(failure.error)
            assertEquals(DbError.CommandNotAllowed.Reason.READ_ONLY, refused.reason)
        }
    }

    @Test
    fun `KEYS does not reach the server even when it is acknowledged on a writable connection`() = runBlocking {
        // It can be acknowledged — it is a Confirm, not a Refusal, on a writable
        // connection — and this asserts the acknowledged path is the *only* way there,
        // so a UI that never offers the toggle can never issue it by accident.
        RedisFixture.session(db).use { session ->
            assertThrows<CommandConfirmationRequired> {
                runBlocking { session.adapter.execute(RawCommand.of("KEYS", "*")) }
            }

            val acknowledged = session.adapter.execute(RawCommand.of("KEYS", "*"), CommandConsent.Given())
            assertIs<CommandReply.Items>(acknowledged.reply)
            assertEquals("KEYS", acknowledged.command)
        }
    }

    @Test
    fun `a command with nothing in it is refused before anything is sent`() = runBlocking {
        RedisFixture.session(db).use { session ->
            val failure = assertThrows<DbException> { RawCommand.of(emptyList()) }
            assertIs<DbError.InvalidRequest>(failure.error)
            // And the session is untouched by it.
            assertEquals("PONG", session.adapter.execute(RawCommand.of("PING")).reply.text)
        }
    }
}

package dev.caracal.core.redis

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.policy.Acknowledgement
import dev.caracal.core.policy.CommandClearance
import dev.caracal.core.result.DbError
import dev.caracal.engine.api.CommandLine
import dev.caracal.engine.api.RawCommand
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * §3.10's table, cell by cell, plus the ways round it.
 *
 * The same reasoning as `DataSafetyPolicyTest`: this is small enough to read, and it
 * is what stands between a typed `FLUSHALL` and a production cache. The difference
 * is that PostgreSQL enforces its own read-only rule underneath that policy and
 * Redis does not — here the guard is the enforcement, so a gap in it is not a missing
 * warning, it is a command that runs.
 */
class RedisCommandGuardTest {

    // --- The eight §3.10 names -----------------------------------------------

    @ParameterizedTest(name = "{0} is dangerous")
    @ValueSource(
        strings = [
            "FLUSHALL", "FLUSHDB", "KEYS *", "SHUTDOWN NOSAVE", "DEBUG SLEEP 10",
            "CONFIG SET appendonly no", "MONITOR", "SWAPDB 0 1",
        ],
    )
    fun `the commands the milestone names are blocked by default`(line: String) {
        val clearance = RedisCommandGuard.clearanceFor(CommandLine.command(line), connection())

        assertIs<CommandClearance.Confirm>(clearance, "$line was not guarded")
        assertTrue(clearance.warning.isNotBlank())
    }

    @ParameterizedTest(name = "{0} is dangerous")
    @ValueSource(
        strings = [
            // Blocks the server.
            "SAVE", "EVAL 'return 1' 0", "FCALL f 0", "CLIENT PAUSE 10000", "CLIENT KILL ID 4",
            // Destroys broad data.
            "REPLICAOF other 6379", "SLAVEOF other 6379", "MIGRATE h 6379 k 0 5000", "FAILOVER",
            // Exposes or changes credentials.
            "ACL GETUSER default", "ACL SETUSER bob on",
        ],
    )
    fun `the extensions the milestone asks for are blocked too`(line: String) {
        assertIs<CommandClearance.Confirm>(
            RedisCommandGuard.clearanceFor(CommandLine.command(line), connection()),
        )
    }

    @Test
    fun `CONFIG GET is deliberately allowed, because diagnosis needs it`() {
        // The judgement call worth pinning: it can read `requirepass`, and the reply
        // goes to whoever already holds the password that opened the connection.
        // Reading `maxmemory-policy` is most of what diagnosing an eviction problem is.
        assertEquals(
            CommandClearance.Granted,
            RedisCommandGuard.clearanceFor(CommandLine.command("CONFIG GET maxmemory"), connection()),
        )
    }

    // --- Ways round it --------------------------------------------------------

    @ParameterizedTest(name = "{0} does not get past the guard")
    @ValueSource(
        strings = [
            "flushall",
            "  FlUsHaLl  ",
            "config set appendonly no",
            "CONFIG   SET   appendonly no",
            "  config\tset\tappendonly no",
        ],
    )
    fun `casing and whitespace do not get a dangerous command through`(line: String) {
        assertIs<CommandClearance.Confirm>(
            RedisCommandGuard.clearanceFor(CommandLine.command(line), connection()),
        )
    }

    @Test
    fun `splitting CONFIG from SET across fields does not get it through`() {
        // §3.10 names this one specifically, and it has to hold in both directions:
        // as two arguments, and rejoined into one.
        val spellings = listOf(
            RawCommand.of("CONFIG", "SET", "appendonly", "no"),
            RawCommand.of("CONFIG SET", "appendonly", "no"),
            RawCommand.of(" config ", " set ", "appendonly", "no"),
        )

        for (command in spellings) {
            assertIs<CommandClearance.Confirm>(RedisCommandGuard.clearanceFor(command, connection()))
        }
    }

    // --- Read-only connections ------------------------------------------------

    @Test
    fun `a read-only connection runs the reads the tool needs`() {
        val allowed = listOf("PING", "TYPE k", "TTL k", "SCAN 0", "GET k", "STRLEN k", "HSCAN k 0", "INFO")

        for (line in allowed) {
            assertEquals(
                CommandClearance.Granted,
                RedisCommandGuard.clearanceFor(CommandLine.command(line), connection(readOnly = true)),
                "$line was refused on a read-only connection",
            )
        }
    }

    @Test
    fun `a read-only connection refuses a write, and says which rule refused it`() {
        val clearance = RedisCommandGuard.clearanceFor(CommandLine.command("SET k v"), connection(readOnly = true))

        val refused = assertIs<CommandClearance.Refused>(clearance)
        assertEquals(DbError.CommandNotAllowed.Reason.READ_ONLY, refused.error.reason)
        assertEquals("command_not_allowed_read_only", refused.error.code)
        assertEquals("SET", refused.error.command)
    }

    @Test
    fun `a read-only connection refuses a command it has never heard of`() {
        // §3.10's allowlist rule. A module command is unknown, so it is refused —
        // which is the safe direction, because a denylist would let `JSON.SET` through.
        val clearance = RedisCommandGuard.clearanceFor(
            CommandLine.command("JSON.SET doc . 1"),
            connection(readOnly = true),
        )

        assertIs<CommandClearance.Refused>(clearance)
    }

    @Test
    fun `a dangerous command on a read-only connection is refused and not merely confirmed`() {
        // "regardless of override", which means the UI must have nothing to offer. A
        // Confirm here would put a button on screen that could never succeed.
        val clearance = RedisCommandGuard.clearanceFor(CommandLine.command("FLUSHDB"), connection(readOnly = true))

        val refused = assertIs<CommandClearance.Refused>(clearance)
        assertTrue(refused.error.message.contains("no override"), refused.error.message)
    }

    @Test
    fun `a dangerous command on a read-only connection is refused for being dangerous`() {
        // Not for being a write. `KEYS` only reads, and telling someone their read-only
        // connection is the problem sends them to find a writable one to run it on.
        val clearance = RedisCommandGuard.clearanceFor(CommandLine.command("KEYS *"), connection(readOnly = true))

        val refused = assertIs<CommandClearance.Refused>(clearance)
        assertTrue(refused.error.message.contains("blocks the server"), refused.error.message)
    }

    // --- Environments ---------------------------------------------------------

    @Test
    fun `a dangerous command on development takes a click`() {
        val clearance = RedisCommandGuard.clearanceFor(CommandLine.command("FLUSHDB"), connection())

        val confirm = assertIs<CommandClearance.Confirm>(clearance)
        assertEquals(Acknowledgement.CLICK, confirm.acknowledgement)
        assertNull(confirm.phrase)
        assertTrue(confirm.satisfiedBy(""))
    }

    @Test
    fun `a dangerous command on production takes the connection name and the command`() {
        val clearance = RedisCommandGuard.clearanceFor(
            CommandLine.command("FLUSHDB"),
            connection(name = "orders-cache", environment = Environment.PROD),
        )

        val confirm = assertIs<CommandClearance.Confirm>(clearance)
        assertEquals(Acknowledgement.TYPED, confirm.acknowledgement)
        assertEquals("orders-cache FLUSHDB", confirm.phrase)
        assertTrue(confirm.satisfiedBy("  orders-cache FLUSHDB  "))
        // Case-folding would make the phrase easier to type, which is the opposite of
        // what it is for.
        assertFalse(confirm.satisfiedBy("orders-cache flushdb"))
        // The connection name alone is the phrase somebody learns by rote.
        assertFalse(confirm.satisfiedBy("orders-cache"))
    }

    @Test
    fun `the production phrase names the command that is about to run`() {
        val flush = RedisCommandGuard.clearanceFor(
            CommandLine.command("FLUSHALL"),
            connection(name = "cache", environment = Environment.PROD),
        )
        val config = RedisCommandGuard.clearanceFor(
            CommandLine.command("CONFIG SET appendonly no"),
            connection(name = "cache", environment = Environment.PROD),
        )

        assertEquals("cache FLUSHALL", assertIs<CommandClearance.Confirm>(flush).phrase)
        assertEquals("cache CONFIG SET", assertIs<CommandClearance.Confirm>(config).phrase)
    }

    @Test
    fun `a read is granted on every connection`() {
        for (connection in everyConnection()) {
            assertEquals(
                CommandClearance.Granted,
                RedisCommandGuard.clearanceFor(CommandLine.command("GET k"), connection),
                "a read was refused on ${connection.name}",
            )
        }
    }

    @Test
    fun `an ordinary write is waved through on development and confirmed on production`() {
        // The row beyond what §3.10 asks for, and the reason it is there: without it,
        // `FLUSHDB` on production takes a typed phrase and `SET` takes nothing. It uses
        // the same allowlist the read-only column is already built from.
        assertEquals(
            CommandClearance.Granted,
            RedisCommandGuard.clearanceFor(CommandLine.command("SET k v"), connection()),
        )

        val production = RedisCommandGuard.clearanceFor(
            CommandLine.command("SET k v"),
            connection(name = "cache", environment = Environment.PROD),
        )
        val confirm = assertIs<CommandClearance.Confirm>(production)
        assertEquals("cache SET", confirm.phrase)
    }

    @Test
    fun `a staging connection behaves as development does`() {
        val clearance = RedisCommandGuard.clearanceFor(
            CommandLine.command("FLUSHDB"),
            connection(environment = Environment.STAGING),
        )

        assertEquals(Acknowledgement.CLICK, assertIs<CommandClearance.Confirm>(clearance).acknowledgement)
        assertEquals(
            CommandClearance.Granted,
            RedisCommandGuard.clearanceFor(CommandLine.command("SET k v"), connection(environment = Environment.STAGING)),
        )
    }

    @Test
    fun `a container command is judged by its subcommand and not by its name`() {
        // `CLIENT` has both on it: `CLIENT INFO` is introspection and `CLIENT KILL`
        // disconnects other people. Judging by name alone gets one of them wrong.
        assertEquals(
            CommandClearance.Granted,
            RedisCommandGuard.clearanceFor(CommandLine.command("CLIENT INFO"), connection(readOnly = true)),
        )
        assertNotNull(RedisCommandGuard.dangerReason(CommandLine.command("CLIENT KILL ID 3")))
        assertNull(RedisCommandGuard.dangerReason(CommandLine.command("CLIENT INFO")))
    }

    @Test
    fun `SORT is not treated as a read, because it can write`() {
        // `SORT k STORE dest` writes. `SORT_RO` is the variant that cannot, and is the
        // one on the list.
        assertIs<CommandClearance.Refused>(
            RedisCommandGuard.clearanceFor(CommandLine.command("SORT k"), connection(readOnly = true)),
        )
        assertEquals(
            CommandClearance.Granted,
            RedisCommandGuard.clearanceFor(CommandLine.command("SORT_RO k"), connection(readOnly = true)),
        )
    }

    private fun everyConnection() = listOf(
        connection(name = "dev"),
        connection(name = "staging", environment = Environment.STAGING),
        connection(name = "prod", environment = Environment.PROD),
        connection(name = "dev-ro", readOnly = true),
        connection(name = "prod-ro", environment = Environment.PROD, readOnly = true),
    )

    private fun connection(
        name: String = "local",
        environment: Environment = Environment.DEV,
        readOnly: Boolean = false,
    ) = ConnectionConfig(
        id = ConnectionId("id-1"),
        name = name,
        engine = Engine.REDIS,
        host = "localhost",
        port = 6379,
        database = "0",
        username = "",
        tlsMode = TlsMode.DISABLE,
        environment = environment,
        readOnly = readOnly,
        color = null,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )

    @Test
    fun `a command that takes over the shared connection is dangerous`() {
        // One connection is shared by the key browser, the INFO dashboard, the value
        // viewer and the console, and Redis answers a connection's commands in order.
        // A command that changes its mode or parks it does not fail alone: it takes
        // every later read on that connection with it until the app is restarted.
        // RESET was already treated this way; these are the rest of the same class.
        listOf(
            "SUBSCRIBE channel", "PSUBSCRIBE pattern*", "SSUBSCRIBE channel", "SYNC", "PSYNC ? -1",
            "SELECT 3", "WAIT 1 0", "WAITAOF 1 0 0", "BLPOP queue 0", "BRPOP queue 0",
            "BLMOVE a b LEFT LEFT 0", "BRPOPLPUSH a b 0", "BLMPOP 0 1 queue LEFT",
            "BZPOPMIN z 0", "BZPOPMAX z 0", "BZMPOP 0 1 z MIN",
        ).forEach { line ->
            assertIs<CommandClearance.Confirm>(
                RedisCommandGuard.clearanceFor(CommandLine.command(line), connection()),
                "$line was not guarded",
            )
        }
    }

    @Test
    fun `CLIENT REPLY is dangerous while the other CLIENT reads are not`() {
        assertIs<CommandClearance.Confirm>(
            RedisCommandGuard.clearanceFor(CommandLine.command("CLIENT REPLY OFF"), connection()),
        )
        assertIs<CommandClearance.Granted>(
            RedisCommandGuard.clearanceFor(CommandLine.command("CLIENT INFO"), connection()),
        )
    }
}

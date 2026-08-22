package dev.caracal.engine.redis

import dev.caracal.engine.api.WriteIntent
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Section 7's per-engine half for Redis, which is a different shape from
 * PostgreSQL's.
 *
 * The shape itself is the property worth protecting. The guard is an allowlist of
 * commands known to only read, so "not on the list" means *we do not know* — and the
 * classifier has to say so rather than guessing at [WriteIntent.WRITE]. A guess
 * would be indistinguishable from knowledge by the time core reads it, and Redis
 * gains commands with every release and with every module somebody loads.
 */
class RedisIntentTest {

    @Test
    fun `a command on the allowlist reads`() {
        listOf("GET user:1", "TTL user:1", "SCAN 0 MATCH user:*", "HGET h f", "TYPE k").forEach { line ->
            assertEquals(WriteIntent.READ_ONLY, RedisIntent.classify(line), line)
        }
    }

    @Test
    fun `casing and spacing do not change the answer`() {
        listOf("get user:1", "  GeT   user:1  ").forEach { line ->
            assertEquals(WriteIntent.READ_ONLY, RedisIntent.classify(line), line)
        }
    }

    @Test
    fun `a dangerous command is destructive`() {
        listOf("FLUSHALL", "FLUSHDB", "KEYS *", "SHUTDOWN").forEach { line ->
            assertEquals(WriteIntent.DESTRUCTIVE, RedisIntent.classify(line), line)
        }
    }

    @Test
    fun `a dangerous command in lower case is still destructive`() {
        assertEquals(WriteIntent.DESTRUCTIVE, RedisIntent.classify("flushall"))
        assertEquals(WriteIntent.DESTRUCTIVE, RedisIntent.classify("  keys   *  "))
    }

    @Test
    fun `an ordinary write is unknown rather than assumed`() {
        // SET is not on the read allowlist and is not dangerous. The honest answer is
        // that this classifier does not know, which is what makes core refuse it on a
        // read-only connection instead of merely labelling it.
        listOf("SET k v", "DEL k", "LPUSH list v").forEach { line ->
            assertEquals(WriteIntent.UNKNOWN, RedisIntent.classify(line), line)
        }
    }

    @Test
    fun `a command nobody has heard of is unknown, and never a read`() {
        listOf("JSON.SET doc . {}", "FT.SEARCH idx *", "MODULE.NONSENSE").forEach { line ->
            val intent = RedisIntent.classify(line)
            assertNotEquals(WriteIntent.READ_ONLY, intent, line)
            assertEquals(WriteIntent.UNKNOWN, intent, line)
        }
    }

    @Test
    fun `a line with no command in it is unknown`() {
        listOf("", "   ").forEach { line ->
            assertEquals(WriteIntent.UNKNOWN, RedisIntent.classify(line), line)
        }
    }

    @Test
    fun `an unterminated quote does not throw its way out of a classifier`() {
        // The console hands over whatever was typed, half-finished included. A
        // classifier that threw here would take the safety check with it.
        assertEquals(WriteIntent.UNKNOWN, RedisIntent.classify("GET \"unterminated"))
    }

    @Test
    fun `a read subcommand of a container command reads`() {
        assertEquals(WriteIntent.READ_ONLY, RedisIntent.classify("OBJECT ENCODING k"))
    }
}

package dev.caracal.core.redis

import dev.caracal.engine.api.DatabaseKeyspace
import dev.caracal.engine.api.ServerInfo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

/**
 * §3.8's parsing, and what it does with a document that is missing half of itself.
 *
 * `INFO` is a different reply on every Redis version, under every ACL, and in every
 * deployment mode. So most of what is asserted here is about absence: a restricted
 * user's reply, a section that is not there, a field a later release renamed. The
 * dashboard has to lose a card, not the workspace.
 */
class RedisInfoTest {

    @Test
    fun `the fields the dashboard shows are read`() {
        val info = RedisInfo.parse(SAMPLE)

        assertEquals("7.2.4", info.version)
        assertEquals("standalone", info.mode)
        assertEquals("master", info.role)
        assertEquals(1_209_600.seconds, info.uptime)
        assertEquals(12, info.connectedClients)
        assertEquals(2, info.connectedReplicas)
        assertEquals(1_048_576, info.usedMemoryBytes)
        assertEquals(4_294_967_296, info.maxMemoryBytes)
        assertEquals("allkeys-lru", info.maxMemoryPolicy)
        assertEquals(9_000, info.keyspaceHits)
        assertEquals(1_000, info.keyspaceMisses)
        assertEquals(4_231_889, info.totalCommands)
        assertEquals(317, info.opsPerSecond)
    }

    @Test
    fun `the keyspace section becomes one row per database, in index order`() {
        val info = RedisInfo.parse(SAMPLE)

        assertEquals(
            listOf(DatabaseKeyspace(0, keys = 1_500, expires = 200), DatabaseKeyspace(3, keys = 7, expires = 0)),
            info.databases,
        )
        assertEquals(1_507, info.totalKeys)
    }

    @Test
    fun `the hit rate is guarded against a server that has served nothing`() {
        // The whole reason §3.8 asks for a guard. An idle server has nought hits and
        // nought misses, and the obvious division reports 0% — which reads as a broken
        // cache, on the screen someone opens when they suspect a broken cache.
        val idle = RedisInfo.parse("keyspace_hits:0\nkeyspace_misses:0")

        assertNull(idle.hitRate)
        assertEquals(0.9, RedisInfo.parse(SAMPLE).hitRate)
    }

    @Test
    fun `a hit rate needs both halves`() {
        assertNull(RedisInfo.parse("keyspace_hits:5").hitRate)
        assertNull(RedisInfo.parse("keyspace_misses:5").hitRate)
    }

    @Test
    fun `no maximum is reported as no maximum, not as a maximum of nothing`() {
        // Redis writes `maxmemory:0` when it has no limit. A zero on a memory gauge is
        // a server that can hold nothing.
        assertNull(RedisInfo.parse("maxmemory:0").maxMemoryBytes)
        assertEquals(64, RedisInfo.parse("maxmemory:64").maxMemoryBytes)
    }

    @Test
    fun `a section that is absent leaves its fields null rather than zero`() {
        // A restricted user gets a reply with whole sections missing, and "nought
        // clients connected" and "not allowed to know" must not look the same.
        val partial = RedisInfo.parse("# Server\nredis_version:7.2.4\nredis_mode:standalone")

        assertEquals("7.2.4", partial.version)
        assertNull(partial.connectedClients)
        assertNull(partial.usedMemoryBytes)
        assertNull(partial.totalCommands)
        assertTrue(partial.databases.isEmpty())
    }

    @Test
    fun `fields this build has never heard of are ignored rather than shown`() {
        // Version-independence, and a disclosure rule: INFO reports `executable` and
        // `config_file`, which are paths on the server, and the project's own rule
        // says those never reach a UI or a log. An allowlist keeps them out by
        // construction.
        val info = RedisInfo.parse(
            """
            # Server
            redis_version:7.2.4
            executable:/usr/local/bin/redis-server
            config_file:/etc/redis/redis.conf
            some_future_field:42
            """.trimIndent(),
        )

        assertEquals("7.2.4", info.version)
        assertFalse(info.toString().contains("/etc/redis"), info.toString())
    }

    @Test
    fun `values containing colons keep everything after the first one`() {
        // `db0:keys=…` is one; a value that is an address is another, and splitting on
        // every colon would lose most of it.
        assertEquals(listOf(DatabaseKeyspace(0, keys = 3, expires = 1)), RedisInfo.parse("db0:keys=3,expires=1,avg_ttl=0").databases)
    }

    @Test
    fun `a keyspace line without a key count is skipped rather than guessed at`() {
        assertTrue(RedisInfo.parse("db0:expires=1,avg_ttl=0").databases.isEmpty())
    }

    @Test
    fun `Redis's carriage returns do not end up inside the values`() {
        // INFO is CRLF-terminated on the wire. A version of "7.2.4\r" compares unequal
        // to every version anyone would test for.
        val info = RedisInfo.parse("# Server\r\nredis_version:7.2.4\r\nconnected_clients:3\r\n")

        assertEquals("7.2.4", info.version)
        assertEquals(3, info.connectedClients)
    }

    @Test
    fun `a reply that said nothing produces a summary that knows it`() {
        assertTrue(RedisInfo.parse("").isEmpty)
        assertTrue(ServerInfo(restricted = true).isEmpty)
        assertNull(ServerInfo(restricted = true).totalKeys)
        assertFalse(RedisInfo.parse(SAMPLE).isEmpty)
    }

    private companion object {
        val SAMPLE = """
            # Server
            redis_version:7.2.4
            redis_mode:standalone
            uptime_in_seconds:1209600

            # Clients
            connected_clients:12

            # Memory
            used_memory:1048576
            used_memory_human:1.00M
            maxmemory:4294967296
            maxmemory_policy:allkeys-lru

            # Stats
            total_commands_processed:4231889
            instantaneous_ops_per_sec:317
            keyspace_hits:9000
            keyspace_misses:1000

            # Replication
            role:master
            connected_slaves:2

            # Keyspace
            db3:keys=7,expires=0,avg_ttl=0
            db0:keys=1500,expires=200,avg_ttl=0
        """.trimIndent()
    }
}

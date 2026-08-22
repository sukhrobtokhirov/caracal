package dev.caracal.core.redis

import dev.caracal.engine.api.KeyMetadata
import dev.caracal.engine.api.KeyRef
import dev.caracal.engine.api.KeyType
import dev.caracal.engine.api.KeyValueLimits
import dev.caracal.engine.api.MemoryEstimate
import dev.caracal.engine.api.ScanCursor
import dev.caracal.engine.api.ScanStop
import dev.caracal.engine.api.TextValue
import dev.caracal.engine.api.Ttl
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * §3.2 and §3.3 against a real server: bounded traversal, pipelined metadata.
 *
 * Against a real Redis because every claim is about Redis's own behaviour rather
 * than this code's. Whether `MATCH` produces empty batches, whether a cursor comes
 * back non-zero after a batch that matched nothing, whether `TYPE` says `none` for a
 * key that just expired — these are the server's properties, and a fake would encode
 * whatever this code already believes about them.
 *
 * The suite that matters most is [browsing never issues a command that blocks the
 * server], which reads the server's own log of what it was asked.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class RedisBrowseIntegrationTest {

    private val db = RedisFixture.BROWSE_DB

    @BeforeEach
    fun reset() {
        RedisFixture.admin(db) { it.flushdb() }
    }

    // --- The loop -------------------------------------------------------------

    @Test
    fun `a traversal ends only when the server says it has come round`() = runBlocking {
        seed(50) { "user:$it" }

        RedisFixture.session(db).use { session ->
            val page = session.adapter.scan()

            assertEquals(ScanStop.COMPLETE, page.stopped)
            assertTrue(page.complete)
            assertTrue(page.cursor.isComplete)
            assertEquals(50, page.keys.size)
        }
    }

    @Test
    fun `an empty batch does not end a traversal`() = runBlocking {
        // The failure §3.2 is emphatic about. `MATCH` filters on the server *after* it
        // has walked a bucket, so a selective pattern over a large keyspace produces
        // batch after batch of nothing while the cursor advances perfectly normally.
        // Treating the first empty batch as the end finds no keys and reports success.
        seed(2_000) { "filler:$it" }
        RedisFixture.admin(db) { it.set("needle:1", "v") }

        RedisFixture.session(db, limits = KeyValueLimits(scanCount = 20, scanIterations = 5)).use { session ->
            var cursor = ScanCursor.START
            var found = 0
            var pages = 0
            var complete = false
            while (!complete && pages < 500) {
                val page = session.adapter.scan(cursor = cursor, match = "needle:*")
                found += page.keys.size
                cursor = page.cursor
                complete = page.complete
                pages++
            }

            assertTrue(complete, "the traversal never reported completion in $pages pages")
            assertEquals(1, found, "the one matching key was not found in $pages pages")
            assertTrue(pages > 1, "a keyspace of 2000 keys was traversed in one page")
        }
    }

    @Test
    fun `a page stops on its budget without ending the traversal`() = runBlocking {
        seed(500) { "user:$it" }

        RedisFixture.session(db, limits = KeyValueLimits(keysPerPage = 20, scanCount = 10)).use { session ->
            val page = session.adapter.scan()

            assertEquals(ScanStop.PAGE_FULL, page.stopped)
            assertFalse(page.complete)
            assertFalse(page.cursor.isComplete)
            assertTrue(page.keys.size >= 20, "asked for 20 and got ${page.keys.size}")
        }
    }

    @Test
    fun `a scan over a keyspace nothing matches gives up rather than walking all of it`() = runBlocking {
        // Replacing KEYS with SCAN only helps if the client stops. A SCAN loop run to
        // completion over a keyspace nothing matches in is KEYS with more round trips.
        seed(5_000) { "filler:$it" }

        RedisFixture.session(db, limits = KeyValueLimits(scanIterations = 3, scanCount = 10)).use { session ->
            val page = session.adapter.scan(match = "nothing-matches-this:*")

            assertEquals(ScanStop.ITERATION_BUDGET, page.stopped)
            assertEquals(3, page.iterations)
            assertTrue(page.keys.isEmpty())
            // And an empty page is a successful result that can be continued from.
            assertFalse(page.cursor.isComplete)
        }
    }

    @Test
    fun `continuing from a returned cursor eventually sees every key`() = runBlocking {
        val names = (1..300).map { "user:$it" }
        seed(names)

        RedisFixture.session(db, limits = KeyValueLimits(keysPerPage = 40, scanCount = 25)).use { session ->
            val seen = mutableSetOf<String>()
            var cursor = ScanCursor.START
            var pages = 0
            while (pages < 100) {
                val page = session.adapter.scan(cursor = cursor)
                page.keys.forEach { seen += it.key.text!! }
                cursor = page.cursor
                pages++
                if (page.complete) break
            }

            assertEquals(names.toSet(), seen)
        }
    }

    @Test
    fun `a page never lists the same key twice`() = runBlocking {
        // Redis returns duplicates whenever the hash table resizes mid-traversal. It is
        // documented behaviour, not a fault, and a browser that showed it would look
        // like it had a data problem.
        seed(400) { "user:$it" }

        RedisFixture.session(db, limits = KeyValueLimits(keysPerPage = 400, scanCount = 5)).use { session ->
            val page = session.adapter.scan()
            val names = page.keys.map { it.key }

            assertEquals(names.size, names.toSet().size)
        }
    }

    @Test
    fun `a glob is a glob and not a regular expression`() = runBlocking {
        RedisFixture.admin(db) { commands ->
            commands.set("user:1", "a")
            commands.set("user:12", "b")
            commands.set("order:1", "c")
        }

        RedisFixture.session(db).use { session ->
            assertEquals(
                setOf("user:1", "user:12"),
                session.adapter.scan(match = "user:*").keys.mapNotNull { it.key.text }.toSet(),
            )
            // `.` is a literal dot to Redis, so a pattern that would match everything as
            // a regular expression matches nothing here.
            assertTrue(session.adapter.scan(match = ".*").keys.isEmpty())
        }
    }

    // --- Metadata -------------------------------------------------------------

    @Test
    fun `each key arrives with its type, its TTL, and an estimate of its size`() = runBlocking {
        RedisFixture.admin(db) { commands ->
            commands.set("plain", "value")
            commands.set("expiring", "value")
            commands.expire("expiring", 600)
            commands.hset("a-hash", "f", "v")
            commands.rpush("a-list", "one")
            commands.sadd("a-set", "one")
            commands.zadd("a-zset", 1.0, "one")
            commands.xadd("a-stream", mapOf("f" to "v"))
        }

        RedisFixture.session(db).use { session ->
            val byName = session.adapter.scan().keys.associateBy { it.key.text }

            assertEquals(KeyType.STRING, byName["plain"]?.type)
            assertEquals(KeyType.HASH, byName["a-hash"]?.type)
            assertEquals(KeyType.LIST, byName["a-list"]?.type)
            assertEquals(KeyType.SET, byName["a-set"]?.type)
            assertEquals(KeyType.ZSET, byName["a-zset"]?.type)
            assertEquals(KeyType.STREAM, byName["a-stream"]?.type)

            assertEquals(Ttl.Persistent, byName["plain"]?.ttl)
            val remaining = assertIs<Ttl.ExpiresIn>(byName["expiring"]?.ttl)
            assertTrue(remaining.seconds in 500..600, "TTL was ${remaining.seconds}")

            val memory = assertIs<MemoryEstimate.Bytes>(byName["plain"]?.memory)
            assertTrue(memory.value > 0)
        }
    }

    @Test
    fun `a key that expires between the scan and the pipeline loses only itself`() = runBlocking {
        // §3.3's race, and it is a race the browser will lose regularly on a cache
        // whose whole purpose is to expire things. Failing the page over it would make
        // a busy server unbrowsable.
        seed(20) { "survivor:$it" }
        RedisFixture.admin(db) { commands ->
            commands.set("doomed", "value")
            // Expired but not yet collected, which is the state that produces the race.
            commands.pexpire("doomed", 1)
        }
        Thread.sleep(50)

        RedisFixture.session(db).use { session ->
            val page = session.adapter.scan()

            assertEquals(20, page.keys.count { it.exists }, "the surviving keys were lost too")
            // Whether the expired key is still listed at all is Redis's choice — it
            // depends on whether the lazy collector reached it first — so what is
            // asserted is that if it is listed, it is listed as gone rather than as a
            // key with a type of nothing.
            val doomed = page.keys.firstOrNull { it.key.text == "doomed" }
            if (doomed != null) {
                assertNull(doomed.type)
                assertEquals(Ttl.Gone, doomed.ttl)
                assertEquals(MemoryEstimate.Absent, doomed.memory)
            }
        }
    }

    @Test
    fun `a key that is not there reads as absent rather than as a failure`() = runBlocking {
        RedisFixture.session(db).use { session ->
            val metadata = session.adapter.metadata(KeyRef("never-existed".toByteArray()))

            assertFalse(metadata.exists)
            assertNull(metadata.type)
            assertEquals(Ttl.Gone, metadata.ttl)
        }
    }

    @Test
    fun `a key holding a type this build cannot open is listed and labelled`() = runBlocking {
        // Not reachable without a module, so this asserts the shape rather than a real
        // module type: a key whose type is not one of the six is still a key, and
        // dropping it from the browser would hide data that is there.
        val metadata = KeyMetadata(
            key = KeyRef("doc".toByteArray()),
            type = null,
            ttl = Ttl.Persistent,
            memory = MemoryEstimate.Bytes(64),
            unsupportedType = "ReJSON-RL",
        )

        assertTrue(metadata.exists)
        assertNull(metadata.type)
    }

    @Test
    fun `a type filter narrows the page to that type`() = runBlocking {
        RedisFixture.admin(db) { commands ->
            (1..10).forEach { commands.set("string:$it", "v") }
            (1..5).forEach { commands.hset("hash:$it", "f", "v") }
        }

        RedisFixture.session(db).use { session ->
            val hashes = session.adapter.scan(type = KeyType.HASH)

            assertEquals(5, hashes.keys.size)
            assertTrue(hashes.keys.all { it.type == KeyType.HASH })
        }
    }

    // --- Binary key names -----------------------------------------------------

    @Test
    fun `a key whose name is not text survives being listed and looked up again`() = runBlocking {
        // The reason the connection uses a byte codec. Decoding this name as UTF-8
        // replaces the undecodable bytes, and sending the replacement back finds
        // nothing — so the key would appear to vanish when clicked.
        val name = byteArrayOf(0x6B, 0xFF.toByte(), 0xFE.toByte(), 0x3A, 0x31)
        RedisFixture.adminBytes(db) { it.set(name, "value".toByteArray()) }

        RedisFixture.session(db).use { session ->
            val listed = session.adapter.scan().keys.single()

            assertContentEquals(name, listed.key.bytes)
            assertIs<TextValue.Binary>(listed.key.display)
            assertNull(listed.key.text)
            // And the listed key is usable: reading it back by the bytes that were
            // listed finds the value rather than nothing.
            assertEquals(KeyType.STRING, session.adapter.metadata(listed.key).type)
        }
    }

    // --- The rule the whole milestone is built on -----------------------------

    @Test
    fun `browsing never issues a command that blocks the server`() = runBlocking {
        // §3.3's testing note, done the way it suggests: read the server's own log of
        // what it was asked. `slowlog-log-slower-than 0` records every command, so this
        // is not a proxy for the claim, it is the claim.
        //
        // These are the commands whose cost is the size of the data. On Redis's single
        // thread, one of them is not a slow response for the person who ran it — it is
        // a stall for every other client of that server.
        val forbidden = setOf("KEYS", "HGETALL", "SMEMBERS", "LRANGE", "SORT", "DEBUG", "FLUSHDB", "FLUSHALL")
        seed(300) { "user:$it" }
        RedisFixture.admin(db) { commands ->
            commands.configSet("slowlog-log-slower-than", "0")
            commands.configSet("slowlog-max-len", "10000")
            commands.slowlogReset()
        }

        RedisFixture.session(db).use { session ->
            var cursor = ScanCursor.START
            repeat(5) {
                val page = session.adapter.scan(cursor = cursor, match = "user:*")
                cursor = page.cursor
                page.keys.firstOrNull()?.let { session.adapter.metadata(it.key) }
            }
        }

        val issued = RedisFixture.admin(db) { commands ->
            // A slow-log entry is `[id, timestamp, microseconds, [argv...], addr, name]`,
            // so the command name is the first argument of the fourth field.
            val entries = commands.slowlogGet(10_000).mapNotNull { entry ->
                ((entry as? List<*>)?.getOrNull(3) as? List<*>)?.firstOrNull()?.toString()?.uppercase()
            }
            commands.configSet("slowlog-log-slower-than", "10000")
            entries
        }

        assertTrue(issued.isNotEmpty(), "the slow log recorded nothing, so this asserted nothing")
        assertTrue(issued.contains("SCAN"), "browsing did not use SCAN at all")
        val offenders = issued.filter { it in forbidden }
        assertTrue(offenders.isEmpty(), "browsing issued ${offenders.distinct()}")
    }


    private fun seed(count: Int, name: (Int) -> String) = seed((1..count).map(name))

    private fun seed(names: List<String>) {
        RedisFixture.admin(db) { commands -> names.forEach { commands.set(it, "v") } }
    }
}

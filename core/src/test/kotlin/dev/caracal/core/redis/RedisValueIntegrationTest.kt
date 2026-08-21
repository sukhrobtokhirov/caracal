package dev.caracal.core.redis

import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
 * §3.6: six viewers, each of which pages rather than loading a whole value.
 *
 * The shared property being asserted across all of them is that the *first* page of
 * a large value costs the same as the first page of a small one. An IDE that opens a
 * key by fetching all of it is fine until somebody clicks the two-gigabyte one, and
 * the person who finds out is everybody else using that server.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class RedisValueIntegrationTest {

    private val db = RedisFixture.VALUE_DB

    @BeforeEach
    fun reset() {
        RedisFixture.admin(db) { it.flushdb() }
    }

    // --- Strings --------------------------------------------------------------

    @Test
    fun `a short string arrives whole and says so`() = runBlocking {
        RedisFixture.admin(db) { it.set("greeting", "hello there") }

        RedisFixture.session(db).use { session ->
            val page = assertIs<ValuePage.Text>(session.adapter.value(request("greeting", KeyType.STRING)))

            assertEquals("hello there", page.content.text)
            assertEquals(11, page.length)
            assertTrue(page.complete)
            assertNull(page.nextOffset)
            assertNull(page.cappedAt)
        }
    }

    @Test
    fun `a large string is read one window at a time`() = runBlocking {
        val value = "x".repeat(50_000)
        RedisFixture.admin(db) { it.set("big", value) }

        RedisFixture.session(db, limits = RedisLimits(stringPageBytes = 1_000)).use { session ->
            val first = assertIs<ValuePage.Text>(session.adapter.value(request("big", KeyType.STRING)))

            assertEquals(1_000, first.content.text?.length)
            assertEquals(50_000, first.length, "the whole length is reported even though it was not read")
            assertFalse(first.complete)
            assertEquals(1_000, first.nextOffset)

            val second = assertIs<ValuePage.Text>(
                session.adapter.value(request("big", KeyType.STRING).copy(offset = first.nextOffset!!.toLong())),
            )
            assertEquals(1_000, second.offset)
            assertEquals(2_000, second.nextOffset)
        }
    }

    @Test
    fun `reading a string to its end reports completion and offers nowhere to continue`() = runBlocking {
        RedisFixture.admin(db) { it.set("medium", "y".repeat(1_500)) }

        RedisFixture.session(db, limits = RedisLimits(stringPageBytes = 1_000)).use { session ->
            val last = assertIs<ValuePage.Text>(
                session.adapter.value(request("medium", KeyType.STRING).copy(offset = 1_000)),
            )

            assertEquals(500, last.content.text?.length)
            assertTrue(last.complete)
            assertNull(last.nextOffset)
        }
    }

    @Test
    fun `a string past the hard maximum stops there and says that is why`() = runBlocking {
        // §3.6's "Show full still obeys a hard maximum, and must warn". A value
        // silently cut at the ceiling looks exactly like a value that was that size.
        RedisFixture.admin(db) { it.set("huge", "z".repeat(20_000)) }

        RedisFixture.session(db, limits = RedisLimits(stringPageBytes = 100_000, stringMaxBytes = 5_000)).use { session ->
            val page = assertIs<ValuePage.Text>(session.adapter.value(request("huge", KeyType.STRING)))

            assertEquals(5_000, page.content.text?.length)
            assertEquals(20_000, page.length)
            assertEquals(5_000, page.cappedAt)
            assertNull(page.nextOffset, "there is nowhere to continue to past the hard maximum")
            assertFalse(page.complete)
        }
    }

    @Test
    fun `string offsets are byte offsets, because Redis strings are bytes`() = runBlocking {
        // A four-byte character means the byte length and the character count differ,
        // and paging by characters would ask `GETRANGE` for the wrong range.
        val value = "🧊".repeat(10)
        RedisFixture.admin(db) { it.set("emoji", value) }

        RedisFixture.session(db).use { session ->
            val page = assertIs<ValuePage.Text>(session.adapter.value(request("emoji", KeyType.STRING)))

            assertEquals(40, page.length, "byte length, not character count")
            assertEquals(value, page.content.text)
        }
    }

    @Test
    fun `a binary string is shown as bytes rather than as damaged text`() = runBlocking {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0xFF.toByte())
        RedisFixture.adminBytes(db) { it.set("png".toByteArray(), bytes) }

        RedisFixture.session(db).use { session ->
            val page = assertIs<ValuePage.Text>(session.adapter.value(request("png", KeyType.STRING)))

            val binary = assertIs<RedisText.Binary>(page.content)
            assertEquals("89504e470d0a1a0aff", binary.hex)
            assertEquals(9, binary.byteCount)
        }
    }

    @Test
    fun `an empty string is a value, not an absence`() = runBlocking {
        RedisFixture.admin(db) { it.set("blank", "") }

        RedisFixture.session(db).use { session ->
            val page = assertIs<ValuePage.Text>(session.adapter.value(request("blank", KeyType.STRING)))

            assertEquals("", page.content.text)
            assertEquals(0, page.length)
            assertTrue(page.complete)
        }
    }

    // --- Hashes ---------------------------------------------------------------

    @Test
    fun `a hash pages through HSCAN and keeps its field names exactly`() = runBlocking {
        RedisFixture.admin(db) { commands ->
            // The encoding is forced, because otherwise this test asserts a Redis
            // internal rather than this code. A small hash is stored as a listpack, and
            // `HSCAN` over a listpack ignores `COUNT` entirely and returns the whole
            // thing with a cursor of zero — so the same hash pages or does not page
            // depending on one server setting, and on the default in whichever image
            // the suite happens to run against. Converting it to a hash table makes
            // `COUNT` mean something, which is what is being tested.
            commands.configSet("hash-max-listpack-entries", "32")
            (1..300).forEach { commands.hset("profile", "field:$it", "value:$it") }
        }

        RedisFixture.session(db, limits = RedisLimits(entriesPerPage = 50)).use { session ->
            var cursor = RedisCursor.START
            val seen = mutableMapOf<String, String>()
            var pages = 0
            while (pages < 100) {
                val page = assertIs<ValuePage.Fields>(
                    session.adapter.value(request("profile", KeyType.HASH).copy(cursor = cursor)),
                )
                page.entries.forEach { seen[it.field.text!!] = it.value.text!! }
                cursor = page.cursor
                pages++
                if (page.complete) break
            }

            assertEquals(300, seen.size)
            assertEquals("value:7", seen["field:7"])
            assertTrue(pages > 1, "300 fields came back in one page")
        }
    }

    @Test
    fun `a hash field whose value is not text keeps its bytes`() = runBlocking {
        RedisFixture.adminBytes(db) { commands ->
            commands.hset("blobs".toByteArray(), "thumb".toByteArray(), byteArrayOf(0xFF.toByte(), 0x00))
        }

        RedisFixture.session(db).use { session ->
            val page = assertIs<ValuePage.Fields>(session.adapter.value(request("blobs", KeyType.HASH)))

            assertEquals("thumb", page.entries.single().field.text)
            assertEquals("ff00", assertIs<RedisText.Binary>(page.entries.single().value).hex)
        }
    }

    // --- Sets -----------------------------------------------------------------

    @Test
    fun `a set pages through SSCAN`() = runBlocking {
        RedisFixture.admin(db) { commands ->
            (1..250).forEach { commands.sadd("members", "member:$it") }
        }

        RedisFixture.session(db, limits = RedisLimits(entriesPerPage = 40)).use { session ->
            var cursor = RedisCursor.START
            val seen = mutableSetOf<String>()
            var pages = 0
            while (pages < 100) {
                val page = assertIs<ValuePage.Members>(
                    session.adapter.value(request("members", KeyType.SET).copy(cursor = cursor)),
                )
                page.members.forEach { seen += it.text!! }
                cursor = page.cursor
                pages++
                if (page.complete) break
            }

            assertEquals(250, seen.size)
            assertTrue(pages > 1)
        }
    }

    // --- Sorted sets ----------------------------------------------------------

    @Test
    fun `a sorted set pages by rank and keeps its scores readable`() = runBlocking {
        RedisFixture.admin(db) { commands ->
            (1..120).forEach { commands.zadd("leaderboard", it.toDouble(), "player:$it") }
            commands.zadd("leaderboard", 2.5, "player:half")
        }

        RedisFixture.session(db, limits = RedisLimits(entriesPerPage = 25)).use { session ->
            val first = assertIs<ValuePage.Scored>(session.adapter.value(request("leaderboard", KeyType.ZSET)))

            assertEquals(121, first.total)
            assertEquals(25, first.members.size)
            assertEquals(0, first.offset)
            assertEquals(25, first.nextOffset)
            assertFalse(first.complete)
            // Ranked, so the lowest score is first.
            assertEquals("player:1", first.members.first().member.text)
            // Redis writes a whole-numbered score without a decimal point, and Java
            // writes "1.0". A user comparing this against redis-cli should see the same
            // thing in both.
            assertEquals("1", first.members.first().score)
            assertEquals("2.5", first.members.first { it.member.text == "player:half" }.score)
        }
    }

    @Test
    fun `the last page of a sorted set completes and offers nowhere to continue`() = runBlocking {
        RedisFixture.admin(db) { commands -> (1..30).forEach { commands.zadd("small", it.toDouble(), "m$it") } }

        RedisFixture.session(db, limits = RedisLimits(entriesPerPage = 25)).use { session ->
            val last = assertIs<ValuePage.Scored>(
                session.adapter.value(request("small", KeyType.ZSET).copy(offset = 25)),
            )

            assertEquals(5, last.members.size)
            assertTrue(last.complete)
            assertNull(last.nextOffset)
        }
    }

    // --- Lists ----------------------------------------------------------------

    @Test
    fun `a list pages by index, and each element carries the index it has`() = runBlocking {
        RedisFixture.admin(db) { commands -> (1..200).forEach { commands.rpush("queue", "job:$it") } }

        RedisFixture.session(db, limits = RedisLimits(entriesPerPage = 30)).use { session ->
            val second = assertIs<ValuePage.Elements>(
                session.adapter.value(request("queue", KeyType.LIST).copy(offset = 30)),
            )

            assertEquals(200, second.total)
            assertEquals(30, second.elements.size)
            // Absolute indices, so a value the user is looking at can be found again.
            assertEquals(30, second.elements.first().index)
            assertEquals("job:31", second.elements.first().value.text)
            assertEquals(60, second.nextOffset)
        }
    }

    // --- Streams --------------------------------------------------------------

    @Test
    fun `a stream pages forward without repeating the entry it stopped on`() = runBlocking {
        // `XRANGE`'s start is inclusive, so continuing from the last ID seen would put
        // that entry at the head of every page after the first.
        RedisFixture.admin(db) { commands ->
            (1..60).forEach { commands.xadd("events", mapOf("n" to "$it")) }
        }

        RedisFixture.session(db, limits = RedisLimits(entriesPerPage = 20)).use { session ->
            val first = assertIs<ValuePage.Entries>(session.adapter.value(request("events", KeyType.STREAM)))

            assertEquals(20, first.entries.size)
            assertFalse(first.complete)
            assertNotNull(first.nextId)
            assertEquals("1", first.entries.first().fields.single().value.text)

            val second = assertIs<ValuePage.Entries>(
                session.adapter.value(request("events", KeyType.STREAM).copy(fromId = first.nextId)),
            )
            assertEquals("21", second.entries.first().fields.single().value.text)
            assertTrue(first.entries.none { it.id == second.entries.first().id })
        }
    }

    @Test
    fun `a stream entry keeps repeated field names, which a map would drop`() = runBlocking {
        // The reason `XRANGE` is dispatched raw instead of through Lettuce's typed
        // `xrange`: `StreamMessage` carries the body as a Map, and a stream is the one
        // structure in Redis whose whole purpose is recording exactly what was appended.
        RedisFixture.admin(db) { commands ->
            commands.xadd("repeats", linkedMapOf("tag" to "a"))
        }
        RedisFixture.adminBytes(db) { commands ->
            commands.dispatchRaw("XADD", "repeats", "*", "tag", "one", "tag", "two")
        }

        RedisFixture.session(db).use { session ->
            val page = assertIs<ValuePage.Entries>(session.adapter.value(request("repeats", KeyType.STREAM)))
            val repeated = page.entries.last()

            assertEquals(2, repeated.fields.size, "a repeated field name was collapsed")
            assertEquals(listOf("tag", "tag"), repeated.fields.map { it.field.text })
            assertEquals(listOf("one", "two"), repeated.fields.map { it.value.text })
        }
    }

    @Test
    fun `a stream that ends inside a page is complete`() = runBlocking {
        RedisFixture.admin(db) { commands -> (1..5).forEach { commands.xadd("short", mapOf("n" to "$it")) } }

        RedisFixture.session(db, limits = RedisLimits(entriesPerPage = 20)).use { session ->
            val page = assertIs<ValuePage.Entries>(session.adapter.value(request("short", KeyType.STREAM)))

            assertEquals(5, page.entries.size)
            assertTrue(page.complete)
            assertNull(page.nextId)
        }
    }

    // --- Races ----------------------------------------------------------------

    @Test
    fun `a key that changed type says what it is now, rather than failing on WRONGTYPE`() = runBlocking {
        // Ordinary in Redis: a key is deleted and recreated as something else by
        // whatever owns it. `WRONGTYPE` says nothing about what to do next; this does.
        RedisFixture.admin(db) { commands ->
            commands.del("reshaped")
            commands.rpush("reshaped", "now-a-list")
        }

        RedisFixture.session(db).use { session ->
            val failure = assertThrows<DbException> {
                runBlocking { session.adapter.value(request("reshaped", KeyType.HASH)) }
            }

            val changed = assertIs<DbError.KeyTypeChanged>(failure.error)
            assertEquals("hash", changed.expected)
            assertEquals("list", changed.actual)
        }
    }

    @Test
    fun `a key that has gone says so rather than showing an empty value`() = runBlocking {
        // An expired key and an empty collection have to look different. "Your hash is
        // empty" and "your hash expired" lead to opposite next steps.
        RedisFixture.session(db).use { session ->
            val failure = assertThrows<DbException> {
                runBlocking { session.adapter.value(request("never-existed", KeyType.HASH)) }
            }

            val changed = assertIs<DbError.KeyTypeChanged>(failure.error)
            assertNull(changed.actual)
        }
    }

    @Test
    fun `an empty collection is distinguishable from a missing key`() = runBlocking {
        // Redis deletes a collection when its last element goes, so this is arranged
        // the only way it can be: a key that exists and holds nothing readable yet.
        RedisFixture.admin(db) { commands ->
            commands.rpush("draining", "one")
            commands.lpop("draining")
            commands.rpush("draining", "two")
            commands.del("gone")
        }

        RedisFixture.session(db).use { session ->
            val page = assertIs<ValuePage.Elements>(session.adapter.value(request("draining", KeyType.LIST)))
            assertEquals(1, page.total)
            assertTrue(page.complete)

            assertFalse(session.adapter.metadata(RedisKey("gone".toByteArray())).exists)
        }
    }

    private fun request(name: String, type: KeyType) =
        ValueRequest(key = RedisKey(name.toByteArray()), type = type)

    /** `XADD` with repeated field names, which no typed Lettuce overload will send. */
    private fun io.lettuce.core.api.sync.RedisCommands<ByteArray, ByteArray>.dispatchRaw(vararg parts: String) {
        val args = io.lettuce.core.protocol.CommandArgs(io.lettuce.core.codec.ByteArrayCodec.INSTANCE)
        parts.drop(1).forEach { args.add(it.toByteArray()) }
        dispatch(
            object : io.lettuce.core.protocol.ProtocolKeyword {
                override fun getBytes(): ByteArray = parts.first().toByteArray()

                override fun toString(): String = parts.first()
            },
            io.lettuce.core.output.StatusOutput(io.lettuce.core.codec.ByteArrayCodec.INSTANCE),
            args,
        )
    }
}

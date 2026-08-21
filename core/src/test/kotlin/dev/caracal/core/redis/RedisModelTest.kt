package dev.caracal.core.redis

import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Cursors, TTL sentinels, key identity, and the hint-capping in [RedisLimits]. */
class RedisModelTest {

    // --- Cursors --------------------------------------------------------------

    @Test
    fun `a cursor past the range of a signed long survives intact`() {
        // The reason §3.2 keeps cursors as text. A Redis cursor is unsigned 64-bit, so
        // half its range reads as a negative Long — and a negative cursor sent back is
        // a protocol error partway through a traversal that was working.
        val large = "18446744073709551615"

        assertEquals(large, RedisCursor.of(large).value)
        assertNull(large.toLongOrNull())
        assertEquals(large, large.toULongOrNull()?.toString())
    }

    @Test
    fun `a cursor is normalized, so two spellings of one cursor compare equal`() {
        assertEquals(RedisCursor.of("7"), RedisCursor.of("007"))
        assertEquals(RedisCursor.START, RedisCursor.of(" 0 "))
    }

    @Test
    fun `only a returned zero means the traversal is over`() {
        assertTrue(RedisCursor.START.isComplete)
        assertFalse(RedisCursor.of("17").isComplete)
    }

    @ParameterizedTest(name = "{0} is not a cursor")
    @ValueSource(strings = ["", "  ", "-1", "1.5", "0x10", "abc", "18446744073709551616", "1 OR 1"])
    fun `anything that is not a cursor is refused before it reaches the server`(text: String) {
        val failure = assertThrows<DbException> { RedisCursor.of(text) }

        assertIs<DbError.InvalidRequest>(failure.error)
    }

    // --- TTL ------------------------------------------------------------------

    @Test
    fun `TTL's two sentinels are not durations`() {
        assertEquals(Ttl.Persistent, Ttl.of(-1))
        assertEquals(Ttl.Gone, Ttl.of(-2))
        assertEquals(Ttl.ExpiresIn(0), Ttl.of(0))
        assertEquals(Ttl.ExpiresIn(3580), Ttl.of(3580))
    }

    @Test
    fun `an unrecognized negative TTL is treated as gone rather than as a countdown`() {
        // Whatever a future Redis means by -3, it does not mean "expires in -3
        // seconds", and showing a negative countdown would be the worst reading.
        assertEquals(Ttl.Gone, Ttl.of(-99))
    }

    // --- Keys -----------------------------------------------------------------

    @Test
    fun `two keys with the same bytes are the same key`() {
        // What makes deduplication work. SCAN returns duplicates whenever the hash
        // table resizes mid-traversal, and array identity would never match.
        val first = RedisKey("user:42".toByteArray())
        val second = RedisKey("user:42".toByteArray())

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(1, setOf(first, second).size)
        assertNotEquals(first, RedisKey("user:43".toByteArray()))
    }

    @Test
    fun `a key keeps its exact bytes, and hands out copies of them`() {
        val bytes = byteArrayOf(0x00, 0xFF.toByte(), 0x41)
        val key = RedisKey(bytes)

        assertContentEquals(bytes, key.bytes)
        // Neither the array handed in nor one handed out can change the key.
        bytes[0] = 0x7F
        key.bytes[1] = 0x00
        assertContentEquals(byteArrayOf(0x00, 0xFF.toByte(), 0x41), key.bytes)
    }

    @Test
    fun `a key whose name is not text still has a display form and no text form`() {
        val key = RedisKey(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))

        assertIs<RedisText.Binary>(key.display)
        assertNull(key.text)
    }

    @Test
    fun `a key never puts its own name in its description`() {
        // A key name can be a customer identifier. This type reaches loggers.
        val rendered = RedisKey("user:42:secret-token".toByteArray()).toString()

        assertFalse(rendered.contains("user:42"), rendered)
    }

    // --- Types ----------------------------------------------------------------

    @Test
    fun `Redis's own type names map onto the six viewers`() {
        assertEquals(KeyType.STRING, KeyType.of("string"))
        assertEquals(KeyType.ZSET, KeyType.of("zset"))
        assertEquals(KeyType.STREAM, KeyType.of(" STREAM "))
        // `none` is what TYPE answers for a key that is not there, and a module type
        // is a type this build has no viewer for. Neither is one of the six.
        assertNull(KeyType.of("none"))
        assertNull(KeyType.of("ReJSON-RL"))
        assertNull(KeyType.of(null))
    }

    // --- Limits ---------------------------------------------------------------

    @Test
    fun `a client's page size is a hint, capped by the server's own limit`() {
        val limits = RedisLimits(keysPerPage = 50, entriesPerPage = 20)

        assertEquals(10, limits.keysFor(10))
        assertEquals(50, limits.keysFor(5_000))
        // Asking for more than is allowed is not an error, it is a bound.
        assertEquals(50, limits.keysFor(null))
        assertEquals(1, limits.keysFor(0))
        assertEquals(1, limits.keysFor(-3))
        assertEquals(20, limits.entriesFor(9_999))
    }

    @Test
    fun `the defaults are small enough to read in one screen`() {
        // Not an arbitrary assertion: these bound how long Redis's single thread is
        // held, and a later edit that raises them by an order of magnitude should have
        // to say so here.
        val limits = RedisLimits()

        assertTrue(limits.keysPerPage <= 500, "a page of keys is a screenful, not a dump")
        assertTrue(limits.scanIterations <= 50, "the scan loop has to stop")
        assertTrue(limits.scanCount <= 1_000, "COUNT is roughly how long the server blocks")
        assertTrue(limits.entriesPerPage <= 500)
    }
}

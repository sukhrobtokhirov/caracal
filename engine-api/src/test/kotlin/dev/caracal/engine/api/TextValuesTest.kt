package dev.caracal.engine.api

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether a Redis value is text, decided once.
 *
 * The failure being ruled out is quiet: bytes that are not UTF-8 decoded leniently
 * become a string full of `U+FFFD`, which looks like text, copies like text, and is
 * not the bytes the server holds. For a *key name* that is worse than cosmetic —
 * sending the decoded form back fetches nothing, and the key appears to have
 * vanished.
 */
class TextValuesTest {

    @Test
    fun `text is text`() {
        val text = TextValues.of("user:42:profile".toByteArray(), limit = 1024)

        assertIs<TextValue.Utf8>(text)
        assertEquals("user:42:profile", text.value)
        assertEquals(15, text.byteCount)
        assertFalse(text.truncated)
        assertTrue(text.isWholeText)
    }

    @Test
    fun `text outside the basic plane survives`() {
        val value = "café ☕ 🧊"
        val text = TextValues.of(value.toByteArray(), limit = 1024)

        assertEquals(value, text.text)
        assertEquals(value.toByteArray().size, text.byteCount)
    }

    @Test
    fun `bytes that are not text arrive as hexadecimal, not as replacement characters`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0xFF.toByte(), 0xFE.toByte())
        val text = TextValues.of(bytes, limit = 1024)

        assertIs<TextValue.Binary>(text)
        assertEquals("89504e47fffe", text.hex)
        assertEquals(6, text.byteCount)
        // The whole point: there is no lossy string form to accidentally use.
        assertNull(text.text)
    }

    @Test
    fun `an empty value is empty text rather than binary`() {
        val text = TextValues.of(ByteArray(0), limit = 1024)

        assertEquals("", text.text)
        assertEquals(0, text.byteCount)
        assertFalse(text.truncated)
    }

    @Test
    fun `a clipped value reports its true size and says it was clipped`() {
        val text = TextValues.of("abcdefghij".toByteArray(), limit = 4)

        assertEquals("abcd", text.text)
        assertEquals(10, text.byteCount)
        assertTrue(text.truncated)
        // Truncated text is not whole text, so nothing downstream tries to parse it.
        assertFalse(text.isWholeText)
    }

    @Test
    fun `clipping through the middle of a character does not make the value look binary`() {
        // The regression this exists for. A four-byte character cut at three bytes is
        // not decodable, and a naive check would call the whole value binary and show
        // hexadecimal for a sentence — with the classification depending on where the
        // limit happened to land.
        val value = "aaa🧊bbb"
        for (limit in 1..value.toByteArray().size) {
            val text = TextValues.of(value.toByteArray(), limit)
            assertIs<TextValue.Utf8>(text, "limit $limit produced binary for ordinary text")
            assertTrue(value.startsWith(text.value), "limit $limit produced ${text.value}")
        }
    }

    @Test
    fun `a genuinely malformed prefix is still binary`() {
        // The other side of the same rule: dropping up to three bytes recovers a cut
        // character, and must not quietly recover an actually-invalid value.
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        assertIs<TextValue.Binary>(TextValues.of(bytes, limit = 5))
        assertIs<TextValue.Binary>(TextValues.of(bytes, limit = 2))
    }

    @Test
    fun `a window reports the value's whole size, not the window's`() {
        val chunk = "hello".toByteArray()
        val text = TextValues.window(chunk, total = 4096, limit = chunk.size)

        assertEquals("hello", text.text)
        assertEquals(4096, text.byteCount)
        assertTrue(text.truncated)
    }

    @Test
    fun `a window covering the whole value is not truncated`() {
        val chunk = "hello".toByteArray()
        val text = TextValues.window(chunk, total = chunk.size, limit = chunk.size)

        assertFalse(text.truncated)
        assertTrue(text.isWholeText)
    }

    @Test
    fun `a binary window keeps its binary label and the value's true size`() {
        val chunk = byteArrayOf(0x00, 0xFF.toByte())
        val text = TextValues.window(chunk, total = 900, limit = chunk.size)

        assertIs<TextValue.Binary>(text)
        assertEquals("00ff", text.hex)
        assertEquals(900, text.byteCount)
        assertTrue(text.truncated)
    }

    @Test
    fun `a NUL byte is text, because Redis strings may contain one`() {
        // `SET k "a\x00b"` is legal and the value is not binary in any useful sense.
        // Treating NUL as a terminator or as evidence of binary would truncate it.
        val text = TextValues.of(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()), limit = 1024)

        // Spelled with an explicit code point: a literal NUL in a source file is
        // invisible in every editor and diff that would need to show it.
        assertEquals("a" + Char(0) + "b", text.text)
        assertEquals(3, text.byteCount)
    }

    @Test
    fun `a window cut inside a character is still text`() {
        // GETRANGE cuts at a byte offset, so it lands mid-character about as often as
        // multi-byte characters occur. Deciding that makes the value binary means a
        // 200 KB JSON document with one accented letter in it renders as hexadecimal.
        val whole = "price: 12\u20AC".toByteArray(Charsets.UTF_8)
        // One byte short of the euro sign's three.
        val cut = whole.copyOfRange(0, whole.size - 1)

        val text = TextValues.window(cut, total = whole.size, limit = cut.size)

        assertIs<TextValue.Utf8>(text)
        assertEquals("price: 12", text.value)
        assertTrue(text.truncated)
    }

    @Test
    fun `the allowance only drops a sequence that is genuinely unfinished`() {
        // The trap in the obvious implementation: retrying successively shorter
        // windows finds that some prefix of binary decodes and calls the value text.
        // 0xFF cannot begin a UTF-8 character, so nothing here is unfinished.
        val text = TextValues.window(byteArrayOf(0x41, 0xFF.toByte()), total = 900, limit = 2)

        assertIs<TextValue.Binary>(text)
        assertEquals("41ff", text.hex)
    }

    @Test
    fun `a window that is nothing but a dangling sequence stays binary`() {
        // Trimming to nothing would decode as empty text and report a value that has
        // bytes in it as empty.
        val text = TextValues.window(byteArrayOf(0xE2.toByte(), 0x82.toByte()), total = 900, limit = 2)

        assertIs<TextValue.Binary>(text)
    }
}

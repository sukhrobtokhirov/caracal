package dev.dbide.core.result

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ResultLimitsTest {

    @Test
    fun `a value within the limit is untouched`() {
        val limits = ResultLimits(cellCharacters = 8)

        assertEquals(CellValue.Text("short"), limits.clip("short"))
        assertEquals(CellValue.Text("exactly8"), limits.clip("exactly8"))
    }

    @Test
    fun `a value past the limit is clipped and says so`() {
        val limits = ResultLimits(cellCharacters = 4)

        val clipped = limits.clip("abcdefgh")

        assertEquals("abcd", clipped.value)
        assertTrue(clipped.truncated)
    }

    @Test
    fun `clipping never splits a character in two`() {
        // The emoji is two UTF-16 units and starts at index 4, so a cut at 5 would
        // land in the middle of it. What comes out would not be a character at all:
        // an unpaired surrogate, which encodes to UTF-8 as a replacement glyph — in
        // the one cell a user is most likely to be looking at closely.
        val limits = ResultLimits(cellCharacters = 5)

        val clipped = limits.clip("abcd🙂efgh")

        assertEquals("abcd", clipped.value)
        assertTrue(clipped.truncated)
        assertRoundTripsAsText(clipped.value)
    }

    @Test
    fun `a character that ends exactly on the limit is kept whole`() {
        // Both halves fit, so trimming one would drop a character that belonged.
        val limits = ResultLimits(cellCharacters = 6)

        val clipped = limits.clip("abcd🙂efgh")

        assertEquals("abcd🙂", clipped.value)
        assertTrue(clipped.truncated)
        assertRoundTripsAsText(clipped.value)
    }

    @Test
    fun `clipping is stable across every boundary of a string full of surrogate pairs`() {
        val text = "🙂".repeat(20)

        for (limit in 1..text.length) {
            val clipped = ResultLimits(cellCharacters = limit).clip(text)

            assertEquals(0, clipped.value.length % 2, "cut a pair at limit $limit")
            assertRoundTripsAsText(clipped.value)
            assertEquals(limit < text.length, clipped.truncated, "wrong truncation flag at limit $limit")
        }
    }

    @Test
    fun `an empty value is not truncated`() {
        val clipped = ResultLimits(cellCharacters = 4).clip("")

        assertEquals("", clipped.value)
        assertFalse(clipped.truncated)
    }

    /** An unpaired surrogate does not survive a trip through UTF-8; a real string does. */
    private fun assertRoundTripsAsText(value: String) =
        assertEquals(value, String(value.toByteArray(Charsets.UTF_8), Charsets.UTF_8), "not valid text: $value")
}

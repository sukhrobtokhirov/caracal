package dev.dbide.app

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * The pretty view is a view. These are the cases where reformatting could quietly
 * become editing — a number too precise for a `Double`, an escape sequence, a string
 * that contains what looks like JSON — and it must not.
 */
class JsonFormatTest {

    @Test
    fun `an object is indented, and keeps its order`() {
        assertEquals(
            """
            {
              "b": 1,
              "a": 2
            }
            """.trimIndent(),
            JsonFormat.pretty("""{"b":1,"a":2}"""),
        )
    }

    @Test
    fun `nesting indents, and empty containers stay on one line`() {
        assertEquals(
            """
            {
              "rows": [
                {
                  "id": 1
                },
                {}
              ],
              "tags": []
            }
            """.trimIndent(),
            JsonFormat.pretty("""{"rows":[{"id":1},{}],"tags":[]}"""),
        )
    }

    @Test
    fun `a number keeps every digit, however many a double would lose`() {
        val precise = """{"n":12345678901234567890.123456789,"e":1.0E+300,"neg":-0.5}"""
        val pretty = JsonFormat.pretty(precise)!!
        assertEquals(true, pretty.contains("12345678901234567890.123456789"))
        assertEquals(true, pretty.contains("1.0E+300"))
        assertEquals(true, pretty.contains("-0.5"))
    }

    @Test
    fun `a string is copied across, escapes and all`() {
        val source = """{"s":"a \"quote\", a \\ and a \n"}"""
        assertEquals(
            "{\n  \"s\": \"a \\\"quote\\\", a \\\\ and a \\n\"\n}",
            JsonFormat.pretty(source),
        )
    }

    @Test
    fun `braces inside a string are text, not structure`() {
        assertEquals("{\n  \"s\": \"{not: json}\"\n}", JsonFormat.pretty("""{"s":"{not: json}"}"""))
    }

    @Test
    fun `a scalar document is a document`() {
        assertEquals("42", JsonFormat.pretty("42"))
        assertEquals("null", JsonFormat.pretty(" null "))
        assertEquals("\"text\"", JsonFormat.pretty("\"text\""))
    }

    @Test
    fun `already-indented JSON survives a round trip`() {
        val pretty = JsonFormat.pretty("""{"a":[1,2]}""")!!
        assertEquals(pretty, JsonFormat.pretty(pretty))
    }

    @Test
    fun `what it cannot read, it declines to rewrite`() {
        assertNull(JsonFormat.pretty(""))
        assertNull(JsonFormat.pretty("{"))
        assertNull(JsonFormat.pretty("""{"a":1,}"""))
        assertNull(JsonFormat.pretty("""{"a" 1}"""))
        assertNull(JsonFormat.pretty("""{"a":1} trailing"""))
        assertNull(JsonFormat.pretty("\"unterminated"))
        assertNull(JsonFormat.pretty("nope"))
        assertNull(JsonFormat.pretty("01"))
    }

    @Test
    fun `nesting deeper than it will follow is left alone rather than overflowing`() {
        val deep = "[".repeat(500) + "]".repeat(500)
        assertNull(JsonFormat.pretty(deep))
    }
}

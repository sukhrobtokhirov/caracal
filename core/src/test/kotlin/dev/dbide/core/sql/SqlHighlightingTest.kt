package dev.dbide.core.sql

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the editor colours, and what it deliberately does not.
 *
 * The interesting half of a highlighter is everything it refuses to be fooled by: the
 * word `delete` inside a string is not a keyword, a bracket inside a comment closes
 * nothing, and `$1` is a placeholder rather than the start of a `$$` body. Those are
 * the same distinctions the splitter and the classifier make, which is why all three
 * read the same lexer.
 */
class SqlHighlightingTest {

    private fun kinds(sql: String): List<Pair<TokenKind, String>> =
        SqlHighlighting.tokens(sql).map { it.kind to sql.substring(it.start, it.end) }

    @Test
    fun `keywords are coloured and names are not`() {
        assertEquals(
            listOf(
                TokenKind.KEYWORD to "select",
                TokenKind.KEYWORD to "from",
                TokenKind.KEYWORD to "where",
            ),
            kinds("select total from invoices where paid"),
        )
    }

    @Test
    fun `a keyword inside a string is part of the string`() {
        assertEquals(listOf(TokenKind.STRING to "'delete from t'"), kinds("'delete from t'"))
    }

    @Test
    fun `a keyword inside a comment is part of the comment`() {
        assertEquals(
            listOf(TokenKind.COMMENT to "-- select everything"),
            kinds("-- select everything"),
        )
    }

    @Test
    fun `a quoted identifier is a name, not a keyword`() {
        assertEquals(
            listOf(TokenKind.KEYWORD to "select", TokenKind.QUOTED_IDENTIFIER to "\"select\""),
            kinds("select \"select\""),
        )
    }

    @Test
    fun `a dollar-quoted body is one string however much SQL it contains`() {
        val sql = "create function f() returns int as \$body\$ select 1; \$body\$ language sql"
        val strings = SqlHighlighting.tokens(sql).filter { it.kind == TokenKind.STRING }

        assertEquals(1, strings.size)
        assertEquals("\$body\$ select 1; \$body\$", sql.substring(strings[0].start, strings[0].end))
    }

    @Test
    fun `an escape string is coloured from its E, backslashes and all`() {
        // The `E` belongs to the literal: colouring it as a name would make the one
        // spelling where a backslash escapes look like the one where it does not.
        assertEquals(listOf(TokenKind.STRING to "E'a\\'b'"), kinds("E'a\\'b'"))
    }

    @Test
    fun `numbers are coloured and the digits inside a name are not`() {
        assertEquals(
            listOf(TokenKind.NUMBER to "1.5e-3"),
            kinds("t1.column2 = 1.5e-3"),
        )
    }

    @Test
    fun `a placeholder is a placeholder and not the start of a dollar quote`() {
        assertEquals(
            listOf(TokenKind.KEYWORD to "where", TokenKind.PARAMETER to "\$1"),
            kinds("where id = \$1"),
        )
    }

    @Test
    fun `an unterminated string is coloured to the end rather than leaving the rest bare`() {
        // The editor is mid-keystroke far more often than it is finished, and a
        // half-typed literal that renders as code flashes the whole tail of the script
        // a different colour on every character typed.
        assertEquals(listOf(TokenKind.STRING to "'abc"), kinds("'abc"))
    }

    @Test
    fun `the scan stops at the limit and the document past it renders plain`() {
        val sql = "select 1; " + "select 2; ".repeat(50)
        val bounded = SqlHighlighting.tokens(sql, limit = 12)

        assertTrue(bounded.all { it.start < 12 }, "no token may begin past the limit")
        assertTrue(bounded.size < SqlHighlighting.tokens(sql).size)
    }

    // --- Brackets -------------------------------------------------------------

    @Test
    fun `the caret before a bracket matches it, and so does the caret after it`() {
        val sql = "select coalesce(a, b)"
        val open = sql.indexOf('(')
        val close = sql.indexOf(')')

        assertEquals(BracketPair(open, close), SqlHighlighting.matchingBracket(sql, open))
        assertEquals(BracketPair(open, close), SqlHighlighting.matchingBracket(sql, close + 1))
    }

    @Test
    fun `nested brackets match their own partner`() {
        val sql = "select f(g(x), y)"
        val inner = sql.indexOf("g(") + 1

        assertEquals(BracketPair(inner, sql.indexOf(')')), SqlHighlighting.matchingBracket(sql, inner))
    }

    @Test
    fun `a bracket inside a string closes nothing`() {
        val sql = "where note = ')' and id in (1)"
        val open = sql.indexOf("(1")

        assertEquals(BracketPair(open, sql.lastIndexOf(')')), SqlHighlighting.matchingBracket(sql, open))
    }

    @Test
    fun `an unmatched bracket matches nothing`() {
        assertNull(SqlHighlighting.matchingBracket("select f(a", 8))
    }

    @Test
    fun `a caret nowhere near a bracket matches nothing`() {
        assertNull(SqlHighlighting.matchingBracket("select 1", 3))
    }
}

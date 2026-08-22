package dev.caracal.core.sql

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * Characterization of the one mapping that makes an error underline land on the
 * right character, ahead of the multi-engine refactor.
 *
 * PostgreSQL reports an error position as a **1-based count of characters — meaning
 * code points — into the statement it was sent**. The editor needs a **0-based
 * offset in UTF-16 units into the whole buffer**. Three things have to be right at
 * once for those to agree, and each of them is a separate way to be off by a few
 * characters:
 *
 *  1. the statement's own `start` in the document, so the second statement in a
 *     script is not underlined as though it were the first;
 *  2. the fact that a leading comment is part of the statement that was *sent*, and
 *     so is part of what the server counted into;
 *  3. the code-point-to-code-unit conversion, which is a no-op until a character
 *     outside the BMP appears earlier in the statement and then drifts by one per
 *     such character.
 *
 * The multi-engine spec asserts this mapping lives in `PostgresErrors.kt`. It does
 * not — `PostgresErrors` only lifts the server's raw position onto the error, and
 * the mapping is `Statement.documentIndex` here. This file is where "move it, do not
 * rewrite it, and keep its tests" attaches.
 */
class ErrorPositionTest {

    // ------------------------------------------------- a single statement

    @Test
    fun `position 1 is the first character of the statement`() {
        val statement = only("select frm t")
        assertEquals(0, statement.documentIndex(1))
    }

    @Test
    fun `a position inside the statement lands on that character`() {
        underlines(script = "select frm t", marker = "frm")
    }

    @Test
    fun `a position on the last character lands on it and not past it`() {
        val script = "select 1 from tbl"
        val statement = only(script)
        val characters = statement.text.codePointCount(0, statement.text.length)

        assertEquals(script.length - 1, statement.documentIndex(characters))
    }

    @Test
    fun `a position one past the end is the end of input error, and maps to the end`() {
        val script = "select 1 from"
        val statement = only(script)
        val characters = statement.text.codePointCount(0, statement.text.length)

        assertEquals(statement.end, statement.documentIndex(characters + 1))
        assertEquals(script.length, statement.end)
    }

    // --------------------------------------------- multi-statement buffers

    @Test
    fun `an error in the second of two statements is offset by the first`() {
        underlines(script = "select 1;\nselect frm t;", index = 1, marker = "frm")
    }

    @Test
    fun `an error in the third of three statements is offset by both before it`() {
        underlines(
            script = "select 1;\nselect 2;\nupdate accounts set balance = wat;",
            index = 2,
            marker = "wat",
        )
    }

    @Test
    fun `the position is counted into the statement, so an earlier statement's length is not double counted`() {
        // Both statements have an error at the same position within themselves. If
        // the mapping ever counted from the document rather than the statement, the
        // second would land somewhere past the end of the script.
        val script = "select aaa;select bbb;"
        val statements = StatementSplitter.split(script).statements

        assertEquals(script.indexOf("aaa"), statements[0].documentIndex(8))
        assertEquals(script.indexOf("bbb"), statements[1].documentIndex(8))
    }

    @Test
    fun `a position past the end of one statement does not spill into the next`() {
        val statements = StatementSplitter.split("select 1;\nselect 2;").statements
        val first = statements[0]
        val characters = first.text.codePointCount(0, first.text.length)

        // `characters + 1` is the end-of-input position and is the last one accepted.
        assertEquals(first.end, first.documentIndex(characters + 1))
        assertNull(first.documentIndex(characters + 2))
    }

    // ------------------------------------------ statements preceded by comments

    @Test
    fun `a leading line comment is part of what was sent, and so part of what is counted`() {
        // The comment is inside statement.text, so the server counted it too. Getting
        // this wrong underlines eight characters to the left.
        underlines(script = "-- why is this here\nselect frm t;", marker = "frm")
    }

    @Test
    fun `a leading block comment counts the same way`() {
        underlines(script = "/* a note\n   over two lines */\nselect frm t;", marker = "frm")
    }

    @Test
    fun `a comment between two statements belongs to the one below it`() {
        val script = "select 1;\n-- about the next one\nselect frm t;"
        val second = StatementSplitter.split(script).statements[1]

        assertEquals(script.indexOf("--"), second.start, "the comment leads the second statement")
        underlines(script = script, index = 1, marker = "frm")
    }

    @Test
    fun `blank lines before the first statement are not part of it`() {
        val script = "\n\n\nselect frm t;"
        val statement = only(script)

        assertEquals(script.indexOf("select"), statement.start)
        underlines(script = script, marker = "frm")
    }

    // ------------------------------------------------------ non-ASCII text

    @Test
    fun `a BMP character before the error does not move it, since it is one of each`() {
        // Cyrillic is one code point and one UTF-16 unit, so the two counts agree.
        underlines(script = "select 'счёт' as label, frm t;", marker = "frm")
    }

    @Test
    fun `a non-BMP character before the error is one PostgreSQL character and two Kotlin ones`() {
        underlines(script = "select '🙂' as mood, frm t;", marker = "frm")
    }

    @Test
    fun `the drift accumulates, so several non-BMP characters shift by several`() {
        val script = "select '🙂🙃😐😑😶' as moods, frm t;"
        val statement = only(script)
        val within = statement.text.indexOf("frm")
        val position = statement.text.codePointCount(0, within) + 1

        // Five emoji: the code-unit index is five higher than the code-point count.
        assertEquals(within, position - 1 + 5)
        assertEquals(script.indexOf("frm"), statement.documentIndex(position))
    }

    @Test
    fun `non-BMP text in an earlier statement does not shift a later one`() {
        // The second statement's positions are counted into itself, so the emoji in
        // the first is only ever part of its `start`.
        underlines(script = "select '🙂';\nselect frm t;", index = 1, marker = "frm")
    }

    @Test
    fun `a non-BMP character after the error changes nothing`() {
        underlines(script = "select frm t where note = '🙂';", marker = "frm")
    }

    // --------------------------------------------------- line endings and bodies

    @Test
    fun `CRLF line endings are counted as the two characters they are`() {
        underlines(script = "select 1,\r\n       2,\r\n       frm t;", marker = "frm")
    }

    @Test
    fun `an error inside a dollar-quoted body is still found`() {
        val body = "$" + "body" + "$"
        underlines(
            script = "create function f() returns int as $body\nbegin\n  return wat;\nend\n$body language plpgsql;",
            marker = "wat",
        )
    }

    // ----------------------------------------------------------- out of range

    @Test
    fun `a position the server never sends maps to nothing rather than to the start`() {
        val statement = only("select 1;")

        // 0 is pgjdbc's "no position", and it must not be mistaken for the first
        // character — an underline under `s` is a claim, and a wrong one.
        assertNull(statement.documentIndex(0))
        assertNull(statement.documentIndex(-1))
        assertNull(statement.documentIndex(Int.MIN_VALUE))
    }

    @Test
    fun `a position past the end of the statement maps to nothing`() {
        val statement = only("select 1;")
        val characters = statement.text.codePointCount(0, statement.text.length)

        assertNull(statement.documentIndex(characters + 2))
        assertNull(statement.documentIndex(Int.MAX_VALUE))
    }

    // ---------------------------------------------------------------- helpers

    private fun only(script: String): Statement = StatementSplitter.split(script).statements.single()

    /**
     * Asserts that the server reporting an error at [marker] underlines [marker] in
     * the document — the position being derived the way PostgreSQL derives it, as a
     * 1-based code-point count into the statement it was sent.
     *
     * [marker] must appear once in the script.
     */
    private fun underlines(script: String, index: Int = 0, marker: String) {
        val statement = StatementSplitter.split(script).statements[index]
        val within = statement.text.indexOf(marker)
        check(within >= 0) { "`$marker` is not in the statement the test picked" }
        check(script.indexOf(marker) == script.lastIndexOf(marker)) { "`$marker` is not unique" }

        val position = statement.text.codePointCount(0, within) + 1
        assertEquals(script.indexOf(marker), statement.documentIndex(position))
    }
}

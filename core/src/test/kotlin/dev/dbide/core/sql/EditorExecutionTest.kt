package dev.dbide.core.sql

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

/**
 * The rule that decides what Run runs.
 *
 * Every case here is one a person can hit in an afternoon: a caret parked on a blank
 * line, a half-typed quote, a selection dragged one statement too far. What they have
 * in common is that guessing wrong sends the wrong SQL to a real server, so each is
 * pinned down rather than left to whichever behaviour the code happened to have.
 */
class EditorExecutionTest {

    private fun ready(script: String, from: Int, to: Int = from): ExecutionTarget {
        val execution = EditorExecution.resolve(script, from, to)
        assertIs<Execution.Ready>(execution, "expected something to run in: $script")
        return execution.target
    }

    private fun refused(script: String, from: Int, to: Int = from): Execution.Refused {
        val execution = EditorExecution.resolve(script, from, to)
        assertIs<Execution.Refused>(execution, "expected a refusal for: $script")
        return execution
    }

    // --- The caret ------------------------------------------------------------

    @Test
    fun `the caret inside a statement runs that statement`() {
        val script = "select 1;\nselect 2;\nselect 3;"
        assertEquals("select 2;", ready(script, script.indexOf("select 2") + 3).sql)
        assertEquals(TargetSource.CURSOR, ready(script, 0).source)
    }

    @Test
    fun `the caret at the very end of a statement is still inside it`() {
        val script = "select 1;\nselect 2;"
        assertEquals("select 1;", ready(script, 9).sql)
    }

    @Test
    fun `the caret between two statements runs the one below it`() {
        val script = "select 1;\n\n-- heading\n\nselect 2;"
        // The blank line under the comment: the user is heading downwards.
        assertEquals("-- heading\n\nselect 2;", ready(script, 11).sql)
    }

    @Test
    fun `the caret after the last statement falls back to it`() {
        val script = "select 1;\n\n\n"
        assertEquals("select 1;", ready(script, script.length).sql)
    }

    @Test
    fun `an empty document has nothing to run`() {
        assertEquals(ExecutionRefusal.NOTHING_TO_RUN.code, refused("", 0).code)
    }

    @Test
    fun `a document of only comments has nothing to run`() {
        val script = "-- just a note\n/* and another */\n"
        assertEquals(ExecutionRefusal.NOTHING_TO_RUN.code, refused(script, script.length).code)
    }

    // --- Half-typed SQL -------------------------------------------------------

    @Test
    fun `the caret inside an unterminated string refuses rather than running the statement above`() {
        val script = "select 1;\nselect 'oops"
        val refusal = refused(script, script.length)

        assertEquals(SqlConstruct.STRING.code, refusal.code)
        assertEquals(SqlConstruct.STRING.message, refusal.message)
    }

    @Test
    fun `a statement before an unterminated one still runs`() {
        // Typing the opening quote of the third statement must not disable Run for
        // the first, which is complete and correct and right there on screen.
        val script = "select 1;\nselect 'oops"
        assertEquals("select 1;", ready(script, 2).sql)
    }

    // --- The selection --------------------------------------------------------

    @Test
    fun `a selection runs exactly what was selected`() {
        val script = "select 1; select 2;"
        val target = ready(script, from = 10, to = 19)

        assertEquals("select 2;", target.sql)
        assertEquals(TargetSource.SELECTION, target.source)
        assertEquals(10, target.start)
        assertEquals(19, target.end)
    }

    @Test
    fun `a backwards selection is the same selection`() {
        val script = "select 1; select 2;"
        assertEquals("select 2;", ready(script, from = 19, to = 10).sql)
    }

    @Test
    fun `a selection of part of a statement runs that part`() {
        // Half a statement is a statement as far as this is concerned: "run what I
        // selected" is the promise, and PostgreSQL decides whether it parses.
        val script = "select id, total from invoices"
        assertEquals("select id", ready(script, 0, 9).sql)
    }

    @Test
    fun `a selection spanning two statements is refused`() {
        val script = "select 1; select 2;"
        val refusal = refused(script, 0, script.length)

        assertEquals(ExecutionRefusal.MULTIPLE_STATEMENTS.code, refusal.code)
    }

    @Test
    fun `a selection of only whitespace and comments is refused`() {
        val script = "select 1;\n  -- a note\nselect 2;"
        assertEquals(ExecutionRefusal.NOTHING_TO_RUN.code, refused(script, 9, 21).code)
    }

    @Test
    fun `a selection that opens a quote it never closes is refused`() {
        val script = "select 'abc';"
        assertEquals(SqlConstruct.STRING.code, refused(script, 0, 10).code)
    }

    @Test
    fun `surrounding whitespace is trimmed off a selection, and the span with it`() {
        val script = "select 1;\n\n   select 2;   \n"
        val target = ready(script, from = 10, to = script.length)

        assertEquals("select 2;", target.sql)
        assertEquals(script.indexOf("select 2"), target.start)
        assertEquals(script.indexOf("select 2") + "select 2;".length, target.end)
    }

    // --- Offsets --------------------------------------------------------------

    @Test
    fun `the span of a selected statement maps a PostgreSQL error position back onto the document`() {
        // Non-ASCII before the error is the case the conversion exists for: PostgreSQL
        // counts characters and Kotlin counts UTF-16 units, and an emoji is two of one
        // and one of the other.
        val script = "select 1;\nselect '🐘', frm invoices;"
        val target = ready(script, script.indexOf("select '🐘"), script.length)
        val position = target.statement.text.codePointCount(0, target.statement.text.indexOf("frm")) + 1

        assertEquals(script.indexOf("frm"), target.statement.documentIndex(position))
    }

    @Test
    fun `offsets out of range are clamped rather than thrown`() {
        val script = "select 1;"
        assertEquals("select 1;", ready(script, -50, -50).sql)
        assertEquals("select 1;", ready(script, 900, 900).sql)
    }
}

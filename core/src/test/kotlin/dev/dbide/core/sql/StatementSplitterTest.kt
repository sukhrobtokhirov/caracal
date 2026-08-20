package dev.dbide.core.sql

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class StatementSplitterTest {

    @Test
    fun `splits two ordinary statements`() {
        assertEquals(
            listOf("select 1;", "select 2;"),
            texts("select 1; select 2;"),
        )
    }

    @Test
    fun `accepts a final statement without a semicolon`() {
        assertEquals(listOf("select 1;", "select 2"), texts("select 1; select 2"))
    }

    @Test
    fun `reports offsets that point back at the script`() {
        val script = "  select 1;\n  select 2  "
        val statements = split(script)

        assertEquals(listOf("select 1;", "select 2"), statements.map { it.text })
        assertEquals(listOf(2, 14), statements.map { it.start })
        // The span excludes the surrounding whitespace, so highlighting a statement
        // does not highlight the blank lines around it.
        assertEquals(listOf(11, 22), statements.map { it.end })
    }

    // A semicolon inside any of these is text, not a boundary. Splitting here is the
    // failure that sends half a function body to the server.

    @Test
    fun `a semicolon inside a string is not a boundary`() {
        assertEquals(listOf("select ';';"), texts("select ';';"))
    }

    @Test
    fun `a doubled quote does not close a string`() {
        assertEquals(listOf("select 'it''s; fine';"), texts("select 'it''s; fine';"))
    }

    @Test
    fun `a semicolon inside a quoted identifier is not a boundary`() {
        assertEquals(listOf("""select "a;b" from t;"""), texts("""select "a;b" from t;"""))
    }

    @Test
    fun `a doubled double quote does not close an identifier`() {
        assertEquals(listOf("""select "a""b;c" from t;"""), texts("""select "a""b;c" from t;"""))
    }

    @Test
    fun `a semicolon inside a line comment is not a boundary`() {
        assertEquals(
            listOf("select 1 -- ; not yet\n, 2;"),
            texts("select 1 -- ; not yet\n, 2;"),
        )
    }

    @Test
    fun `a semicolon inside a block comment is not a boundary`() {
        assertEquals(listOf("select /* ; */ 1;"), texts("select /* ; */ 1;"))
    }

    @Test
    fun `block comments nest`() {
        // C would end the comment at the first `*/` and run `still comment */ 1`.
        assertEquals(listOf("select /* /* ; */ still comment */ 1;"), texts("select /* /* ; */ still comment */ 1;"))
    }

    @Test
    fun `an escaped quote in an escape string does not close it`() {
        assertEquals(listOf("""select E'it\'s; fine';"""), texts("""select E'it\'s; fine';"""))
    }

    @Test
    fun `a backslash in an ordinary string is not an escape`() {
        // standard_conforming_strings has been on by default since 9.1: this string
        // ends at the second quote, and the statement ends at the semicolon.
        assertEquals(listOf("""select '\';""", "select 2;"), texts("""select '\'; select 2;"""))
    }

    @Test
    fun `an E ending an identifier does not start an escape string`() {
        // `sizeE` is a name; `'\'` is an ordinary string that ends at its own quote.
        assertEquals(listOf("""select sizeE'\';""", "select 2;"), texts("""select sizeE'\'; select 2;"""))
    }

    @Test
    fun `keeps a plpgsql body in one statement`() {
        val script = """
            create function f() returns int as $$
            begin
              raise notice 'one; two';
              return 1;
            end;
            $$ language plpgsql;
            select 1;
        """.trimIndent()

        val statements = texts(script)
        assertEquals(2, statements.size, "the function body was split at a semicolon inside it")
        assertTrue(statements[0].endsWith("language plpgsql;"))
        assertEquals("select 1;", statements[1])
    }

    @Test
    fun `a tagged dollar quote ignores a different dollar token inside it`() {
        val script = "select ${'$'}body${'$'} a ${'$'}other${'$'} b ; c ${'$'}body${'$'};"
        assertEquals(listOf(script), texts(script))
    }

    @Test
    fun `a parameter placeholder does not open a dollar quote`() {
        // `$1` is not a delimiter. Reading it as one would swallow every boundary
        // until the next `$`, which here is `$2`.
        assertEquals(
            listOf("select * from t where a = ${'$'}1;", "select ${'$'}2;"),
            texts("select * from t where a = ${'$'}1; select ${'$'}2;"),
        )
    }

    @Test
    fun `ignores empty and comment-only input`() {
        assertEquals(emptyList(), texts(""))
        assertEquals(emptyList(), texts("   \n\t "))
        assertEquals(emptyList(), texts(";;;"))
        assertEquals(emptyList(), texts("-- just a note"))
        assertEquals(emptyList(), texts("/* just a note */"))
        assertEquals(emptyList(), texts("-- a note\n/* another */\n;"))
    }

    @Test
    fun `drops empty segments between statements`() {
        assertEquals(listOf("select 1;", "select 2;"), texts("select 1;;; select 2;; -- done"))
    }

    @Test
    fun `keeps a comment that leads a statement`() {
        // PostgreSQL accepts it, and keeping it means the highlighted span is the
        // span that was sent, so reported error positions still line up.
        assertEquals(listOf("-- why\nselect 1;"), texts("\n-- why\nselect 1;\n"))
    }

    @Test
    fun `a line comment ends at a CRLF newline`() {
        assertEquals(listOf("select 1 -- note\r\n, 2;", "select 3;"), texts("select 1 -- note\r\n, 2; select 3;"))
    }

    @Test
    fun `a quote or comment may end the script`() {
        assertEquals(listOf("select 1;"), texts("select 1; -- done"))
        assertEquals(listOf("select 1;"), texts("select 1; /* done */"))
        assertEquals(listOf("select ';';"), texts("select ';';"))
        assertEquals(listOf("select ${'$'}${'$'}x${'$'}${'$'}"), texts("select ${'$'}${'$'}x${'$'}${'$'}"))
    }

    @Test
    fun `every unterminated construct is reported with the offset it opened at`() {
        assertEquals(LexicalError(SqlConstruct.STRING, 7), StatementSplitter.split("select 'oops").error)
        assertEquals(LexicalError(SqlConstruct.ESCAPE_STRING, 7), StatementSplitter.split("""select E'oops\'""").error)
        assertEquals(LexicalError(SqlConstruct.QUOTED_IDENTIFIER, 7), StatementSplitter.split("""select "oops""").error)
        assertEquals(LexicalError(SqlConstruct.BLOCK_COMMENT, 7), StatementSplitter.split("select /* oops").error)
        assertEquals(
            LexicalError(SqlConstruct.BLOCK_COMMENT, 7),
            StatementSplitter.split("select /* /* oops */").error,
            "an inner comment closed and the outer one was reported as complete",
        )
        assertEquals(
            LexicalError(SqlConstruct.DOLLAR_QUOTE, 7),
            StatementSplitter.split("select ${'$'}body${'$'} oops").error,
        )
    }

    @Test
    fun `an unterminated construct still yields the statements before it`() {
        val result = StatementSplitter.split("select 1; select 2; select 'unfinished")

        assertEquals(listOf("select 1;", "select 2;"), result.statements.map { it.text })
        assertEquals(SqlConstruct.STRING, result.error?.construct)
    }

    @Test
    fun `a clean script has no error`() {
        assertNull(StatementSplitter.split("select 1;").error)
    }

    // Which statement Run acts on when nothing is selected.

    @Test
    fun `the caret inside a statement selects it`() {
        val script = "select 1;\nselect 2;"
        val parsed = StatementSplitter.split(script)

        assertEquals("select 1;", parsed.statementAt(script.indexOf("1"))?.text)
        assertEquals("select 2;", parsed.statementAt(script.indexOf("2"))?.text)
    }

    @Test
    fun `the caret at either edge of a statement selects it`() {
        val parsed = StatementSplitter.split("select 1;")

        assertEquals("select 1;", parsed.statementAt(0)?.text)
        assertEquals("select 1;", parsed.statementAt(9)?.text)
    }

    @Test
    fun `the caret between statements selects the one below`() {
        val script = "select 1;\n\n\nselect 2;"
        val parsed = StatementSplitter.split(script)

        assertEquals("select 2;", parsed.statementAt(10)?.text)
    }

    @Test
    fun `the caret in a comment above a statement selects that statement`() {
        // The comment leads the statement, so it is inside its span rather than in
        // the gap before it — and Run there means running what the comment describes.
        val script = "select 1;\n\n-- next\nselect 2;"
        val parsed = StatementSplitter.split(script)

        assertEquals("-- next\nselect 2;", parsed.statementAt(script.indexOf("next"))?.text)
    }

    @Test
    fun `the caret on a boundary between adjacent statements selects the one below`() {
        val parsed = StatementSplitter.split("select 1;select 2;")

        assertEquals("select 2;", parsed.statementAt(9)?.text)
    }

    @Test
    fun `the caret after the last statement falls back to it`() {
        // The caret parked on the empty line under the statement you just typed.
        val script = "select 1;\n\n"
        val parsed = StatementSplitter.split(script)

        assertEquals("select 1;", parsed.statementAt(script.length)?.text)
    }

    @Test
    fun `a script with no statement selects nothing`() {
        assertNull(StatementSplitter.split("-- just a note").statementAt(3))
        assertNull(StatementSplitter.split("").statementAt(0))
    }

    // PostgreSQL counts error positions in characters; Kotlin counts UTF-16 units.

    @Test
    fun `maps a PostgreSQL error position onto the document`() {
        val script = "select 1;\nselect frm t;"
        val second = split(script)[1]

        // 1-based: position 8 is the `f` of `frm`.
        assertEquals(script.indexOf("frm"), second.documentIndex(8))
        assertEquals(second.start, second.documentIndex(1))
    }

    @Test
    fun `a non-BMP character before the error does not shift the position`() {
        // The emoji is one PostgreSQL character and two Kotlin chars. Counting units
        // would underline the `frm` one character to the left, and two emoji would
        // put it two to the left.
        val script = "select '🙂' as a, frm t;"
        val statement = split(script).single()
        val position = statement.text.codePointCount(0, statement.text.indexOf("frm")) + 1

        assertEquals(script.indexOf("frm"), statement.documentIndex(position))
    }

    @Test
    fun `an error position at end of input maps to the end of the statement`() {
        val statement = split("select 1 from").single()
        val characters = statement.text.codePointCount(0, statement.text.length)

        assertEquals(statement.end, statement.documentIndex(characters + 1))
    }

    @Test
    fun `an out-of-range error position maps to nothing`() {
        val statement = split("select 1;").single()

        assertNull(statement.documentIndex(0))
        assertNull(statement.documentIndex(-1))
        assertNull(statement.documentIndex(statement.text.length + 2))
    }

    /**
     * The splitter runs on every keystroke against whatever the user has typed so
     * far, which is usually not valid SQL. It has to survive all of it: no hang, no
     * exception, and no span that lies about where it came from.
     *
     * The seed is fixed. A splitter bug that only shows up on Tuesday's random
     * corpus is a flaky test, not a finding.
     */
    @Test
    @Timeout(60)
    fun `arbitrary input yields well-formed spans without hanging`() {
        val fragments = listOf(
            "select", " ", "1", ",", ";", "\n", "\r\n", "'", "\"", "\\", "--", "/*", "*/",
            "$", "$$", "${'$'}body${'$'}", "e'", "E'", "u&'", "b'", "x'", "😀", "é", "_t", "(", ")",
        )
        val random = Random(20260820)

        repeat(2_000) {
            val script = (0..random.nextInt(40)).joinToString("") { fragments.random(random) }
            val result = StatementSplitter.split(script)

            var previousEnd = 0
            for (statement in result.statements) {
                assertTrue(statement.start in previousEnd..statement.end, "overlapping span in ${script.quoted()}")
                assertTrue(statement.end <= script.length, "span past the end of ${script.quoted()}")
                assertEquals(
                    script.substring(statement.start, statement.end),
                    statement.text,
                    "text does not match its own span in ${script.quoted()}",
                )
                assertTrue(statement.text.isNotBlank(), "blank statement from ${script.quoted()}")
                previousEnd = statement.end
            }
            result.error?.let {
                assertTrue(it.start in 0..script.length, "error offset outside ${script.quoted()}")
                assertTrue(it.start >= previousEnd, "error inside an already-split statement in ${script.quoted()}")
            }
        }
    }

    private fun split(script: String): List<Statement> = StatementSplitter.split(script).statements

    private fun texts(script: String): List<String> = split(script).map { it.text }

    private fun String.quoted(): String = "<<$this>>"
}

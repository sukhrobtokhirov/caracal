package dev.dbide.core.sql

/**
 * One executable statement, and where it sits in the script it came from.
 *
 * [text] is `script.substring(start, end)` — never normalized, never rewritten.
 * What the editor highlights, what the server executes, and what a PostgreSQL
 * error position counts into are all the same string, which is the only reason
 * [documentIndex] can map an error back onto the document.
 *
 * Offsets are Kotlin string indices, which is to say UTF-16 code units. That is
 * what Compose's text field uses for its selection, so the common case — take a
 * span, show it — needs no conversion at all. PostgreSQL is the odd one out, and
 * [documentIndex] is the only place the difference is handled.
 */
data class Statement(val text: String, val start: Int, val end: Int) {
    /**
     * Where a PostgreSQL error position lands in the document, or `null` if the
     * position is not inside this statement.
     *
     * `PSQLException.getErrorPosition` is 1-based and counts *characters*, meaning
     * code points. Kotlin counts UTF-16 code units. The two agree exactly until a
     * value outside the BMP appears — one emoji in a literal before the error and
     * every subsequent highlight is off by one, drifting further with each. The
     * conversion is cheap and the bug it prevents is the kind that gets reported
     * as "the error underline is in the wrong place sometimes".
     *
     * A position one past the last character is accepted: PostgreSQL reports an
     * error at end of input that way, and it maps to [end].
     */
    fun documentIndex(postgresPosition: Int): Int? {
        if (postgresPosition < 1) return null
        val characters = text.codePointCount(0, text.length)
        if (postgresPosition > characters + 1) return null
        return start + text.offsetByCodePoints(0, minOf(postgresPosition - 1, characters))
    }
}

/** A PostgreSQL lexical construct that can be left open at the end of a script. */
enum class SqlConstruct(val code: String, val message: String) {
    STRING("unterminated_string", "A quoted string is never closed."),
    ESCAPE_STRING("unterminated_escape_string", "An escape string is never closed."),
    QUOTED_IDENTIFIER("unterminated_identifier", "A quoted identifier is never closed."),
    BLOCK_COMMENT("unterminated_block_comment", "A block comment is never closed."),
    DOLLAR_QUOTE("unterminated_dollar_quote", "A dollar-quoted block is never closed."),
}

/** An unterminated [construct] that opened at [start]. */
data class LexicalError(val construct: SqlConstruct, val start: Int) {
    val code: String get() = construct.code
    val message: String get() = construct.message
}

/**
 * The statements in a script, and the lexical error that stopped the scan.
 *
 * [statements] holds everything that closed cleanly *before* [error], rather than
 * nothing at all. Half-typed SQL is the normal state of an editor: the moment you
 * type the opening quote of the third statement, the first two are still perfectly
 * runnable, and refusing to name them would disable Run while the user types.
 */
data class SplitScript(val statements: List<Statement>, val error: LexicalError? = null) {
    /**
     * The statement to run when the caret is at [cursor] and nothing is selected.
     *
     * The caret is usually inside a statement, and then this is obvious. The cases
     * worth stating are the others. A caret in the blank line or the comment between
     * two statements belongs to the one *below* it — that is where the user is
     * heading, and it matches how they read the script. A caret in the trailing
     * whitespace after the last statement has nowhere below to go, so it falls back
     * to the one above rather than doing nothing: pressing Run with the caret parked
     * on the empty last line is supposed to run the statement you just finished
     * typing, not silently no-op.
     *
     * Returns `null` only when the script holds no statement at all.
     */
    fun statementAt(cursor: Int): Statement? =
        statements.lastOrNull { cursor >= it.start && cursor <= it.end }
            ?: statements.firstOrNull { it.start > cursor }
            ?: statements.lastOrNull()
}

/**
 * Splits a PostgreSQL script into executable statements.
 *
 * Not by splitting on semicolons. A semicolon inside a string, an identifier, a
 * comment, or a `$$` function body is ordinary text, and a splitter that misses
 * that will cheerfully cut a PL/pgSQL body in half and send the pieces to the
 * server. So this is a real lexer for the constructs that hide a semicolon — and
 * only those. It recognizes nothing about SQL semantics, and it does not need to.
 */
object StatementSplitter {

    fun split(script: String): SplitScript {
        val statements = mutableListOf<Statement>()
        var segmentStart = 0
        // Whether this segment holds anything but whitespace and comments. A segment
        // that does not is not an empty statement, it is not a statement at all.
        var hasContent = false
        var index = 0

        fun endSegment(segmentEnd: Int) {
            if (hasContent) statements += statement(script, segmentStart, segmentEnd)
            segmentStart = segmentEnd
            hasContent = false
        }

        while (index < script.length) {
            val char = script[index]
            when {
                char == '-' && script.startsWith("--", index) ->
                    index = endOfLineComment(script, index)

                char == '/' && script.startsWith("/*", index) ->
                    index = endOfBlockComment(script, index)
                        ?: return SplitScript(statements, LexicalError(SqlConstruct.BLOCK_COMMENT, index))

                char == '\'' -> {
                    hasContent = true
                    index = endOfQuoted(script, index, escapes = false)
                        ?: return SplitScript(statements, LexicalError(SqlConstruct.STRING, index))
                }

                (char == 'e' || char == 'E') && startsEscapeString(script, index) -> {
                    hasContent = true
                    index = endOfQuoted(script, index + 1, escapes = true)
                        ?: return SplitScript(statements, LexicalError(SqlConstruct.ESCAPE_STRING, index))
                }

                char == '"' -> {
                    hasContent = true
                    index = endOfQuoted(script, index, escapes = false)
                        ?: return SplitScript(statements, LexicalError(SqlConstruct.QUOTED_IDENTIFIER, index))
                }

                char == '$' -> {
                    val tag = dollarTagAt(script, index)
                    if (tag == null) {
                        // A lone `$` — a parameter placeholder like `$1`, or an operator.
                        hasContent = true
                        index++
                    } else {
                        hasContent = true
                        val close = script.indexOf(tag, startIndex = index + tag.length)
                        if (close < 0) {
                            return SplitScript(statements, LexicalError(SqlConstruct.DOLLAR_QUOTE, index))
                        }
                        index = close + tag.length
                    }
                }

                char == ';' -> {
                    index++
                    endSegment(index)
                }

                else -> {
                    if (!char.isWhitespace()) hasContent = true
                    index++
                }
            }
        }

        // A script whose last statement has no semicolon still ends in a statement.
        endSegment(script.length)
        return SplitScript(statements)
    }

    /**
     * Trims the segment down to the text worth executing.
     *
     * Surrounding whitespace goes; a leading comment stays. PostgreSQL accepts a
     * comment before a statement, and keeping it means the span the editor
     * highlights is the span that was sent, so error positions still line up.
     */
    private fun statement(script: String, from: Int, to: Int): Statement {
        var start = from
        var end = to
        while (start < end && script[start].isWhitespace()) start++
        while (end > start && script[end - 1].isWhitespace()) end--
        return Statement(script.substring(start, end), start, end)
    }

    /** The index after a `--` comment: the newline that ends it, or end of input. */
    private fun endOfLineComment(script: String, open: Int): Int {
        val newline = script.indexOf('\n', startIndex = open)
        return if (newline < 0) script.length else newline
    }

    /**
     * The index after a block comment, or `null` if it never closes.
     *
     * PostgreSQL nests these, unlike C: a comment opened twice has to be closed
     * twice, and stopping at the first closing delimiter would leave the tail of
     * the comment to be parsed as live SQL.
     */
    private fun endOfBlockComment(script: String, open: Int): Int? {
        var depth = 0
        var index = open
        while (index < script.length) {
            when {
                script.startsWith("/*", index) -> {
                    depth++
                    index += 2
                }

                script.startsWith("*/", index) -> {
                    depth--
                    index += 2
                    if (depth == 0) return index
                }

                else -> index++
            }
        }
        return null
    }

    /**
     * The index after a string or quoted identifier opening at [open], or `null` if
     * it never closes.
     *
     * A doubled quote (`''` or `""`) is an escaped quote rather than a close, in
     * both flavors. [escapes] additionally makes a backslash consume the next
     * character, which is true of `E'...'` and — since `standard_conforming_strings`
     * became the default — of nothing else. Ordinary `'\'` is a complete string
     * containing a backslash, and reading it as an escape would swallow the rest of
     * the script.
     */
    private fun endOfQuoted(script: String, open: Int, escapes: Boolean): Int? {
        val quote = script[open]
        var index = open + 1
        while (index < script.length) {
            val char = script[index]
            when {
                escapes && char == '\\' -> index += 2
                char != quote -> index++
                script.getOrNull(index + 1) == quote -> index += 2
                else -> return index + 1
            }
        }
        return null
    }

    /**
     * Whether an `E` at [index] introduces an escape string rather than being the
     * tail of a name. `E'x'` is one; the `e` of `value e'x'` is one; the `E` of
     * `sizeE'x'` is not — that is the identifier `sizeE` followed by a plain string,
     * where a backslash is just a backslash.
     *
     * `B'..'`, `X'..'`, and `U&'..'` need no such test: none of them give the
     * backslash a meaning, so the plain-string scan already handles them.
     */
    private fun startsEscapeString(script: String, index: Int): Boolean {
        if (script.getOrNull(index + 1) != '\'') return false
        val before = script.getOrNull(index - 1) ?: return true
        return !before.isLetterOrDigit() && before != '_' && before != '$'
    }

    /**
     * The delimiter of the dollar quote opening at [index] (`"$$"`, `"$body$"`), or
     * `null` if this `$` opens nothing.
     *
     * The `null` case is the one that matters: `$1` is a parameter placeholder, and
     * a splitter that read it as an opening delimiter would treat everything up to
     * the next `$` as quoted and lose every statement boundary in between.
     */
    private fun dollarTagAt(script: String, index: Int): String? {
        var cursor = index + 1
        while (cursor < script.length) {
            val char = script[cursor]
            when {
                char == '$' -> return script.substring(index, cursor + 1)
                isTagChar(char, first = cursor == index + 1) -> cursor++
                else -> return null
            }
        }
        return null
    }

    /** PostgreSQL's tag alphabet: letters, underscore, non-ASCII, and digits after the first. */
    private fun isTagChar(char: Char, first: Boolean): Boolean =
        char.isLetter() || char == '_' || char.code >= 0x80 || (!first && char.isDigit())
}

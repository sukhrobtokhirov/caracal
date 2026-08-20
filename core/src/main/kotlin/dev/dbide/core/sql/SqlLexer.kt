package dev.dbide.core.sql

/**
 * The parts of PostgreSQL's lexical grammar that can hide a character from a
 * naive scan — strings, quoted identifiers, comments, and dollar-quoted blocks.
 *
 * Both readers of a script need exactly this and nothing more. [StatementSplitter]
 * looks for semicolons that are really statement boundaries; [StatementClassifier]
 * looks for keywords that are really keywords. Neither knows any SQL semantics,
 * and a second copy of these rules would eventually disagree with the first.
 */
internal object SqlLexer {

    /** The index after a `--` comment: the newline that ends it, or end of input. */
    fun endOfLineComment(script: String, open: Int): Int {
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
    fun endOfBlockComment(script: String, open: Int): Int? {
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
    fun endOfQuoted(script: String, open: Int, escapes: Boolean): Int? {
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
    fun startsEscapeString(script: String, index: Int): Boolean {
        if (script.getOrNull(index + 1) != '\'') return false
        val before = script.getOrNull(index - 1) ?: return true
        return !before.isLetterOrDigit() && before != '_' && before != '$'
    }

    /**
     * The delimiter of the dollar quote opening at [index] (`"$$"`, `"$body$"`), or
     * `null` if this `$` opens nothing.
     *
     * The `null` case is the one that matters: `$1` is a parameter placeholder, and
     * a scan that read it as an opening delimiter would treat everything up to the
     * next `$` as quoted.
     */
    fun dollarTagAt(script: String, index: Int): String? {
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

    /**
     * The bare words of [sql], uppercased, with everything quoted or commented left
     * out.
     *
     * "Bare" is the whole point. A column called `"update"` is an identifier, the
     * text `'delete from t'` is data, and `-- drop it` is a note to a colleague.
     * None of them is a keyword, and a classifier that counted them would refuse to
     * run perfectly ordinary queries.
     */
    fun bareWords(sql: String): Sequence<String> = sequence {
        var index = 0
        while (index < sql.length) {
            val char = sql[index]
            index = when {
                sql.startsWith("--", index) -> endOfLineComment(sql, index)
                sql.startsWith("/*", index) -> endOfBlockComment(sql, index) ?: sql.length
                char == '\'' -> endOfQuoted(sql, index, escapes = false) ?: sql.length
                (char == 'e' || char == 'E') && startsEscapeString(sql, index) ->
                    endOfQuoted(sql, index + 1, escapes = true) ?: sql.length

                char == '"' -> endOfQuoted(sql, index, escapes = false) ?: sql.length
                char == '$' -> skipDollarQuote(sql, index)
                isWordStart(char) -> {
                    val end = endOfWord(sql, index)
                    yield(sql.substring(index, end).uppercase())
                    end
                }

                else -> index + 1
            }
        }
    }

    private fun skipDollarQuote(sql: String, index: Int): Int {
        val tag = dollarTagAt(sql, index) ?: return index + 1
        val close = sql.indexOf(tag, startIndex = index + tag.length)
        return if (close < 0) sql.length else close + tag.length
    }

    private fun endOfWord(sql: String, start: Int): Int {
        var index = start
        while (index < sql.length && isWordPart(sql[index])) index++
        return index
    }

    private fun isWordStart(char: Char): Boolean = char.isLetter() || char == '_' || char.code >= 0x80

    private fun isWordPart(char: Char): Boolean = isWordStart(char) || char.isDigit()

    /** PostgreSQL's tag alphabet: letters, underscore, non-ASCII, and digits after the first. */
    private fun isTagChar(char: Char, first: Boolean): Boolean =
        char.isLetter() || char == '_' || char.code >= 0x80 || (!first && char.isDigit())
}

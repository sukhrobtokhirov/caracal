package dev.dbide.core.sql

/** What a stretch of a script is, for the purpose of colouring it. */
enum class TokenKind {
    /** A reserved or otherwise well-known PostgreSQL word, in code rather than in text. */
    KEYWORD,

    /** A string literal, in any of PostgreSQL's spellings, dollar-quoted bodies included. */
    STRING,

    /** A `--` line comment, or a block comment however deeply nested. */
    COMMENT,

    /** A numeric literal. */
    NUMBER,

    /** A double-quoted identifier — a name, and never a keyword however it is spelled. */
    QUOTED_IDENTIFIER,

    /** A `$1` placeholder. */
    PARAMETER,
}

/** One coloured span of a script: `script.substring(start, end)`. */
data class SqlToken(val kind: TokenKind, val start: Int, val end: Int)

/** An `(`…`)` or `[`…`]` pair, by document offset. */
data class BracketPair(val open: Int, val close: Int)

/**
 * A script broken into the spans an editor colours, and the bracket under the caret.
 *
 * This lives beside [SqlLexer] rather than in the UI for the same reason
 * [StatementClassifier] does: the rules about what is a string, what is a comment,
 * and what is a `$$` body are already written down once here, and a second copy in a
 * Compose file would eventually disagree with this one about where a statement ends.
 * What stays in the UI is the part that is genuinely about looking at things —
 * which colour a [TokenKind] gets.
 *
 * Only the spans that are *not* ordinary code are returned. Everything else is
 * plain text drawn in the default colour, so a large script produces a list
 * proportional to its literals and keywords rather than to its length.
 */
object SqlHighlighting {

    /**
     * How much of a script is coloured by default.
     *
     * Highlighting re-runs on every keystroke, and Compose lays out the whole styled
     * document each time. That is nothing for the twenty-line query this editor is
     * for and real work for the two-thousand-line migration file someone pastes into
     * it, so the scan stops here and the rest of the document renders as plain text —
     * still editable, still executable, just not coloured. The plan names this trade
     * as the price of a Compose editor.
     */
    const val DEFAULT_LIMIT: Int = 40_000

    /**
     * The coloured spans of [sql], scanning at most [limit] characters.
     *
     * A token that begins inside the limit is returned whole, so a `$$` body that
     * crosses the boundary is coloured as the one thing it is rather than being cut
     * in half.
     */
    fun tokens(sql: String, limit: Int = DEFAULT_LIMIT): List<SqlToken> {
        val stop = minOf(sql.length, limit)
        val tokens = mutableListOf<SqlToken>()
        var index = 0

        fun span(kind: TokenKind, end: Int, from: Int = index) {
            tokens += SqlToken(kind, from, end)
            index = end
        }

        while (index < stop) {
            val char = sql[index]
            when {
                sql.startsWith("--", index) -> span(TokenKind.COMMENT, SqlLexer.endOfLineComment(sql, index))

                sql.startsWith("/*", index) ->
                    span(TokenKind.COMMENT, SqlLexer.endOfBlockComment(sql, index) ?: sql.length)

                char == '\'' ->
                    span(TokenKind.STRING, SqlLexer.endOfQuoted(sql, index, escapes = false) ?: sql.length)

                (char == 'e' || char == 'E') && SqlLexer.startsEscapeString(sql, index) ->
                    span(TokenKind.STRING, SqlLexer.endOfQuoted(sql, index + 1, escapes = true) ?: sql.length)

                char == '"' ->
                    span(
                        TokenKind.QUOTED_IDENTIFIER,
                        SqlLexer.endOfQuoted(sql, index, escapes = false) ?: sql.length,
                    )

                char == '$' -> index = dollar(sql, index, tokens)

                char.isDigit() && !continuesWord(sql, index) -> span(TokenKind.NUMBER, endOfNumber(sql, index))

                isWordStart(char) -> {
                    val end = endOfWord(sql, index)
                    // Only keywords are coloured. A table name is a name, and colouring
                    // every word the same as `SELECT` would say nothing at all.
                    if (sql.substring(index, end).uppercase() in KEYWORDS) {
                        span(TokenKind.KEYWORD, end)
                    } else {
                        index = end
                    }
                }

                else -> index++
            }
        }
        return tokens
    }

    /**
     * The brackets around, or beside, a caret at [caret], or `null` if there are none
     * or the one there is unmatched.
     *
     * A caret sits between two characters, so both are considered: standing just
     * after a `)` matches it, which is where the caret is when you have finished
     * typing one. Brackets inside a string or a comment are text and take no part —
     * `WHERE note = ')'` closes nothing.
     */
    fun matchingBracket(sql: String, caret: Int, limit: Int = DEFAULT_LIMIT): BracketPair? {
        if (sql.isEmpty()) return null
        val pairs = bracketPairs(sql, limit)
        val at = caret.coerceIn(0, sql.length)
        return pairs.firstOrNull { it.open == at || it.close == at } // caret before a bracket
            ?: pairs.firstOrNull { it.open == at - 1 || it.close == at - 1 } // caret after one
    }

    /** Every matched bracket pair in the code of [sql], strings and comments excluded. */
    private fun bracketPairs(sql: String, limit: Int): List<BracketPair> {
        val text = tokens(sql, limit).filter { it.kind == TokenKind.STRING || it.kind == TokenKind.COMMENT }
        val pairs = mutableListOf<BracketPair>()
        val open = ArrayDeque<Int>()
        var index = 0
        var skipped = 0

        while (index < sql.length) {
            // The literals are in document order, so each is passed once rather than
            // searched for.
            val hidden = text.getOrNull(skipped)
            if (hidden != null && index >= hidden.start) {
                index = hidden.end
                skipped++
                continue
            }
            when (sql[index]) {
                '(', '[' -> open.addLast(index)
                ')', ']' -> open.removeLastOrNull()?.let { start ->
                    if (closes(sql[start], sql[index])) pairs += BracketPair(start, index)
                }
            }
            index++
        }
        return pairs
    }

    private fun closes(open: Char, close: Char): Boolean =
        (open == '(' && close == ')') || (open == '[' && close == ']')

    /**
     * A `$` opens a dollar-quoted string, or is a parameter placeholder, or is part of
     * an operator. Only the first two are worth colouring, and telling them apart is
     * exactly what [SqlLexer.dollarTagAt] is for.
     */
    private fun dollar(sql: String, index: Int, tokens: MutableList<SqlToken>): Int {
        val tag = SqlLexer.dollarTagAt(sql, index)
        if (tag != null) {
            val close = sql.indexOf(tag, startIndex = index + tag.length)
            val end = if (close < 0) sql.length else close + tag.length
            tokens += SqlToken(TokenKind.STRING, index, end)
            return end
        }
        if (sql.getOrNull(index + 1)?.isDigit() != true) return index + 1
        val end = endOfNumber(sql, index + 1)
        tokens += SqlToken(TokenKind.PARAMETER, index, end)
        return end
    }

    /**
     * The end of a numeric literal. Deliberately permissive: `1.5e-3` is one token,
     * and so is anything close enough to it that colouring the parts separately would
     * look like an error the script does not contain.
     */
    private fun endOfNumber(sql: String, start: Int): Int {
        var index = start
        while (index < sql.length) {
            val char = sql[index]
            val exponentSign = (char == '+' || char == '-') &&
                (sql[index - 1] == 'e' || sql[index - 1] == 'E')
            index = when {
                char.isDigit() || char == '.' || char == 'e' || char == 'E' || exponentSign -> index + 1
                else -> return index
            }
        }
        return index
    }

    private fun endOfWord(sql: String, start: Int): Int {
        var index = start
        while (index < sql.length && isWordPart(sql[index])) index++
        return index
    }

    /** Whether the character before [index] makes this the tail of a word: `t1`, not `1`. */
    private fun continuesWord(sql: String, index: Int): Boolean =
        index > 0 && isWordPart(sql[index - 1])

    private fun isWordStart(char: Char): Boolean = char.isLetter() || char == '_' || char.code >= 0x80

    private fun isWordPart(char: Char): Boolean = isWordStart(char) || char.isDigit()

    /**
     * The words worth colouring.
     *
     * Not PostgreSQL's full keyword list, which runs past a thousand entries and
     * includes words like `name` and `value` that are keywords only in grammatical
     * positions nobody writes by hand. What is here is what appears in the queries
     * this editor is for; a word that is missing renders as plain text, which is the
     * failure mode that costs nothing.
     */
    private val KEYWORDS: Set<String> = setOf(
        "ALL", "ALTER", "ANALYZE", "AND", "ANY", "ARRAY", "AS", "ASC", "BEGIN", "BETWEEN",
        "BY", "CASCADE", "CASE", "CAST", "CHECK", "COALESCE", "COLLATE", "COLUMN", "COMMIT",
        "CONFLICT", "CONSTRAINT", "COPY", "CREATE", "CROSS", "CUBE", "CURRENT", "CURRENT_DATE",
        "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "CURSOR", "DECLARE", "DEFAULT",
        "DELETE", "DESC", "DISTINCT", "DO", "DROP", "ELSE", "END", "ESCAPE", "EXCEPT", "EXCLUDE",
        "EXECUTE", "EXISTS", "EXPLAIN", "FALSE", "FETCH", "FILTER", "FIRST", "FOLLOWING", "FOR",
        "FOREIGN", "FROM", "FULL", "FUNCTION", "GRANT", "GROUP", "GROUPING", "HAVING", "IF",
        "ILIKE", "IN", "INDEX", "INHERITS", "INNER", "INSERT", "INTERSECT", "INTO", "IS",
        "ISNULL", "JOIN", "KEY", "LAST", "LATERAL", "LEADING", "LEFT", "LIKE", "LIMIT",
        "LOCK", "MATERIALIZED", "MERGE", "NATURAL", "NEW", "NEXT", "NO", "NOT", "NOTHING",
        "NOTNULL", "NULL", "NULLIF", "NULLS", "OFFSET", "ON", "ONLY", "OR", "ORDER", "OUTER",
        "OVER", "OVERLAPS", "OWNER", "PARTITION", "PRECEDING", "PRIMARY", "PROCEDURE", "RANGE",
        "REFERENCES", "REFRESH", "RENAME", "REPLACE", "RESET", "RETURNING", "RETURNS", "REVOKE",
        "RIGHT", "ROLLBACK", "ROLLUP", "ROW", "ROWS", "SAVEPOINT", "SCHEMA", "SELECT", "SEQUENCE",
        "SESSION", "SET", "SETS", "SHOW", "SIMILAR", "SOME", "TABLE", "TABLESAMPLE", "TEMPORARY",
        "THEN", "TIES", "TO", "TRAILING", "TRANSACTION", "TRIGGER", "TRUE", "TRUNCATE", "TYPE",
        "UNBOUNDED", "UNION", "UNIQUE", "UNKNOWN", "UPDATE", "USER", "USING", "VACUUM", "VALUES",
        "VIEW", "WHEN", "WHERE", "WINDOW", "WITH", "WITHIN", "WITHOUT",
    )
}

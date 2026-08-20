package dev.dbide.core.sql

/**
 * What a statement looks like it will do.
 *
 * "Looks like" is the honest verb. This is a keyword classifier, and a keyword
 * classifier cannot be a security boundary: a function called from a `SELECT` can
 * write, and no amount of scanning the text will reveal it. Enforcement is a
 * PostgreSQL `READ ONLY` transaction, where the server decides. What the
 * classifier is for is telling the user what they are about to do *before* they do
 * it, which the server cannot help with.
 *
 * It errs toward [WRITE]. A false [WRITE] costs one confirmation dialog; a false
 * [READ] means a production table changed without one being shown.
 */
enum class StatementKind {
    /** Nothing in the text suggests a modification. */
    READ,

    /** The text contains a data-modifying or schema-modifying command. */
    WRITE,

    /**
     * Transaction, session, or cursor state — `BEGIN`, `SET`, `DISCARD`, `LOCK`.
     *
     * Harmless-looking and the one case worth refusing outright on a read-only
     * connection. Connections are pooled, so `SET SESSION CHARACTERISTICS AS
     * TRANSACTION READ WRITE` outlives the transaction it was run in and would
     * quietly disarm the read-only guarantee for whoever gets that connection next.
     */
    SESSION,

    /** Nothing recognizable. Warn, and let the read-only transaction decide. */
    UNKNOWN,
}

/**
 * Classifies a single statement by the keywords it actually contains.
 *
 * "Actually" is doing work there too: the text `'delete from t'` is a string, the
 * column `"update"` is an identifier, and `-- drop it` is a comment. [SqlLexer]
 * removes all three before a single keyword is compared, so ordinary queries do
 * not collect spurious warnings.
 */
object StatementClassifier {

    fun classify(sql: String): StatementKind {
        val words = SqlLexer.bareWords(sql).iterator()
        if (!words.hasNext()) return StatementKind.UNKNOWN

        val first = words.next()
        if (first in SESSION_COMMANDS) return StatementKind.SESSION
        if (first in COMMAND_WRITES || first in WRITES) return StatementKind.WRITE

        // The rest of the statement matters as much as its first word. `WITH deleted
        // AS (DELETE FROM t RETURNING *) SELECT * FROM deleted` begins with WITH and
        // empties a table, and `SELECT ... FOR UPDATE` takes row locks that a
        // read-only transaction refuses.
        while (words.hasNext()) {
            if (words.next() in WRITES) return StatementKind.WRITE
        }

        return if (first in READS) StatementKind.READ else StatementKind.UNKNOWN
    }

    /**
     * Keywords that mean a modification anywhere they appear, including nested in a
     * CTE or behind `EXPLAIN ANALYZE`.
     *
     * Most are non-reserved words that a table may legitimately use as a column
     * name, so `SELECT update FROM audit` is classified as a write. That is the
     * trade accepted deliberately: an unnecessary confirmation on an unusual schema,
     * against missing a real write. `INTO` is here for `SELECT ... INTO new_table`,
     * which creates one.
     */
    private val WRITES = setOf(
        "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "COPY",
        "CREATE", "ALTER", "DROP", "GRANT", "REVOKE",
        "CALL", "DO", "INTO",
    )

    /**
     * Modifying commands recognized only as the first word.
     *
     * `ANALYZE` is why this set exists separately: as a command it rewrites
     * statistics, but in `EXPLAIN ANALYZE SELECT ...` it is a read that the guide's
     * own workflow depends on. Matching it anywhere would refuse the most common
     * way to inspect a slow query.
     */
    private val COMMAND_WRITES = setOf(
        "VACUUM", "ANALYZE", "ANALYSE", "REINDEX", "CLUSTER", "CHECKPOINT",
        "REFRESH", "COMMENT", "IMPORT", "REASSIGN", "EXECUTE", "SECURITY",
    )

    /** Transaction, session, and cursor control. First word only; all unambiguous there. */
    private val SESSION_COMMANDS = setOf(
        "BEGIN", "START", "COMMIT", "END", "ROLLBACK", "ABORT",
        "SAVEPOINT", "RELEASE", "PREPARE", "DEALLOCATE",
        "SET", "RESET", "DISCARD", "LOCK",
        "LISTEN", "UNLISTEN", "NOTIFY",
        "DECLARE", "FETCH", "MOVE", "CLOSE",
    )

    /** Statements that read, unless something later in the text says otherwise. */
    private val READS = setOf("SELECT", "TABLE", "VALUES", "WITH", "SHOW", "EXPLAIN")
}

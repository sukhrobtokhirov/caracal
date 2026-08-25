package dev.caracal.engine.sqlite

import dev.caracal.core.sql.StatementClassifier
import dev.caracal.core.sql.StatementKind
import dev.caracal.engine.api.IntentClassifier
import dev.caracal.engine.api.WriteIntent

/**
 * SQLite's half of §7: what a statement looks like it will do.
 *
 * The shared [StatementClassifier] with one dialect-shaped addition, and the reason
 * for reusing it is the reason §12 gives for sharing the splitter: a SQL keyword
 * scanner that lexes strings and comments away first is machinery, not dialect, and a
 * second copy of it would disagree with the first the day either is edited.
 *
 * What SQLite adds is the word the shared table has never had to know: **`REPLACE`**.
 * `REPLACE INTO t VALUES (...)` is SQLite's `INSERT OR REPLACE`, it deletes the rows
 * it conflicts with, and the shared classifier answers `UNKNOWN` for it — safe, and
 * a confirmation dialog on one of the three statements people write most. `PRAGMA` is
 * the mirror image and is deliberately left alone: some pragmas read, some rewrite the
 * whole file, and telling them apart means a table of pragma names that is wrong the
 * next time SQLite gains one. `UNKNOWN` is the right answer to a question this cannot
 * answer, and it is the answer that costs a dialog rather than a database.
 *
 * [StatementKind.SESSION] becomes [WriteIntent.CONNECTION_AFFECTING] as it does for
 * PostgreSQL, and it means something slightly different here. SQLite's `BEGIN` and
 * `COMMIT` are refused outright rather than quietly undone — a connection that is
 * already inside this engine's transaction cannot start another — so the editor has
 * something more specific to say than "this succeeded and did nothing". That is a
 * message, not a classification, and it does not change the arm.
 */
object SqliteIntent : IntentClassifier {

    override fun classify(statement: String): WriteIntent {
        val shared = when (StatementClassifier.classify(statement)) {
            StatementKind.READ -> WriteIntent.READ_ONLY
            StatementKind.SESSION -> WriteIntent.CONNECTION_AFFECTING
            StatementKind.WRITE -> WriteIntent.WRITE
            StatementKind.UNKNOWN -> WriteIntent.UNKNOWN
        }
        if (shared != WriteIntent.UNKNOWN) return shared
        return if (startsWithReplace(statement)) WriteIntent.WRITE else WriteIntent.UNKNOWN
    }

    /**
     * Whether this statement's first word is `REPLACE`.
     *
     * First word only, and never anywhere: `replace()` is SQLite's string function and
     * appears in the middle of a great many `SELECT`s. Matching it there would classify
     * `SELECT replace(name, 'a', 'b') FROM t` as a write, which is the false positive
     * that teaches people to click through confirmations.
     *
     * The scan skips leading whitespace and nothing else, which is the conservative
     * direction: a `REPLACE` hidden behind a comment falls through to `UNKNOWN`, and
     * `UNKNOWN` is treated as at least a write by whoever consumes it.
     */
    private fun startsWithReplace(statement: String): Boolean {
        val trimmed = statement.trimStart()
        if (!trimmed.startsWith(REPLACE, ignoreCase = true)) return false
        // `REPLACEMENT` is a legal table name and starts with the same seven letters.
        val next = trimmed.getOrNull(REPLACE.length) ?: return false
        return !next.isLetterOrDigit() && next != '_'
    }

    private const val REPLACE = "REPLACE"
}

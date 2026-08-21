package dev.caracal.core.export

import dev.caracal.core.sql.Statement
import dev.caracal.core.sql.StatementClassifier
import dev.caracal.core.sql.StatementKind
import dev.caracal.core.sql.StatementSplitter

/**
 * Why a statement cannot be exported, in words a user can act on.
 *
 * There is no `code` string here because the constant is the code: the UI
 * branches on the enum and shows the [message], and a parallel set of strings
 * would only be a second thing to keep in step.
 */
enum class ExportRefusal(val message: String) {
    NOTHING_TO_EXPORT("There is no statement to export."),

    /** A quote or comment that never closes. What the rest of the text means is a guess. */
    UNFINISHED_STATEMENT("The statement is unfinished, so it cannot be run again."),

    SEVERAL_STATEMENTS("Export runs a single statement, and this is a script of several."),

    /** The classifier's [StatementKind.WRITE]. Running it again is the objection. */
    MODIFIES_DATA("Export runs the statement again, and this one modifies data."),

    SESSION_STATEMENT("This statement changes session state and returns no rows to export."),

    UNRECOGNIZED_STATEMENT("This statement is not recognizably a read, and export would run it again."),

    /** Recognized as a read and still produced no result set — `SELECT` into nothing, or a false positive. */
    NO_ROWS("The statement returned no rows to export."),
}

/**
 * Whether a statement may be exported, decided before anything runs.
 *
 * Export re-runs the query. That is the design the plan asks for — streaming a
 * fresh read costs no memory, where holding a completed result until someone
 * might click Export costs all of it — and it is also the whole of this check's
 * reason to exist. Everything the grid can display was already safe to run *once*.
 * Running it a *second* time is a new decision, and a statement that modified data
 * would modify it again.
 *
 * The read-only transaction remains the boundary; the pool opens every connection
 * read-only and the server refuses the write whatever this concludes. What this
 * adds is the refusal that can be explained: a disabled Export button with a
 * sentence next to it, rather than a SQLSTATE 25006 the user has to interpret
 * after the fact.
 */
sealed interface ExportEligibility {

    /** [statement] is the single statement to run, exactly as it was written. */
    data class Allowed(val statement: Statement) : ExportEligibility

    data class Refused(val refusal: ExportRefusal) : ExportEligibility

    companion object {
        fun of(script: String): ExportEligibility {
            val split = StatementSplitter.split(script)
            // Refused even when statements did close before the error. The splitter is
            // right that the first two of three are runnable, but which one the user
            // means to export is not a question a broken script can answer.
            if (split.error != null) return Refused(ExportRefusal.UNFINISHED_STATEMENT)

            val statement = split.statements.singleOrNull()
                ?: return Refused(
                    if (split.statements.isEmpty()) ExportRefusal.NOTHING_TO_EXPORT
                    else ExportRefusal.SEVERAL_STATEMENTS,
                )

            return when (StatementClassifier.classify(statement.text)) {
                StatementKind.READ -> Allowed(statement)
                StatementKind.WRITE -> Refused(ExportRefusal.MODIFIES_DATA)
                StatementKind.SESSION -> Refused(ExportRefusal.SESSION_STATEMENT)
                StatementKind.UNKNOWN -> Refused(ExportRefusal.UNRECOGNIZED_STATEMENT)
            }
        }
    }
}

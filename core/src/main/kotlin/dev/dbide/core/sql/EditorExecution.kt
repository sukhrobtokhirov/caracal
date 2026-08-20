package dev.dbide.core.sql

/** Why the editor picked this text: the user selected it, or the caret was in it. */
enum class TargetSource { SELECTION, CURSOR }

/**
 * What Run will send, and where in the document it came from.
 *
 * [statement] carries document offsets in both cases, including when the text was
 * selected rather than found — which is what lets the editor draw the span it is
 * about to execute, and lets a PostgreSQL error position land back on the right
 * character through [Statement.documentIndex].
 */
data class ExecutionTarget(val statement: Statement, val source: TargetSource) {
    val sql: String get() = statement.text
    val start: Int get() = statement.start
    val end: Int get() = statement.end
}

/** A reason the editor will not run what it was given. */
enum class ExecutionRefusal(val code: String, val message: String) {
    NOTHING_TO_RUN("nothing_to_run", "There is nothing to run."),
    MULTIPLE_STATEMENTS(
        "multiple_statements",
        "The selection holds more than one statement. Select one of them, or put the caret in it.",
    ),
}

/** What Run would do right now. */
sealed interface Execution {
    data class Ready(val target: ExecutionTarget) : Execution

    /**
     * Run is unavailable, and [message] says why in a sentence the editor can show
     * beside a disabled button. [code] is the stable case, exactly as `DbError` and
     * `Failure` carry one, so nothing downstream parses the sentence.
     */
    data class Refused(val code: String, val message: String) : Execution {
        constructor(refusal: ExecutionRefusal) : this(refusal.code, refusal.message)
        constructor(error: LexicalError) : this(error.code, error.message)
    }
}

/**
 * The one rule that decides what Run runs.
 *
 * Predictability is the whole feature. An editor that sometimes sends the statement
 * you meant and sometimes the one above it is an editor you check twice before every
 * click, so the rule is short enough to hold in your head:
 *
 * - a selection runs exactly the selected text, once it has been checked to be a
 *   single statement;
 * - otherwise the statement the caret is in runs;
 * - a caret between statements belongs to the one below it, and a caret past the
 *   last one falls back to the one above.
 *
 * Checking the selection here rather than trusting it is the point of [resolve]
 * being in `:core`. "Execute the selected text" is not the same promise as "execute
 * whatever the user happened to drag over": a selection spanning two statements is
 * refused rather than sent, because one statement is what Run means and a
 * half-selected `WHERE` clause is how a demonstration becomes an incident.
 *
 * Running a whole script is deliberately absent. The guide makes Run script
 * optional, and every statement here executes in its own read-only transaction, so
 * a sequence of them would offer none of the atomicity that would make it worth the
 * extra failure modes in v0.1.
 */
object EditorExecution {

    /**
     * What Run would send for a caret or selection between [selectionStart] and
     * [selectionEnd]. The two may arrive in either order — a selection dragged
     * backwards reports its anchor first — and out of range offsets are clamped
     * rather than throwing, because they arrive from a text field that may be one
     * frame behind the text.
     */
    fun resolve(script: String, selectionStart: Int, selectionEnd: Int): Execution {
        val from = minOf(selectionStart, selectionEnd).coerceIn(0, script.length)
        val to = maxOf(selectionStart, selectionEnd).coerceIn(0, script.length)
        return if (from == to) atCursor(script, from) else inSelection(script, from, to)
    }

    /**
     * The selected text, if it is one statement.
     *
     * The span is trimmed of surrounding whitespace, which is the only rewriting done
     * anywhere on the way to the server: what is highlighted, what is executed, and
     * what an error position counts into stay the same string.
     */
    private fun inSelection(script: String, from: Int, to: Int): Execution {
        val split = StatementSplitter.split(script.substring(from, to))
        split.error?.let { return Execution.Refused(it) }
        val statement = when (split.statements.size) {
            0 -> return Execution.Refused(ExecutionRefusal.NOTHING_TO_RUN)
            1 -> split.statements.single()
            else -> return Execution.Refused(ExecutionRefusal.MULTIPLE_STATEMENTS)
        }
        val target = Statement(statement.text, from + statement.start, from + statement.end)
        return Execution.Ready(ExecutionTarget(target, TargetSource.SELECTION))
    }

    /**
     * The statement the caret is in.
     *
     * An unterminated quote at or before the caret refuses rather than falling back
     * to the statement above it. Half-typed SQL is the normal state of an editor, and
     * the statements before the opening quote are still perfectly runnable — but the
     * caret being *inside* the unterminated one says which statement the user means,
     * and silently running a different one is the failure this rule exists to prevent.
     */
    private fun atCursor(script: String, cursor: Int): Execution {
        val split = StatementSplitter.split(script)
        val error = split.error
        if (error != null && cursor >= error.start) return Execution.Refused(error)
        val statement = split.statementAt(cursor)
            ?: return Execution.Refused(ExecutionRefusal.NOTHING_TO_RUN)
        return Execution.Ready(ExecutionTarget(statement, TargetSource.CURSOR))
    }
}

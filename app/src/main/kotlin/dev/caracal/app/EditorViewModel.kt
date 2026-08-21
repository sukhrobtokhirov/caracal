package dev.caracal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionService
import dev.caracal.core.policy.Clearance
import dev.caracal.core.policy.DataSafetyPolicy
import dev.caracal.core.result.Failure
import dev.caracal.core.result.toFailure
import dev.caracal.core.sql.Execution
import dev.caracal.core.sql.ExecutionTarget
import dev.caracal.core.sql.EditorExecution
import dev.caracal.core.sql.SplitScript
import dev.caracal.core.sql.Statement
import dev.caracal.core.sql.StatementSplitter
import dev.caracal.core.sql.TargetSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** What the editor has to show for the last thing Run was asked to do. */
sealed interface EditorRun {
    /** Nothing has been run since the editor was opened or cleared. */
    data object Idle : EditorRun

    /** [target] is on the server now, and can be cancelled. */
    data class Running(val target: ExecutionTarget) : EditorRun

    /**
     * [grid] is what came back, and [target] is the statement that produced it —
     * kept because export re-runs that statement, and the text in the editor may
     * have been edited since it was sent. Exporting what is on screen means
     * exporting what filled it, not whatever the caret is in now.
     */
    data class Done(val target: ExecutionTarget, val grid: ResultGridState) : EditorRun

    /**
     * It failed, and [errorAt] is where in the document — not in the statement — the
     * server said the problem was.
     *
     * `null` covers three different situations that all deserve the same treatment:
     * the server reported no position, it reported one inside a query it generated
     * itself, or the failure never reached a server at all. §2.9's rule for all
     * three is the same — show the message, and do not guess at a place to point at.
     *
     * [statement] is the text that was sent, at the offsets it was sent from, and it
     * is kept for one reason: [errorAt] was computed against a script that can be
     * edited while the statement is still on the server. [ErrorMarker] uses it to
     * decide whether that offset still describes anything. `null` for a failure that
     * never had a statement — the read-only refusal below is raised before one is
     * chosen.
     */
    data class Failed(
        val failure: Failure,
        val errorAt: Int? = null,
        val statement: Statement? = null,
    ) : EditorRun

    /** The user stopped it. Not a failure, and not drawn as one. */
    data object Cancelled : EditorRun

    /**
     * There was a result and it has been let go to keep the window's memory bounded.
     *
     * §4.10's rule, and the second half of it is what this state exists for: an
     * evicted result must keep its query text and say that it has to be run again.
     * A tab that quietly went back to "No result yet" would be a tab that looks like
     * it was never used, and its owner would go looking for what they had lost.
     *
     * [target] is the statement that produced the result, so the pane can say what
     * is missing and Run sends the same thing again. The script itself was never
     * touched — it is in the editor, where the user left it.
     */
    data class Released(val target: ExecutionTarget) : EditorRun
}

/**
 * Where the editor should point for the last failure, if anywhere.
 *
 * Three answers rather than a nullable offset, because "nowhere to point" and
 * "there was somewhere and it is gone" are different things to say to a user. The
 * first is ordinary — most failures carry no position at all. The second means the
 * server did name a character and the script has since moved out from under it, and
 * §4.6 asks for that to be said rather than papered over: an underline drawn at a
 * stale offset is a confident claim about the wrong word.
 */
sealed interface ErrorMarker {
    /** Nothing to point at: no failure, or a failure the server gave no position for. */
    data object None : ErrorMarker

    /** The character the server named, at its place in the document as it reads now. */
    data class At(val index: Int) : ErrorMarker

    /** The server named a character; the text it counted into is no longer there. */
    data object Moved : ErrorMarker
}

/**
 * A statement the policy will not send until the user says so, and the terms it
 * has to be agreed to on.
 *
 * The target is captured here rather than re-resolved when the dialog is
 * dismissed. Between opening the dialog and clicking its button the caret can
 * move, and what runs must be the statement the dialog described — not whichever
 * one the caret has since wandered into.
 */
data class PendingWrite(val target: ExecutionTarget, val clearance: Clearance.Confirm)

/**
 * One SQL editor: the text, what Run would send, and what the last run produced.
 *
 * The rule that decides *what* runs is [EditorExecution]'s, in `:core`, where it can
 * be tested against a corpus rather than against a window. What is here is the part
 * that is genuinely about an editor being used by a person: that the text and the
 * caret are the same state the text field edits, that Run is unavailable while a
 * statement is on the server, and that cancelling stops that statement and nothing
 * else — the object browser keeps loading, the connection stays open, and the text
 * is exactly where it was.
 *
 * The query runs in its own [Job] inside the window's [scope]. Cancelling that job
 * is what reaches the server: `PostgresAdapter` cancels the statement from another
 * thread the moment the job is cancelled, because a coroutine cancellation on its
 * own cannot interrupt a blocked JDBC call.
 */
class EditorViewModel(
    private val service: ConnectionService,
    private val scope: CoroutineScope,
) {
    /**
     * The connection statements are sent to, or `null` when there is none.
     *
     * The whole configuration and not just the identifier, because §2.4's policy is
     * a question about the connection: whether it is marked read only, which
     * environment it is, and what it is called — the last one being the word a
     * production confirmation asks the user to type.
     */
    var connection: ConnectionConfig? by mutableStateOf(null)
        private set

    /** Where statements are sent, or `null` when there is nowhere to send them. */
    val connectionId: ConnectionId? get() = connection?.id

    /** The script and the caret, as the text field holds them. */
    var text: TextFieldValue by mutableStateOf(TextFieldValue())
        private set

    /** The statements in [text], recomputed as it is typed. */
    var script: SplitScript by mutableStateOf(StatementSplitter.split(""))
        private set

    /** What Run would send right now, or why it would not. */
    var execution: Execution by mutableStateOf(EditorExecution.resolve("", 0, 0))
        private set

    /**
     * The text this tab last took from somewhere it could be taken from again.
     *
     * Empty for a tab that was opened blank, and the statement itself for one opened
     * from history — which is the whole of what [dirty] needs to know. A script that
     * still reads exactly as history has it is a script history still has; anything
     * else in here exists in this process and nowhere else, because §4.3 deliberately
     * does not persist drafts.
     */
    private var baseline: String by mutableStateOf("")

    var run: EditorRun by mutableStateOf(EditorRun.Idle)
        private set

    /** The statement waiting on a confirmation, or `null` when none is being asked for. */
    var pending: PendingWrite? by mutableStateOf(null)
        private set

    /**
     * A script waiting to take the editor over, once the user agrees to lose what is
     * there now. `null` when nothing is being asked for.
     *
     * The text is held here rather than fetched again when the dialog is answered,
     * for the same reason [PendingWrite] holds its target: what arrives must be what
     * the dialog offered, and the panel it came from can be scrolled, refiltered, or
     * closed while the question is on screen.
     */
    var pendingScript: String? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /** The span Run would send, for the editor to draw. `null` when Run is unavailable. */
    val target: ExecutionTarget? get() = (execution as? Execution.Ready)?.target

    val running: Boolean get() = run is EditorRun.Running

    /**
     * Where the editor should draw the last failure, checked against the text as it
     * reads now.
     *
     * The check is here rather than in the composable because it is a rule, not a
     * drawing decision: the same answer has to hold for the message beside the
     * result as for the underline in the script, and two surfaces deciding it
     * separately is how they come to disagree.
     *
     * A marker deliberately survives edits *elsewhere* in the script. A failure you
     * are in the middle of reading is not something to snatch away because the caret
     * moved into the statement below it.
     */
    val marker: ErrorMarker
        get() {
            val failed = run as? EditorRun.Failed ?: return ErrorMarker.None
            val index = failed.errorAt ?: return ErrorMarker.None
            val statement = failed.statement ?: return ErrorMarker.None
            if (!statement.isIntactIn(text.text)) return ErrorMarker.Moved
            // One past the last character is a place, not an overrun: PostgreSQL
            // reports an error at end of input that way, and `documentIndex` maps it
            // to the statement's end. There is no character there to underline, and
            // the editor draws it as a position rather than a span.
            return if (index <= text.text.length) ErrorMarker.At(index) else ErrorMarker.Moved
        }

    /**
     * Whether closing this tab would lose work.
     *
     * Not "has been edited": a tab holding a statement reopened from history and left
     * alone is a tab whose contents can be reopened again, and asking about it would
     * be asking a question whose answer never matters. Whitespace is not work either.
     */
    val dirty: Boolean get() = text.text.isNotBlank() && text.text != baseline

    /** Whether Run would do anything if it were pressed. */
    val runnable: Boolean
        get() = connection != null && !running && pending == null && execution is Execution.Ready

    /**
     * What Run is about to do, in the toolbar.
     *
     * Naming the statement by its number is the cheap half of the promise §2.3 makes:
     * the editor also draws the span, but a person glancing at a toolbar before
     * pressing a button on a production connection deserves to be told in words which
     * of the four statements on screen is the one that will go.
     */
    val runLabel: String
        get() = when (val execution = execution) {
            is Execution.Refused -> execution.message
            is Execution.Ready -> when (execution.target.source) {
                TargetSource.SELECTION -> "Runs the selection."
                TargetSource.CURSOR -> {
                    val position = script.statements.indexOf(execution.target.statement)
                    if (position < 0 || script.statements.size == 1) {
                        "Runs the statement at the caret."
                    } else {
                        "Runs statement ${position + 1} of ${script.statements.size}."
                    }
                }
            }
        }

    /**
     * Points the editor at a connection, or at nothing.
     *
     * The text survives the change and the result does not. A script is the user's
     * work and belongs to them; a result is a claim about one server, and leaving it
     * under a different connection's editor would be the same grid saying something
     * that is no longer true.
     *
     * Being handed the *same* connection with edited settings is not a change of
     * connection: the new configuration is taken — the policy must ask the current
     * one, or unticking Read only would go unnoticed until the editor was reopened —
     * and the result stays, because it is still a true statement about that server.
     */
    fun show(config: ConnectionConfig?) {
        if (config == connection) return
        if (config?.id != connection?.id) {
            stop()
            run = EditorRun.Idle
        }
        connection = config
    }

    /** Takes an edit from the text field: new text, new caret, or both. */
    fun edit(value: TextFieldValue) {
        val changed = value.text != text.text
        text = value
        if (changed) script = StatementSplitter.split(value.text)
        execution = EditorExecution.resolve(value.text, value.selection.start, value.selection.end)
    }

    /**
     * Inserts [sql] at the caret, replacing the selection, and leaves the caret after
     * it.
     *
     * A space is added when the character before would otherwise run into the new
     * text, so double-clicking a table in the browser after typing `select * from`
     * produces valid SQL rather than `fromsales."orders"`.
     */
    fun insert(sql: String) {
        val selection = text.selection
        val from = minOf(selection.start, selection.end)
        val to = maxOf(selection.start, selection.end)
        val separator = if (from > 0 && needsSpace(text.text[from - 1])) " " else ""
        val inserted = separator + sql
        val updated = text.text.replaceRange(from, to, inserted)
        edit(TextFieldValue(updated, TextRange(from + inserted.length)))
    }

    /**
     * Puts [sql] in the editor, in place of whatever is there.
     *
     * This is how a statement reopened from history arrives. It asks first when there
     * is something to lose — §4.3's unsaved-change rule, and the reason it is enforced
     * here rather than in the panel that calls it: the editor is the only thing that
     * knows whether its text is a script someone is halfway through or the blank it
     * was opened with. A script that is only whitespace is not work.
     *
     * The caret lands at the end, where someone about to edit the statement wants it,
     * and the result of the previous run is left alone — it is still a true statement
     * about the server, and it is often the reason the query is being reopened.
     */
    fun open(sql: String) {
        if (text.text.isBlank() || text.text == sql) replace(sql) else pendingScript = sql
    }

    /** Agrees to lose the current script, and takes the one that was offered. */
    fun confirmOpen() {
        val waiting = pendingScript ?: return
        pendingScript = null
        replace(waiting)
    }

    /** Keeps what is in the editor. The offered script is dropped. */
    fun cancelOpen() {
        pendingScript = null
    }

    private fun replace(sql: String) {
        edit(TextFieldValue(sql, TextRange(sql.length)))
        baseline = sql
    }

    /**
     * Runs what [execution] resolved to, once §2.4's policy has been satisfied.
     *
     * Three outcomes, and the policy picks between them: send it, ask first, or
     * refuse it here without a round trip. The refusal is the one worth explaining.
     * A read-only connection's pool would reject a write on the server anyway, and
     * that rejection is the guarantee — but "you cannot run this, and here is why"
     * said before the statement leaves is a better sentence than the same fact
     * arriving as SQLSTATE 25006 half a second after a `DELETE` was sent to
     * production.
     *
     * A second call while one is running, or while a confirmation is open, is
     * ignored.
     */
    fun execute() {
        val connection = connection ?: return
        val ready = execution as? Execution.Ready ?: return
        if (running || pending != null) return

        when (val clearance = DataSafetyPolicy.clearanceFor(ready.target.sql, connection)) {
            Clearance.Granted -> send(ready.target)
            is Clearance.Confirm -> pending = PendingWrite(ready.target, clearance)
            is Clearance.Refused -> run = EditorRun.Failed(Failure(clearance.code, clearance.message))
        }
    }

    /**
     * Agrees to the pending statement and sends it.
     *
     * [acknowledgement] is what the user typed, and it is checked here rather than
     * only in the dialog that collected it. A rule that lives in a composable is a
     * rule that holds for as long as nobody adds a second way to press the button;
     * this is the one place a production write can be released, so this is where the
     * word has to match.
     */
    fun confirm(acknowledgement: String = "") {
        val waiting = pending ?: return
        if (!waiting.clearance.satisfiedBy(acknowledgement)) return
        pending = null
        send(waiting.target)
    }

    /** Dismisses the confirmation without running anything. */
    fun cancelConfirmation() {
        pending = null
    }

    private fun send(target: ExecutionTarget) {
        val id = connectionId ?: return
        run = EditorRun.Running(target)
        job = scope.launch {
            try {
                val result = service.execute(id, target.sql)
                // A result that arrived after the user cancelled is not shown: they
                // asked for it to stop, and the grid would be saying otherwise.
                ensureActive()
                run = EditorRun.Done(target, ResultGridState(result))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (problem: Throwable) {
                val failure = problem.toFailure()
                // §2.9: the server counts its position into the statement it was sent,
                // so only the statement that was sent can turn it back into a place in
                // the document. Doing it here, while that statement is still in hand,
                // is why the editor never has to guess.
                run = EditorRun.Failed(
                    failure = failure,
                    errorAt = failure.query?.position?.let { target.statement.documentIndex(it) },
                    statement = target.statement,
                )
            }
        }
    }

    /**
     * Stops the running statement.
     *
     * Cancelled is its own state rather than a red error, because nothing failed:
     * the user asked for the work to stop and it stopped.
     */
    fun cancel() {
        if (!running) return
        job?.cancel()
        job = null
        run = EditorRun.Cancelled
    }

    /**
     * Lets go of the result while keeping everything else.
     *
     * §4.10 bounds how many result models the window holds at once, and this is the
     * one operation that does it. It is only ever applied to a finished result: a
     * running statement is about to produce one, and a failure is a sentence rather
     * than a grid, so neither is worth anything to evict.
     *
     * The export goes too. It reports on a file streamed from a statement the tab is
     * no longer showing the result of, and leaving "Wrote 12 rows" under a pane that
     * says the result is gone is two answers to one question.
     */
    fun release(): Boolean {
        val done = run as? EditorRun.Done ?: return false
        run = EditorRun.Released(done.target)
        return true
    }

    /** Drops the script and everything run from it. Called when the vault locks. */
    fun clear() {
        stop()
        connection = null
        edit(TextFieldValue())
        baseline = ""
        run = EditorRun.Idle
    }

    private fun stop() {
        job?.cancel()
        job = null
        // A confirmation belongs to the connection it was raised against. Leaving one
        // open across a change of connection is how the wrong server gets written to.
        pending = null
        pendingScript = null
    }

    private fun needsSpace(before: Char): Boolean =
        !before.isWhitespace() && before != '(' && before != '.' && before != ','
}

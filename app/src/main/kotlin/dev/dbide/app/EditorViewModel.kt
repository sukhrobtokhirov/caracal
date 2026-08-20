package dev.dbide.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionService
import dev.dbide.core.policy.Clearance
import dev.dbide.core.policy.DataSafetyPolicy
import dev.dbide.core.result.Failure
import dev.dbide.core.result.toFailure
import dev.dbide.core.sql.Execution
import dev.dbide.core.sql.ExecutionTarget
import dev.dbide.core.sql.EditorExecution
import dev.dbide.core.sql.SplitScript
import dev.dbide.core.sql.StatementSplitter
import dev.dbide.core.sql.TargetSource
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
     */
    data class Failed(val failure: Failure, val errorAt: Int? = null) : EditorRun

    /** The user stopped it. Not a failure, and not drawn as one. */
    data object Cancelled : EditorRun
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

    var run: EditorRun by mutableStateOf(EditorRun.Idle)
        private set

    /** The statement waiting on a confirmation, or `null` when none is being asked for. */
    var pending: PendingWrite? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /** The span Run would send, for the editor to draw. `null` when Run is unavailable. */
    val target: ExecutionTarget? get() = (execution as? Execution.Ready)?.target

    val running: Boolean get() = run is EditorRun.Running

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

    /** Drops the script and everything run from it. Called when the vault locks. */
    fun clear() {
        stop()
        connection = null
        edit(TextFieldValue())
        run = EditorRun.Idle
    }

    private fun stop() {
        job?.cancel()
        job = null
        // A confirmation belongs to the connection it was raised against. Leaving one
        // open across a change of connection is how the wrong server gets written to.
        pending = null
    }

    private fun needsSpace(before: Char): Boolean =
        !before.isWhitespace() && before != '(' && before != '.' && before != ','
}

package dev.caracal.engine.sqlite

import dev.caracal.core.result.DbException
import dev.caracal.engine.api.CancelResult
import dev.caracal.engine.api.QueryFacet
import dev.caracal.engine.api.QuoteStyle
import dev.caracal.engine.api.ResultDescriptor
import dev.caracal.engine.api.Row
import dev.caracal.engine.api.SplitterConfig
import dev.caracal.engine.api.StatementExecution
import dev.caracal.engine.api.StatementOutcome
import dev.caracal.engine.api.StatementRequest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Running statements against SQLite, over a cursor that stays open while it is read. */
internal class SqliteQueryFacet(private val adapter: SqliteAdapter) : QueryFacet {

    /**
     * What makes SQLite's dialect different to split, which is almost nothing.
     *
     * That is the interesting part. SQLite has no dollar quoting and no `E'...'`, and
     * a backslash inside a literal is a backslash — the standard's rules, where the
     * only escape for a quote is doubling it. It is the plainest configuration the
     * shared splitter has been given, which is a useful thing for a third engine to
     * be: the flags PostgreSQL turns on are genuinely PostgreSQL's, and a splitter
     * that had quietly come to depend on one of them would show it here.
     *
     * `--` is the only line comment. SQLite does not have MySQL's `#`.
     */
    override val splitterConfig = SplitterConfig(
        dollarQuotedStrings = false,
        escapedStringLiterals = false,
        backslashEscapesStrings = false,
        lineCommentPrefixes = listOf("--"),
        identifierQuote = QuoteStyle.DOUBLE_QUOTE,
    )

    override suspend fun execute(request: StatementRequest): StatementExecution =
        SqliteStatementExecution(adapter, request)
}

/**
 * One statement in flight, over a cursor that is still open.
 *
 * [outcomes] is cold, and that is what makes cancelling the *collector* mean
 * something: collecting the flow is what runs the statement, so the collector's job is
 * the statement's job. Cancelling it reaches the guard `SqliteAdapter` installs around
 * every blocking step, which calls `Statement.cancel()` from another thread —
 * sqlite-jdbc implements that as `sqlite3_interrupt` on the connection's own database
 * handle, which is the [dev.caracal.engine.api.CancellationSupport.INTERRUPT] mechanism
 * SQLite declares. Without that guard, cancelling a coroutine cannot interrupt a
 * blocked JDBC call at all, and a recursive CTE runs to the end of its recursion with
 * nobody waiting for it.
 *
 * The lifetime rule on [StatementOutcome.Rows] applies here exactly as it does to
 * PostgreSQL, and for a reason that is easy to underrate for a file: the cursor is
 * holding a transaction, and a transaction on a SQLite database is a **lock on the
 * file**. A collector that kept the row flow to read later would not merely find a
 * closed result set; while it held it, every other connection in the window — and
 * every other process on the machine — would be queued behind it.
 */
internal class SqliteStatementExecution(
    private val adapter: SqliteAdapter,
    private val request: StatementRequest,
) : StatementExecution {

    /** The job running the statement, while one is. Read by [cancel] from another one. */
    private val running = AtomicReference<Job?>(null)

    override val outcomes: Flow<StatementOutcome> = flow {
        running.set(currentCoroutineContext()[Job])
        try {
            // A statement that never opened has nothing to close and one outcome to
            // report. Caught here and nowhere wider on purpose: past this point the
            // flow is emitting, and an exception raised by whatever is *collecting* it
            // must reach that collector rather than be caught here and re-emitted into
            // a collector that has already thrown.
            val live = try {
                adapter.open(request)
            } catch (failure: DbException) {
                emit(StatementOutcome.Failed(SqliteEngineErrors.of(failure)))
                return@flow
            }

            var succeeded = false
            try {
                if (live.hasRows) emitRows(live) else emitUpdateCount(live)
                live.complete()
                succeeded = true
            } finally {
                live.close(succeeded)
            }
        } finally {
            running.set(null)
        }
    }

    /**
     * The result set, described and then streamed off the live cursor.
     *
     * No notices are emitted before it, and there is nothing missing: SQLite has no
     * second channel to carry one. That is declared as
     * [dev.caracal.engine.api.EngineCapabilities.surfacesNotices] `= false`, so the
     * conformance suite skips the case that would look for one rather than passing it
     * against an engine that swallows them.
     */
    private suspend fun FlowCollector<StatementOutcome>.emitRows(live: SqliteLiveStatement) {
        emit(
            StatementOutcome.Rows(
                descriptor = ResultDescriptor(columns = live.columns, elapsed = live.elapsed),
                rows = live.rows().map { cells -> Row(cells) },
            ),
        )
        // Only knowable once the reading stopped, which is why it comes after. A
        // collector that abandoned the rows part way through never reached a limit and
        // is told about none.
        live.truncation.reason()?.let { emit(StatementOutcome.Truncated(it)) }
    }

    /**
     * `INSERT`, DDL and `PRAGMA` all come back without a result set. Only the first
     * has a count; the others report zero, which says "this succeeded and changed no
     * rows" and is the honest reading of a statement that reported no number.
     */
    private suspend fun FlowCollector<StatementOutcome>.emitUpdateCount(live: SqliteLiveStatement) {
        emit(StatementOutcome.UpdateCount(count = live.updateCount ?: 0L))
    }

    /**
     * Cancels the statement, if one is running.
     *
     * [CancelResult.ServerAcknowledged] rather than [CancelResult.ClientAbandoned], and
     * "server" is the wrong noun for an engine that has none — but the distinction the
     * type is drawing is the one that matters, and SQLite is on the right side of it:
     * the running statement is *told*, it stops, and it fails with `SQLITE_INTERRUPT`,
     * which arrives back through the same flow. Redis, which can only stop listening,
     * must never report the same thing.
     *
     * Cancelling the collector's job is how the interrupt is reached, rather than a
     * second route to the same `Statement.cancel()`. It is the only one that works
     * wherever the statement happens to be: the blocking call may be the `execute()`
     * that has not returned a cursor yet, and the guard around that call is what fires
     * the interrupt.
     */
    override suspend fun cancel(): CancelResult {
        val job = running.get() ?: return CancelResult.Unsupported("This statement is not running.")
        job.cancel()
        return CancelResult.ServerAcknowledged
    }
}

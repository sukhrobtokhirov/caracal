package dev.caracal.engine.postgres

import dev.caracal.core.postgres.LiveStatement
import dev.caracal.core.postgres.PostgresAdapter
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

/** Running statements against PostgreSQL, through the machinery that already does it. */
internal class PostgresQueryFacet(private val adapter: PostgresAdapter) : QueryFacet {

    /**
     * What makes PostgreSQL's dialect different to split, and nothing else.
     *
     * Dollar quoting is the one that matters: `$tag$ ... $tag$` nests, contains
     * semicolons and quotes and anything else, and is how every function body is
     * written. `E'\n'` is the other — a literal where a backslash escapes, which
     * ordinary SQL literals do not have and MySQL has by default.
     */
    override val splitterConfig = SplitterConfig(
        dollarQuotedStrings = true,
        escapedStringLiterals = true,
        backslashEscapesStrings = false,
        lineCommentPrefixes = listOf("--"),
        identifierQuote = QuoteStyle.DOUBLE_QUOTE,
    )

    override suspend fun execute(request: StatementRequest): StatementExecution =
        PostgresStatementExecution(adapter, request)
}

/**
 * One statement in flight, over a cursor that is still open.
 *
 * [outcomes] is cold, and that is what makes cancelling the *collector* mean
 * something: collecting the flow is what runs the statement, so the collector's job
 * is the statement's job. Cancelling it reaches the guard `PostgresAdapter` installs
 * around every blocking step, which calls `Statement.cancel()` from another thread —
 * pgjdbc opens a side channel and sends the server a cancel request, which is the
 * [dev.caracal.engine.api.CancellationSupport.OUT_OF_BAND] mechanism PostgreSQL
 * declares. Without that guard, cancelling a coroutine cannot interrupt a blocked
 * JDBC call at all and the query runs to completion on the server with nobody
 * waiting for it.
 *
 * The rows are read from the live result set as they are collected, which is what
 * [StatementOutcome.Rows] promises and what issue #4 changed: the adapter used to
 * assemble a list first, and an export streamed out of a list is an export that was
 * held in memory before it reached the disk. The cost is the lifetime rule — the row
 * flow is valid only inside the emission that carried it — and it is stated on
 * [StatementOutcome.Rows] because every engine that streams will have the same one.
 */
internal class PostgresStatementExecution(
    private val adapter: PostgresAdapter,
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
            // a collector that has already thrown. The consequence is worth stating: a
            // failure that arrives after the first row — a connection lost mid-fetch —
            // is thrown rather than reported as [StatementOutcome.Failed], because by
            // then the collector is holding a half-read result and needs to know that
            // rather than to receive another outcome.
            val live = try {
                adapter.open(request)
            } catch (failure: DbException) {
                emit(StatementOutcome.Failed(PostgresEngineErrors.of(failure, request)))
                return@flow
            }

            var succeeded = false
            try {
                emitNotices(live)
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
     * What the server said on the way to the result.
     *
     * First, because a `RAISE NOTICE` was raised before the result existed and because
     * a statement whose entire output is one has nothing else coming. Dropping them
     * shows a blank grid and calls it success.
     */
    private suspend fun FlowCollector<StatementOutcome>.emitNotices(live: LiveStatement) {
        live.notices.forEach { notice ->
            emit(
                StatementOutcome.Notice(
                    text = notice.message,
                    severity = notice.severity,
                    sqlState = notice.sqlState,
                    detail = notice.detail,
                    hint = notice.hint,
                ),
            )
        }
    }

    /**
     * The result set, described and then streamed off the live cursor.
     *
     * The row flow is read by the collector from inside this emission, which is the
     * lifetime rule [StatementOutcome.Rows] states: the transaction and the cursor
     * behind it are released as soon as this returns.
     */
    private suspend fun FlowCollector<StatementOutcome>.emitRows(live: LiveStatement) {
        emit(
            StatementOutcome.Rows(
                descriptor = ResultDescriptor(
                    columns = live.columns.map(PostgresCells::descriptor),
                    elapsed = live.elapsed,
                ),
                rows = live.rows().map { cells ->
                    Row(cells.mapIndexed { index, cell -> PostgresCells.value(cell, live.columns[index]) })
                },
            ),
        )
        // Only knowable once the reading stopped, which is why it comes after. A
        // collector that abandoned the rows part way through never reached a limit and
        // is told about none.
        PostgresCells.truncation(live.truncation)?.let { emit(StatementOutcome.Truncated(it)) }
    }

    /**
     * `INSERT`, DDL, and `SET` all come back without a result set. Only the first of
     * the three has a count; the others report zero, which says "this succeeded and
     * changed no rows" and is the honest reading of a server that sent no number.
     */
    private suspend fun FlowCollector<StatementOutcome>.emitUpdateCount(live: LiveStatement) {
        emit(StatementOutcome.UpdateCount(count = live.updateCount ?: 0L))
    }

    /**
     * Cancels the statement, if one is running.
     *
     * [CancelResult.ServerAcknowledged] rather than [CancelResult.ClientAbandoned]
     * because that is what actually happens: the server receives a cancel request on a
     * second connection and fails the running statement with `57014`, which arrives
     * back through the same flow as a [StatementOutcome.Failed]. The distinction
     * matters enough that Redis, which cannot do this, must never report the same
     * thing.
     *
     * Cancelling the collector's job is how the server is reached, rather than a
     * second route to the same `Statement.cancel()`. It is the only one that works
     * wherever the statement happens to be: the blocking call may be the `execute()`
     * that has not returned a cursor yet, in which case there is no statement for
     * anything else to hold, and the guard around that call is what tells the server.
     */
    override suspend fun cancel(): CancelResult {
        val job = running.get() ?: return CancelResult.Unsupported("This statement is not running.")
        job.cancel()
        return CancelResult.ServerAcknowledged
    }
}

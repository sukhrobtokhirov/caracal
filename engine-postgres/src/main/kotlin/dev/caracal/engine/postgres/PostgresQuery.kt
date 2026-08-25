package dev.caracal.engine.postgres

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
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
 * One statement in flight.
 *
 * [outcomes] is cold, and that is what makes [cancel] mean anything: collecting the
 * flow is what runs the statement, so the collector's job *is* the statement's job.
 * Cancelling it reaches `PostgresAdapter`'s existing guard, which calls
 * `Statement.cancel()` from another thread — pgjdbc opens a side channel and sends
 * the server a cancel request, which is the [dev.caracal.engine.api.CancellationSupport.OUT_OF_BAND]
 * mechanism PostgreSQL declares. Without that guard, cancelling a coroutine cannot
 * interrupt a blocked JDBC call at all and the query runs to completion on the
 * server with nobody waiting for it.
 *
 * The adapter materializes its rows before returning them, so [StatementOutcome.Rows]
 * carries a flow over a list here rather than a live cursor. That is a property of
 * the current adapter and not of the interface: a streaming engine emits the
 * descriptor first and the rows as they arrive, and every caller is already written
 * for that.
 */
internal class PostgresStatementExecution(
    private val adapter: PostgresAdapter,
    private val request: StatementRequest,
) : StatementExecution {

    /** The job running the statement, while one is. Read by [cancel] from another one. */
    private val running = AtomicReference<Job?>(null)

    override val outcomes: Flow<StatementOutcome> = flow {
        val result = try {
            running.set(currentCoroutineContext()[Job])
            adapter.execute(request.sql)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: DbException) {
            emit(StatementOutcome.Failed(PostgresEngineErrors.of(failure, request)))
            return@flow
        } finally {
            running.set(null)
        }

        // Notices first, because they were produced on the way to the result and
        // because a statement whose entire output is a `RAISE NOTICE` has nothing
        // else coming. Dropping them shows a blank grid and calls it success.
        result.notices.forEach { notice ->
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

        if (result.columns.isEmpty()) {
            // `INSERT`, DDL, and `SET` all come back without a result set. Only the
            // first of the three has a count; the others report zero, which says
            // "this succeeded and changed no rows" and is the honest reading of a
            // server that sent no number.
            emit(StatementOutcome.UpdateCount(count = result.rowsAffected ?: 0L))
            return@flow
        }

        val descriptor = ResultDescriptor(
            columns = result.columns.map(PostgresCells::descriptor),
            elapsed = result.duration,
        )
        emit(
            StatementOutcome.Rows(
                descriptor = descriptor,
                rows = result.rows.asFlow().map { cells ->
                    Row(cells.mapIndexed { index, cell -> PostgresCells.value(cell, result.columns[index]) })
                },
            ),
        )
        PostgresCells.truncation(result.truncation)?.let { emit(StatementOutcome.Truncated(it)) }
    }

    /**
     * Cancels the statement, if one is running.
     *
     * [CancelResult.ServerAcknowledged] rather than
     * [CancelResult.ClientAbandoned] because that is what actually happens: the
     * server receives a cancel request on a second connection and fails the running
     * statement with `57014`, which arrives back through the same flow as a
     * [StatementOutcome.Failed]. The distinction matters enough that Redis, which
     * cannot do this, must never report the same thing.
     */
    override suspend fun cancel(): CancelResult {
        val job = running.get() ?: return CancelResult.Unsupported("This statement is not running.")
        job.cancel()
        return CancelResult.ServerAcknowledged
    }
}

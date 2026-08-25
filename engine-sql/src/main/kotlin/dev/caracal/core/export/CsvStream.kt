package dev.caracal.core.export

import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.result.asDbException
import dev.caracal.core.result.toColumn
import dev.caracal.core.result.toCore
import dev.caracal.engine.api.QueryFacet
import dev.caracal.engine.api.StatementOutcome
import dev.caracal.engine.api.StatementRequest
import dev.caracal.engine.api.ValueDetail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile

/**
 * Streaming a statement's rows straight out as CSV, holding none of them.
 *
 * The loop used to live inside `PostgresAdapter`, reachable from `:core` only through
 * `LegacySqlAdapter` — the last place the SPI was bypassed. It is here now because
 * §12 puts export among the things that are shared correctly and because nothing in
 * it is PostgreSQL's: the eligibility check is the shared classifier's, the budgets
 * are the file's, and the rows arrive through [QueryFacet] like every other row in
 * the application. What stayed in the engine is the half that is genuinely its own —
 * reading a value out of its driver.
 *
 * *Re-running* is the word the UI has to pass on to the user. This executes the
 * statement again against the live server, so a table that has changed since the grid
 * was filled exports as it is now and not as it was drawn. The alternative — keeping
 * every completed result alive in case someone exports it — is how an IDE ends up
 * holding a gigabyte per tab, and it is the trade the plan makes deliberately.
 */
object CsvStream {

    /**
     * Runs [sql] again and writes its rows to [out] as CSV.
     *
     * Refused before the statement is sent when [ExportEligibility] says it does not
     * qualify, and refused after it runs when it turns out to produce no result set at
     * all. Neither is the safety boundary — the connection's read-only transaction is,
     * and it still refuses a write that reaches the server by any route — but a refusal
     * here is one that can be explained in a sentence.
     *
     * Returns what was written. A [CsvExportReport] that is not `complete` means
     * [limits] ended the file early, and the caller must say so; cancellation and
     * failure throw instead, so a returned report always describes a file that was
     * finished on purpose.
     */
    suspend fun write(
        facet: QueryFacet,
        sql: String,
        out: Appendable,
        options: CsvOptions = CsvOptions(),
        limits: ExportLimits = ExportLimits(),
    ): CsvExportReport {
        val eligibility = ExportEligibility.of(sql)
        if (eligibility is ExportEligibility.Refused) {
            throw DbException(DbError.ExportUnavailable(eligibility.refusal.message))
        }
        // What runs is the statement the check looked at, rather than the string it was
        // cut from — otherwise the two could differ, and the one that was vetted would
        // not be the one that reaches the server.
        val statement = (eligibility as ExportEligibility.Allowed).statement
        val started = TimeSource.Monotonic.markNow()

        val execution = facet.execute(
            StatementRequest(
                sql = statement.text,
                sourceOffset = statement.start,
                // The row budget is the engine's to enforce, and it is the only one of
                // the three that is: it bounds what the *server* is asked for, where
                // the other two bound a file the engine has never heard of. It reports
                // back through [StatementOutcome.Truncated], which is where this
                // learns that a result was cut rather than finished.
                maxRows = limits.rows,
                // A cell in the grid is clipped because a person is looking at it. A
                // cell in a file is the whole value or it is a lie.
                values = ValueDetail.FULL,
                timeout = limits.duration + CANCEL_GRACE,
            ),
        )

        var report: CsvExportReport? = null
        execution.outcomes.collect { outcome ->
            when (outcome) {
                is StatementOutcome.Rows -> report = outcome.writeTo(out, options, limits, started)

                // `execute()` returned no result set. Nothing a read classifies as
                // should reach this, which is exactly why it is worth having: the
                // alternative is a CSV holding a header and no rows, which reads as an
                // empty table rather than as a statement that had none to give.
                is StatementOutcome.UpdateCount ->
                    throw DbException(DbError.ExportUnavailable(ExportRefusal.NO_ROWS.message))

                is StatementOutcome.Failed -> throw outcome.error.asDbException()

                // The row budget was reached. It arrives after the rows because that is
                // when it became knowable, so the report is amended rather than built
                // from it — and only when the file itself did not stop first, since a
                // file that ran out of bytes stopped for its own reason.
                is StatementOutcome.Truncated -> report = report?.let { written ->
                    if (written.complete) written.copy(stopped = ExportStop.ROW_LIMIT) else written
                }

                // Not part of the file, and there is nowhere in a CSV to put one.
                is StatementOutcome.Notice -> Unit
            }
        }
        return report ?: throw DbException(DbError.ExportUnavailable(ExportRefusal.NO_ROWS.message))
    }

    /**
     * Writes this result out, row by row, keeping nothing.
     *
     * Every budget is checked between rows rather than inside one, so a record is
     * never half-written; the file passes its byte budget by at most its last record,
     * and stops.
     *
     * The rows are consumed inside the emission that carried them, which is what
     * [StatementOutcome.Rows] requires: the engine is holding a cursor open behind the
     * flow and closes it when this returns.
     */
    private suspend fun StatementOutcome.Rows.writeTo(
        out: Appendable,
        options: CsvOptions,
        limits: ExportLimits,
        started: TimeSource.Monotonic.ValueTimeMark,
    ): CsvExportReport {
        val writer = CsvWriter(out, options)
        writer.header(descriptor.columns.map { it.toColumn() })

        var count = 0L
        var stopped = ExportStop.COMPLETE
        // The file's two budgets, asked before each row rather than during one, which
        // is what makes the answer a whole record; `takeWhile` returning false ends the
        // collection, and ending it is the engine's signal to stop fetching and let the
        // cursor go. The row budget is not asked about here — the engine was given it
        // and reports it back.
        rows.takeWhile {
            // Cancellation stops the statement at the server, but rows the driver
            // already fetched keep arriving from its buffer, and writing them to disk is
            // work nobody is waiting for any more. This is what stops that.
            currentCoroutineContext().ensureActive()
            stopped = when {
                writer.bytes >= limits.bytes -> ExportStop.SIZE_LIMIT
                started.elapsedNow() >= limits.duration -> ExportStop.TIME_LIMIT
                else -> ExportStop.COMPLETE
            }
            stopped == ExportStop.COMPLETE
        }.collect { row ->
            writer.row(row.cells.toCore())
            count++
        }

        return CsvExportReport(
            rows = count,
            bytes = writer.bytes,
            duration = started.elapsedNow(),
            stopped = stopped,
        )
    }

    /**
     * How much longer than its own deadline an export's statement is given.
     *
     * Both deadlines exist for different failures. The loop's stops a long export
     * politely, between rows, with a file that ends at a record boundary and a report
     * that says why. The statement's is for the fetch that never comes back, where
     * there is no between-rows to stop in — and it is set later on purpose so that the
     * polite one wins every race that is not that.
     */
    private val CANCEL_GRACE = 30.seconds
}

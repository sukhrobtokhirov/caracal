package dev.dbide.core.postgres

import dev.dbide.core.export.CsvExportReport
import dev.dbide.core.export.CsvOptions
import dev.dbide.core.export.CsvWriter
import dev.dbide.core.export.ExportEligibility
import dev.dbide.core.export.ExportLimits
import dev.dbide.core.export.ExportRefusal
import dev.dbide.core.export.ExportStop
import dev.dbide.core.result.CellValue
import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import dev.dbide.core.result.QueryResult
import dev.dbide.core.result.ResultLimits
import dev.dbide.core.result.Truncation
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Runs one statement at a time against a pooled connection: bounded result,
 * classified error, real cancellation.
 *
 * Nothing here decides whether a statement is *allowed* to write. That is settled
 * one layer down, when the pool is built: a read-only connection opens every
 * connection in a PostgreSQL `READ ONLY` transaction ([PostgresDataSources]), so
 * the server refuses a write with SQLSTATE 25006 whether it arrived as an
 * `UPDATE`, inside a CTE, or inside a function body compiled last year.
 * `DataSafetyPolicy` warns the user beforehand and `StatementClassifier` is what it
 * asks; neither is consulted here, because a keyword scan that could veto a
 * statement would only add ways to refuse a legitimate read.
 *
 * [readOnly] must match the pool's own setting. It is not a second enforcement
 * point — it decides how a transaction *ends*, which is the one thing the pool flag
 * does not settle. See [endTransaction].
 */
class PostgresAdapter(
    private val dataSource: DataSource,
    private val redaction: Redaction = Redaction.NONE,
    private val queryTimeout: Duration = DEFAULT_STATEMENT_TIMEOUT,
    private val limits: ResultLimits = ResultLimits(),
    private val readOnly: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(PostgresAdapter::class.java)

    suspend fun selectOne(): QueryResult = execute(SELECT_ONE)

    /**
     * Runs [sql] and reads the whole (bounded) result. Throws [DbException] for a
     * database failure and `CancellationException` when the caller's scope is
     * cancelled — the running statement is cancelled server-side either way.
     *
     * One statement. Splitting a script is [dev.dbide.core.sql.StatementSplitter]'s
     * job, and doing it here would mean guessing at a boundary while holding a
     * connection.
     */
    suspend fun execute(sql: String): QueryResult = withStatement(sql, queryTimeout) { statement, started ->
        // One row past the limit, so a truncated result is detectable rather than
        // silently complete-looking.
        statement.maxRows = limits.rows + 1
        statement.readResult(started)
    }

    /**
     * Runs [sql] again and streams its rows to [out] as CSV, holding none of them.
     *
     * *Again* is the word the UI has to pass on to the user: this is a second
     * execution against the live server, so a table that has changed since the grid
     * was filled will export as it is now and not as it was drawn. The alternative
     * — keeping every completed result alive in case someone exports it — is how an
     * IDE ends up holding a gigabyte per tab, and it is the trade the plan makes
     * deliberately.
     *
     * Refused before a connection is taken when [ExportEligibility] says the
     * statement does not qualify, and refused after execution when it turns out to
     * produce no result set at all. Neither is the safety boundary — the pool's
     * read-only transaction is, and it still refuses a write that reaches the server
     * by any route — but a refusal here is one that can be explained in a sentence.
     *
     * Returns what was written. A [CsvExportReport] that is not `complete` means
     * [exportLimits] ended the file early, and the caller must say so; cancellation and
     * failure throw instead, so a returned report always describes a file that was
     * finished on purpose.
     */
    suspend fun exportCsv(
        sql: String,
        out: Appendable,
        options: CsvOptions = CsvOptions(),
        exportLimits: ExportLimits = ExportLimits(),
    ): CsvExportReport {
        val eligibility = ExportEligibility.of(sql)
        if (eligibility is ExportEligibility.Refused) {
            throw DbException(DbError.ExportUnavailable(eligibility.refusal.message))
        }
        // What runs is the statement the check looked at, rather than the string it
        // was cut from — otherwise the two could differ, and the one that was vetted
        // would not be the one that reaches the server.
        val statementText = (eligibility as ExportEligibility.Allowed).statement.text
        // Cancelling this scope cancels the one inside withStatement, so the caller's
        // context is what the row loop asks about; it cannot ask for its own.
        val caller = coroutineContext
        return withStatement(statementText, exportLimits.duration + CANCEL_GRACE) { statement, started ->
            // The driver-side backstop for the row budget. `maxRows` is an Int and the
            // budget is a Long, so a budget past two billion rows is left to the loop.
            if (exportLimits.rows < Int.MAX_VALUE) statement.maxRows = (exportLimits.rows + 1).toInt()
            if (!statement.execute()) {
                throw DbException(DbError.ExportUnavailable(ExportRefusal.NO_ROWS.message))
            }
            statement.resultSet.use { rows -> rows.writeCsv(out, options, exportLimits, started, caller) }
        }
    }

    /**
     * Executes and reads whichever kind of result came back.
     *
     * `INSERT`, `DDL`, and friends produce no result set at all, and asking for one
     * throws. A writable connection is allowed to run them, so the count is what
     * comes back instead of columns.
     */
    private fun PreparedStatement.readResult(started: TimeSource.Monotonic.ValueTimeMark): QueryResult {
        val hasRows = execute()
        val elapsed = started.elapsedNow()
        if (!hasRows) {
            return QueryResult(
                columns = emptyList(),
                rows = emptyList(),
                duration = elapsed,
                rowsAffected = updateCount.takeIf { it >= 0 }?.toLong(),
            )
        }
        return resultSet.use { rows -> rows.read(elapsed) }
    }

    /**
     * The parts every statement needs: a transaction to hold a cursor, a timeout, a
     * fetch size, cancellation wired to the caller's scope, and a classified error
     * instead of a raw [SQLException].
     *
     * [timeout] is both what the statement is given and what a failure is measured
     * against, since "the server stopped this" and "this ran out of time" are the
     * same event seen from two sides.
     */
    private suspend fun <T> withStatement(
        sql: String,
        timeout: Duration,
        body: (PreparedStatement, TimeSource.Monotonic.ValueTimeMark) -> T,
    ): T = withContext(Dispatchers.IO) {
        val started = TimeSource.Monotonic.markNow()
        try {
            dataSource.connection.use { connection ->
                // A cursor needs a transaction; without one pgjdbc buffers the entire
                // result into heap regardless of the fetch size.
                connection.autoCommit = false
                var succeeded = false
                try {
                    val value = connection.prepareStatement(sql).use { statement ->
                        statement.queryTimeout = timeout.asQueryTimeoutSeconds()
                        statement.fetchSize = FETCH_SIZE
                        statement.cancelledWithScope { body(statement, started) }
                    }
                    // Only a statement that ran to completion gets here. A failure, and
                    // a cancellation — which arrives as an exception out of
                    // cancelledWithScope — both leave this false, and a cancelled write
                    // must not be committed on its way out.
                    succeeded = true
                    value
                } finally {
                    connection.endTransaction(commit = succeeded)
                }
            }
        } catch (failure: SQLException) {
            // A cancelled scope must surface as cancellation, not as a database error.
            coroutineContext.ensureActive()
            val error = PostgresErrors.classify(
                throwable = failure,
                redaction = redaction,
                timedOut = started.elapsedNow() >= timeout,
                limit = timeout,
            )
            log.debug("query failed: {} ({})", error.code, redaction.scrub(failure.message))
            throw DbException(error, failure)
        }
    }

    /**
     * Runs [body] with the statement cancelled from another thread the moment the
     * calling scope is cancelled. Without this, closing a tab leaves the query running
     * on the server: cancelling a coroutine cannot interrupt a blocked JDBC call, and
     * `Job.invokeOnCompletion` is no help because a job blocked in JDBC does not
     * complete until that call returns.
     */
    private suspend fun <T> PreparedStatement.cancelledWithScope(body: () -> T): T = coroutineScope {
        val statement = this@cancelledWithScope
        val owner = checkNotNull(coroutineContext[Job])
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                // Reached either because the scope was cancelled, or because body()
                // finished and cancelled this guard. Only the first needs the server told.
                if (owner.isCancelled) {
                    runCatching { statement.cancel() }
                        .onFailure { log.debug("statement cancel after scope cancellation failed") }
                }
            }
        }
        try {
            body()
        } finally {
            guard.cancel()
        }
    }

    /**
     * Closes the statement's transaction, and this is where [readOnly] earns its
     * place.
     *
     * On a read-only connection there is nothing to commit, so it always rolls back
     * — which returns a clean connection to the pool rather than one holding an open
     * snapshot, and has a second effect worth stating because a test asserts it: a
     * `SET` is transactional in PostgreSQL, so it is undone too, and a session
     * setting cannot leak from one statement onto the next user of that pooled
     * connection.
     *
     * On a writable connection a successful statement must commit, or the write
     * feature is a lie: an `UPDATE` that reports "3 rows affected" and is then rolled
     * back is the worst failure this application could have. The cost is the mirror
     * image of the paragraph above — a committed `SET` does persist on that pooled
     * connection — which is why `StatementClassifier` keeps session statements in a
     * case of their own and the editor says so rather than pretending they are reads.
     *
     * A failed statement rolls back either way. There is nothing else to do with an
     * aborted transaction, and PostgreSQL will refuse every subsequent statement on
     * it until it is ended.
     */
    /**
     * This duration as the whole seconds JDBC will accept, rounded *up*.
     *
     * `setQueryTimeout` takes an int of seconds and reads zero as "no limit", so the
     * obvious conversion turns any timeout under a second into no timeout at all —
     * a configuration that asks for the tightest possible limit and silently gets
     * none. Rounding up means a sub-second limit is honoured as one second, which is
     * the coarsest guarantee JDBC can make and is at least the right kind of wrong.
     *
     * A zero or negative duration is passed through as zero, because that is the only
     * case where "no limit" is what was actually asked for.
     */
    private fun Duration.asQueryTimeoutSeconds(): Int {
        if (this <= Duration.ZERO) return 0
        val milliseconds = inWholeMilliseconds
        if (milliseconds >= (Int.MAX_VALUE.toLong() - 1) * 1_000L) return Int.MAX_VALUE
        return ((milliseconds + 999L) / 1_000L).toInt().coerceAtLeast(1)
    }

    private fun Connection.endTransaction(commit: Boolean) {
        val ending = if (commit && !readOnly) "commit" else "rollback"
        runCatching { if (commit && !readOnly) commit() else rollback() }
            .onFailure { log.debug("{} on return to pool failed", ending) }
    }

    /**
     * Reads the result, stopping at whichever bound is reached first.
     *
     * The row limit is the obvious one. The character budget is the one that
     * matters in practice: a thousand rows of `jsonb` documents is a thousand rows
     * and also several gigabytes, and a limit that only counts rows would let the
     * window die holding a result nobody could have read.
     */
    private fun ResultSet.read(elapsed: Duration): QueryResult {
        val columns = (1..metaData.columnCount).map { index -> PostgresValues.column(metaData, index) }
        val rows = ArrayList<List<CellValue>>()
        var truncation = Truncation.NONE
        var spent = 0L
        while (next()) {
            if (rows.size == limits.rows) {
                truncation = Truncation.ROW_LIMIT
                break
            }
            val row = columns.mapIndexed { position, column ->
                PostgresValues.read(this, position + 1, column, limits)
            }
            rows += row
            spent += row.sumOf { it.weight() }
            if (spent > limits.totalCharacters) {
                truncation = Truncation.SIZE_LIMIT
                break
            }
        }
        return QueryResult(columns = columns, rows = rows, duration = elapsed, truncation = truncation)
    }

    /**
     * Writes the result out as CSV, row by row, keeping nothing.
     *
     * Read through the same encoder as the grid, so that a `timestamptz` means the
     * same instant in the file as it does on screen and an extension type the
     * encoder has never heard of exports as the server's own text. Read with the
     * previews switched off, though: a cell in the grid is clipped because a person
     * is looking at it, and a cell in a file is the whole value or it is a lie.
     *
     * Every budget is checked between rows rather than inside one, so a record is
     * never half-written; the file passes its byte budget by at most its last
     * record, and stops.
     */
    private fun ResultSet.writeCsv(
        out: Appendable,
        options: CsvOptions,
        exportLimits: ExportLimits,
        started: TimeSource.Monotonic.ValueTimeMark,
        caller: CoroutineContext,
    ): CsvExportReport {
        val columns = (1..metaData.columnCount).map { index -> PostgresValues.column(metaData, index) }
        val writer = CsvWriter(out, options)
        writer.header(columns)

        var count = 0L
        var stopped = ExportStop.COMPLETE
        while (next()) {
            // Cancellation cancels the statement from the guard thread, but rows the
            // driver already fetched keep arriving from its buffer, and writing them to
            // disk is work nobody is waiting for any more. This is what stops that.
            caller.ensureActive()
            if (count == exportLimits.rows) {
                stopped = ExportStop.ROW_LIMIT
                break
            }
            if (writer.bytes >= exportLimits.bytes) {
                stopped = ExportStop.SIZE_LIMIT
                break
            }
            if (started.elapsedNow() >= exportLimits.duration) {
                stopped = ExportStop.TIME_LIMIT
                break
            }
            writer.row(
                columns.mapIndexed { position, column ->
                    PostgresValues.read(this, position + 1, column, EXPORT_CELLS)
                },
            )
            count++
        }
        return CsvExportReport(
            rows = count,
            bytes = writer.bytes,
            duration = started.elapsedNow(),
            stopped = stopped,
        )
    }

    /** Roughly what holding this cell costs, in characters. Small values need no accounting. */
    private fun CellValue.weight(): Long = when (this) {
        is CellValue.Text -> value.length.toLong()
        is CellValue.Binary -> preview.length.toLong()
        else -> 8
    }

    companion object {
        /**
         * How long a statement may run before the server stops it.
         *
         * §2.4 asks for a configurable timeout with a safe default; this is the
         * default, and [PostgresSession] is what makes it configurable. Thirty seconds
         * is chosen to be longer than any query a person waits at a keyboard for and
         * far shorter than the accidental cross join that would otherwise hold a
         * connection until the window is killed. A user's own Stop is independent of
         * it, and produces a different message.
         */
        val DEFAULT_STATEMENT_TIMEOUT: Duration = 30.seconds

        internal const val SELECT_ONE = "SELECT 1 AS one"
        private const val FETCH_SIZE = 500

        /**
         * Per-cell bounds for an export: none.
         *
         * The row and byte budgets are what keep an export finite, and they are
         * enforced on the file. Clipping a cell as well would produce a file that is
         * within budget and wrong in one place, which is the one failure mode a CSV
         * gives its reader no way to notice.
         */
        private val EXPORT_CELLS = ResultLimits(
            cellCharacters = Int.MAX_VALUE,
            binaryPreviewBytes = Int.MAX_VALUE,
        )

        /**
         * How much longer than its own deadline an export's statement is given.
         *
         * Both deadlines exist for different failures. The loop's stops a long export
         * politely, between rows, with a file that ends at a record boundary and a
         * report that says why. The statement's is for the fetch that never comes back,
         * where there is no between-rows to stop in — and it is set later on purpose so
         * that the polite one wins every race that is not that.
         */
        private val CANCEL_GRACE = 30.seconds
    }
}

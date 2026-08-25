package dev.caracal.core.postgres

import dev.caracal.core.result.CellValue
import dev.caracal.core.result.Column
import dev.caracal.core.result.DbException
import dev.caracal.core.result.Notice
import dev.caracal.core.result.QueryResult
import dev.caracal.core.result.ResultLimits
import dev.caracal.core.result.Truncation
import dev.caracal.core.text.Redaction
import dev.caracal.engine.api.StatementRequest
import dev.caracal.engine.api.ValueDetail
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import javax.sql.DataSource
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Runs one statement at a time against a pooled connection: streamed result,
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
 * does not settle. See [LiveStatement.complete].
 *
 * **The rows stream, and issue #4 is why.** [open] hands back a cursor that is still
 * open, and the caller reads it a row at a time and then lets it go. Reading it into
 * a list first was what `LegacySqlAdapter` existed to work around: a result the
 * engine had already assembled could serve a grid, and could not serve an export of
 * a few hundred megabytes without building the whole file in heap on its way to
 * disk. [execute] still returns a whole [QueryResult] — a grid draws a page and a
 * page is a finished thing — but it is now one reader of the stream rather than the
 * only shape the engine can produce.
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
     * One statement. Splitting a script is [dev.caracal.core.sql.StatementSplitter]'s
     * job, and doing it here would mean guessing at a boundary while holding a
     * connection.
     *
     * The materializing reader, kept because the engine itself needs one: [selectOne]
     * is a round trip whose answer is one cell, and a ping that streamed it would be
     * ceremony. Everything outside this class reads the cursor instead, through
     * [dev.caracal.engine.api.QueryFacet].
     */
    suspend fun execute(sql: String): QueryResult = stream(StatementRequest(sql)) { live ->
        val elapsed = live.elapsed
        val notices = live.notices
        if (!live.hasRows) {
            return@stream QueryResult(
                columns = emptyList(),
                rows = emptyList(),
                duration = elapsed,
                rowsAffected = live.updateCount,
                notices = notices,
            )
        }
        val rows = ArrayList<List<CellValue>>()
        live.rows().collect { row -> rows += row }
        QueryResult(
            columns = live.columns,
            rows = rows,
            duration = elapsed,
            truncation = live.truncation,
            notices = notices,
        )
    }

    /**
     * Opens a cursor for [request], runs [body] against it, and closes everything
     * afterwards whatever happened.
     *
     * The shape is what streaming costs. A connection, a transaction and a result set
     * have to stay open across the suspension points where the caller is emitting
     * rows, so they cannot live inside a single `withContext(Dispatchers.IO)` block
     * the way a materializing read's did. Instead every *blocking* step hops to
     * [Dispatchers.IO] on its own and [body] runs on the caller's own context — which
     * is not an implementation detail, because [body] is a flow builder and a flow
     * that emits from a context other than its collector's is an error the coroutines
     * library raises at runtime.
     *
     * The transaction ends exactly as it did before: committed only when [body]
     * returned normally, rolled back otherwise, and the commit's own failure
     * classified like any other. A cancelled read is not a completed one, so it rolls
     * back — a cancelled write must never be committed on its way out.
     */
    internal suspend fun <T> stream(request: StatementRequest, body: suspend (LiveStatement) -> T): T {
        val live = open(request)
        var succeeded = false
        try {
            val value = body(live)
            live.complete()
            succeeded = true
            return value
        } finally {
            live.close(succeeded)
        }
    }

    /**
     * Runs [request] and hands back the open cursor, for a caller that ends it itself.
     *
     * [stream] is the shape to reach for. This one exists for the caller that cannot
     * use it: `PostgresStatementExecution` emits its rows from inside a flow, and a
     * failure raised by the *collector* of that flow must reach the collector rather
     * than be caught here and re-emitted into a collector that has already thrown. A
     * caller that opens a statement owns closing it — [LiveStatement.complete] on the
     * way out and [LiveStatement.close] in a `finally`, in that order.
     */
    internal suspend fun open(request: StatementRequest): LiveStatement = LiveStatement.open(
        dataSource = dataSource,
        request = request,
        redaction = redaction,
        timeout = request.timeout ?: queryTimeout,
        limits = limits,
        readOnly = readOnly,
        log = log,
    )

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
    }
}

/**
 * A statement that has run and whose cursor is still open.
 *
 * Everything blocking is behind a suspend call that hops to [Dispatchers.IO] and
 * classifies its own failures, so a caller can read this from a flow without ever
 * putting JDBC on the thread it is emitting into.
 *
 * Cancellation is not a method here. Every blocking step runs inside the guard
 * [cancelledWithScope] installs, which calls `Statement.cancel()` from another thread
 * the moment the caller's scope is cancelled — and that covers the `execute()` that
 * has not returned a cursor yet, which a method on this object could not.
 */
internal class LiveStatement private constructor(
    private val connection: Connection,
    private val statement: PreparedStatement,
    private val redaction: Redaction,
    private val timeout: Duration,
    private val limits: ResultLimits,
    private val cells: ResultLimits,
    private val rowLimit: Long,
    private val batchSize: Int,
    private val detail: ValueDetail,
    private val readOnly: Boolean,
    private val started: TimeSource.Monotonic.ValueTimeMark,
    private val log: org.slf4j.Logger,
    /** True when `execute()` produced a result set rather than an update count. */
    val hasRows: Boolean,
    /** Measured where it always was: the moment the server answered, before any row is read. */
    val elapsed: Duration,
    val columns: List<Column>,
    val updateCount: Long?,
    val notices: List<Notice>,
    /** The open cursor, or null for a statement that produced an update count. */
    private var resultSet: ResultSet?,
) {
    /** What ended the result, once [rows] has been read to the end. */
    var truncation: Truncation = Truncation.NONE
        private set

    private var rowsRead = 0L
    private var spent = 0L

    /**
     * The rows, read a fetch at a time and forgotten as they go.
     *
     * A batch is read on [Dispatchers.IO] and then emitted from the collector's own
     * context, which is what keeps the flow legal to build inside another flow. The
     * batch is the driver's own fetch size, so one hop to the IO dispatcher costs one
     * round trip to the server rather than one per row.
     *
     * Collectable once. It reads a cursor, and a cursor that has been read to the end
     * has nothing left to give — a second collection would report an empty result for
     * a query that matched rows, which is worse than failing.
     *
     * Both bounds are applied here, and both are the ones that were applied before.
     * The row bound is [StatementRequest.maxRows] or the session's own. The character
     * budget is the one that matters in practice — a thousand rows of `jsonb` is a
     * thousand rows and also several gigabytes — and it applies only when the caller
     * asked for previews: it bounds what is *retained and drawn*, and an export
     * retains nothing, so charging it against a file would cut an export at the grid's
     * memory limit for no reason.
     */
    fun rows(): Flow<List<CellValue>> = flow {
        if (!hasRows) return@flow
        while (true) {
            val batch = guarded { readBatch() }
            batch.forEach { row -> emit(row) }
            if (batch.size < batchSize || truncation != Truncation.NONE) return@flow
        }
    }

    /**
     * Ends the transaction on the success path, and this is where the connection's
     * read-only setting earns its place.
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
     * A commit that itself fails is not swallowed: a deferred constraint, a
     * serialization failure, a connection lost between the last row and the commit.
     * Swallowing that would return a result saying "3 rows affected" for a transaction
     * the server threw away — the one failure this application must never have — so it
     * leaves as a classified [DbException] like every other `SQLException`.
     */
    suspend fun complete() {
        closeResultSet()
        // NonCancellable, because this is the moment a successful write becomes
        // durable. A cancellation that arrived while the last row was being read must
        // not turn an `UPDATE` that reported three rows affected into three rows the
        // server threw away.
        withContext(NonCancellable) {
            guarded {
                if (readOnly) connection.rollbackQuietly() else connection.commit()
            }
        }
    }

    /** Releases the cursor, the statement and the connection. Never throws. */
    suspend fun close(succeeded: Boolean) {
        withContext(NonCancellable + Dispatchers.IO) {
            closeResultSet()
            // Only the failure and cancellation paths reach this with work outstanding;
            // there is nothing to do with an aborted transaction but end it, and a
            // rollback that fails has no better answer.
            if (!succeeded) connection.rollbackQuietly()
            runCatching { statement.close() }.onFailure { log.debug("closing a statement failed") }
            runCatching { connection.close() }.onFailure { log.debug("returning a connection to the pool failed") }
        }
    }

    /**
     * The next fetch of rows, or fewer when the result or a bound ran out.
     *
     * Blocking, and called only from [guarded].
     */
    private fun readBatch(): List<List<CellValue>> {
        val rows = resultSet ?: return emptyList()
        val batch = ArrayList<List<CellValue>>(batchSize)
        while (batch.size < batchSize) {
            if (!rows.next()) return batch
            if (rowsRead == rowLimit) {
                truncation = Truncation.ROW_LIMIT
                return batch
            }
            val row = columns.mapIndexed { position, column ->
                PostgresValues.read(rows, position + 1, column, cells)
            }
            batch += row
            rowsRead++
            if (detail == ValueDetail.PREVIEW) {
                spent += row.sumOf { it.weight() }
                if (spent > limits.totalCharacters) {
                    truncation = Truncation.SIZE_LIMIT
                    return batch
                }
            }
        }
        return batch
    }

    private fun closeResultSet() {
        resultSet?.let { rows -> runCatching { rows.close() }.onFailure { log.debug("closing a result set failed") } }
        resultSet = null
    }

    /**
     * One blocking step, on the IO dispatcher, with the statement cancelled from
     * another thread the moment the caller's scope is cancelled and any
     * [SQLException] classified on the way out.
     *
     * The cancellation guard is per step rather than around the whole read because
     * that is where the blocking is: between steps this coroutine is suspended and
     * cancels like any other, and while a step runs there is a JDBC call that no
     * amount of coroutine cancellation can interrupt on its own.
     */
    private suspend fun <T> guarded(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            statement.cancelledWithScope(
                onCancelFailure = { log.debug("statement cancel after scope cancellation failed") },
            ) { block() }
        } catch (failure: SQLException) {
            throw classify(failure, redaction, timeout, started, log)
        }
    }

    /** Roughly what holding this cell costs, in characters. Small values need no accounting. */
    private fun CellValue.weight(): Long = when (this) {
        is CellValue.Text -> value.length.toLong()
        is CellValue.Binary -> preview.length.toLong()
        else -> 8
    }

    companion object {

        /**
         * Takes a connection, runs the statement, and hands back the open cursor.
         *
         * Everything up to and including the server's answer is one hop to
         * [Dispatchers.IO], because none of it can be interleaved with anything and all
         * of it blocks. A failure anywhere in it releases whatever had been taken: a
         * statement that never returned a cursor must not leave a pooled connection
         * behind holding an open transaction.
         */
        suspend fun open(
            dataSource: DataSource,
            request: StatementRequest,
            redaction: Redaction,
            timeout: Duration,
            limits: ResultLimits,
            readOnly: Boolean,
            log: org.slf4j.Logger,
        ): LiveStatement = withContext(Dispatchers.IO) {
            val started = TimeSource.Monotonic.markNow()
            val rowLimit = request.maxRows ?: limits.rows.toLong()
            val cells = if (request.values == ValueDetail.FULL) FULL_CELLS else limits
            var connection: Connection? = null
            var statement: PreparedStatement? = null
            try {
                val taken = dataSource.connection.also { connection = it }
                // A cursor needs a transaction; without one pgjdbc buffers the entire
                // result into heap regardless of the fetch size.
                taken.autoCommit = false
                // This connection has been used before. Whatever the last statement on
                // it made the server say is not this statement's news, and reporting it
                // as such is worse than reporting nothing.
                runCatching { taken.clearWarnings() }

                val prepared = taken.prepareStatement(request.sql).also { statement = it }
                prepared.queryTimeout = timeout.asQueryTimeoutSeconds()
                prepared.fetchSize = request.fetchSize.coerceAtLeast(1)
                // The driver-side backstop for the row budget. `maxRows` is an Int and
                // the budget is a Long, so a budget past two billion rows is left to the
                // read loop. One past the budget, so a truncated result is detectable
                // rather than silently complete-looking.
                if (rowLimit < Int.MAX_VALUE) prepared.maxRows = (rowLimit + 1).toInt()

                val hasRows = prepared.cancelledWithScope(
                    onCancelFailure = { log.debug("statement cancel after scope cancellation failed") },
                ) { prepared.execute() }
                val elapsed = started.elapsedNow()
                val rows = if (hasRows) prepared.resultSet else null

                LiveStatement(
                    connection = taken,
                    statement = prepared,
                    redaction = redaction,
                    timeout = timeout,
                    limits = limits,
                    cells = cells,
                    rowLimit = rowLimit,
                    batchSize = request.fetchSize.coerceAtLeast(1),
                    detail = request.values,
                    readOnly = readOnly,
                    started = started,
                    log = log,
                    hasRows = hasRows,
                    elapsed = elapsed,
                    columns = rows?.metaData?.let { meta ->
                        (1..meta.columnCount).map { PostgresValues.column(meta, it) }
                    } ?: emptyList(),
                    // `INSERT`, DDL, and `SET` all come back without a result set. Only
                    // the first of the three has a count, and a server that sent no
                    // number reports none rather than a zero it never said.
                    updateCount = if (hasRows) null else prepared.updateCount.takeIf { it >= 0 }?.toLong(),
                    // Read here, before any row is, exactly as they were before: a notice
                    // is produced on the way to the result, and a statement whose entire
                    // output is a `RAISE NOTICE` has nothing else coming.
                    notices = prepared.collectNotices(redaction),
                    resultSet = rows,
                )
            } catch (failure: Throwable) {
                // Nothing was handed back, so nothing else will release these. A pooled
                // connection left holding an open transaction is worse than the failure
                // that produced it.
                statement?.let { runCatching { it.close() } }
                connection?.let {
                    it.rollbackQuietly()
                    runCatching { it.close() }
                }
                throw if (failure is SQLException) {
                    classify(failure, redaction, timeout, started, log)
                } else {
                    failure
                }
            }
        }

        /**
         * Per-cell bounds for a caller that asked for whole values: none.
         *
         * The row budget is what keeps such a read finite, and the file's own budgets
         * are what keep an export finite. Clipping a cell as well would produce a file
         * that is within budget and wrong in one place, which is the one failure mode a
         * CSV gives its reader no way to notice.
         */
        private val FULL_CELLS = ResultLimits(
            cellCharacters = Int.MAX_VALUE,
            binaryPreviewBytes = Int.MAX_VALUE,
        )

        /**
         * Classifies an [SQLException] the way every statement's failure has always
         * been classified, and reports a cancelled scope as cancellation rather than as
         * a database error.
         *
         * [timeout] is both what the statement was given and what the failure is
         * measured against, since "the server stopped this" and "this ran out of time"
         * are the same event seen from two sides.
         */
        private suspend fun classify(
            failure: SQLException,
            redaction: Redaction,
            timeout: Duration,
            started: TimeSource.Monotonic.ValueTimeMark,
            log: org.slf4j.Logger,
        ): Throwable {
            // A cancelled scope must surface as cancellation, not as a database error.
            coroutineContext.ensureActive()
            val error = PostgresErrors.classify(
                throwable = failure,
                redaction = redaction,
                timedOut = started.elapsedNow() >= timeout,
                limit = timeout,
            )
            log.debug("query failed: {} ({})", error.code, redaction.scrub(failure.message))
            return DbException(error, failure)
        }
    }
}

/**
 * The notices this statement's execution produced, from both chains JDBC offers.
 *
 * Both, because which one a notice lands on is a driver detail and not a promise:
 * pgjdbc routes a notice raised during an execution to the statement, and one
 * raised outside a statement — during connection setup, or by an asynchronous
 * `LISTEN` — to the connection, and the boundary between those has moved between
 * versions. Reading only one of them is how this works on the driver it was
 * written against and quietly stops working on the next.
 *
 * What is here belongs to this execution: the statement is prepared fresh, so its
 * chain starts empty, and the connection's is cleared before the statement runs —
 * otherwise a notice would be reported against whatever ran next on that pooled
 * connection.
 */
private fun PreparedStatement.collectNotices(redaction: Redaction): List<Notice> {
    val fromStatement = PostgresErrors.notices(runCatching { warnings }.getOrNull(), redaction)
    // The connection chain gets whatever room the statement's chain left, and
    // nothing once that is gone. Not a trailing `take` over the joined list: the
    // last entry of a full list is the line saying the list was cut, and trimming
    // to length would remove exactly the sentence that stops a shortened list from
    // looking complete.
    val room = PostgresErrors.MAX_NOTICES - fromStatement.size
    if (room <= 0) return fromStatement
    val fromConnection = PostgresErrors.notices(
        runCatching { connection.warnings }.getOrNull(),
        redaction,
        limit = room,
    )
    // Deduplicated across the two chains only, never within one. A driver that
    // reported the same notice on both would otherwise say it twice — but a
    // function that raises the same sentence on three rows raised it three times,
    // and collapsing those would be reporting something that did not happen.
    val seen = fromStatement.toSet()
    return fromStatement + fromConnection.filterNot { it in seen }
}

private fun Connection.rollbackQuietly() {
    runCatching { rollback() }
}

/**
 * Runs [body] with the statement cancelled from another thread the moment the
 * calling scope is cancelled. Without this, closing a tab leaves the query running
 * on the server: cancelling a coroutine cannot interrupt a blocked JDBC call, and
 * `Job.invokeOnCompletion` is no help because a job blocked in JDBC does not
 * complete until that call returns.
 *
 * Shared with [PostgresCatalog], which reads the same pool: a schema expansion the
 * user has collapsed holds its pooled connection for as long as the server takes,
 * and four of those is the whole pool.
 */
internal suspend fun <T> Statement.cancelledWithScope(
    onCancelFailure: () -> Unit = {},
    body: () -> T,
): T = coroutineScope {
    val statement = this@cancelledWithScope
    val owner = checkNotNull(coroutineContext[Job])
    val guard = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            // Reached either because the scope was cancelled, or because body()
            // finished and cancelled this guard. Only the first needs the server told.
            if (owner.isCancelled) {
                runCatching { statement.cancel() }.onFailure { onCancelFailure() }
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
internal fun Duration.asQueryTimeoutSeconds(): Int {
    if (this <= Duration.ZERO) return 0
    val milliseconds = inWholeMilliseconds
    if (milliseconds >= (Int.MAX_VALUE.toLong() - 1) * 1_000L) return Int.MAX_VALUE
    return ((milliseconds + 999L) / 1_000L).toInt().coerceAtLeast(1)
}

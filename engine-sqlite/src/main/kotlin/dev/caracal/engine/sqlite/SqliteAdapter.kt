package dev.caracal.engine.sqlite

import dev.caracal.core.result.DbException
import dev.caracal.core.result.ResultLimits
import dev.caracal.core.result.Truncation
import dev.caracal.core.text.Redaction
import dev.caracal.engine.api.CellValue
import dev.caracal.engine.api.ColumnDescriptor
import dev.caracal.engine.api.StatementRequest
import dev.caracal.engine.api.TruncationReason
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
 * The same shape as `PostgresAdapter` and deliberately so — the constraints that
 * produced that shape are JDBC's rather than PostgreSQL's, and an engine that solved
 * them differently would be a second set of lifetime rules for `:ui` to learn. What is
 * *not* shared is any of the code, which is the trade §12 of the spec asks for: the
 * duplicated part is thirty lines of cursor bookkeeping, and the alternative is a
 * generic JDBC adapter that both engines have to be bent around the moment one of them
 * disagrees. The two disagree already. SQLite has no notices to collect, no side
 * channel to cancel through, and a type system that is decided per value rather than
 * per column.
 *
 * Nothing here decides whether a statement is *allowed* to write. That is settled when
 * the file is opened: a read-only connection is opened `SQLITE_OPEN_READONLY`, so
 * SQLite refuses a write whether it arrived as an `UPDATE`, from a trigger, or through
 * a view. [readOnly] must match that setting; it is not a second enforcement point,
 * it decides how a transaction *ends*. See [SqliteLiveStatement.complete].
 */
class SqliteAdapter(
    private val dataSource: DataSource,
    private val redaction: Redaction = Redaction.NONE,
    private val queryTimeout: Duration = DEFAULT_STATEMENT_TIMEOUT,
    private val limits: ResultLimits = ResultLimits(),
    private val readOnly: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(SqliteAdapter::class.java)

    /**
     * One round trip, for the ping and for the open.
     *
     * It reads a cell rather than merely preparing a statement because `sqlite3_open`
     * is lazy: a handle to a file that is not a database is handed over without
     * complaint, and the header is only read when something asks for data.
     */
    suspend fun selectOne() {
        val live = open(StatementRequest(SELECT_ONE))
        var succeeded = false
        try {
            live.rows().collect { }
            live.complete()
            succeeded = true
        } finally {
            live.close(succeeded)
        }
    }

    /**
     * Runs [request] and hands back the open cursor, for a caller that ends it itself.
     *
     * A caller that opens a statement owns closing it — [SqliteLiveStatement.complete]
     * on the way out and [SqliteLiveStatement.close] in a `finally`, in that order.
     */
    internal suspend fun open(request: StatementRequest): SqliteLiveStatement = SqliteLiveStatement.open(
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
         * How long a statement may run before it is stopped.
         *
         * The same thirty seconds PostgreSQL is given, and enforced by a different
         * mechanism: sqlite-jdbc implements `setQueryTimeout` with a progress handler
         * that interrupts the statement, so the limit is checked between byte-code
         * steps rather than by a server watching a clock. The observable difference is
         * that a statement blocked on the file system — a locked database, a slow
         * network mount — is not interrupted by it. `busy_timeout` is what bounds that.
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
 * [interruptedWithScope] installs, which calls `Statement.cancel()` from another
 * thread the moment the caller's scope is cancelled — and that covers the `execute()`
 * that has not returned a cursor yet, which a method on this object could not.
 */
internal class SqliteLiveStatement private constructor(
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
    /** Measured the moment the statement answered, before any row is read. */
    val elapsed: Duration,
    val columns: List<ColumnDescriptor>,
    val updateCount: Long?,
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
     * Collectable once: it reads a cursor, and a cursor read to the end has nothing
     * left to give. Both bounds are applied here — the row bound from the request or
     * the session, and the character budget that applies only when the caller asked
     * for previews, because it bounds what is *retained and drawn* and an export
     * retains nothing.
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
     * Ends the transaction on the success path.
     *
     * A read-only connection always rolls back: there is nothing to commit, and it
     * returns a clean connection to the pool rather than one holding an open read
     * transaction against the file. A writable one commits, or the write feature is a
     * lie — an `INSERT` that reports one row affected and is then rolled back is the
     * worst failure this application could have.
     *
     * A commit that itself fails is not swallowed. In SQLite that is the case worth
     * naming: the write is in the write-ahead log or the rollback journal until the
     * commit, and a full disk, a deferred foreign key or a lost lock all fail *here*
     * rather than at the statement. Reporting success for a transaction SQLite threw
     * away is exactly what must not happen.
     */
    suspend fun complete() {
        closeResultSet()
        // NonCancellable, because this is the moment a successful write becomes
        // durable. A cancellation that arrived while the last row was being read must
        // not turn an `UPDATE` that reported three rows affected into three rows
        // SQLite threw away.
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
                SqliteValues.read(rows, position + 1, column, cells)
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
     * One blocking step, on the IO dispatcher, with the statement interrupted from
     * another thread the moment the caller's scope is cancelled and any [SQLException]
     * classified on the way out.
     */
    private suspend fun <T> guarded(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            statement.interruptedWithScope(
                onCancelFailure = { log.debug("interrupting a statement after scope cancellation failed") },
            ) { block() }
        } catch (failure: SQLException) {
            throw classify(failure, redaction, timeout, started, log)
        }
    }

    /** Roughly what holding this cell costs, in characters. Small values need no accounting. */
    private fun CellValue.weight(): Long = when (this) {
        is CellValue.Text -> value.length.toLong()
        is CellValue.Json -> raw.length.toLong()
        is CellValue.Bytes -> preview.length.toLong()
        is CellValue.Opaque -> display.length.toLong()
        is CellValue.Temporal -> raw.length.toLong()
        else -> 8
    }

    companion object {

        /**
         * Takes a connection, runs the statement, and hands back the open cursor.
         *
         * Everything up to and including the first answer is one hop to
         * [Dispatchers.IO]. A failure anywhere in it releases whatever had been taken:
         * a statement that never returned a cursor must not leave a pooled connection
         * behind holding an open transaction, which in SQLite is a lock on the file
         * that every other connection then waits behind.
         */
        suspend fun open(
            dataSource: DataSource,
            request: StatementRequest,
            redaction: Redaction,
            timeout: Duration,
            limits: ResultLimits,
            readOnly: Boolean,
            log: org.slf4j.Logger,
        ): SqliteLiveStatement = withContext(Dispatchers.IO) {
            val started = TimeSource.Monotonic.markNow()
            val rowLimit = request.maxRows ?: limits.rows.toLong()
            val cells = if (request.values == ValueDetail.FULL) FULL_CELLS else limits
            var connection: Connection? = null
            var statement: PreparedStatement? = null
            try {
                val taken = dataSource.connection.also { connection = it }
                taken.autoCommit = false

                val prepared = taken.prepareStatement(request.sql).also { statement = it }
                prepared.queryTimeout = timeout.asQueryTimeoutSeconds()
                // Not a hint SQLite has anything to do with — there is no server to
                // fetch from — but the driver accepts it and a future one may use it.
                runCatching { prepared.fetchSize = request.fetchSize.coerceAtLeast(1) }
                // One past the budget, so a truncated result is detectable rather than
                // silently complete-looking. `maxRows` is an Int and the budget is a
                // Long, so a budget past two billion rows is left to the read loop.
                if (rowLimit < Int.MAX_VALUE) prepared.maxRows = (rowLimit + 1).toInt()

                val hasRows = prepared.interruptedWithScope(
                    onCancelFailure = { log.debug("interrupting a statement after scope cancellation failed") },
                ) { prepared.execute() }
                val elapsed = started.elapsedNow()
                val rows = if (hasRows) prepared.resultSet else null

                SqliteLiveStatement(
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
                    columns = rows?.let { SqliteValues.columns(it) }.orEmpty(),
                    // `INSERT`, DDL and `PRAGMA` all come back without a result set.
                    // Only the first has a count, and a driver that reported none
                    // reports none rather than a zero it never said.
                    updateCount = if (hasRows) null else prepared.updateCount.takeIf { it >= 0 }?.toLong(),
                    resultSet = rows,
                )
            } catch (failure: Throwable) {
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
         * The row budget is what keeps such a read finite. Clipping a cell as well
         * would produce a file that is within budget and wrong in one place, which is
         * the one failure mode a CSV gives its reader no way to notice.
         */
        private val FULL_CELLS = ResultLimits(
            cellCharacters = Int.MAX_VALUE,
            binaryPreviewBytes = Int.MAX_VALUE,
        )

        /**
         * Classifies an [SQLException] and reports a cancelled scope as cancellation
         * rather than as a database error.
         *
         * [timeout] is both what the statement was given and what the failure is
         * measured against: SQLite reports the query timeout and a user's interrupt
         * with the same `SQLITE_INTERRUPT`, because both *are* an interrupt. Only the
         * caller, which knows which deadline it set and whether that deadline passed,
         * can tell the two apart.
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
            val error = SqliteErrors.classify(
                throwable = failure,
                redaction = redaction,
                timedOut = started.elapsedNow() >= timeout,
                limit = timeout,
            )
            log.debug("statement failed: {} ({})", error.code, redaction.scrub(failure.message))
            return DbException(error, failure)
        }
    }
}

private fun Connection.rollbackQuietly() {
    runCatching { rollback() }
}

/**
 * Runs [body] with the statement interrupted from another thread the moment the
 * calling scope is cancelled.
 *
 * Without this, closing a tab leaves the statement running: cancelling a coroutine
 * cannot interrupt a blocked JDBC call, and `Job.invokeOnCompletion` is no help
 * because a job blocked in JDBC does not complete until that call returns.
 *
 * `Statement.cancel()` reaches `sqlite3_interrupt` on the connection's own database
 * handle, which is why the guard is per blocking step rather than around the whole
 * read: an interrupt sets a flag that the *currently running* statement notices at its
 * next byte-code step and then clears. Firing it between steps, when this coroutine is
 * merely suspended, would interrupt nothing and be indistinguishable from success.
 *
 * Shared with [SqliteCatalog], which reads the same pool: a schema expansion the user
 * has collapsed holds its pooled connection for as long as the file takes, and four of
 * those is the whole pool.
 */
internal suspend fun <T> Statement.interruptedWithScope(
    onCancelFailure: () -> Unit = {},
    body: () -> T,
): T = coroutineScope {
    val statement = this@interruptedWithScope
    val owner = checkNotNull(coroutineContext[Job])
    val guard = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            // Reached either because the scope was cancelled, or because body()
            // finished and cancelled this guard. Only the first needs the interrupt.
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
 * obvious conversion turns any timeout under a second into no timeout at all — a
 * configuration that asks for the tightest possible limit and silently gets none.
 * Rounding up means a sub-second limit is honoured as one second, which is the
 * coarsest guarantee JDBC can make and is at least the right kind of wrong.
 */
internal fun Duration.asQueryTimeoutSeconds(): Int {
    if (this <= Duration.ZERO) return 0
    val milliseconds = inWholeMilliseconds
    if (milliseconds >= (Int.MAX_VALUE.toLong() - 1) * 1_000L) return Int.MAX_VALUE
    return ((milliseconds + 999L) / 1_000L).toInt().coerceAtLeast(1)
}

/** Where a truncated read stopped, in the SPI's words. */
internal fun Truncation.reason(): TruncationReason? = when (this) {
    Truncation.NONE -> null
    Truncation.ROW_LIMIT -> TruncationReason.ROW_LIMIT
    Truncation.SIZE_LIMIT -> TruncationReason.SIZE_LIMIT
}

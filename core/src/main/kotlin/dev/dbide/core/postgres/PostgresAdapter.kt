package dev.dbide.core.postgres

import dev.dbide.core.result.CellValue
import dev.dbide.core.result.DbException
import dev.dbide.core.result.QueryResult
import dev.dbide.core.result.ResultLimits
import dev.dbide.core.result.Truncation
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
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
 * Nothing here decides whether a statement is allowed to write. The pool opens
 * every connection read-only ([PostgresDataSources]), so the server refuses a
 * write with SQLSTATE 25006 whether it arrived as an `UPDATE`, inside a CTE, or
 * inside a function body compiled last year. `StatementClassifier` warns the user
 * beforehand; it is not consulted here, because a keyword scan that could veto a
 * statement would only add ways to refuse a legitimate read.
 */
class PostgresAdapter(
    private val dataSource: DataSource,
    private val redaction: Redaction = Redaction.NONE,
    private val queryTimeout: Duration = 30.seconds,
    private val limits: ResultLimits = ResultLimits(),
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
    suspend fun execute(sql: String): QueryResult = withContext(Dispatchers.IO) {
        val started = TimeSource.Monotonic.markNow()
        try {
            dataSource.connection.use { connection ->
                // A cursor needs a transaction; without one pgjdbc buffers the entire
                // result into heap regardless of the fetch size.
                connection.autoCommit = false
                try {
                    connection.prepareStatement(sql).use { statement ->
                        statement.configure()
                        statement.cancelledWithScope { statement.readResult(started) }
                    }
                } finally {
                    connection.endTransaction()
                }
            }
        } catch (failure: SQLException) {
            // A cancelled scope must surface as cancellation, not as a database error.
            coroutineContext.ensureActive()
            val error = PostgresErrors.classify(
                throwable = failure,
                redaction = redaction,
                timedOut = started.elapsedNow() >= queryTimeout,
            )
            log.debug("query failed: {} ({})", error.code, redaction.scrub(failure.message))
            throw DbException(error, failure)
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

    private fun PreparedStatement.configure() {
        queryTimeout = this@PostgresAdapter.queryTimeout.inWholeSeconds.toInt()
        // One row past the limit, so a truncated result is detectable rather than
        // silently complete-looking.
        maxRows = limits.rows + 1
        fetchSize = FETCH_SIZE
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

    private fun Connection.endTransaction() {
        // Read-only work: there is nothing to commit, and rolling back returns a clean
        // connection to the pool instead of one holding an open snapshot.
        runCatching { rollback() }.onFailure { log.debug("rollback on return to pool failed") }
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

    /** Roughly what holding this cell costs, in characters. Small values need no accounting. */
    private fun CellValue.weight(): Long = when (this) {
        is CellValue.Text -> value.length.toLong()
        is CellValue.Binary -> preview.length.toLong()
        else -> 8
    }

    companion object {
        internal const val SELECT_ONE = "SELECT 1 AS one"
        private const val FETCH_SIZE = 500
    }
}

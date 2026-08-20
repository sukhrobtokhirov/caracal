package dev.dbide.core.postgres

import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column
import dev.dbide.core.result.DbException
import dev.dbide.core.result.QueryResult
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
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
    private val rowLimit: Int = 1_000,
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
        maxRows = rowLimit + 1
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

    private fun ResultSet.read(elapsed: Duration): QueryResult {
        val columns = (1..metaData.columnCount).map { index ->
            Column(name = metaData.getColumnLabel(index), typeName = metaData.getColumnTypeName(index))
        }
        val rows = ArrayList<List<CellValue>>()
        var truncated = false
        while (next()) {
            if (rows.size == rowLimit) {
                truncated = true
                break
            }
            rows += (1..columns.size).map { index -> cell(index) }
        }
        return QueryResult(columns = columns, rows = rows, duration = elapsed, truncated = truncated)
    }

    private fun ResultSet.cell(index: Int): CellValue {
        val type = metaData.getColumnType(index)
        val value: CellValue = when (type) {
            Types.SMALLINT, Types.INTEGER, Types.BIGINT -> CellValue.Integer(getLong(index))
            Types.NUMERIC, Types.DECIMAL -> getBigDecimal(index)?.let(CellValue::Decimal) ?: CellValue.Null
            Types.BOOLEAN, Types.BIT -> CellValue.Bool(getBoolean(index))
            Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR -> CellValue.Text(getString(index).orEmpty())
            else -> CellValue.Unmapped(getString(index).orEmpty())
        }
        // getLong and getBoolean return 0 and false for SQL NULL, so the null check has
        // to happen after the read, not before it.
        return if (wasNull()) CellValue.Null else value
    }

    companion object {
        internal const val SELECT_ONE = "SELECT 1 AS one"
        private const val FETCH_SIZE = 500
    }
}

package dev.caracal.core.postgres

import dev.caracal.core.text.Redaction
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A live PostgreSQL connection, owned by whoever opened it. The pool type stays
 * inside `:core`: the UI gets an adapter and a `close()`, and never learns what a
 * `HikariDataSource` is.
 *
 * [statementTimeout] is §2.4's configurable timeout, and this is the seam it is
 * configured at: the adapter carries a safe default, and whoever opens a session
 * can raise or lower it for that server without every caller of `execute` knowing
 * a timeout exists.
 */
class PostgresSession(
    config: PostgresConnectionConfig,
    statementTimeout: Duration = PostgresAdapter.DEFAULT_STATEMENT_TIMEOUT,
) : AutoCloseable {
    private val dataSource = PostgresDataSources.create(config)
    private val redaction = config.redaction()

    val adapter: PostgresAdapter = PostgresAdapter(
        dataSource = dataSource,
        redaction = redaction,
        queryTimeout = statementTimeout,
        // The pool decides whether a write is *permitted*; the adapter needs the same
        // answer to decide whether a successful statement is committed or rolled back.
        readOnly = config.readOnly,
    )

    /** The object browser's view of this server. Shares the pool with [adapter]. */
    val catalog: PostgresCatalog = PostgresCatalog(dataSource, redaction)

    /** Live pool statistics. Tests assert on these instead of trusting the code. */
    val activeConnections: Int get() = dataSource.hikariPoolMXBean?.activeConnections ?: 0

    /**
     * The server's version string, or null when it would not say.
     *
     * pgjdbc reads this from the startup parameters the server sends before the first
     * query, so it costs a pooled connection and no round trip — the same reason
     * [PostgresProbe] reads it there. Failure is swallowed on purpose: a session that
     * cannot name its server is still a working session, and this is decoration on a
     * dashboard rather than something a caller acts on.
     */
    internal suspend fun serverVersion(): String? = withContext(Dispatchers.IO) {
        runCatching { dataSource.connection.use { it.metaData.databaseProductVersion } }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    override fun close() = dataSource.close()

    companion object {
        /**
         * Opens a session and proves it can reach the server. The pool is configured
         * never to fail at construction — an unreachable server must surface as a
         * classified error rather than an exception during startup — so the round trip
         * here is what turns "a pool exists" into "a connection works".
         */
        suspend fun open(
            config: PostgresConnectionConfig,
            statementTimeout: Duration = PostgresAdapter.DEFAULT_STATEMENT_TIMEOUT,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): PostgresSession = withContext(dispatcher) {
            // Building the pool and closing it are both blocking work. Without this
            // switch they run on whichever thread called open, which for the UI is the
            // AWT event thread — the one thing that must never wait on a database.
            val session = PostgresSession(config, statementTimeout)
            try {
                session.adapter.selectOne()
            } catch (failure: Throwable) {
                session.close()
                throw failure
            }
            session
        }
    }
}

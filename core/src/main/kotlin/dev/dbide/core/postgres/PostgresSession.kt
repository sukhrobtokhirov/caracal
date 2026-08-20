package dev.dbide.core.postgres

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A live PostgreSQL connection, owned by whoever opened it. The pool type stays
 * inside `:core`: the UI gets an adapter and a `close()`, and never learns what a
 * `HikariDataSource` is.
 */
class PostgresSession(config: PostgresConnectionConfig) : AutoCloseable {
    private val dataSource = PostgresDataSources.create(config)
    private val redaction = Redaction(config.secrets())

    val adapter: PostgresAdapter = PostgresAdapter(dataSource, redaction)

    /** The object browser's view of this server. Shares the pool with [adapter]. */
    val catalog: PostgresCatalog = PostgresCatalog(dataSource, redaction)

    /** Live pool statistics. Tests assert on these instead of trusting the code. */
    val activeConnections: Int get() = dataSource.hikariPoolMXBean?.activeConnections ?: 0

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
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): PostgresSession = withContext(dispatcher) {
            // Building the pool and closing it are both blocking work. Without this
            // switch they run on whichever thread called open, which for the UI is the
            // AWT event thread — the one thing that must never wait on a database.
            val session = PostgresSession(config)
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

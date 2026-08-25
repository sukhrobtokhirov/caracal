package dev.caracal.engine.sqlite

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An open SQLite database, owned by whoever opened it.
 *
 * A pool of connections to a *file*, which reads like a contradiction and is not.
 * Two things make it the right shape anyway. The object browser and the editor issue
 * their statements independently and neither may wait on the other's cursor, so there
 * has to be more than one handle; and a handle is where SQLite's read-only flag lives,
 * so all of them have to be opened the same way, once, by something that knows the
 * policy. A pool is exactly that thing, and it is the same one PostgreSQL uses, which
 * is worth more than the twenty lines a bespoke handle cache would save.
 *
 * What the pool does *not* buy here is the thing pools are usually for. Opening a
 * SQLite database is opening a file; there is no handshake to amortize. So it is small
 * and it idles empty: several connections to one file cost several sets of page
 * caches, and a window with twenty connections open would be paying for twenty.
 */
class SqliteSession(
    config: SqliteConnectionConfig,
    statementTimeout: Duration = SqliteAdapter.DEFAULT_STATEMENT_TIMEOUT,
) : AutoCloseable {
    private val dataSource = pool(config)
    private val redaction = config.redaction()

    val adapter: SqliteAdapter = SqliteAdapter(
        dataSource = dataSource,
        redaction = redaction,
        queryTimeout = statementTimeout,
        // The open mode decides whether a write is *permitted*; the adapter needs the
        // same answer to decide whether a successful statement is committed or rolled
        // back. See `SqliteLiveStatement.complete`.
        readOnly = config.readOnly,
    )

    /** The object browser's view of this database. Shares the pool with [adapter]. */
    val catalog: SqliteCatalog = SqliteCatalog(dataSource, redaction)

    /** Live pool statistics. Tests assert on these instead of trusting the code. */
    val activeConnections: Int get() = dataSource.hikariPoolMXBean?.activeConnections ?: 0

    /**
     * The SQLite library's version, or null when it would not say.
     *
     * "Server version" is a small lie for an engine with no server, and it is the
     * honest reading of the field: what answers a query here is a library compiled
     * into this process, and its version is what decides whether a statement parses.
     * Failure is swallowed for the same reason PostgreSQL's is — a session that cannot
     * name its library is still a working session.
     */
    internal suspend fun libraryVersion(): String? = withContext(Dispatchers.IO) {
        runCatching { dataSource.connection.use { it.metaData.databaseProductVersion } }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    override fun close() = dataSource.close()

    companion object {
        /**
         * Opens a database and proves it can be read.
         *
         * The pool is configured never to fail at construction — an unreadable file
         * must surface as a classified error rather than as an exception during
         * startup — so the round trip here is what turns "a pool exists" into "this
         * file is a SQLite database and we can open it".
         *
         * That round trip is doing more work than PostgreSQL's. `sqlite3_open` is lazy:
         * it will happily hand back a handle to a file that is not a database at all,
         * and the header is not read until the first statement. Without the `SELECT 1`
         * below, a connection to a JPEG would succeed and fail on the user's first
         * query instead.
         */
        suspend fun open(
            config: SqliteConnectionConfig,
            statementTimeout: Duration = SqliteAdapter.DEFAULT_STATEMENT_TIMEOUT,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): SqliteSession = withContext(dispatcher) {
            // Building the pool and closing it are both blocking work. Without this
            // switch they run on whichever thread called open, which for the UI is the
            // AWT event thread — the one thing that must never wait on a database.
            val session = SqliteSession(config, statementTimeout)
            try {
                session.adapter.selectOne()
            } catch (failure: Throwable) {
                session.close()
                throw failure
            }
            session
        }

        /**
         * The pool, sized for a file rather than for a server.
         *
         * Four, because that is the browser, the editor, an export and one spare —
         * beyond which the connections are not waiting on a network and there is
         * nothing to overlap. `minimumIdle = 0` matters more than the maximum: a
         * database the user opened and stopped looking at should be holding no page
         * cache at all.
         */
        private fun pool(config: SqliteConnectionConfig): HikariDataSource {
            val hikari = HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                driverClassName = "org.sqlite.JDBC"
                dataSourceProperties = config.properties()
                maximumPoolSize = POOL_SIZE
                minimumIdle = 0
                connectionTimeout = CONNECTION_TIMEOUT.inWholeMilliseconds
                validationTimeout = CONNECTION_TIMEOUT.inWholeMilliseconds
                // Never fail at construction: an unreadable file must surface as a
                // classified error in the window, not as an exception during startup.
                initializationFailTimeout = -1
                // Kept in step with the open mode on purpose. Hikari corrects a
                // connection whose `isReadOnly` disagrees with the pool's, and
                // sqlite-jdbc refuses that correction once the file is open — so a
                // disagreement here is a connection that fails on its way out of the
                // pool rather than on the write it was opened to refuse.
                isReadOnly = config.readOnly
                poolName = "caracal-sqlite"
            }
            return HikariDataSource(hikari)
        }

        private const val POOL_SIZE = 4

        private val CONNECTION_TIMEOUT = 5.seconds
    }
}

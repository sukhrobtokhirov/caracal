package dev.dbide.core.postgres

import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.TestResult
import dev.dbide.core.result.DbException
import dev.dbide.core.text.Redaction
import java.sql.SQLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The connection test: dial, authenticate, make one round trip, report what was
 * found, and always disconnect. It never touches the runtime registry, so testing a
 * connection cannot disturb an open one.
 */
object PostgresProbe {
    /**
     * Bounds DNS, TCP, TLS, and authentication together, so a wrong host cannot
     * freeze the connection form.
     */
    val CONNECT_TIMEOUT: Duration = 5.seconds

    suspend fun test(config: PostgresConnectionConfig): TestResult = withContext(Dispatchers.IO) {
        val redaction = Redaction(config.secrets())
        val started = TimeSource.Monotonic.markNow()
        try {
            // A pool of one: the test opens exactly the connection it closes.
            PostgresDataSources.create(config, poolSize = 1, connectionTimeout = CONNECT_TIMEOUT)
                .use { dataSource ->
                    dataSource.connection.use { connection ->
                        connection.createStatement().use { it.execute("SELECT 1") }
                        // pgjdbc reads this from the startup parameters, so it costs
                        // no extra round trip.
                        val version = connection.metaData.databaseProductVersion
                        TestResult(
                            engine = Engine.POSTGRES,
                            serverVersion = version?.takeIf { it.isNotBlank() },
                            latencyMillis = started.elapsedNow().inWholeMilliseconds,
                        )
                    }
                }
        } catch (failure: SQLException) {
            throw DbException(PostgresErrors.classify(failure, redaction), failure)
        }
    }
}

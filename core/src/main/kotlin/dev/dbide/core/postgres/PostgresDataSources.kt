package dev.dbide.core.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dbide.core.connections.TlsMode
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Builds the pooled data source. The pool is where connection timeouts, validation,
 * and read-only enforcement live, which is why even M0 does not open a bare
 * `DriverManager` connection.
 */
object PostgresDataSources {
    fun create(
        config: PostgresConnectionConfig,
        poolSize: Int = 4,
        connectionTimeout: Duration = 5.seconds,
    ): HikariDataSource {
        val properties = Properties().apply {
            setProperty("user", config.user)
            setProperty("password", config.password.expose())
            setProperty("sslmode", config.tlsMode.sslModeValue)
            setProperty("ApplicationName", "Database IDE")
            setProperty("connectTimeout", connectionTimeout.inWholeSeconds.toString())
        }

        val hikari = HikariConfig().apply {
            // The URL carries no credentials; they travel in dataSourceProperties.
            jdbcUrl = config.jdbcUrl
            driverClassName = "org.postgresql.Driver"
            dataSourceProperties = properties
            maximumPoolSize = poolSize
            minimumIdle = 0
            this.connectionTimeout = connectionTimeout.inWholeMilliseconds
            validationTimeout = connectionTimeout.inWholeMilliseconds
            // Never fail at construction: an unreachable server must surface as a
            // classified error in the window, not as an exception during startup.
            initializationFailTimeout = -1
            // §2.4's enforcing half, and plan §2's sixth principle: read-only is a
            // property of the pool, not of a disabled button. Every connection this
            // pool hands out begins a PostgreSQL `READ ONLY` transaction, so a write
            // is refused on the server whether it arrived as an `UPDATE`, inside a
            // CTE, or inside a function body compiled last year — the three cases a
            // keyword scan cannot tell apart.
            isReadOnly = config.readOnly
            poolName = "dbide-postgres"
        }
        return HikariDataSource(hikari)
    }

    /**
     * The libpq spelling pgjdbc expects. `require` encrypts without proving the
     * server's identity, which is what the word means in PostgreSQL and why
     * `verify-full` exists at all.
     */
    private val TlsMode.sslModeValue: String
        get() = when (this) {
            TlsMode.DISABLE -> "disable"
            TlsMode.REQUIRE -> "require"
            TlsMode.VERIFY_FULL -> "verify-full"
        }
}

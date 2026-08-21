package dev.caracal.core.postgres

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.TlsMode

/**
 * Everything needed to reach one PostgreSQL server.
 *
 * This is the dialing form, built from a stored connection at the moment a client
 * is opened or tested. It holds a plaintext password, so it lives no longer than
 * the operation that needs it.
 */
data class PostgresConnectionConfig(
    val host: String,
    val port: Int = 5432,
    val database: String,
    val user: String,
    val password: Secret,
    val tlsMode: TlsMode = TlsMode.DISABLE,
    /**
     * Whether every connection in this pool opens in a PostgreSQL `READ ONLY`
     * transaction. This is §2.4's enforcing half, and it defaults to `true` because
     * a safety property whose default is off is not a safety property.
     */
    val readOnly: Boolean = true,
) {
    /** Never carries credentials: the driver receives those as `Properties`. */
    val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$database"

    /** The strings that must never survive into a message or a log line. */
    fun secrets(): List<String> =
        listOf(jdbcUrl, host, "$host:$port", database, user, password.expose())

    override fun toString(): String = "PostgresConnectionConfig(tlsMode=$tlsMode)"

    companion object {
        /** Builds the dialing form from a saved connection and its decrypted secret. */
        fun of(config: ConnectionConfig, password: Secret) = PostgresConnectionConfig(
            host = config.host,
            port = config.port,
            database = config.database,
            user = config.username,
            password = password,
            tlsMode = config.tlsMode,
            readOnly = config.readOnly,
        )
    }
}

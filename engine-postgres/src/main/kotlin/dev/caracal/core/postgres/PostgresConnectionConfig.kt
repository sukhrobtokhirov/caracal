package dev.caracal.core.postgres

import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.text.Redaction

/**
 * Everything needed to reach one PostgreSQL server.
 *
 * This is the dialing form, built by `PostgresEngine` from a descriptor at the
 * moment a client is opened. It holds a plaintext password, so it lives no longer
 * than the operation that needs it.
 *
 * It used to be built from a `ConnectionConfig` too, by a `companion` this file no
 * longer has: the stored form and the dialing form were mapped in two places, here
 * and in the engine, and Phase 3 left the engine as the only one. What the store
 * holds is now a descriptor's shape anyway.
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

    /** The connection identity alone, for the half of [Redaction] that has a length floor. */
    fun identity(): List<String> = listOf(jdbcUrl, host, "$host:$port", database, user)

    /** The redaction this connection's driver messages pass through. */
    fun redaction(): Redaction = Redaction(identity(), listOf(password.expose()))

    override fun toString(): String = "PostgresConnectionConfig(tlsMode=$tlsMode)"
}

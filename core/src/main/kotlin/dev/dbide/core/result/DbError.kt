package dev.dbide.core.result

/**
 * Every failure the UI can be shown. [code] is the stable, machine-readable case
 * the UI branches on; [message] is human-readable and must never contain a host,
 * port, user name, database name, or JDBC URL.
 */
sealed interface DbError {
    val code: String
    val message: String

    /** The connection could not be established or was lost. */
    sealed interface ConnectionUnavailable : DbError

    data class AuthenticationFailed(
        override val message: String = "Authentication failed. Check the user name and password.",
    ) : ConnectionUnavailable {
        override val code: String = "authentication_failed"
    }

    data class HostUnreachable(
        override val message: String = "The database server could not be reached.",
    ) : ConnectionUnavailable {
        override val code: String = "host_unreachable"
    }

    data class DatabaseNotFound(
        override val message: String = "The database does not exist on this server.",
    ) : ConnectionUnavailable {
        override val code: String = "database_not_found"
    }

    data class TlsVerificationFailed(
        override val message: String = "The server certificate could not be verified.",
    ) : ConnectionUnavailable {
        override val code: String = "tls_verification_failed"
    }

    data class ConnectionFailed(
        override val message: String = "The connection to the database failed.",
    ) : ConnectionUnavailable {
        override val code: String = "connection_failed"
    }

    data class Timeout(
        override val message: String = "The query took too long and was stopped.",
    ) : DbError {
        override val code: String = "timeout"
    }

    data class Cancelled(
        override val message: String = "The query was cancelled.",
    ) : DbError {
        override val code: String = "cancelled"
    }

    data class QueryFailed(
        override val message: String,
        val sqlState: String? = null,
        val position: Int? = null,
        val detail: String? = null,
        val hint: String? = null,
    ) : DbError {
        override val code: String = "query_failed"
    }

    data class ReadOnlyViolation(
        override val message: String = "This connection is read-only.",
    ) : DbError {
        override val code: String = "read_only_violation"
    }

    data class CommandNotAllowed(
        override val message: String,
        val command: String,
    ) : DbError {
        override val code: String = "command_not_allowed"
    }

    /** A connection whose engine or transport settings this build cannot use. */
    data class UnsupportedConfiguration(
        override val message: String = "This connection configuration is not supported.",
    ) : DbError {
        override val code: String = "unsupported_configuration"
    }
}

/** Thrown by adapters. Carries the classified [error]; the cause is never exposed to the UI. */
open class DbException(val error: DbError, cause: Throwable? = null) : Exception(error.message, cause)

/**
 * The classified error behind any failure. Adapters always throw [DbException], so
 * the fallback is for a caller that has caught something else entirely.
 */
fun Throwable.asDbError(): DbError =
    (this as? DbException)?.error ?: DbError.ConnectionFailed()

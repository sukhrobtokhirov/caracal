package dev.dbide.core.result

import kotlin.time.Duration

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

    /**
     * The statement passed its configured limit and the server stopped it.
     *
     * Distinct from [Cancelled] deliberately, and §2.4 asks for exactly that: both
     * arrive as SQLSTATE 57014, and a user who pressed Stop and a user whose query
     * outlived a limit they have never seen need opposite things said to them. The
     * second one needs the number, which is why [limit] is here rather than only in
     * the configuration that set it.
     */
    data class Timeout(
        val limit: Duration? = null,
        override val message: String = if (limit == null) {
            "The query took too long and was stopped."
        } else {
            "The query ran past its $limit limit and was stopped."
        },
    ) : DbError {
        override val code: String = "timeout"
    }

    data class Cancelled(
        override val message: String = "The query was cancelled.",
    ) : DbError {
        override val code: String = "cancelled"
    }

    /**
     * The server rejected the statement, and said why.
     *
     * Everything PostgreSQL puts in an error report that is safe to repeat, and
     * nothing it puts there that is not. `file`, `line`, and `routine` name the C
     * source that raised the error and are deliberately dropped: they help nobody
     * outside the PostgreSQL source tree, and they are how a screenshot of an error
     * turns into a statement about the server build. The `WHERE` context stack is
     * dropped for the same reason — it quotes the bodies of functions the user may
     * never have been shown.
     *
     * [position] is 1-based and counts characters of the statement that was
     * submitted; [dev.dbide.core.sql.Statement.documentIndex] is what turns it back
     * into a place in the editor. [internalPosition] points into a query the server
     * generated internally — inside a function body, typically — and so points at
     * nothing the user can see. It is reported and never used to highlight, because
     * §2.9's rule is that an unmappable position is shown as a message rather than
     * guessed at.
     */
    data class QueryFailed(
        override val message: String,
        val sqlState: String? = null,
        val position: Int? = null,
        val detail: String? = null,
        val hint: String? = null,
        val severity: String? = null,
        val internalPosition: Int? = null,
        val subject: ErrorSubject? = null,
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

    /**
     * The statement cannot be exported. Export runs the statement again, so what
     * qualifies is narrower than what may be looked at: the message says which of
     * the reasons applied.
     */
    data class ExportUnavailable(
        override val message: String,
    ) : DbError {
        override val code: String = "export_unavailable"
    }

    /** A connection whose engine or transport settings this build cannot use. */
    data class UnsupportedConfiguration(
        override val message: String = "This connection configuration is not supported.",
    ) : DbError {
        override val code: String = "unsupported_configuration"
    }
}

/**
 * The database object a server error names.
 *
 * PostgreSQL fills these in for the errors where it knows precisely what was
 * wrong — a constraint violation names its constraint, a type mismatch names its
 * type — and leaves them empty for everything else. They are worth surfacing
 * because the primary message often does not repeat them: "duplicate key value
 * violates unique constraint" is only actionable once you know which one.
 */
data class ErrorSubject(
    val schema: String? = null,
    val table: String? = null,
    val column: String? = null,
    val dataType: String? = null,
    val constraint: String? = null,
) {
    val isEmpty: Boolean
        get() = schema == null && table == null && column == null &&
            dataType == null && constraint == null

    /**
     * The object named, in one line, or `null` when nothing was named.
     *
     * The relation is joined with dots because that is how it would be written in
     * SQL; the constraint and the type are labelled because they would not be.
     */
    fun describe(): String? {
        if (isEmpty) return null
        val parts = mutableListOf<String>()
        listOfNotNull(schema, table, column).takeIf { it.isNotEmpty() }
            ?.let { parts += it.joinToString(".") }
        constraint?.let { parts += "constraint $it" }
        dataType?.let { parts += "type $it" }
        return parts.joinToString(", ")
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

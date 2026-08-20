package dev.dbide.core.postgres

import dev.dbide.core.result.DbError
import dev.dbide.core.result.ErrorSubject
import java.security.cert.CertificateException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import javax.net.ssl.SSLException
import kotlin.time.Duration
import org.postgresql.util.PSQLException

/**
 * Maps a driver failure onto [DbError].
 *
 * The SQLSTATE is the only stable signal: message text is localized and changes
 * between driver versions, so nothing here branches on it.
 */
object PostgresErrors {
    const val QUERY_CANCELED = "57014"

    /**
     * [timedOut] and [limit] are the caller's, not the driver's. PostgreSQL reports a
     * statement timeout and a user cancellation with the same SQLSTATE — the server
     * was asked to stop either way — so only the adapter, which knows which deadline
     * it set and whether that deadline passed, can tell the two apart.
     */
    fun classify(
        throwable: Throwable,
        redaction: Redaction = Redaction.NONE,
        timedOut: Boolean = false,
        limit: Duration? = null,
    ): DbError {
        // A certificate problem is worth its own message: the fix is a trust or
        // hostname change, not a password. It is checked before SQLSTATE because
        // pgjdbc reports it as a generic connection failure.
        if (throwable.isTlsFailure()) return DbError.TlsVerificationFailed()

        val sqlException = throwable.firstSqlException()
            ?: return DbError.ConnectionFailed()

        if (timedOut || sqlException is SQLTimeoutException) return DbError.Timeout(limit)

        return when (val state = sqlException.sqlStateOrInherited()) {
            "28P01", "28000" -> DbError.AuthenticationFailed()
            "3D000" -> DbError.DatabaseNotFound()
            "08001", "08S01" -> DbError.HostUnreachable()
            "08000", "08003", "08004", "08006", "08007", "57P01", "57P03" -> DbError.ConnectionFailed()
            QUERY_CANCELED -> DbError.Cancelled()
            "25006" -> DbError.ReadOnlyViolation()
            else -> queryFailed(sqlException, state, redaction)
        }
    }

    /**
     * The server's own report, minus the parts that describe the server.
     *
     * Every field is copied across except `file`, `line`, `routine`, and the `WHERE`
     * context stack. Those four are §2.9's exclusion list: they name the C source
     * that raised the error and quote the bodies of functions, which tells the user
     * nothing they can act on and tells anyone reading over their shoulder rather
     * more than they should know. Everything that survives still goes through
     * [Redaction] first, because a server message is free to quote the connection it
     * arrived on.
     */
    private fun queryFailed(
        exception: SQLException,
        state: String?,
        redaction: Redaction,
    ): DbError.QueryFailed {
        val server = (exception as? PSQLException)?.serverErrorMessage
        val message = redaction.scrub(server?.message ?: exception.message)
            ?: "The query failed."
        val subject = server?.let {
            ErrorSubject(
                schema = redaction.scrub(it.schema).orNullIfBlank(),
                table = redaction.scrub(it.table).orNullIfBlank(),
                column = redaction.scrub(it.column).orNullIfBlank(),
                dataType = redaction.scrub(it.datatype).orNullIfBlank(),
                constraint = redaction.scrub(it.constraint).orNullIfBlank(),
            )
        }
        return DbError.QueryFailed(
            message = message,
            sqlState = state,
            position = server?.position?.takeIf { it > 0 },
            detail = redaction.scrub(server?.detail).orNullIfBlank(),
            hint = redaction.scrub(server?.hint).orNullIfBlank(),
            // Localized by the server's `lc_messages`, so it is shown and never
            // branched on. `code` is what anything downstream tests.
            severity = server?.severity.orNullIfBlank(),
            internalPosition = server?.internalPosition?.takeIf { it > 0 },
            subject = subject?.takeUnless { it.isEmpty },
        )
    }

    /** pgjdbc returns an absent field as `null` or as the empty string, depending on it. */
    private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

    /** Walks the cause chain for the TLS layer's own exception types. */
    private fun Throwable.isTlsFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is SSLException || current is CertificateException) return true
            current = current.cause
        }
        return false
    }

    /** Hikari and pgjdbc both wrap the interesting exception one or two layers down. */
    private fun Throwable.firstSqlException(): SQLException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is SQLException) return current
            current = current.cause
        }
        return null
    }

    /**
     * A pool timeout arrives as an SQLException with no state of its own, carrying the
     * connection failure that caused it underneath.
     */
    private fun SQLException.sqlStateOrInherited(): String? {
        sqlState?.let { return it }
        var current: Throwable? = cause
        while (current != null) {
            if (current is SQLException) current.sqlState?.let { return it }
            current = current.cause
        }
        return null
    }
}

package dev.dbide.core.postgres

import dev.dbide.core.result.DbError
import java.security.cert.CertificateException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import javax.net.ssl.SSLException
import org.postgresql.util.PSQLException

/**
 * Maps a driver failure onto [DbError].
 *
 * The SQLSTATE is the only stable signal: message text is localized and changes
 * between driver versions, so nothing here branches on it.
 */
object PostgresErrors {
    const val QUERY_CANCELED = "57014"

    fun classify(
        throwable: Throwable,
        redaction: Redaction = Redaction.NONE,
        timedOut: Boolean = false,
    ): DbError {
        // A certificate problem is worth its own message: the fix is a trust or
        // hostname change, not a password. It is checked before SQLSTATE because
        // pgjdbc reports it as a generic connection failure.
        if (throwable.isTlsFailure()) return DbError.TlsVerificationFailed()

        val sqlException = throwable.firstSqlException()
            ?: return DbError.ConnectionFailed()

        if (timedOut || sqlException is SQLTimeoutException) return DbError.Timeout()

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

    private fun queryFailed(
        exception: SQLException,
        state: String?,
        redaction: Redaction,
    ): DbError.QueryFailed {
        val server = (exception as? PSQLException)?.serverErrorMessage
        val message = redaction.scrub(server?.message ?: exception.message)
            ?: "The query failed."
        return DbError.QueryFailed(
            message = message,
            sqlState = state,
            position = server?.position?.takeIf { it > 0 },
            detail = redaction.scrub(server?.detail),
            hint = redaction.scrub(server?.hint),
        )
    }

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

package dev.caracal.core.postgres

import dev.caracal.core.result.DbError
import dev.caracal.core.result.ErrorSubject
import dev.caracal.core.result.Notice
import dev.caracal.core.text.Redaction
import java.security.cert.CertificateException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLWarning
import javax.net.ssl.SSLException
import kotlin.time.Duration
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLWarning

/**
 * Maps a driver failure onto [DbError].
 *
 * The SQLSTATE is the only stable signal: message text is localized and changes
 * between driver versions, so nothing here branches on it.
 */
object PostgresErrors {
    const val QUERY_CANCELED = "57014"

    /** How many notices one statement may hand back. See [notices]. */
    const val MAX_NOTICES = 100

    /** How long any one field of a notice may be. A notice can quote a whole row. */
    private const val MAX_NOTICE_LENGTH = 4_096

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
     * The server's notices, read off a JDBC warning chain.
     *
     * PostgreSQL's NoticeResponse and its ErrorResponse are the same message with a
     * different severity, and pgjdbc reflects that: a notice arrives as a
     * [PSQLWarning] wrapping the same `ServerErrorMessage` an error would. So the
     * fields are copied across on the same terms [queryFailed] uses — everything the
     * user can act on, nothing that describes the server's own source tree, and all
     * of it through [Redaction] first.
     *
     * The chain is walked at most [MAX_NOTICES] links. A loop that raises a notice per
     * iteration is a normal thing to write and would otherwise hand the grid a list as
     * long as the loop ran, which is the same unbounded retention `ResultLimits` exists
     * to prevent one field over. [truncatedNotice] is appended when that happens, so a
     * shortened list never looks like a complete one.
     */
    fun notices(
        first: SQLWarning?,
        redaction: Redaction = Redaction.NONE,
        limit: Int = MAX_NOTICES,
    ): List<Notice> {
        if (first == null || limit <= 0) return emptyList()
        val notices = mutableListOf<Notice>()
        var warning: SQLWarning? = first
        while (warning != null) {
            if (notices.size == limit) return notices + truncatedNotice()
            notices += warning.toNotice(redaction)
            // Guarded against a chain that links to itself. pgjdbc does not build one,
            // but this walk is over driver-owned state and a hang here would look like
            // a hung query.
            val next = warning.nextWarning
            warning = if (next === warning) null else next
        }
        return notices
    }

    private fun SQLWarning.toNotice(redaction: Redaction): Notice {
        val server = (this as? PSQLWarning)?.serverErrorMessage
        val state: String? = server?.sqlState
        return Notice(
            message = redaction.scrub(server?.message ?: message)?.take(MAX_NOTICE_LENGTH)
                ?: "The server sent a notice with no message.",
            severity = redaction.scrub(server?.severity).orNullIfBlank(),
            sqlState = state.orNullIfBlank() ?: sqlState.orNullIfBlank(),
            detail = redaction.scrub(server?.detail).orNullIfBlank()?.take(MAX_NOTICE_LENGTH),
            hint = redaction.scrub(server?.hint).orNullIfBlank()?.take(MAX_NOTICE_LENGTH),
        )
    }

    private fun truncatedNotice() = Notice(
        message = "The server sent more than $MAX_NOTICES notices; the rest were not kept.",
    )

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

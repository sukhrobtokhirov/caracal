package dev.caracal.engine.sqlite

import dev.caracal.core.result.DbError
import dev.caracal.core.text.Redaction
import kotlin.time.Duration
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

/**
 * Maps a driver failure onto [DbError].
 *
 * SQLite has no SQLSTATE worth reading — sqlite-jdbc reports `null` for almost
 * everything — so what is branched on instead is the **result code**, which SQLite
 * documents, keeps stable across versions, and exposes through
 * `SQLiteException.getResultCode()`. Nothing here branches on message text, for the
 * reason every error mapper in this repository gives: message text is the part that
 * changes.
 *
 * The interesting half is what a failure to *open* looks like, because SQLite's answer
 * is one code for a great many causes. `SQLITE_CANTOPEN` is a missing file, a
 * directory that does not exist, a permission the process does not have, and a path
 * with a typo in it; `SQLITE_NOTADB` is the JPEG somebody renamed. Neither can be told
 * apart further from inside the library, and inventing a distinction would be guessing
 * at the user's disk. What matters — and what the conformance suite asserts — is that
 * they do not arrive as the same sentence as a statement that was simply wrong.
 */
internal object SqliteErrors {

    /**
     * [timedOut] and [limit] are the caller's, not the driver's.
     *
     * SQLite reports a statement that ran past its query timeout and one the user
     * stopped with the same `SQLITE_INTERRUPT`, because both are literally an
     * interrupt on the same flag. Only the caller, which knows which deadline it set
     * and whether that deadline passed, can tell them apart — and it must, because a
     * user who pressed Stop and a user whose query outlived a limit they have never
     * seen need opposite things said to them.
     */
    fun classify(
        throwable: Throwable,
        redaction: Redaction = Redaction.NONE,
        timedOut: Boolean = false,
        limit: Duration? = null,
    ): DbError {
        val code = throwable.resultCode()
        if (code == SQLiteErrorCode.SQLITE_INTERRUPT) {
            return if (timedOut) DbError.Timeout(limit) else DbError.Cancelled()
        }
        return when (code) {
            // The file is not there, is not reachable, or is not ours to read. One
            // code, several disks' worth of causes, and the message says so rather
            // than picking one.
            SQLiteErrorCode.SQLITE_CANTOPEN -> DbError.ConnectionFailed(
                "The database file could not be opened. Check that the path is right and " +
                    "that it can be read.",
            )

            // Opened, and it is not a database. Worth its own sentence because the fix
            // is a different file rather than a different permission.
            SQLiteErrorCode.SQLITE_NOTADB -> DbError.ConnectionFailed(
                "That database file is not a SQLite database, or it is encrypted.",
            )

            // The header parsed and the contents did not. Also a connection problem
            // rather than a statement problem: nothing the user typed caused it.
            SQLiteErrorCode.SQLITE_CORRUPT -> DbError.ConnectionFailed(
                "That database file is damaged and could not be read.",
            )

            SQLiteErrorCode.SQLITE_READONLY -> DbError.ReadOnlyViolation(
                "This connection opened the database file read-only.",
            )

            // Another connection is holding the write lock, in this process or in
            // another one. Not a failed connection — this one works — and not the
            // user's statement being wrong either.
            SQLiteErrorCode.SQLITE_BUSY,
            SQLiteErrorCode.SQLITE_LOCKED,
            -> DbError.QueryFailed(
                message = "The database file is locked by something else. Try again in a moment.",
                sqlState = code.name,
            )

            SQLiteErrorCode.SQLITE_FULL -> DbError.QueryFailed(
                message = "There is not enough room on the disk to write to this database.",
                sqlState = code.name,
            )

            SQLiteErrorCode.SQLITE_PERM,
            SQLiteErrorCode.SQLITE_AUTH,
            -> DbError.ConnectionFailed(
                "This database file cannot be opened with the permissions this application has.",
            )

            else -> queryFailed(throwable, code, redaction)
        }
    }

    /**
     * The statement was rejected, and SQLite said why.
     *
     * The message is the library's own, redacted. It is worth repeating rather than
     * replacing because SQLite's parse errors are specific and useful — `no such
     * column: naem` names the typo — and because there is nothing else: there is no
     * detail, no hint, and no position.
     *
     * **No position, and that is a declaration rather than an omission.** SQLite
     * reports the offending token by quoting it in the message and never by offset, so
     * this engine's errors carry no [dev.caracal.engine.api.SourcePosition] and the
     * editor underlines the whole statement. §2.9's rule is that an unmappable
     * position is shown as a message rather than guessed at, and searching the buffer
     * for the quoted token would be a guess that lands on the wrong `naem` in any
     * statement that has two.
     *
     * [SQLiteErrorCode.name] rides along as the code. It is not a SQLSTATE and does not
     * pretend to be one; it is the stable string a bug report can quote.
     */
    private fun queryFailed(
        throwable: Throwable,
        code: SQLiteErrorCode?,
        redaction: Redaction,
    ): DbError = DbError.QueryFailed(
        message = redaction.scrub(throwable.message)?.takeIf { it.isNotBlank() }
            ?: "The statement was rejected by the database.",
        sqlState = code?.name,
    )

    /**
     * The SQLite result code behind a failure, from anywhere in the cause chain.
     *
     * Walked rather than read off the top, because a pool wraps what the driver threw
     * and a `SQLException` raised while a connection was being taken carries the real
     * failure two links down. Reading only the outermost is how a missing file arrives
     * as "connection failed" with the actual answer still attached to it.
     */
    private fun Throwable.resultCode(): SQLiteErrorCode? = generateSequence(this) { it.cause }
        .take(MAX_CAUSES)
        .filterIsInstance<SQLiteException>()
        .firstOrNull()
        ?.resultCode
        // The primary code is the low byte; the extended codes above it say *which*
        // kind — SQLITE_READONLY_DBMOVED, SQLITE_CANTOPEN_ISDIR and their relatives.
        // Narrowing to the primary one is what keeps this `when` from being a list of
        // eighty arms that means the same as five.
        ?.let { extended -> SQLiteErrorCode.getErrorCode(extended.code and 0xff) }

    /** How far down a cause chain to look. Deep enough for a pool and a driver. */
    private const val MAX_CAUSES = 10
}

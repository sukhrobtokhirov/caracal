package dev.caracal.engine.sqlite

import dev.caracal.core.result.DbError
import dev.caracal.core.text.Redaction
import java.nio.file.Path
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

/**
 * What a SQLite failure becomes, and what it must never carry with it.
 *
 * Every case here is one the conformance suite cannot reach: the suite provokes two
 * real failures against a real file, and the codes below are the ones that need a
 * full disk, a lock held by another process, or a database somebody moved out from
 * under an open handle. They are exactly the failures a user hits and a test never
 * does, which is why they are asserted against constructed exceptions rather than left
 * to the day one happens.
 */
class SqliteErrorsTest {

    @Test
    fun `a file that will not open is a connection failure, not a bad statement`() {
        val error = SqliteErrors.classify(exception(SQLiteErrorCode.SQLITE_CANTOPEN))

        assertIs<DbError.ConnectionUnavailable>(error, "a missing file blamed the user's SQL")
        assertTrue(error.message.contains("could not be opened"), error.message)
    }

    @Test
    fun `a file that is not a database says so rather than saying nothing`() {
        // The renamed JPEG. Worth its own sentence because the fix is a different file
        // rather than a different permission, and SQLite is the only one that can tell
        // — the header is not read until the first statement runs.
        val error = SqliteErrors.classify(exception(SQLiteErrorCode.SQLITE_NOTADB))

        assertIs<DbError.ConnectionUnavailable>(error)
        assertTrue(error.message.contains("not a SQLite database"), error.message)
        assertTrue(
            error.message != SqliteErrors.classify(exception(SQLiteErrorCode.SQLITE_CANTOPEN)).message,
            "a file that is missing and a file that is not a database arrive as one sentence",
        )
    }

    @Test
    fun `an extended result code is read as the primary one it extends`() {
        // SQLite has eighty-odd extended codes over about twenty primaries, and the
        // extension is in the high bytes. Without the mask, `SQLITE_READONLY_DBMOVED`
        // — a database file moved out from under an open handle — would fall through to
        // "the statement was rejected", which is not what happened and not what to do
        // about it.
        val error = SqliteErrors.classify(exception(SQLiteErrorCode.SQLITE_READONLY_DBMOVED))

        assertIs<DbError.ReadOnlyViolation>(error)
    }

    @Test
    fun `an interrupt is a cancellation, or a timeout, depending on who asked`() {
        // The one place the driver genuinely cannot tell the caller what happened.
        // SQLite reports a statement stopped by the query timeout and one stopped by
        // the user with the same code, because both are the same flag being set.
        val cancelled = SqliteErrors.classify(exception(SQLiteErrorCode.SQLITE_INTERRUPT), timedOut = false)
        val timedOut = SqliteErrors.classify(
            exception(SQLiteErrorCode.SQLITE_INTERRUPT),
            timedOut = true,
            limit = 30.seconds,
        )

        assertIs<DbError.Cancelled>(cancelled)
        val limit = assertIs<DbError.Timeout>(timedOut)
        assertEquals(30.seconds, limit.limit)
    }

    @Test
    fun `a locked database is a statement that can be retried, not a broken connection`() {
        // Two writers on one file. The connection works; it is the file that is busy,
        // and offering to reopen the connection would be offering the wrong button.
        val error = SqliteErrors.classify(exception(SQLiteErrorCode.SQLITE_BUSY))

        val failed = assertIs<DbError.QueryFailed>(error)
        assertEquals(SQLiteErrorCode.SQLITE_BUSY.name, failed.sqlState)
        assertTrue(failed.message.contains("locked"), failed.message)
    }

    @Test
    fun `the code behind a pooled failure is found under the exception that wraps it`() {
        // How a connection failure actually arrives: the pool gives up waiting and
        // throws its own exception with the driver's underneath. Reading only the
        // outermost is how a missing file is reported as "connection failed" with the
        // actual answer still attached to it.
        val pooled = SQLTransientConnectionException(
            "caracal-sqlite - Connection is not available, request timed out",
            exception(SQLiteErrorCode.SQLITE_CANTOPEN),
        )

        val error = SqliteErrors.classify(pooled)

        assertTrue(error.message.contains("could not be opened"), error.message)
    }

    @Test
    fun `a failure with no SQLite code anywhere under it is still classified`() {
        // A driver that could not be loaded, a pool that gave up with nothing to say.
        // There is no result code to read and there still has to be a sentence.
        val error = SqliteErrors.classify(SQLException("something went wrong in the pool"))

        val failed = assertIs<DbError.QueryFailed>(error)
        assertEquals(null, failed.sqlState, "a code was invented for a failure that carried none")
    }

    @Test
    fun `a damaged file and a full disk are told apart from each other and from a bad statement`() {
        // Three failures a test never provokes and a user eventually does, and the
        // reason each is its own arm is that the three fixes are unrelated: restore a
        // backup, free some room, and correct the SQL. Collapsing them into "the
        // statement was rejected" sends every one of those users to read the wrong
        // page.
        val corrupt = SqliteErrors.classify(exception(SQLiteErrorCode.SQLITE_CORRUPT))
        val full = SqliteErrors.classify(exception(SQLiteErrorCode.SQLITE_FULL))
        val forbidden = SqliteErrors.classify(exception(SQLiteErrorCode.SQLITE_PERM))

        assertIs<DbError.ConnectionUnavailable>(corrupt)
        assertTrue(corrupt.message.contains("damaged"), corrupt.message)

        // A full disk is a statement that failed, not a connection that is gone: the
        // handle still works and the next read from it will succeed.
        val disk = assertIs<DbError.QueryFailed>(full)
        assertTrue(disk.message.contains("not enough room"), disk.message)

        assertIs<DbError.ConnectionUnavailable>(forbidden)
        assertTrue(forbidden.message.contains("permissions"), forbidden.message)
        assertEquals(
            forbidden.message,
            SqliteErrors.classify(exception(SQLiteErrorCode.SQLITE_AUTH)).message,
            "denied by the file system and denied by an authorizer are the same problem to the user",
        )
    }

    @Test
    fun `the path never survives into the message`() {
        // The whole reason this engine has a `Redaction` at all. SQLite quotes the file
        // it was working on into most of its I/O errors, and a path names the user's
        // disk, their projects and often their customers. `DbError`'s contract forbids
        // an address in a message; for this engine the path is the address.
        val config = SqliteConnectionConfig(path = Path.of("/private/acme-payroll.db"))
        val failure = SQLiteException(
            "unable to open database file: /private/acme-payroll.db",
            SQLiteErrorCode.SQLITE_ERROR,
        )

        val error = SqliteErrors.classify(failure, config.redaction())

        assertTrue(!error.message.contains("acme-payroll"), "the file name reached the message: ${error.message}")
        assertTrue(!error.message.contains("/private/"), "the directory reached the message: ${error.message}")
    }

    @Test
    fun `a rejected statement keeps what SQLite said about it`() {
        // The other direction, and it matters as much: SQLite's parse errors name the
        // token they could not resolve, which is the most useful thing this engine's
        // errors ever carry. Redacting the path must not redact the diagnosis.
        val failure = SQLiteException("[SQLITE_ERROR] SQL error: no such column: naem", SQLiteErrorCode.SQLITE_ERROR)

        val error = SqliteErrors.classify(failure, Redaction.NONE)

        assertTrue(error.message.contains("no such column: naem"), error.message)
    }

    private fun exception(code: SQLiteErrorCode) = SQLiteException(code.message, code)
}

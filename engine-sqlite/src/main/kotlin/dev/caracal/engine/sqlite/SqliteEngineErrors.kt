package dev.caracal.engine.sqlite

import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.engine.api.EngineError

/**
 * A classified `:core` failure, carried across into the SPI's error model.
 *
 * Shorter than PostgreSQL's equivalent by exactly the field that makes that one
 * interesting: there is no position. SQLite reports the offending token by quoting it
 * in the message — `no such column: naem` — and never by offset, so nothing here can
 * produce a [dev.caracal.engine.api.SourcePosition] and the editor falls back to
 * underlining the whole statement. That fallback is written once, in the UI, which is
 * why an engine with no positions costs nothing to add.
 *
 * Searching the buffer for the quoted token would be the obvious way to invent one,
 * and it is refused for §2.9's reason: a statement with two `naem`s in it would be
 * underlined in the wrong place, and an underline in the wrong place is a claim the
 * user has no way to check.
 */
internal object SqliteEngineErrors {

    fun of(failure: DbException): EngineError = of(failure.error, failure)

    fun of(error: DbError, cause: Throwable? = null): EngineError = when (error) {
        is DbError.QueryFailed -> EngineError(
            message = error.message,
            // The SQLite result code's name, not a SQLSTATE. See [SqliteErrors].
            code = error.sqlState,
            detail = error.detail,
            hint = error.hint,
            cause = cause,
        )

        // Everything else — a file that would not open, a timeout, an interrupt — has
        // a stable code and a message that has already been redacted, and points at
        // nothing in particular in the buffer.
        else -> EngineError(message = error.message, code = error.code, cause = cause)
    }
}

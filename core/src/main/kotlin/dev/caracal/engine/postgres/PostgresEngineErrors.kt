package dev.caracal.engine.postgres

import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.sql.Statement
import dev.caracal.engine.api.EngineError
import dev.caracal.engine.api.SourcePosition
import dev.caracal.engine.api.StatementRequest

/**
 * A classified `:core` failure, carried across into the SPI's error model.
 *
 * The only interesting line in the file is the position, and it is the line the
 * whole refactor is most likely to break. PostgreSQL reports a **1-based position in
 * code points, within the statement it received**. The editor needs a **0-based
 * UTF-16 offset within the whole buffer**. Those two differ by the statement's own
 * start offset and by a code-point conversion that only matters when a character
 * outside the BMP appears earlier in the statement — one emoji in a literal and
 * every subsequent underline is off by one, drifting further with each.
 *
 * That conversion already exists, is already correct, and is already tested:
 * [Statement.documentIndex]. This calls it. It does not reimplement it, and a future
 * engine that reports positions should not either — the mapping is shared SQL
 * machinery, bound for `:engine-sql`, and not something each driver works out again.
 *
 * A position that does not land inside the statement produces no [SourcePosition] at
 * all rather than a clamped one, because an underline in the wrong place is a claim
 * the user has no way to check.
 */
internal object PostgresEngineErrors {

    fun of(failure: DbException, request: StatementRequest): EngineError =
        of(failure.error, request, failure)

    fun of(error: DbError, request: StatementRequest, cause: Throwable? = null): EngineError =
        when (error) {
            is DbError.QueryFailed -> EngineError(
                message = error.message,
                code = error.sqlState,
                position = error.position?.let { position(it, request) },
                detail = error.detail,
                hint = error.hint,
                cause = cause,
            )

            // Everything else — a dropped connection, a timeout, a refused export —
            // has a stable code and a message that has already been redacted, and
            // points at nothing in particular in the buffer.
            else -> EngineError(message = error.message, code = error.code, cause = cause)
        }

    private fun position(reported: Int, request: StatementRequest): SourcePosition? {
        val statement = Statement(
            text = request.sql,
            start = request.sourceOffset,
            end = request.sourceOffset + request.sql.length,
        )
        return statement.documentIndex(reported)?.let { SourcePosition(it) }
    }
}

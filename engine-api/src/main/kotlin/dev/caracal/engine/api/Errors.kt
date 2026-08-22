package dev.caracal.engine.api

/**
 * What went wrong, in the terms the editor needs to point at it.
 *
 * [message] arrives already redacted. That is not a suggestion: a driver writes the
 * address it dialled into most of its connection failures, and PostgreSQL quotes the
 * offending literal back at you in the error text. Redaction happens where the
 * connection's own secrets are known, which is inside the engine, and an
 * [EngineError] constructed anywhere else has already lost that chance.
 *
 * [position] is the field that makes this product feel precise and the easiest one
 * to lose in a refactor, so it is worth being exact about what it means: a 0-based
 * UTF-16 offset into the **whole editor buffer**, not into the statement the server
 * saw. Getting there from what PostgreSQL reports — a 1-based count of *code points*
 * within one statement — takes the statement's own start offset and a code-point
 * aware conversion, and that conversion already exists and is already tested as
 * `dev.caracal.core.sql.Statement.documentIndex`. Engines call it. They do not
 * reimplement it.
 *
 * [position] is null for engines that do not report one at all — MySQL does not —
 * and the caller degrades to underlining the whole statement. That fallback is
 * written once, in the UI, never per engine.
 */
data class EngineError(
    val message: String,
    val code: String? = null,
    val position: SourcePosition? = null,
    val detail: String? = null,
    val hint: String? = null,
    val internalQuery: String? = null,
    val cause: Throwable? = null,
)

/** A span in the editor buffer. [offset] is 0-based and counts UTF-16 units. */
data class SourcePosition(val offset: Int, val length: Int = 1)

/**
 * The request was malformed before any server was asked.
 *
 * A scan cursor that is not a cursor, a command line with an unclosed quote, a
 * command with no command in it. It is here rather than in `:core` because the types
 * that raise it are here: [ScanCursor.of] and [CommandLine] are read by the engine
 * that sends the bytes *and* by the console that previews them, and neither end may
 * see `:core`'s error hierarchy.
 *
 * `:core` classifies it back into `DbError.InvalidRequest`, with the same code and
 * the same sentence, so nothing downstream can tell that the throw site moved.
 */
class InvalidRequestException(override val message: String) : Exception(message)

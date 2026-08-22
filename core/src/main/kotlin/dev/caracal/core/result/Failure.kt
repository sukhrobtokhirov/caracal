package dev.caracal.core.result

import dev.caracal.core.connections.ValidationError
import dev.caracal.core.connections.ValidationException
import dev.caracal.core.store.StoreException
import dev.caracal.core.vault.VaultException
import dev.caracal.engine.api.InvalidRequestException
import kotlinx.coroutines.CancellationException

/**
 * Any `:core` failure, in the one shape the UI renders.
 *
 * [code] is the stable, machine-readable case; [message] is human-readable and
 * carries no host, port, user name, connection string, or driver text. [fields]
 * is non-empty only for a rejected form, and names the inputs to mark.
 *
 * [query] is the one place this shape is not flat, and §2.9 is why. A server error
 * carries a SQLSTATE, a severity, a detail, a hint, the object it names, and a
 * position in the statement — and flattening all of that into one sentence throws
 * away both the position the editor needs to highlight and the hint that is
 * frequently the entire answer. It is `null` for every failure that is not one.
 */
data class Failure(
    val code: String,
    val message: String,
    val fields: List<ValidationError> = emptyList(),
    val query: DbError.QueryFailed? = null,
) {
    /** The message for one field, if this failure names it. */
    fun messageFor(field: String): String? = fields.firstOrNull { it.field == field }?.message
}

/**
 * Classifies a thrown failure for display.
 *
 * The four hierarchies `:core` throws — database, vault, store, and validation —
 * all already carry a stable code and a safe message, so this is a join rather
 * than a translation. Anything else is deliberately given a generic message:
 * an unexpected exception's text has not been through redaction.
 *
 * [InvalidRequestException] is the fifth and it is a join too. The SPI raises it for
 * a request that was malformed before any server was asked — a scan cursor that is
 * not a cursor, a command line with an unclosed quote — because the types that raise
 * it live in `:engine-api` and cannot see `DbError`. It lands on the same code and
 * the same sentence `DbError.InvalidRequest` always produced, so nothing downstream
 * can tell that the throw site moved out of `:core`.
 */
fun Throwable.toFailure(): Failure = when (this) {
    is CancellationException -> Failure("cancelled", "The operation was cancelled.")
    is DbException -> Failure(error.code, error.message, query = error as? DbError.QueryFailed)
    is InvalidRequestException -> Failure(DbError.InvalidRequest(message).code, message)
    is VaultException -> Failure(code, safeMessage)
    is StoreException -> Failure(code, safeMessage)
    is ValidationException -> Failure(
        code = "invalid_input",
        message = errors.firstOrNull()?.message ?: "Check the highlighted fields.",
        fields = errors,
    )

    else -> Failure("unexpected_error", "Something went wrong. Please try again.")
}

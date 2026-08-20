package dev.dbide.core.result

import dev.dbide.core.connections.ValidationError
import dev.dbide.core.connections.ValidationException
import dev.dbide.core.store.StoreException
import dev.dbide.core.vault.VaultException
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
 */
fun Throwable.toFailure(): Failure = when (this) {
    is CancellationException -> Failure("cancelled", "The operation was cancelled.")
    is DbException -> Failure(error.code, error.message, query = error as? DbError.QueryFailed)
    is VaultException -> Failure(code, safeMessage)
    is StoreException -> Failure(code, safeMessage)
    is ValidationException -> Failure(
        code = "invalid_input",
        message = errors.firstOrNull()?.message ?: "Check the highlighted fields.",
        fields = errors,
    )

    else -> Failure("unexpected_error", "Something went wrong. Please try again.")
}

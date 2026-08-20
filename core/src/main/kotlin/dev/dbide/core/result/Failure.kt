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
 */
data class Failure(
    val code: String,
    val message: String,
    val fields: List<ValidationError> = emptyList(),
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
    is DbException -> Failure(error.code, error.message)
    is VaultException -> Failure(code, safeMessage)
    is StoreException -> Failure(code, safeMessage)
    is ValidationException -> Failure(
        code = "invalid_input",
        message = errors.firstOrNull()?.message ?: "Check the highlighted fields.",
        fields = errors,
    )

    else -> Failure("unexpected_error", "Something went wrong. Please try again.")
}

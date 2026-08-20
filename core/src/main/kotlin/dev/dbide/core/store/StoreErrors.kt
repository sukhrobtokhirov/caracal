package dev.dbide.core.store

import dev.dbide.core.connections.ConnectionId
import java.sql.SQLException

/**
 * A configuration-store failure the UI can render. [code] is the stable case;
 * [message] is human-readable and never carries SQL, a file path, or driver text.
 */
sealed class StoreException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val safeMessage: String get() = message ?: code
}

/** The configuration database could not be created, opened, or migrated. */
class StoreOpenException(message: String, cause: Throwable? = null) :
    StoreException("store_unavailable", message, cause)

/** A stored row could not be turned back into a domain type. */
class StoreReadException(message: String) : StoreException("store_corrupt", message)

/** No connection has this identifier. */
class ConnectionNotFoundException(val id: ConnectionId) :
    StoreException("connection_not_found", "That connection no longer exists.")

/** Another connection already uses this name. */
class DuplicateNameException :
    StoreException("duplicate_name", "A connection with that name already exists.")

/**
 * The configuration database could not be read or written.
 *
 * The realistic causes are a second copy of the application holding the file and a
 * damaged file, so the message names both rather than blaming the user's last click.
 */
class StoreUnavailableException(cause: Throwable) :
    StoreException(
        "store_unavailable",
        "The configuration database could not be used. It may be open in another " +
            "copy of the application, or the file may be damaged.",
        cause,
    )

/**
 * Turns a driver exception into a store failure.
 *
 * A duplicate name is the one case a user can act on, so it keeps its own message;
 * everything else means the store itself is unusable. SQLite reports the unique-index
 * violation by name in its message text, which is checked because the extended
 * result code is not always propagated through JDBC.
 */
internal fun SQLException.asStoreFailure(): StoreException {
    val text = message.orEmpty()
    val duplicateName = text.contains("connections.name") || text.contains("idx_connections_name")
    return if (duplicateName) DuplicateNameException() else StoreUnavailableException(this)
}

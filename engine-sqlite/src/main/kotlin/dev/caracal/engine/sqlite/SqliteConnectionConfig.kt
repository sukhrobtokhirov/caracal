package dev.caracal.engine.sqlite

import dev.caracal.core.text.Redaction
import java.nio.file.Path
import java.util.Properties
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteOpenMode

/**
 * Everything needed to open one SQLite database.
 *
 * The dialing form, built by [SqliteEngine] from a descriptor at the moment a session
 * is opened. It holds no secret — SQLite has none — which does not make it free of
 * anything worth hiding: the path *is* the connection's identity, and a path is where
 * a person's project names, a customer's name and the layout of their disk live.
 * `DbError`'s contract forbids a host or a URL in a message for the same reason, so
 * the path is redacted on the same terms.
 */
data class SqliteConnectionConfig(
    val path: Path,
    /**
     * Whether the database handle is opened `SQLITE_OPEN_READONLY`.
     *
     * §5.1's enforcing half, and it defaults to `true` because a safety property whose
     * default is off is not a safety property.
     */
    val readOnly: Boolean = true,
    /**
     * Whether a path that names nothing becomes an empty database.
     *
     * Ignored when [readOnly]: creating a file in order to open it read-only is a
     * contradiction, and SQLite refuses the flag combination outright.
     */
    val createIfMissing: Boolean = false,
) {
    /**
     * The absolute path, resolved once.
     *
     * Resolved here rather than left to the driver because a relative path is
     * otherwise interpreted against the process's working directory, which for a
     * desktop application is wherever the launcher happened to start it — a different
     * database depending on how the user opened the window.
     */
    val absolutePath: Path get() = path.toAbsolutePath().normalize()

    /**
     * The JDBC URL, with the file named as a URI.
     *
     * `jdbc:sqlite:/some/path` is the spelling everyone writes and it has a hole in
     * it: sqlite-jdbc reads its own connection parameters out of everything after the
     * first `?`, so a database called `q?.db` is opened as `q` with a parameter list.
     * A URI filename closes it — [Path.toUri] percent-encodes `?`, `#` and the spaces,
     * SQLite decodes them again, and the driver enables `SQLITE_OPEN_URI` on every
     * connection it makes, so nothing has to be turned on for this to work.
     *
     * It also carries no credentials, because there are none to carry. That is the one
     * pleasant thing about this engine's error messages.
     */
    val jdbcUrl: String get() = "jdbc:sqlite:" + absolutePath.toUri().toString()

    /**
     * The driver properties, which is where read-only lives.
     *
     * The open mode is the whole of SQLite's read-only enforcement and it is applied
     * to the database handle rather than to the transaction: `SQLITE_OPEN_READONLY`
     * makes every write fail inside SQLite with `SQLITE_READONLY`, whether it arrived
     * as an `UPDATE`, from a trigger, or through a view. It is what
     * [dev.caracal.engine.api.ReadOnlyEnforcement.CONNECTION_URI] claims, and there is
     * nothing in this process that could be walked past to reach the file.
     *
     * The flag has a second job the URI form could not do: `Connection.isReadOnly()`
     * reads it, so the pool and the driver agree about what this connection is.
     * HikariCP compares the two and corrects a disagreement by calling `setReadOnly`,
     * which sqlite-jdbc refuses after the connection is open — so a connection that
     * is read-only in fact and writable in JDBC's opinion fails on the way out of the
     * pool rather than on the write it was opened to refuse.
     */
    fun properties(): Properties = SQLiteConfig().apply {
        if (readOnly) {
            setReadOnly(true)
        } else {
            setReadOnly(false)
            // `setReadOnly(false)` turns CREATE back on with READWRITE, which is the
            // driver's default and is the wrong default here: a path that names
            // nothing must be an error unless the connection asked for a new database.
            if (!createIfMissing) resetOpenMode(SQLiteOpenMode.CREATE)
        }
        // Two writers on one file is SQLITE_BUSY, and the honest fix is to wait a
        // moment rather than to fail a statement the user is watching. Short, because
        // past a few seconds waiting is indistinguishable from a hang and the
        // statement timeout is the thing that should end it.
        setBusyTimeout(BUSY_TIMEOUT_MILLIS)
    }.toProperties()

    /**
     * The strings that must never survive into a message or a log line.
     *
     * The URL and both spellings of the path. The file *name* is deliberately included
     * as well: a database called `acme-payroll.db` names a customer whether or not the
     * directory above it is in the message.
     */
    fun secrets(): List<String> = listOf(jdbcUrl, absolutePath.toString(), path.toString())
        .plus(absolutePath.fileName?.toString().orEmpty())
        .filter { it.isNotBlank() }
        .distinct()

    /** The redaction this connection's driver messages pass through. */
    fun redaction(): Redaction = Redaction(secrets())

    /** Never the path. A config that prints itself into a log has undone the rest of this file. */
    override fun toString(): String = "SqliteConnectionConfig(readOnly=$readOnly)"

    private companion object {
        const val BUSY_TIMEOUT_MILLIS = 3_000
    }
}

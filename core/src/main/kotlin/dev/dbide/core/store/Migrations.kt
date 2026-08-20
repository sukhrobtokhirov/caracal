package dev.dbide.core.store

import java.sql.Connection
import java.sql.SQLException

/**
 * One forward schema step. Steps are applied in order, each in its own
 * transaction, and the recorded version advances only if the step commits.
 */
private class Migration(val version: Int, val name: String, val statements: List<String>)

/** The `app_metadata` key holding the applied schema version. */
internal const val META_SCHEMA_VERSION = "schema_version"

/**
 * The migration list is append-only. Never edit an applied step; add a new one.
 */
private val MIGRATIONS = listOf(
    Migration(
        version = 1,
        name = "initial schema",
        statements = listOf(
            """
            CREATE TABLE connections (
                id            TEXT PRIMARY KEY,
                name          TEXT NOT NULL,
                engine        TEXT NOT NULL,
                host          TEXT NOT NULL,
                port          INTEGER NOT NULL,
                "database"    TEXT,
                username      TEXT,
                secret_sealed BLOB,
                tls_mode      TEXT,
                environment   TEXT NOT NULL DEFAULT 'dev',
                read_only     INTEGER NOT NULL DEFAULT 0,
                color         TEXT,
                created_at    TIMESTAMP NOT NULL
            )
            """,
            // Two connections with the same name would make the environment badge
            // useless as a safety signal.
            "CREATE UNIQUE INDEX idx_connections_name ON connections(name)",
            // query_history is unused until M2/M4. Creating it now fixes the storage
            // contract before anything depends on it.
            """
            CREATE TABLE query_history (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
                statement     TEXT NOT NULL,
                duration_ms   INTEGER,
                row_count     INTEGER,
                status        TEXT NOT NULL,
                error         TEXT,
                executed_at   TIMESTAMP NOT NULL
            )
            """,
            "CREATE INDEX idx_history_conn_time ON query_history(connection_id, executed_at DESC)",
        ),
    ),
)

/** The schema version this build writes and understands. */
internal val LATEST_SCHEMA_VERSION = MIGRATIONS.last().version

/** Brings an empty or partially migrated database up to date. */
internal fun migrate(connection: Connection) {
    // app_metadata holds the version itself, so it cannot be a versioned step.
    connection.createStatement().use { statement ->
        statement.execute("CREATE TABLE IF NOT EXISTS app_metadata (key TEXT PRIMARY KEY, value BLOB NOT NULL)")
    }

    val current = readSchemaVersion(connection)
    if (current > LATEST_SCHEMA_VERSION) {
        throw StoreOpenException(
            "This configuration file was written by a newer version of the application " +
                "(schema $current; this build understands $LATEST_SCHEMA_VERSION).",
        )
    }

    MIGRATIONS.filter { it.version > current }.forEach { migration ->
        try {
            applyMigration(connection, migration)
        } catch (failure: SQLException) {
            throw StoreOpenException(
                "The configuration database could not be prepared (step ${migration.version}, ${migration.name}).",
                failure,
            )
        }
    }
}

private fun applyMigration(connection: Connection, migration: Migration) {
    connection.autoCommit = false
    try {
        connection.createStatement().use { statement ->
            migration.statements.forEach { statement.execute(it.trimIndent()) }
        }
        connection.prepareStatement(
            """
            INSERT INTO app_metadata(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, META_SCHEMA_VERSION)
            statement.setBytes(2, migration.version.toString().toByteArray(Charsets.US_ASCII))
            statement.executeUpdate()
        }
        connection.commit()
    } catch (failure: Throwable) {
        runCatching { connection.rollback() }
        throw failure
    } finally {
        connection.autoCommit = true
    }
}

private fun readSchemaVersion(connection: Connection): Int {
    connection.prepareStatement("SELECT value FROM app_metadata WHERE key = ?").use { statement ->
        statement.setString(1, META_SCHEMA_VERSION)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return 0
            val raw = String(rows.getBytes(1) ?: ByteArray(0), Charsets.US_ASCII).trim()
            return raw.toIntOrNull()
                ?: throw StoreOpenException("The stored schema version \"$raw\" is not a number.")
        }
    }
}

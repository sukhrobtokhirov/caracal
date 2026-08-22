package dev.caracal.core.store

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
    Migration(
        version = 2,
        name = "read_only becomes load-bearing",
        statements = listOf(
            // Until M2 §2.4 the pool opened every connection read-only whatever this
            // column said, so an unticked box was not a decision to allow writes — it
            // was a box that did nothing. Now that it decides how the pool is built,
            // leaving those rows at 0 would quietly turn every connection saved before
            // this release into a writable one.
            //
            // So every existing row is set to read only, and the user re-enables
            // writing where they want it. Turning a connection writable is one visible
            // click; discovering it was already writable is a `DELETE` that ran.
            "UPDATE connections SET read_only = 1",
            // New connections start read-only too. The column default is the second
            // half of that; ConnectionDraft is the first.
            """
            CREATE TABLE connections_new (
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
                read_only     INTEGER NOT NULL DEFAULT 1,
                color         TEXT,
                created_at    TIMESTAMP NOT NULL
            )
            """,
            """
            INSERT INTO connections_new
            SELECT id, name, engine, host, port, "database", username, secret_sealed,
                   tls_mode, environment, read_only, color, created_at
            FROM connections
            """,
            "DROP TABLE connections",
            "ALTER TABLE connections_new RENAME TO connections",
            "CREATE UNIQUE INDEX idx_connections_name ON connections(name)",
        ),
    ),
    Migration(
        version = 3,
        name = "history is paged by id",
        statements = listOf(
            // M4's panel reads history newest-first and pages through it with a
            // keyset, and the key is `id` rather than `executed_at` — the column
            // holds `Instant.toString()`, whose optional fractional second makes a
            // text comparison disagree with a chronological one. ConfigStore.history
            // explains it in full. What that leaves behind is an index on a column
            // nothing orders by any more, and its replacement.
            "DROP INDEX IF EXISTS idx_history_conn_time",
            "CREATE INDEX idx_history_conn_id ON query_history(connection_id, id DESC)",
        ),
    ),
    Migration(
        version = 4,
        name = "a connection names its engine and declares its own fields",
        statements = listOf(
            // Phase 3: an engine is no longer one of two, so a connection can no
            // longer be four columns the application picked. What it points at is a
            // target — a host and a port, or a file — and everything else is whatever
            // that engine's connection form declared, under the engine's own key.
            //
            // `username` and `tls_mode` are the two columns that become settings.
            // They are copied out before the rebuild rather than after, because after
            // is too late: the rebuild drops the table they are in.
            """
            CREATE TABLE connection_settings (
                connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
                key           TEXT NOT NULL,
                value         TEXT NOT NULL,
                PRIMARY KEY (connection_id, key)
            )
            """,
            """
            INSERT INTO connection_settings(connection_id, key, value)
            SELECT id, 'user', username FROM connections
            WHERE username IS NOT NULL AND username <> ''
            """,
            """
            INSERT INTO connection_settings(connection_id, key, value)
            SELECT id, 'tls', tls_mode FROM connections
            WHERE tls_mode IS NOT NULL AND tls_mode <> ''
            """,
            // host and port lose their NOT NULL because a file target has neither,
            // and file_path arrives empty for every row that exists: no engine that
            // opens a file has shipped yet, and inventing a path for a PostgreSQL
            // connection would be worse than leaving the column null.
            """
            CREATE TABLE connections_new (
                id            TEXT PRIMARY KEY,
                name          TEXT NOT NULL,
                engine        TEXT NOT NULL,
                target_kind   TEXT NOT NULL DEFAULT 'network',
                host          TEXT,
                port          INTEGER,
                "database"    TEXT,
                file_path     TEXT,
                secret_sealed BLOB,
                environment   TEXT NOT NULL DEFAULT 'dev',
                read_only     INTEGER NOT NULL DEFAULT 1,
                color         TEXT,
                created_at    TIMESTAMP NOT NULL
            )
            """,
            """
            INSERT INTO connections_new
                (id, name, engine, target_kind, host, port, "database", file_path,
                 secret_sealed, environment, read_only, color, created_at)
            SELECT id, name, engine, 'network', host, port, "database", NULL,
                   secret_sealed, environment, read_only, color, created_at
            FROM connections
            """,
            // Safe because migrations run with foreign keys off — see applyMigration.
            // With them on, this DROP would cascade into the settings just written
            // and into every row of query_history.
            "DROP TABLE connections",
            "ALTER TABLE connections_new RENAME TO connections",
            "CREATE UNIQUE INDEX idx_connections_name ON connections(name)",
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

/**
 * Applies one step with foreign keys off, which is SQLite's documented procedure
 * for a table rebuild.
 *
 * With `foreign_keys` on — and [ConfigStore] turns it on — SQLite performs an
 * implicit `DELETE FROM` before dropping a table, and that fires every
 * `ON DELETE CASCADE` pointing at it. Step 2 rebuilds `connections` that way, and
 * `query_history.connection_id` cascades from it: the rebuild would take the
 * user's entire query history with it, inside the migration's own transaction, and
 * commit. It costs nothing today because a schema-1 database predates history
 * being written, but the rebuild is now the idiom in this file and the next one
 * would land on a populated table.
 *
 * The pragma is a no-op inside a transaction, so it is set here, outside the one
 * below, and `foreign_key_check` verifies before committing that the step did not
 * leave a dangling reference behind — which is what enforcement would have caught.
 */
private fun applyMigration(connection: Connection, migration: Migration) {
    connection.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
    try {
        applyMigrationStatements(connection, migration)
    } finally {
        connection.createStatement().use { runCatching { it.execute("PRAGMA foreign_keys = ON") } }
    }
}

private fun applyMigrationStatements(connection: Connection, migration: Migration) {
    connection.autoCommit = false
    try {
        connection.createStatement().use { statement ->
            migration.statements.forEach { statement.execute(it.trimIndent()) }
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_check").use { violations ->
                if (violations.next()) {
                    throw StoreOpenException(
                        "The configuration database could not be prepared " +
                            "(step ${migration.version}, ${migration.name} left a dangling reference).",
                    )
                }
            }
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

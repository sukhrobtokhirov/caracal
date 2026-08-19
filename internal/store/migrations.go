package store

import (
	"context"
	"database/sql"
	"fmt"
)

// migration is one forward schema step. Steps are applied in order, each in its
// own transaction, and the recorded version advances only if the step commits.
type migration struct {
	version int
	name    string
	stmts   []string
}

// metaSchemaVersion is the app_metadata key holding the applied version.
const metaSchemaVersion = "schema_version"

// migrations is append-only. Never edit an applied step; add a new one.
var migrations = []migration{
	{
		version: 1,
		name:    "initial schema",
		stmts: []string{
			`CREATE TABLE connections (
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
			)`,
			`CREATE UNIQUE INDEX idx_connections_name ON connections(name)`,
			// query_history is unused until M2/M4. Creating it now fixes the
			// storage contract before anything depends on it.
			`CREATE TABLE query_history (
				id            INTEGER PRIMARY KEY AUTOINCREMENT,
				connection_id TEXT NOT NULL REFERENCES connections(id) ON DELETE CASCADE,
				statement     TEXT NOT NULL,
				duration_ms   INTEGER,
				row_count     INTEGER,
				status        TEXT NOT NULL,
				error         TEXT,
				executed_at   TIMESTAMP NOT NULL
			)`,
			`CREATE INDEX idx_history_conn_time ON query_history(connection_id, executed_at DESC)`,
		},
	},
}

// migrate brings an empty or partially migrated database up to date.
func migrate(ctx context.Context, db *sql.DB) error {
	// app_metadata holds the version itself, so it cannot be a versioned step.
	if _, err := db.ExecContext(ctx,
		`CREATE TABLE IF NOT EXISTS app_metadata (key TEXT PRIMARY KEY, value BLOB NOT NULL)`); err != nil {
		return fmt.Errorf("create app_metadata: %w", err)
	}

	current, err := schemaVersion(ctx, db)
	if err != nil {
		return err
	}
	if latest := migrations[len(migrations)-1].version; current > latest {
		return fmt.Errorf("this configuration file was written by a newer version (schema %d, this build understands %d)", current, latest)
	}

	for _, m := range migrations {
		if m.version <= current {
			continue
		}
		if err := applyMigration(ctx, db, m); err != nil {
			return fmt.Errorf("migration %d (%s): %w", m.version, m.name, err)
		}
	}
	return nil
}

func applyMigration(ctx context.Context, db *sql.DB, m migration) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	for _, stmt := range m.stmts {
		if _, err := tx.ExecContext(ctx, stmt); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO app_metadata(key, value) VALUES(?, ?)
		 ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
		metaSchemaVersion, []byte(fmt.Sprint(m.version))); err != nil {
		return err
	}
	return tx.Commit()
}

func schemaVersion(ctx context.Context, db *sql.DB) (int, error) {
	var raw []byte
	err := db.QueryRowContext(ctx, `SELECT value FROM app_metadata WHERE key = ?`, metaSchemaVersion).Scan(&raw)
	switch {
	case err == sql.ErrNoRows:
		return 0, nil
	case err != nil:
		return 0, fmt.Errorf("read schema version: %w", err)
	}
	var v int
	if _, err := fmt.Sscanf(string(raw), "%d", &v); err != nil {
		return 0, fmt.Errorf("the stored schema version %q is not a number", raw)
	}
	return v, nil
}

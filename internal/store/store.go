// Package store is the local configuration database: a pure-Go SQLite file
// holding connection records, encryption metadata, and (from M2) query history.
//
// Callers work with domain types. Nothing outside this package needs to know
// about SQL, column names, or that sealed secrets are stored as BLOBs.
package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "modernc.org/sqlite" // pure-Go driver: no cgo, so cross-compilation stays trivial

	"github.com/stohirov/database-ide/internal/connections"
)

// Store errors.
var (
	// ErrNotFound reports a missing connection.
	ErrNotFound = errors.New("connection not found")
	// ErrDuplicateName reports a name already used by another connection.
	ErrDuplicateName = errors.New("a connection with that name already exists")
)

const busyTimeoutMs = 5000

// Store owns the SQLite handle.
type Store struct {
	db   *sql.DB
	path string
}

// Open creates or opens the configuration database and migrates it.
//
// The file and its directory are created with owner-only permissions where the
// operating system supports it: it holds sealed credentials.
func Open(ctx context.Context, path string) (*Store, error) {
	if dir := filepath.Dir(path); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return nil, fmt.Errorf("create configuration directory: %w", err)
		}
	}

	// A single connection avoids SQLite write contention entirely; this is a
	// single-user desktop tool, not a server.
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(%d)&_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)",
		path, busyTimeoutMs)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open configuration database: %w", err)
	}
	db.SetMaxOpenConns(1)

	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("open configuration database: %w", err)
	}
	if err := restrictPermissions(path); err != nil {
		slog.Debug("could not restrict configuration file permissions",
			"component", "store", "error", err.Error())
	}
	if err := migrate(ctx, db); err != nil {
		_ = db.Close()
		return nil, err
	}
	return &Store{db: db, path: path}, nil
}

// Path reports the database file location.
func (s *Store) Path() string { return s.path }

// Close releases the database handle.
func (s *Store) Close() error {
	if s == nil || s.db == nil {
		return nil
	}
	return s.db.Close()
}

// restrictPermissions tightens the database file to owner-only. Not every
// platform or filesystem implements this, so a failure is reported for logging
// rather than treated as fatal.
func restrictPermissions(path string) error {
	return os.Chmod(path, 0o600)
}

// GetMeta reads one metadata value.
func (s *Store) GetMeta(ctx context.Context, key string) ([]byte, bool, error) {
	var value []byte
	err := s.db.QueryRowContext(ctx, `SELECT value FROM app_metadata WHERE key = ?`, key).Scan(&value)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, false, nil
	case err != nil:
		return nil, false, fmt.Errorf("read metadata %q: %w", key, err)
	}
	return value, true, nil
}

// PutMeta writes one metadata value.
func (s *Store) PutMeta(ctx context.Context, key string, value []byte) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO app_metadata(key, value) VALUES(?, ?)
		 ON CONFLICT(key) DO UPDATE SET value = excluded.value`, key, value)
	if err != nil {
		return fmt.Errorf("write metadata %q: %w", key, err)
	}
	return nil
}

const connectionColumns = `id, name, engine, host, port, "database", username, secret_sealed,
	tls_mode, environment, read_only, color, created_at`

// CreateConnection inserts a new connection record.
func (s *Store) CreateConnection(ctx context.Context, rec connections.Record) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO connections (`+connectionColumns+`)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		rec.ID, rec.Name, string(rec.Engine), rec.Host, rec.Port, rec.Database, rec.Username,
		rec.SealedSecret, string(rec.TLSMode), string(rec.Environment), boolToInt(rec.ReadOnly),
		rec.Color, rec.CreatedAt.UTC().Format(time.RFC3339Nano))
	if err != nil {
		return wrapConstraint(err)
	}
	return nil
}

// UpdateConnection replaces every mutable field of an existing connection.
// created_at is immutable.
func (s *Store) UpdateConnection(ctx context.Context, rec connections.Record) error {
	res, err := s.db.ExecContext(ctx,
		`UPDATE connections SET name = ?, engine = ?, host = ?, port = ?, "database" = ?,
		 username = ?, secret_sealed = ?, tls_mode = ?, environment = ?, read_only = ?, color = ?
		 WHERE id = ?`,
		rec.Name, string(rec.Engine), rec.Host, rec.Port, rec.Database, rec.Username,
		rec.SealedSecret, string(rec.TLSMode), string(rec.Environment), boolToInt(rec.ReadOnly),
		rec.Color, rec.ID)
	if err != nil {
		return wrapConstraint(err)
	}
	affected, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("update connection: %w", err)
	}
	if affected == 0 {
		return ErrNotFound
	}
	return nil
}

// GetConnection reads one connection, including its sealed secret.
func (s *Store) GetConnection(ctx context.Context, id string) (connections.Record, error) {
	row := s.db.QueryRowContext(ctx, `SELECT `+connectionColumns+` FROM connections WHERE id = ?`, id)
	rec, err := scanConnection(row)
	if errors.Is(err, sql.ErrNoRows) {
		return connections.Record{}, ErrNotFound
	}
	return rec, err
}

// ListConnections returns every connection ordered by environment severity
// (prod first, so the dangerous ones are never buried) then by name,
// case-insensitively.
func (s *Store) ListConnections(ctx context.Context) ([]connections.Record, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+connectionColumns+` FROM connections
		 ORDER BY CASE environment WHEN 'prod' THEN 0 WHEN 'staging' THEN 1 ELSE 2 END,
		          name COLLATE NOCASE, id`)
	if err != nil {
		return nil, fmt.Errorf("list connections: %w", err)
	}
	defer rows.Close()

	out := []connections.Record{}
	for rows.Next() {
		rec, err := scanConnection(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, rec)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("list connections: %w", err)
	}
	return out, nil
}

// DeleteConnection removes a connection. Its query history cascades away.
func (s *Store) DeleteConnection(ctx context.Context, id string) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM connections WHERE id = ?`, id)
	if err != nil {
		return fmt.Errorf("delete connection: %w", err)
	}
	affected, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("delete connection: %w", err)
	}
	if affected == 0 {
		return ErrNotFound
	}
	return nil
}

// scanner covers both *sql.Row and *sql.Rows.
type scanner interface {
	Scan(dest ...any) error
}

func scanConnection(row scanner) (connections.Record, error) {
	var (
		rec       connections.Record
		engine    string
		tlsMode   sql.NullString
		database  sql.NullString
		username  sql.NullString
		color     sql.NullString
		env       string
		readOnly  int
		createdAt string
	)
	if err := row.Scan(&rec.ID, &rec.Name, &engine, &rec.Host, &rec.Port, &database, &username,
		&rec.SealedSecret, &tlsMode, &env, &readOnly, &color, &createdAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return connections.Record{}, err
		}
		return connections.Record{}, fmt.Errorf("read connection: %w", err)
	}

	rec.Engine = connections.Engine(engine)
	rec.Database = database.String
	rec.Username = username.String
	rec.TLSMode = connections.TLSMode(tlsMode.String)
	rec.Environment = connections.Environment(env)
	rec.ReadOnly = readOnly != 0
	rec.Color = color.String

	parsed, err := time.Parse(time.RFC3339Nano, createdAt)
	if err != nil {
		return connections.Record{}, fmt.Errorf("connection %s has an unreadable creation time", rec.ID)
	}
	rec.CreatedAt = parsed.UTC()
	return rec, nil
}

func wrapConstraint(err error) error {
	msg := err.Error()
	if strings.Contains(msg, "UNIQUE constraint failed: connections.name") ||
		strings.Contains(msg, "idx_connections_name") {
		return ErrDuplicateName
	}
	return fmt.Errorf("write connection: %w", err)
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

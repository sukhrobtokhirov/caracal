// Package postgres holds the M0 PostgreSQL round-trip scaffolding.
//
// SCAFFOLDING: the single hardcoded connection and the fixed statement below
// are replaced by the connection registry in M1 and the real query path in M2.
// Nothing outside this package should grow to depend on the fixed query.
package postgres

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	connectTimeout = 5 * time.Second
	queryTimeout   = 10 * time.Second
)

// Column describes one result column. This mirrors the shape M2 will use so the
// frontend result view is reusable.
type Column struct {
	Name     string `json:"name"`
	TypeOID  uint32 `json:"typeOid"`
	TypeName string `json:"typeName"`
}

// Result is the wire format for a query result. Every value is already
// stringified server-side except booleans and nulls, so JSON cannot silently
// lose precision on numeric or int8 columns.
type Result struct {
	Columns    []Column `json:"columns"`
	Rows       [][]any  `json:"rows"`
	RowCount   int      `json:"rowCount"`
	DurationMs int64    `json:"durationMs"`
}

// Adapter owns one pgx pool.
type Adapter struct {
	pool *pgxpool.Pool
}

// Open parses dsn and creates a lazily-connecting pool. It does not dial; use
// Ping to verify reachability.
func Open(dsn string) (*Adapter, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		// The DSN may embed a password; never surface the parse input.
		return nil, fmt.Errorf("%w: connection string is not valid", ErrConfig)
	}
	cfg.MaxConns = 4
	cfg.ConnConfig.ConnectTimeout = connectTimeout

	pool, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		return nil, fmt.Errorf("%w: pool could not be created", ErrConfig)
	}
	return &Adapter{pool: pool}, nil
}

// Ping verifies the database is reachable.
func (a *Adapter) Ping(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, connectTimeout)
	defer cancel()
	return a.pool.Ping(ctx)
}

// Close releases all pooled connections.
func (a *Adapter) Close() {
	if a != nil && a.pool != nil {
		a.pool.Close()
	}
}

// SelectOne executes the literal bootstrap statement. User-supplied SQL is
// deliberately not accepted in M0.
func (a *Adapter) SelectOne(ctx context.Context) (*Result, error) {
	return a.queryFixed(ctx, "SELECT 1 AS value")
}

func (a *Adapter) queryFixed(ctx context.Context, sql string) (*Result, error) {
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()

	start := time.Now()
	rows, err := a.pool.Query(ctx, sql)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	fields := rows.FieldDescriptions()
	cols := make([]Column, len(fields))
	for i, f := range fields {
		cols[i] = Column{Name: f.Name, TypeOID: f.DataTypeOID, TypeName: typeName(f.DataTypeOID)}
	}

	out := &Result{Columns: cols, Rows: [][]any{}}
	for rows.Next() {
		vals, err := rows.Values()
		if err != nil {
			return nil, err
		}
		encoded := make([]any, len(vals))
		for i, v := range vals {
			encoded[i] = encode(v)
		}
		out.Rows = append(out.Rows, encoded)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	out.RowCount = len(out.Rows)
	out.DurationMs = time.Since(start).Milliseconds()
	return out, nil
}

// encode stringifies a value so JSON round-trips it exactly. Booleans and nulls
// keep their native JSON representation; M2 extends this per OID class.
func encode(v any) any {
	switch t := v.(type) {
	case nil:
		return nil
	case bool:
		return t
	case string:
		return t
	case []byte:
		return "\\x" + fmt.Sprintf("%x", t)
	case time.Time:
		return t.Format(time.RFC3339Nano)
	case int64:
		return strconv.FormatInt(t, 10)
	case int32:
		return strconv.FormatInt(int64(t), 10)
	case int16:
		return strconv.FormatInt(int64(t), 10)
	case float64:
		return strconv.FormatFloat(t, 'g', -1, 64)
	case float32:
		return strconv.FormatFloat(float64(t), 'g', -1, 32)
	default:
		return fmt.Sprintf("%v", t)
	}
}

// typeName maps the few OIDs M0 can encounter. M2 replaces this with a lookup
// against pg_type for the connected server.
func typeName(oid uint32) string {
	switch oid {
	case 16:
		return "bool"
	case 17:
		return "bytea"
	case 20:
		return "int8"
	case 21:
		return "int2"
	case 23:
		return "int4"
	case 25:
		return "text"
	case 1043:
		return "varchar"
	case 1114:
		return "timestamp"
	case 1184:
		return "timestamptz"
	case 1700:
		return "numeric"
	case 2950:
		return "uuid"
	case 3802:
		return "jsonb"
	default:
		return "oid:" + strconv.FormatUint(uint64(oid), 10)
	}
}

// ErrConfig marks an unusable connection configuration.
var ErrConfig = errors.New("postgres configuration error")

// Failure is the safe, classified form of a driver error. It never carries a
// DSN, a stack trace, or the driver's internal error text verbatim unless that
// text came from the server as a SQL error message.
type Failure struct {
	Code    string
	Message string
}

// Classify converts a driver error into a safe Failure. The returned message is
// suitable for display in the browser.
func Classify(err error) Failure {
	switch {
	case err == nil:
		return Failure{}
	case errors.Is(err, context.DeadlineExceeded):
		return Failure{Code: "query_timeout", Message: "The query exceeded the time limit and was cancelled."}
	case errors.Is(err, context.Canceled):
		return Failure{Code: "query_cancelled", Message: "The query was cancelled."}
	case errors.Is(err, ErrConfig):
		return Failure{Code: "database_unavailable", Message: "The PostgreSQL connection is not configured correctly."}
	}

	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		// A PgError is a server response to our statement: safe to show.
		return Failure{Code: "query_failed", Message: pgErr.Severity + " " + pgErr.Code + ": " + pgErr.Message}
	}

	var connErr *pgconn.ConnectError
	if errors.As(err, &connErr) {
		return Failure{Code: "database_unavailable", Message: "PostgreSQL could not be reached."}
	}
	return Failure{Code: "database_unavailable", Message: "PostgreSQL could not be reached."}
}

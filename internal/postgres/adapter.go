// Package postgres adapts a stored connection to a live PostgreSQL client and
// converts driver errors into safe, user-facing failures.
//
// Clients are always built from individual fields. A connection URL is never
// assembled, so a password cannot leak into a log line, an error string, or a
// process listing.
package postgres

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
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

// Failure codes shared with the frontend. Keep them stable.
const (
	CodeUnavailable    = "database_unavailable"
	CodeConnectTimeout = "connect_timeout"
	CodeAuthFailed     = "authentication_failed"
	CodeTLSFailed      = "tls_verification_failed"
	CodeQueryFailed    = "query_failed"
	CodeQueryTimeout   = "query_timeout"
	CodeCancelled      = "query_cancelled"
	CodeUnsupported    = "unsupported_configuration"
)

// Failure is the safe, classified form of a driver error. It never carries a
// DSN, a password, or driver internals; the one exception is a message the
// server itself sent in response to our statement.
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
	case errors.Is(err, context.Canceled):
		return Failure{Code: CodeCancelled, Message: "The operation was cancelled."}
	case errors.Is(err, ErrConfig):
		return Failure{Code: CodeUnsupported, Message: "This connection configuration is not supported."}
	}

	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		if isAuthCode(pgErr.Code) {
			return Failure{Code: CodeAuthFailed, Message: "The server rejected the username or password."}
		}
		// A PgError is a server response to our statement: safe to show.
		return Failure{Code: CodeQueryFailed, Message: pgErr.Severity + " " + pgErr.Code + ": " + pgErr.Message}
	}

	if f, ok := classifyTLS(err); ok {
		return f
	}

	// A deadline during connect is a timeout reaching the host; during a query
	// it is a statement that ran too long. The caller distinguishes them by
	// which operation it was running, so report the transport case here only
	// when the error came from dialling.
	if errors.Is(err, context.DeadlineExceeded) || isTimeout(err) {
		var connErr *pgconn.ConnectError
		if errors.As(err, &connErr) {
			return Failure{Code: CodeConnectTimeout, Message: "Could not reach the host before the timeout."}
		}
		return Failure{Code: CodeQueryTimeout, Message: "The operation exceeded the time limit and was cancelled."}
	}

	return Failure{Code: CodeUnavailable, Message: "PostgreSQL could not be reached."}
}

// isAuthCode reports SQLSTATE classes that mean "your credentials were
// rejected" rather than "your statement was wrong".
func isAuthCode(code string) bool {
	switch code {
	case "28P01", // invalid_password
		"28000": // invalid_authorization_specification
		return true
	}
	return false
}

func isTimeout(err error) bool {
	var timeout interface{ Timeout() bool }
	return errors.As(err, &timeout) && timeout.Timeout()
}

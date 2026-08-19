package postgres

import (
	"context"
	"os"
	"testing"
	"time"
)

// TestSelectOneAgainstRealPostgres is opt-in: it runs only when
// DBIDE_TEST_POSTGRES_DSN points at a throwaway database. CI is expected to
// provide one; a developer without a local server sees a skip, not a failure.
func TestSelectOneAgainstRealPostgres(t *testing.T) {
	dsn := os.Getenv("DBIDE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("set DBIDE_TEST_POSTGRES_DSN to run the PostgreSQL integration test")
	}

	a, err := Open(dsn)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer a.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	if err := a.Ping(ctx); err != nil {
		t.Fatalf("Ping: %v", Classify(err).Message)
	}

	res, err := a.SelectOne(ctx)
	if err != nil {
		t.Fatalf("SelectOne: %v", Classify(err).Message)
	}
	if res.RowCount != 1 || len(res.Rows) != 1 {
		t.Fatalf("rowCount = %d, rows = %d, want 1 and 1", res.RowCount, len(res.Rows))
	}
	if len(res.Columns) != 1 || res.Columns[0].Name != "value" || res.Columns[0].TypeName != "int4" {
		t.Fatalf("columns = %+v", res.Columns)
	}
	if got := res.Rows[0][0]; got != "1" {
		t.Fatalf("value = %#v, want the string \"1\"", got)
	}
	if res.DurationMs < 0 {
		t.Fatalf("durationMs = %d", res.DurationMs)
	}
}

// TestUnreachableServerIsClassifiedSafely needs no database: it dials a port
// nothing listens on.
func TestUnreachableServerIsClassifiedSafely(t *testing.T) {
	a, err := Open("postgres://nobody:secret@127.0.0.1:1/nodb?connect_timeout=1")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer a.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	err = a.Ping(ctx)
	if err == nil {
		t.Skip("something is listening on port 1; cannot test the unreachable path")
	}
	f := Classify(err)
	if f.Code != "database_unavailable" {
		t.Fatalf("code = %q, want database_unavailable", f.Code)
	}
	if got := f.Message; got != "PostgreSQL could not be reached." {
		t.Fatalf("message = %q, want a safe fixed message", got)
	}
}

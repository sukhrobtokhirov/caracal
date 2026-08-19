package store

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/stohirov/database-ide/internal/connections"
)

func openTestStore(t *testing.T) *Store {
	t.Helper()
	path := filepath.Join(t.TempDir(), "nested", "dbide.db")
	s, err := Open(context.Background(), path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}

func sampleRecord(id, name string) connections.Record {
	return connections.Record{
		Config: connections.Config{
			ID:          id,
			Name:        name,
			Engine:      connections.EnginePostgres,
			Host:        "db.internal",
			Port:        5432,
			Database:    "app",
			Username:    "app_ro",
			TLSMode:     connections.TLSRequire,
			Environment: connections.EnvProd,
			ReadOnly:    true,
			Color:       "#ff5555",
			CreatedAt:   time.Date(2026, 8, 20, 10, 30, 0, 0, time.UTC),
		},
		SealedSecret: []byte{1, 2, 3, 4},
	}
}

func TestOpenCreatesAndMigratesAnEmptyFile(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	path := filepath.Join(dir, "sub", "dbide.db")

	s, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer s.Close()

	if _, err := os.Stat(path); err != nil {
		t.Fatalf("database file was not created: %v", err)
	}
	v, err := schemaVersion(ctx, s.db)
	if err != nil {
		t.Fatalf("schemaVersion: %v", err)
	}
	if want := migrations[len(migrations)-1].version; v != want {
		t.Fatalf("schema version = %d, want %d", v, want)
	}
}

func TestOpenIsIdempotent(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "dbide.db")

	first, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("first Open: %v", err)
	}
	if err := first.CreateConnection(ctx, sampleRecord("id-1", "prod db")); err != nil {
		t.Fatalf("CreateConnection: %v", err)
	}
	if err := first.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	second, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("second Open: %v", err)
	}
	defer second.Close()

	got, err := second.ListConnections(ctx)
	if err != nil {
		t.Fatalf("ListConnections: %v", err)
	}
	if len(got) != 1 || got[0].ID != "id-1" {
		t.Fatalf("connections after reopen = %+v", got)
	}
}

func TestDatabaseFileIsOwnerOnly(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX file modes do not apply on Windows")
	}
	s := openTestStore(t)
	info, err := os.Stat(s.Path())
	if err != nil {
		t.Fatalf("Stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm&0o077 != 0 {
		t.Fatalf("database mode = %v, want owner-only", perm)
	}
}

func TestConnectionRoundTrip(t *testing.T) {
	ctx := context.Background()
	s := openTestStore(t)
	want := sampleRecord("id-1", "prod db")

	if err := s.CreateConnection(ctx, want); err != nil {
		t.Fatalf("CreateConnection: %v", err)
	}
	got, err := s.GetConnection(ctx, "id-1")
	if err != nil {
		t.Fatalf("GetConnection: %v", err)
	}
	if got.Config != want.Config {
		t.Fatalf("config = %+v, want %+v", got.Config, want.Config)
	}
	if string(got.SealedSecret) != string(want.SealedSecret) {
		t.Fatalf("sealed secret = %v, want %v", got.SealedSecret, want.SealedSecret)
	}
}

func TestRedisRecordWithoutUsernameOrSecret(t *testing.T) {
	ctx := context.Background()
	s := openTestStore(t)
	rec := connections.Record{
		Config: connections.Config{
			ID: "id-r", Name: "cache", Engine: connections.EngineRedis,
			Host: "127.0.0.1", Port: 6379, Database: "0",
			TLSMode: connections.TLSDisable, Environment: connections.EnvDev,
			CreatedAt: time.Now().UTC().Truncate(time.Microsecond),
		},
	}
	if err := s.CreateConnection(ctx, rec); err != nil {
		t.Fatalf("CreateConnection: %v", err)
	}
	got, err := s.GetConnection(ctx, "id-r")
	if err != nil {
		t.Fatalf("GetConnection: %v", err)
	}
	if got.Username != "" || len(got.SealedSecret) != 0 {
		t.Fatalf("empty fields did not round-trip: %+v", got)
	}
	if got.Summarize().HasSecret {
		t.Fatal("a record without a sealed secret must report HasSecret false")
	}
}

func TestGetMissingConnection(t *testing.T) {
	if _, err := openTestStore(t).GetConnection(context.Background(), "nope"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("error = %v, want ErrNotFound", err)
	}
}

func TestDuplicateNameIsRejected(t *testing.T) {
	ctx := context.Background()
	s := openTestStore(t)
	if err := s.CreateConnection(ctx, sampleRecord("id-1", "same name")); err != nil {
		t.Fatalf("CreateConnection: %v", err)
	}
	err := s.CreateConnection(ctx, sampleRecord("id-2", "same name"))
	if !errors.Is(err, ErrDuplicateName) {
		t.Fatalf("error = %v, want ErrDuplicateName", err)
	}
}

func TestUpdateConnection(t *testing.T) {
	ctx := context.Background()
	s := openTestStore(t)
	rec := sampleRecord("id-1", "before")
	if err := s.CreateConnection(ctx, rec); err != nil {
		t.Fatalf("CreateConnection: %v", err)
	}

	rec.Name = "after"
	rec.Color = "#00ff00"
	rec.ReadOnly = false
	rec.SealedSecret = []byte{9, 9}
	if err := s.UpdateConnection(ctx, rec); err != nil {
		t.Fatalf("UpdateConnection: %v", err)
	}

	got, err := s.GetConnection(ctx, "id-1")
	if err != nil {
		t.Fatalf("GetConnection: %v", err)
	}
	if got.Name != "after" || got.Color != "#00ff00" || got.ReadOnly {
		t.Fatalf("update did not apply: %+v", got.Config)
	}
	if !got.CreatedAt.Equal(rec.CreatedAt) {
		t.Fatalf("created_at changed: %v", got.CreatedAt)
	}
	if string(got.SealedSecret) != string([]byte{9, 9}) {
		t.Fatalf("sealed secret = %v", got.SealedSecret)
	}
}

func TestUpdateMissingConnection(t *testing.T) {
	err := openTestStore(t).UpdateConnection(context.Background(), sampleRecord("ghost", "ghost"))
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("error = %v, want ErrNotFound", err)
	}
}

func TestDeleteConnection(t *testing.T) {
	ctx := context.Background()
	s := openTestStore(t)
	if err := s.CreateConnection(ctx, sampleRecord("id-1", "gone soon")); err != nil {
		t.Fatalf("CreateConnection: %v", err)
	}
	if err := s.DeleteConnection(ctx, "id-1"); err != nil {
		t.Fatalf("DeleteConnection: %v", err)
	}
	if _, err := s.GetConnection(ctx, "id-1"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("error = %v, want ErrNotFound", err)
	}
	if err := s.DeleteConnection(ctx, "id-1"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("second delete error = %v, want ErrNotFound", err)
	}
}

func TestDeletingAConnectionCascadesItsHistory(t *testing.T) {
	ctx := context.Background()
	s := openTestStore(t)
	if err := s.CreateConnection(ctx, sampleRecord("id-1", "with history")); err != nil {
		t.Fatalf("CreateConnection: %v", err)
	}
	if _, err := s.db.ExecContext(ctx,
		`INSERT INTO query_history(connection_id, statement, status, executed_at)
		 VALUES (?, ?, ?, ?)`, "id-1", "SELECT 1", "ok", time.Now().UTC().Format(time.RFC3339Nano)); err != nil {
		t.Fatalf("insert history: %v", err)
	}

	if err := s.DeleteConnection(ctx, "id-1"); err != nil {
		t.Fatalf("DeleteConnection: %v", err)
	}
	var remaining int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM query_history`).Scan(&remaining); err != nil {
		t.Fatalf("count history: %v", err)
	}
	if remaining != 0 {
		t.Fatalf("history rows remaining = %d, want 0 (foreign keys are not enforced)", remaining)
	}
}

func TestHistoryRequiresAnExistingConnection(t *testing.T) {
	ctx := context.Background()
	s := openTestStore(t)
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO query_history(connection_id, statement, status, executed_at)
		 VALUES (?, ?, ?, ?)`, "ghost", "SELECT 1", "ok", time.Now().UTC().Format(time.RFC3339Nano))
	if err == nil {
		t.Fatal("history for a missing connection was accepted; foreign keys are off")
	}
}

func TestListOrdersProductionFirstThenNameCaseInsensitively(t *testing.T) {
	ctx := context.Background()
	s := openTestStore(t)

	add := func(id, name string, env connections.Environment) {
		rec := sampleRecord(id, name)
		rec.Environment = env
		if err := s.CreateConnection(ctx, rec); err != nil {
			t.Fatalf("CreateConnection %s: %v", id, err)
		}
	}
	add("d1", "zeta dev", connections.EnvDev)
	add("p2", "beta prod", connections.EnvProd)
	add("s1", "staging one", connections.EnvStaging)
	add("d2", "Alpha dev", connections.EnvDev)
	add("p1", "alpha prod", connections.EnvProd)

	got, err := s.ListConnections(ctx)
	if err != nil {
		t.Fatalf("ListConnections: %v", err)
	}
	want := []string{"alpha prod", "beta prod", "staging one", "Alpha dev", "zeta dev"}
	if len(got) != len(want) {
		t.Fatalf("got %d connections, want %d", len(got), len(want))
	}
	for i, name := range want {
		if got[i].Name != name {
			t.Fatalf("position %d = %q, want %q (full order %v)", i, got[i].Name, name, names(got))
		}
	}
}

func TestListOnAnEmptyStoreReturnsAnEmptySlice(t *testing.T) {
	got, err := openTestStore(t).ListConnections(context.Background())
	if err != nil {
		t.Fatalf("ListConnections: %v", err)
	}
	if got == nil || len(got) != 0 {
		t.Fatalf("got %v, want an empty non-nil slice", got)
	}
}

func TestMetadataRoundTrip(t *testing.T) {
	ctx := context.Background()
	s := openTestStore(t)

	if _, ok, err := s.GetMeta(ctx, "absent"); err != nil || ok {
		t.Fatalf("GetMeta(absent) = ok %v, err %v", ok, err)
	}
	if err := s.PutMeta(ctx, "salt", []byte{1, 2, 3}); err != nil {
		t.Fatalf("PutMeta: %v", err)
	}
	got, ok, err := s.GetMeta(ctx, "salt")
	if err != nil || !ok || string(got) != string([]byte{1, 2, 3}) {
		t.Fatalf("GetMeta = %v, %v, %v", got, ok, err)
	}

	if err := s.PutMeta(ctx, "salt", []byte{4}); err != nil {
		t.Fatalf("PutMeta overwrite: %v", err)
	}
	got, _, _ = s.GetMeta(ctx, "salt")
	if string(got) != string([]byte{4}) {
		t.Fatalf("overwrite did not apply: %v", got)
	}
}

func TestOpenRefusesANewerSchema(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "dbide.db")
	s, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.PutMeta(ctx, metaSchemaVersion, []byte("999")); err != nil {
		t.Fatalf("PutMeta: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	if _, err := Open(ctx, path); err == nil {
		t.Fatal("expected a refusal to open a database from a newer version")
	}
}

func names(recs []connections.Record) []string {
	out := make([]string, len(recs))
	for i, r := range recs {
		out[i] = r.Name
	}
	return out
}

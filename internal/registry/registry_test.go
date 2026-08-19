package registry

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"

	"github.com/stohirov/database-ide/internal/connections"
)

// unreachable points at a port nothing listens on, so open failures are fast
// and deterministic without a database.
func unreachable(engine connections.Engine, id string) connections.Config {
	cfg := connections.Config{
		ID: id, Name: "unreachable", Engine: engine,
		Host: "127.0.0.1", Port: 1, TLSMode: connections.TLSDisable,
		Environment: connections.EnvDev,
	}
	if engine == connections.EngineRedis {
		cfg.Database = "0"
	} else {
		cfg.Database = "postgres"
		cfg.Username = "postgres"
	}
	return cfg
}

func TestUnknownConnectionIsClosed(t *testing.T) {
	r := New()
	if got := r.State("nobody"); got.Status != StatusClosed {
		t.Fatalf("status = %q, want %q", got.Status, StatusClosed)
	}
	if _, err := r.PG("nobody"); !errors.Is(err, ErrNotOpen) {
		t.Fatalf("PG error = %v, want ErrNotOpen", err)
	}
	if _, err := r.Redis("nobody"); !errors.Is(err, ErrNotOpen) {
		t.Fatalf("Redis error = %v, want ErrNotOpen", err)
	}
}

func TestFailedOpenRecordsASafeError(t *testing.T) {
	for _, engine := range []connections.Engine{connections.EnginePostgres, connections.EngineRedis} {
		t.Run(string(engine), func(t *testing.T) {
			r := New()
			cfg := unreachable(engine, "id-1")
			err := r.Open(context.Background(), cfg, "hunter2")
			if err == nil {
				t.Skip("something is listening on port 1")
			}

			state := r.State("id-1")
			if state.Status != StatusError {
				t.Fatalf("status = %q, want %q", state.Status, StatusError)
			}
			if state.LastError == "" {
				t.Fatal("a failed open must record a user-facing message")
			}
			if strings.Contains(state.LastError, "hunter2") {
				t.Fatalf("the password leaked into the status: %q", state.LastError)
			}
			if strings.Contains(strings.ToLower(state.LastError), "dial tcp") {
				t.Fatalf("driver internals leaked into the status: %q", state.LastError)
			}
			if state.OpenedAt != nil {
				t.Fatal("a failed open must not record an open time")
			}
			if _, err := r.PG("id-1"); !errors.Is(err, ErrNotOpen) {
				t.Fatalf("PG error = %v, want ErrNotOpen", err)
			}
		})
	}
}

func TestUnsupportedEngineIsRejected(t *testing.T) {
	r := New()
	cfg := unreachable(connections.EnginePostgres, "id-1")
	cfg.Engine = "mysql"
	if err := r.Open(context.Background(), cfg, ""); err == nil {
		t.Fatal("expected an error for an unsupported engine")
	}
	if got := r.State("id-1"); got.Status != StatusError {
		t.Fatalf("status = %q, want %q", got.Status, StatusError)
	}
}

func TestCloseIsIdempotent(t *testing.T) {
	r := New()
	r.Close("never seen")

	cfg := unreachable(connections.EnginePostgres, "id-1")
	_ = r.Open(context.Background(), cfg, "pw")
	r.Close("id-1")
	r.Close("id-1")

	state := r.State("id-1")
	if state.Status != StatusClosed || state.LastError != "" {
		t.Fatalf("state after close = %+v", state)
	}
}

func TestForgetDropsTheEntry(t *testing.T) {
	r := New()
	cfg := unreachable(connections.EnginePostgres, "id-1")
	_ = r.Open(context.Background(), cfg, "pw")

	if _, tracked := r.States()["id-1"]; !tracked {
		t.Fatal("the connection should be tracked after an open attempt")
	}
	r.Forget("id-1")
	if _, tracked := r.States()["id-1"]; tracked {
		t.Fatal("Forget must leave no phantom entry behind")
	}
}

func TestFingerprintChangesWithEveryDialingField(t *testing.T) {
	base := connections.Config{
		ID: "id", Engine: connections.EnginePostgres, Host: "a", Port: 5432,
		Database: "app", Username: "u", TLSMode: connections.TLSDisable,
	}
	original := Fingerprint(base, "pw")

	mutations := map[string]func(c *connections.Config, pw *string){
		"host":     func(c *connections.Config, _ *string) { c.Host = "b" },
		"port":     func(c *connections.Config, _ *string) { c.Port = 5433 },
		"database": func(c *connections.Config, _ *string) { c.Database = "other" },
		"username": func(c *connections.Config, _ *string) { c.Username = "v" },
		"tls":      func(c *connections.Config, _ *string) { c.TLSMode = connections.TLSRequire },
		"engine":   func(c *connections.Config, _ *string) { c.Engine = connections.EngineRedis },
		"password": func(_ *connections.Config, pw *string) { *pw = "different" },
	}
	for name, mutate := range mutations {
		t.Run(name, func(t *testing.T) {
			cfg, pw := base, "pw"
			mutate(&cfg, &pw)
			if Fingerprint(cfg, pw) == original {
				t.Fatalf("changing %s did not change the fingerprint", name)
			}
		})
	}

	// Fields that do not affect dialing must not invalidate an open client.
	cosmetic := base
	cosmetic.Name = "renamed"
	cosmetic.Color = "#123456"
	cosmetic.ReadOnly = true
	cosmetic.Environment = connections.EnvProd
	if Fingerprint(cosmetic, "pw") != original {
		t.Fatal("a cosmetic change must not invalidate an open connection")
	}

	if strings.Contains(original, "pw") {
		t.Fatal("the fingerprint must not contain the password")
	}
}

func TestInvalidateIfChanged(t *testing.T) {
	r := New()
	cfg := unreachable(connections.EnginePostgres, "id-1")

	// Nothing tracked yet.
	if r.InvalidateIfChanged(cfg, "pw") {
		t.Fatal("an untracked connection cannot be invalidated")
	}

	// A failed open leaves no open client, so there is nothing to invalidate.
	_ = r.Open(context.Background(), cfg, "pw")
	if r.InvalidateIfChanged(cfg, "different") {
		t.Fatal("a connection that is not open cannot be invalidated")
	}
}

func TestCloseAll(t *testing.T) {
	r := New()
	for _, id := range []string{"a", "b", "c"} {
		_ = r.Open(context.Background(), unreachable(connections.EnginePostgres, id), "pw")
	}
	r.CloseAll()
	for id, state := range r.States() {
		if state.Status != StatusClosed {
			t.Fatalf("connection %s status = %q, want %q", id, state.Status, StatusClosed)
		}
	}
}

func TestConcurrentUse(t *testing.T) {
	r := New()
	ctx := context.Background()
	var wg sync.WaitGroup

	for i := 0; i < 16; i++ {
		id := string(rune('a' + i%4))
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = r.Open(ctx, unreachable(connections.EnginePostgres, id), "pw")
		}()
		wg.Add(1)
		go func() {
			defer wg.Done()
			r.State(id)
			r.States()
			_, _ = r.PG(id)
			r.Close(id)
		}()
	}
	wg.Wait()
	r.CloseAll()
}

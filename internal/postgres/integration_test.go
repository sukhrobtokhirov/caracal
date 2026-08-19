package postgres

import (
	"context"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/stohirov/database-ide/internal/connections"
)

// configFromDSN turns the opt-in test DSN into a stored connection config so
// the integration test exercises the same field-based path the app uses.
func configFromDSN(t *testing.T, dsn string) (connections.Config, string) {
	t.Helper()
	u, err := url.Parse(dsn)
	if err != nil {
		t.Fatalf("DBIDE_TEST_POSTGRES_DSN is not a URL: %v", err)
	}
	port := connections.DefaultPostgresPort
	if p := u.Port(); p != "" {
		if port, err = strconv.Atoi(p); err != nil {
			t.Fatalf("DBIDE_TEST_POSTGRES_DSN has a bad port: %v", err)
		}
	}
	password, _ := u.User.Password()
	tlsMode := connections.TLSDisable
	if u.Query().Get("sslmode") == "require" {
		tlsMode = connections.TLSRequire
	}
	return connections.Config{
		ID:          "integration",
		Name:        "integration",
		Engine:      connections.EnginePostgres,
		Host:        u.Hostname(),
		Port:        port,
		Database:    strings.TrimPrefix(u.Path, "/"),
		Username:    u.User.Username(),
		TLSMode:     tlsMode,
		Environment: connections.EnvDev,
	}, password
}

func testConfig(t *testing.T) (connections.Config, string) {
	t.Helper()
	dsn := os.Getenv("DBIDE_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("set DBIDE_TEST_POSTGRES_DSN to run the PostgreSQL integration test")
	}
	return configFromDSN(t, dsn)
}

func TestAgainstRealPostgres(t *testing.T) {
	cfg, password := testConfig(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	info, err := Test(ctx, cfg, password)
	if err != nil {
		t.Fatalf("Test: %s", Classify(err).Message)
	}
	if info.Engine != string(connections.EnginePostgres) {
		t.Fatalf("engine = %q", info.Engine)
	}
	if info.ServerVersion == "" {
		t.Error("the server version should be reported")
	}
	if info.LatencyMs < 0 {
		t.Errorf("latency = %d", info.LatencyMs)
	}

	pool, err := OpenPool(ctx, cfg, password)
	if err != nil {
		t.Fatalf("OpenPool: %s", Classify(err).Message)
	}
	defer pool.Close()

	var got int
	if err := pool.QueryRow(ctx, "SELECT 1").Scan(&got); err != nil {
		t.Fatalf("query through the pool: %v", err)
	}
	if got != 1 {
		t.Fatalf("SELECT 1 returned %d", got)
	}
}

func TestWrongPasswordIsClassifiedAsAuthenticationFailure(t *testing.T) {
	cfg, _ := testConfig(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	_, err := Test(ctx, cfg, "definitely-not-the-password")
	if err == nil {
		t.Skip("this server accepts any password; cannot test the auth failure path")
	}
	f := Classify(err)
	if f.Code != CodeAuthFailed {
		t.Fatalf("code = %q, want %q (message %q)", f.Code, CodeAuthFailed, f.Message)
	}
	if strings.Contains(f.Message, "definitely-not-the-password") {
		t.Fatalf("the password leaked into the message: %q", f.Message)
	}
}

// TestUnreachableServerIsClassifiedSafely needs no database: it dials a port
// nothing listens on.
func TestUnreachableServerIsClassifiedSafely(t *testing.T) {
	cfg := connections.Config{
		ID: "unreachable", Engine: connections.EnginePostgres,
		Host: "127.0.0.1", Port: 1, Database: "postgres", Username: "postgres",
		TLSMode: connections.TLSDisable,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	_, err := Test(ctx, cfg, "secret")
	if err == nil {
		t.Skip("something is listening on port 1; cannot test the unreachable path")
	}
	f := Classify(err)
	if f.Code != CodeUnavailable && f.Code != CodeConnectTimeout {
		t.Fatalf("code = %q, want an unreachable-host code (message %q)", f.Code, f.Message)
	}
	if strings.Contains(f.Message, "secret") {
		t.Fatalf("the password leaked into the message: %q", f.Message)
	}
}

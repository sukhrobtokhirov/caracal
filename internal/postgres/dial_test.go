package postgres

import (
	"crypto/tls"
	"errors"
	"strings"
	"testing"

	"github.com/stohirov/database-ide/internal/connections"
)

func baseConfig() connections.Config {
	return connections.Config{
		ID: "id-1", Name: "prod", Engine: connections.EnginePostgres,
		Host: "db.internal", Port: 6543, Database: "app", Username: "app_ro",
		TLSMode: connections.TLSDisable,
	}
}

func TestPoolConfigUsesStoredFields(t *testing.T) {
	cfg, err := PoolConfig(baseConfig(), "hunter2")
	if err != nil {
		t.Fatalf("PoolConfig: %v", err)
	}
	conn := cfg.ConnConfig
	if conn.Host != "db.internal" || conn.Port != 6543 {
		t.Fatalf("host:port = %s:%d", conn.Host, conn.Port)
	}
	if conn.Database != "app" || conn.User != "app_ro" || conn.Password != "hunter2" {
		t.Fatalf("connection identity = %s/%s", conn.Database, conn.User)
	}
	if conn.ConnectTimeout != connectTimeout {
		t.Fatalf("connect timeout = %v, want %v", conn.ConnectTimeout, connectTimeout)
	}
	if cfg.MaxConns != maxPoolConns {
		t.Fatalf("max conns = %d, want %d", cfg.MaxConns, maxPoolConns)
	}
	if len(conn.Fallbacks) != 0 {
		t.Fatal("TLS fallbacks must be cleared so the requested mode is a guarantee")
	}
}

func TestPoolConfigIgnoresTheEnvironment(t *testing.T) {
	// A stored connection must not be redirected by ambient PG* variables.
	t.Setenv("PGHOST", "attacker.example")
	t.Setenv("PGPORT", "9999")
	t.Setenv("PGDATABASE", "elsewhere")
	t.Setenv("PGUSER", "someone")
	t.Setenv("PGPASSWORD", "leak")

	cfg, err := PoolConfig(baseConfig(), "hunter2")
	if err != nil {
		t.Fatalf("PoolConfig: %v", err)
	}
	conn := cfg.ConnConfig
	if conn.Host != "db.internal" || conn.Port != 6543 || conn.Database != "app" ||
		conn.User != "app_ro" || conn.Password != "hunter2" {
		t.Fatalf("environment overrode the stored connection: %s@%s:%d/%s",
			conn.User, conn.Host, conn.Port, conn.Database)
	}
}

func TestTLSModes(t *testing.T) {
	cases := []struct {
		mode       connections.TLSMode
		wantTLS    bool
		wantSkip   bool
		wantErrCfg bool
	}{
		{connections.TLSDisable, false, false, false},
		{"", false, false, false},
		{connections.TLSRequire, true, true, false},
		{connections.TLSVerifyFull, true, false, false},
		{connections.TLSMode("nonsense"), false, false, true},
	}
	for _, tc := range cases {
		t.Run(string(tc.mode), func(t *testing.T) {
			cfg := baseConfig()
			cfg.TLSMode = tc.mode
			pool, err := PoolConfig(cfg, "pw")
			if tc.wantErrCfg {
				if !errors.Is(err, ErrConfig) {
					t.Fatalf("error = %v, want ErrConfig", err)
				}
				return
			}
			if err != nil {
				t.Fatalf("PoolConfig: %v", err)
			}
			got := pool.ConnConfig.TLSConfig
			if tc.wantTLS != (got != nil) {
				t.Fatalf("TLS config present = %v, want %v", got != nil, tc.wantTLS)
			}
			if got == nil {
				return
			}
			if got.InsecureSkipVerify != tc.wantSkip {
				t.Fatalf("InsecureSkipVerify = %v, want %v", got.InsecureSkipVerify, tc.wantSkip)
			}
			if got.ServerName != cfg.Host {
				t.Fatalf("ServerName = %q, want %q", got.ServerName, cfg.Host)
			}
			if !tc.wantSkip && got.MinVersion < tls.VersionTLS12 {
				t.Fatalf("MinVersion = %x, want at least TLS 1.2", got.MinVersion)
			}
		})
	}
}

func TestConfigErrorDoesNotEchoTheConfiguration(t *testing.T) {
	cfg := baseConfig()
	cfg.TLSMode = connections.TLSMode("nonsense")
	_, err := PoolConfig(cfg, "hunter2")
	if err == nil {
		t.Fatal("expected an error")
	}
	if strings.Contains(err.Error(), "hunter2") {
		t.Fatalf("the password leaked into the error: %q", err.Error())
	}
}

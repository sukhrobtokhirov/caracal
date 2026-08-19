package redisx

import (
	"context"
	"crypto/x509"
	"errors"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/stohirov/database-ide/internal/connections"
)

func baseConfig() connections.Config {
	return connections.Config{
		ID: "id-r", Name: "cache", Engine: connections.EngineRedis,
		Host: "cache.internal", Port: 6380, Database: "3",
		TLSMode: connections.TLSDisable,
	}
}

func TestOptionsUsesStoredFields(t *testing.T) {
	opts, err := Options(baseConfig(), "hunter2")
	if err != nil {
		t.Fatalf("Options: %v", err)
	}
	if opts.Addr != "cache.internal:6380" {
		t.Fatalf("addr = %q", opts.Addr)
	}
	if opts.DB != 3 {
		t.Fatalf("db = %d, want 3", opts.DB)
	}
	if opts.Password != "hunter2" {
		t.Fatal("the password was not passed through")
	}
	if opts.Username != "" {
		t.Fatalf("username = %q, want empty for legacy AUTH", opts.Username)
	}
	if opts.DialTimeout != dialTimeout || opts.ReadTimeout != opTimeout {
		t.Fatalf("timeouts = %v / %v", opts.DialTimeout, opts.ReadTimeout)
	}
	if opts.PoolSize != maxPoolSize {
		t.Fatalf("pool size = %d, want %d", opts.PoolSize, maxPoolSize)
	}
}

func TestOptionsTLSModes(t *testing.T) {
	cfg := baseConfig()

	cfg.TLSMode = connections.TLSDisable
	if opts, err := Options(cfg, ""); err != nil || opts.TLSConfig != nil {
		t.Fatalf("disable: opts.TLSConfig = %v, err = %v", opts.TLSConfig, err)
	}

	cfg.TLSMode = connections.TLSRequire
	opts, err := Options(cfg, "")
	if err != nil {
		t.Fatalf("require: %v", err)
	}
	if opts.TLSConfig == nil || opts.TLSConfig.ServerName != cfg.Host {
		t.Fatalf("require: TLS config = %+v", opts.TLSConfig)
	}

	// verify-full is a PostgreSQL-only mode in the MVP.
	cfg.TLSMode = connections.TLSVerifyFull
	if _, err := Options(cfg, ""); !errors.Is(err, ErrConfig) {
		t.Fatalf("verify-full error = %v, want ErrConfig", err)
	}
}

func TestOptionsDefaultsAnUnparseableDatabaseToZero(t *testing.T) {
	cfg := baseConfig()
	cfg.Database = "not a number"
	opts, err := Options(cfg, "")
	if err != nil {
		t.Fatalf("Options: %v", err)
	}
	if opts.DB != 0 {
		t.Fatalf("db = %d, want 0", opts.DB)
	}
}

func TestClassify(t *testing.T) {
	cases := []struct {
		name     string
		err      error
		wantCode string
	}{
		{"nil", nil, ""},
		{"cancelled", context.Canceled, CodeCancelled},
		{"deadline", context.DeadlineExceeded, CodeConnectTimeout},
		{"config", ErrConfig, CodeUnsupported},
		{"wrong password", errors.New("WRONGPASS invalid username-password pair"), CodeAuthFailed},
		{"no auth", errors.New("NOAUTH Authentication required."), CodeAuthFailed},
		{"password not set", errors.New("ERR Client sent AUTH, but no password is set"), CodeAuthFailed},
		{"unknown authority", x509.UnknownAuthorityError{}, CodeTLSFailed},
		{"anything else", errors.New("dial tcp 10.0.0.1:6379: connect: connection refused"), CodeUnavailable},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := Classify(tc.err)
			if got.Code != tc.wantCode {
				t.Fatalf("code = %q, want %q", got.Code, tc.wantCode)
			}
			if strings.Contains(got.Message, "dial tcp") {
				t.Fatalf("driver internals leaked: %q", got.Message)
			}
		})
	}
}

func TestParseInfoField(t *testing.T) {
	info := "# Server\r\nredis_version:7.2.4\r\nredis_mode:standalone\r\n"
	if got := parseInfoField(info, "redis_version"); got != "7.2.4" {
		t.Fatalf("redis_version = %q", got)
	}
	if got := parseInfoField(info, "absent"); got != "" {
		t.Fatalf("absent field = %q", got)
	}
}

func TestUnreachableServerIsClassifiedSafely(t *testing.T) {
	cfg := baseConfig()
	cfg.Host, cfg.Port = "127.0.0.1", 1

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
		t.Fatalf("the password leaked: %q", f.Message)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func splitHostPort(t *testing.T, addr string) (string, int) {
	t.Helper()
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		t.Fatalf("DBIDE_TEST_REDIS_ADDR must be host:port: %v", err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		t.Fatalf("DBIDE_TEST_REDIS_ADDR has a bad port: %v", err)
	}
	return host, port
}

func TestAgainstRealRedis(t *testing.T) {
	addr := envOr("DBIDE_TEST_REDIS_ADDR", "")
	if addr == "" {
		t.Skip("set DBIDE_TEST_REDIS_ADDR (host:port) to run the Redis integration test")
	}
	host, port := splitHostPort(t, addr)

	cfg := connections.Config{
		ID: "integration", Name: "integration", Engine: connections.EngineRedis,
		Host: host, Port: port, Database: "0", TLSMode: connections.TLSDisable,
		Environment: connections.EnvDev,
	}
	password := envOr("DBIDE_TEST_REDIS_PASSWORD", "")

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	info, err := Test(ctx, cfg, password)
	if err != nil {
		t.Fatalf("Test: %s", Classify(err).Message)
	}
	if info.Engine != string(connections.EngineRedis) {
		t.Fatalf("engine = %q", info.Engine)
	}
	if info.ServerVersion == "" {
		t.Error("the server version should be reported")
	}

	client, err := Open(ctx, cfg, password)
	if err != nil {
		t.Fatalf("Open: %s", Classify(err).Message)
	}
	defer client.Close()

	if err := client.Ping(ctx).Err(); err != nil {
		t.Fatalf("ping through the client: %v", err)
	}
}

// Package redisx adapts a stored connection to a live Redis client and
// converts client errors into safe, user-facing failures.
//
// As with PostgreSQL, clients are built from individual fields so a password
// never appears in a URL, a log line, or an error string.
package redisx

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/stohirov/database-ide/internal/connections"
)

const (
	// dialTimeout bounds DNS, TCP, and TLS together.
	dialTimeout = 5 * time.Second
	// opTimeout bounds a single command.
	opTimeout = 5 * time.Second
	// maxPoolSize stays small for a single-user desktop tool.
	maxPoolSize = 4
)

// ErrConfig marks an unusable connection configuration.
var ErrConfig = errors.New("redis configuration error")

// Failure codes, aligned with the PostgreSQL adapter so the frontend handles
// one vocabulary.
const (
	CodeUnavailable    = "database_unavailable"
	CodeConnectTimeout = "connect_timeout"
	CodeAuthFailed     = "authentication_failed"
	CodeTLSFailed      = "tls_verification_failed"
	CodeCommandFailed  = "command_failed"
	CodeCancelled      = "query_cancelled"
	CodeUnsupported    = "unsupported_configuration"
)

// Failure is the safe, classified form of a client error.
type Failure struct {
	Code    string
	Message string
}

// ServerInfo is the sanitized description of a server returned by Test.
type ServerInfo struct {
	Engine        string `json:"engine"`
	ServerVersion string `json:"serverVersion,omitempty"`
	LatencyMs     int64  `json:"latencyMs"`
}

// Options builds go-redis options from stored fields.
func Options(cfg connections.Config, password string) (*redis.Options, error) {
	tlsCfg, err := tlsConfig(cfg)
	if err != nil {
		return nil, err
	}
	return &redis.Options{
		Addr:         fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Username:     cfg.Username, // empty means legacy AUTH with the password only
		Password:     password,
		DB:           cfg.RedisDBIndex(),
		DialTimeout:  dialTimeout,
		ReadTimeout:  opTimeout,
		WriteTimeout: opTimeout,
		PoolSize:     maxPoolSize,
		MinIdleConns: 0,
		TLSConfig:    tlsCfg,
		ClientName:   "dbide",
	}, nil
}

func tlsConfig(cfg connections.Config) (*tls.Config, error) {
	switch cfg.TLSMode {
	case connections.TLSDisable, "":
		return nil, nil
	case connections.TLSRequire:
		return &tls.Config{ServerName: cfg.Host, MinVersion: tls.VersionTLS12}, nil
	default:
		// verify-full is not offered for Redis in the MVP, so anything else is
		// a configuration the user cannot have chosen through the UI.
		return nil, fmt.Errorf("%w: TLS mode %q", ErrConfig, cfg.TLSMode)
	}
}

// Open creates a client and verifies it can reach the server once.
func Open(ctx context.Context, cfg connections.Config, password string) (*redis.Client, error) {
	opts, err := Options(cfg, password)
	if err != nil {
		return nil, err
	}
	client := redis.NewClient(opts)
	pingCtx, cancel := context.WithTimeout(ctx, dialTimeout+opTimeout)
	defer cancel()
	if err := client.Ping(pingCtx).Err(); err != nil {
		_ = client.Close()
		return nil, err
	}
	return client, nil
}

// Test dials, authenticates, reads the server version, and disconnects.
func Test(ctx context.Context, cfg connections.Config, password string) (ServerInfo, error) {
	opts, err := Options(cfg, password)
	if err != nil {
		return ServerInfo{}, err
	}
	ctx, cancel := context.WithTimeout(ctx, dialTimeout+opTimeout)
	defer cancel()

	client := redis.NewClient(opts)
	defer func() { _ = client.Close() }()

	start := time.Now()
	if err := client.Ping(ctx).Err(); err != nil {
		return ServerInfo{}, err
	}
	latency := time.Since(start).Milliseconds()

	info := ServerInfo{Engine: string(connections.EngineRedis), LatencyMs: latency}
	// The version is a nicety. A server that restricts INFO should still test
	// successfully, so this failure is swallowed on purpose.
	if raw, err := client.Info(ctx, "server").Result(); err == nil {
		info.ServerVersion = parseInfoField(raw, "redis_version")
	}
	return info, nil
}

// parseInfoField pulls one `key:value` line out of an INFO section.
func parseInfoField(info, key string) string {
	for _, line := range strings.Split(info, "\n") {
		line = strings.TrimSpace(line)
		name, value, found := strings.Cut(line, ":")
		if found && name == key {
			return value
		}
	}
	return ""
}

// Classify converts a client error into a safe Failure.
func Classify(err error) Failure {
	switch {
	case err == nil:
		return Failure{}
	case errors.Is(err, context.Canceled):
		return Failure{Code: CodeCancelled, Message: "The operation was cancelled."}
	case errors.Is(err, ErrConfig):
		return Failure{Code: CodeUnsupported, Message: "This connection configuration is not supported."}
	}

	var certErr *tls.CertificateVerificationError
	var hostErr x509.HostnameError
	var authorityErr x509.UnknownAuthorityError
	if errors.As(err, &certErr) || errors.As(err, &hostErr) || errors.As(err, &authorityErr) {
		return Failure{Code: CodeTLSFailed, Message: "The server certificate could not be verified."}
	}

	// Redis reports authentication problems as ordinary error replies, so the
	// text is the only signal available.
	if msg := err.Error(); isAuthError(msg) {
		return Failure{Code: CodeAuthFailed, Message: "The server rejected the username or password."}
	}

	if errors.Is(err, context.DeadlineExceeded) || isTimeout(err) {
		return Failure{Code: CodeConnectTimeout, Message: "Could not reach the host before the timeout."}
	}
	return Failure{Code: CodeUnavailable, Message: "Redis could not be reached."}
}

func isAuthError(msg string) bool {
	upper := strings.ToUpper(msg)
	for _, marker := range []string{
		"WRONGPASS",
		"NOAUTH",
		"INVALID PASSWORD",
		"INVALID USERNAME-PASSWORD PAIR",
		"CLIENT SENT AUTH, BUT NO PASSWORD IS SET",
	} {
		if strings.Contains(upper, marker) {
			return true
		}
	}
	return false
}

func isTimeout(err error) bool {
	var timeout interface{ Timeout() bool }
	return errors.As(err, &timeout) && timeout.Timeout()
}

package postgres

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/stohirov/database-ide/internal/connections"
)

const (
	// connectTimeout bounds DNS, TCP, TLS, and authentication together, so a
	// wrong host cannot freeze the connection form.
	connectTimeout = 5 * time.Second
	// maxPoolConns is deliberately small: this is a single-user desktop tool,
	// not a service, and an idle IDE should not hold a server's connection slots.
	maxPoolConns = 4
	// poolIdleTimeout releases connections a user has stopped using.
	poolIdleTimeout = 5 * time.Minute
)

// ServerInfo is the sanitized description of a server returned by Test. It
// carries only what helps a user confirm they reached the right machine.
type ServerInfo struct {
	Engine        string `json:"engine"`
	ServerVersion string `json:"serverVersion,omitempty"`
	LatencyMs     int64  `json:"latencyMs"`
}

// PoolConfig builds a pgxpool configuration from stored fields.
func PoolConfig(cfg connections.Config, password string) (*pgxpool.Config, error) {
	// ParseConfig("") supplies pgx's defaults; every field that identifies the
	// server or authenticates to it is then set explicitly, so no PG* variable
	// in the environment can redirect a saved connection.
	pool, err := pgxpool.ParseConfig("")
	if err != nil {
		return nil, fmt.Errorf("%w: %s", ErrConfig, "driver defaults are unavailable")
	}
	conn := pool.ConnConfig
	conn.Host = cfg.Host
	conn.Port = uint16(cfg.Port)
	conn.Database = cfg.Database
	conn.User = cfg.Username
	conn.Password = password
	conn.ConnectTimeout = connectTimeout
	conn.RuntimeParams = map[string]string{"application_name": "dbide"}

	tlsCfg, err := tlsConfig(cfg)
	if err != nil {
		return nil, err
	}
	conn.TLSConfig = tlsCfg
	// Fallbacks are pgx's automatic retry with different TLS settings. Clearing
	// them keeps the requested TLS mode a guarantee rather than a preference.
	conn.Fallbacks = nil

	pool.MaxConns = maxPoolConns
	pool.MinConns = 0
	pool.MaxConnIdleTime = poolIdleTimeout
	return pool, nil
}

func tlsConfig(cfg connections.Config) (*tls.Config, error) {
	switch cfg.TLSMode {
	case connections.TLSDisable, "":
		return nil, nil
	case connections.TLSRequire:
		// Encrypt without proving identity. This is what `sslmode=require`
		// means in PostgreSQL, and it is why verify-full exists.
		return &tls.Config{ServerName: cfg.Host, InsecureSkipVerify: true}, nil //nolint:gosec // documented mode
	case connections.TLSVerifyFull:
		return &tls.Config{ServerName: cfg.Host, MinVersion: tls.VersionTLS12}, nil
	default:
		return nil, fmt.Errorf("%w: TLS mode %q", ErrConfig, cfg.TLSMode)
	}
}

// OpenPool creates a pool and verifies it can reach the server once.
func OpenPool(ctx context.Context, cfg connections.Config, password string) (*pgxpool.Pool, error) {
	poolCfg, err := PoolConfig(cfg, password)
	if err != nil {
		return nil, err
	}
	pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
	if err != nil {
		return nil, err
	}
	pingCtx, cancel := context.WithTimeout(ctx, connectTimeout)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil {
		pool.Close()
		return nil, err
	}
	return pool, nil
}

// Test dials, authenticates, reads the server version, and disconnects. It
// never leaves a connection behind.
func Test(ctx context.Context, cfg connections.Config, password string) (ServerInfo, error) {
	connCfg, err := PoolConfig(cfg, password)
	if err != nil {
		return ServerInfo{}, err
	}
	ctx, cancel := context.WithTimeout(ctx, connectTimeout)
	defer cancel()

	start := time.Now()
	conn, err := pgx.ConnectConfig(ctx, connCfg.ConnConfig)
	if err != nil {
		return ServerInfo{}, err
	}
	defer func() {
		closeCtx, closeCancel := context.WithTimeout(context.WithoutCancel(ctx), 2*time.Second)
		defer closeCancel()
		_ = conn.Close(closeCtx)
	}()

	if err := conn.Ping(ctx); err != nil {
		return ServerInfo{}, err
	}
	return ServerInfo{
		Engine:        string(connections.EnginePostgres),
		ServerVersion: conn.PgConn().ParameterStatus("server_version"),
		LatencyMs:     time.Since(start).Milliseconds(),
	}, nil
}

// classifyTLS recognises certificate problems, which deserve their own message:
// the fix is a trust or hostname change, not a password.
func classifyTLS(err error) (Failure, bool) {
	var certErr *tls.CertificateVerificationError
	var hostErr x509.HostnameError
	var authorityErr x509.UnknownAuthorityError
	var invalidErr x509.CertificateInvalidError
	if errors.As(err, &certErr) || errors.As(err, &hostErr) ||
		errors.As(err, &authorityErr) || errors.As(err, &invalidErr) {
		return Failure{Code: CodeTLSFailed, Message: "The server certificate could not be verified."}, true
	}

	// A server with TLS switched off refuses the handshake outright.
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "08P01" {
		return Failure{Code: CodeTLSFailed, Message: "The server does not support the requested TLS mode."}, true
	}
	return Failure{}, false
}

// Package registry holds the live database clients for open connections.
//
// Decrypted configuration lives here and nowhere else: never in a temporary
// file, never in a log line, never in a response body.
package registry

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"github.com/stohirov/database-ide/internal/connections"
	"github.com/stohirov/database-ide/internal/postgres"
	"github.com/stohirov/database-ide/internal/redisx"
)

// Status is a connection's runtime state.
type Status string

const (
	StatusClosed  Status = "closed"
	StatusOpening Status = "opening"
	StatusOpen    Status = "open"
	StatusError   Status = "error"
)

// Registry errors.
var (
	// ErrNotOpen reports a request for a client that is not established.
	ErrNotOpen = errors.New("the connection is not open")
	// ErrWrongEngine reports an engine-specific request against another engine.
	ErrWrongEngine = errors.New("this operation does not apply to this connection's engine")
)

// State is the safe, serializable view of one runtime entry.
type State struct {
	Status Status `json:"status"`
	// LastError is a classified, user-facing message. It never carries driver
	// internals or credentials.
	LastError string     `json:"lastError,omitempty"`
	OpenedAt  *time.Time `json:"openedAt,omitempty"`
}

type entry struct {
	// opMu serializes the slow operations (dial, close) for one connection so
	// a long dial never blocks status reads for every other connection.
	opMu sync.Mutex

	engine      connections.Engine
	status      Status
	lastError   string
	openedAt    time.Time
	fingerprint string

	pg    *pgxpool.Pool
	redis *redis.Client
}

// Registry maps connection IDs to live clients.
type Registry struct {
	mu      sync.Mutex
	entries map[string]*entry
}

// New returns an empty registry.
func New() *Registry {
	return &Registry{entries: map[string]*entry{}}
}

// Open establishes a client, or returns successfully if a healthy one already
// exists for the same configuration.
func (r *Registry) Open(ctx context.Context, cfg connections.Config, password string) error {
	e := r.ensure(cfg.ID, cfg.Engine)
	e.opMu.Lock()
	defer e.opMu.Unlock()

	fingerprint := Fingerprint(cfg, password)

	r.mu.Lock()
	alreadyOpen := e.status == StatusOpen && e.fingerprint == fingerprint && e.engine == cfg.Engine
	r.mu.Unlock()
	if alreadyOpen {
		// Open is idempotent for a healthy connection.
		return nil
	}

	// A reopen with changed settings must not leave the old client behind.
	r.closeClients(e)

	r.mu.Lock()
	e.engine = cfg.Engine
	e.status = StatusOpening
	e.lastError = ""
	r.mu.Unlock()

	switch cfg.Engine {
	case connections.EnginePostgres:
		pool, err := postgres.OpenPool(ctx, cfg, password)
		if err != nil {
			r.fail(e, postgres.Classify(err).Message)
			return err
		}
		r.succeed(e, fingerprint, func() { e.pg = pool })
	case connections.EngineRedis:
		client, err := redisx.Open(ctx, cfg, password)
		if err != nil {
			r.fail(e, redisx.Classify(err).Message)
			return err
		}
		r.succeed(e, fingerprint, func() { e.redis = client })
	default:
		err := fmt.Errorf("%w: %s", postgres.ErrConfig, cfg.Engine)
		r.fail(e, "This connection configuration is not supported.")
		return err
	}
	return nil
}

// Close releases a connection's client. It is idempotent.
func (r *Registry) Close(id string) {
	r.mu.Lock()
	e, ok := r.entries[id]
	r.mu.Unlock()
	if !ok {
		return
	}

	e.opMu.Lock()
	defer e.opMu.Unlock()
	r.closeClients(e)

	r.mu.Lock()
	e.status = StatusClosed
	e.lastError = ""
	e.openedAt = time.Time{}
	e.fingerprint = ""
	r.mu.Unlock()
}

// Forget closes a connection and drops its entry entirely. Used when the
// connection is deleted, so no phantom entry survives the record.
func (r *Registry) Forget(id string) {
	r.Close(id)
	r.mu.Lock()
	delete(r.entries, id)
	r.mu.Unlock()
}

// InvalidateIfChanged closes a connection whose dialing configuration or secret
// no longer matches the open client.
func (r *Registry) InvalidateIfChanged(cfg connections.Config, password string) bool {
	r.mu.Lock()
	e, ok := r.entries[cfg.ID]
	var stale bool
	if ok {
		stale = e.status == StatusOpen && e.fingerprint != Fingerprint(cfg, password)
	}
	r.mu.Unlock()

	if !ok || !stale {
		return false
	}
	r.Close(cfg.ID)
	return true
}

// State reports one connection's runtime state.
func (r *Registry) State(id string) State {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.entries[id]
	if !ok {
		return State{Status: StatusClosed}
	}
	return stateOf(e)
}

// States reports every tracked connection's runtime state.
func (r *Registry) States() map[string]State {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make(map[string]State, len(r.entries))
	for id, e := range r.entries {
		out[id] = stateOf(e)
	}
	return out
}

// PG returns the open PostgreSQL pool for a connection.
func (r *Registry) PG(id string) (*pgxpool.Pool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.entries[id]
	if !ok || e.status != StatusOpen {
		return nil, ErrNotOpen
	}
	if e.engine != connections.EnginePostgres || e.pg == nil {
		return nil, ErrWrongEngine
	}
	return e.pg, nil
}

// Redis returns the open Redis client for a connection.
func (r *Registry) Redis(id string) (*redis.Client, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.entries[id]
	if !ok || e.status != StatusOpen {
		return nil, ErrNotOpen
	}
	if e.engine != connections.EngineRedis || e.redis == nil {
		return nil, ErrWrongEngine
	}
	return e.redis, nil
}

// CloseAll releases every client. Called during application shutdown.
func (r *Registry) CloseAll() {
	r.mu.Lock()
	ids := make([]string, 0, len(r.entries))
	for id := range r.entries {
		ids = append(ids, id)
	}
	r.mu.Unlock()

	for _, id := range ids {
		r.Close(id)
	}
}

// Fingerprint summarizes everything that affects how a client dials, including
// the secret. Comparing fingerprints detects a configuration change without
// keeping the password around for comparison.
func Fingerprint(cfg connections.Config, password string) string {
	h := sha256.New()
	for _, part := range []string{
		string(cfg.Engine), cfg.Host, strconv.Itoa(cfg.Port), cfg.Database,
		cfg.Username, string(cfg.TLSMode), password,
	} {
		h.Write([]byte(part))
		h.Write([]byte{0})
	}
	return hex.EncodeToString(h.Sum(nil))
}

func (r *Registry) ensure(id string, engine connections.Engine) *entry {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.entries[id]
	if !ok {
		e = &entry{engine: engine, status: StatusClosed}
		r.entries[id] = e
	}
	return e
}

// closeClients releases whatever client the entry holds. The caller holds opMu.
func (r *Registry) closeClients(e *entry) {
	r.mu.Lock()
	pg, rdb := e.pg, e.redis
	e.pg, e.redis = nil, nil
	r.mu.Unlock()

	if pg != nil {
		pg.Close()
	}
	if rdb != nil {
		_ = rdb.Close()
	}
}

func (r *Registry) fail(e *entry, message string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e.status = StatusError
	e.lastError = message
	e.openedAt = time.Time{}
	e.fingerprint = ""
}

func (r *Registry) succeed(e *entry, fingerprint string, assign func()) {
	r.mu.Lock()
	defer r.mu.Unlock()
	assign()
	e.status = StatusOpen
	e.lastError = ""
	e.openedAt = time.Now().UTC()
	e.fingerprint = fingerprint
}

// stateOf reads an entry. The caller holds r.mu.
func stateOf(e *entry) State {
	s := State{Status: e.status, LastError: e.lastError}
	if !e.openedAt.IsZero() {
		at := e.openedAt
		s.OpenedAt = &at
	}
	return s
}

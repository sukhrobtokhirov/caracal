// Package manager is the connection manager: it joins the configuration store,
// the secret vault, and the runtime registry into the operations the API
// exposes.
//
// It is the only package that holds a decrypted password, and it holds one only
// for the duration of a single operation.
package manager

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/stohirov/database-ide/internal/connections"
	"github.com/stohirov/database-ide/internal/postgres"
	"github.com/stohirov/database-ide/internal/redisx"
	"github.com/stohirov/database-ide/internal/registry"
	"github.com/stohirov/database-ide/internal/secrets"
	"github.com/stohirov/database-ide/internal/store"
)

// Manager errors. Storage and vault errors are re-exported so callers depend on
// this package alone.
var (
	ErrLocked        = secrets.ErrLocked
	ErrNotFound      = store.ErrNotFound
	ErrDuplicateName = store.ErrDuplicateName
	// ErrSecretUnreadable reports a sealed credential that will not open under
	// the current master key.
	ErrSecretUnreadable = secrets.ErrCannotDecrypt
)

// OperationFailure is a classified engine error, already safe to display.
type OperationFailure struct {
	Code    string
	Message string
}

func (e OperationFailure) Error() string { return e.Message }

// View is the API projection of a connection: its safe summary plus the live
// runtime state.
type View struct {
	connections.Summary
	Runtime registry.State `json:"runtime"`
}

// TestResult reports a successful connection test.
type TestResult struct {
	OK            bool   `json:"ok"`
	Engine        string `json:"engine"`
	ServerVersion string `json:"serverVersion,omitempty"`
	LatencyMs     int64  `json:"latencyMs"`
}

// Manager owns the connection lifecycle.
type Manager struct {
	store    *store.Store
	vault    *secrets.Vault
	registry *registry.Registry
	now      func() time.Time
	newID    func() string
}

// New builds a manager over its three collaborators.
func New(st *store.Store, vault *secrets.Vault, reg *registry.Registry) *Manager {
	return &Manager{
		store:    st,
		vault:    vault,
		registry: reg,
		now:      func() time.Time { return time.Now().UTC() },
		newID:    func() string { return uuid.NewString() },
	}
}

// SetClock replaces the creation-time source. Tests use it for determinism.
func (m *Manager) SetClock(now func() time.Time) { m.now = now }

// SetIDSource replaces the identifier generator. Tests use it for determinism.
func (m *Manager) SetIDSource(newID func() string) { m.newID = newID }

// AuthState reports whether setup is required, and whether the vault is open.
func (m *Manager) AuthState(ctx context.Context) (secrets.State, error) {
	return m.vault.State(ctx)
}

// Setup chooses the master password on first run.
func (m *Manager) Setup(ctx context.Context, password string) error {
	return m.vault.Setup(ctx, password)
}

// Unlock opens the vault with the master password.
func (m *Manager) Unlock(ctx context.Context, password string) error {
	return m.vault.Unlock(ctx, password)
}

// Lock discards the master key and closes every live client: a locked
// application must not keep talking to databases.
func (m *Manager) Lock() {
	m.vault.Lock()
	m.registry.CloseAll()
}

// List returns every saved connection with its runtime state.
func (m *Manager) List(ctx context.Context) ([]View, error) {
	if err := m.requireUnlocked(); err != nil {
		return nil, err
	}
	records, err := m.store.ListConnections(ctx)
	if err != nil {
		return nil, err
	}
	states := m.registry.States()
	out := make([]View, len(records))
	for i, rec := range records {
		state, tracked := states[rec.ID]
		if !tracked {
			// A connection the registry has never seen — every connection after
			// a restart — is closed, not statusless.
			state = registry.State{Status: registry.StatusClosed}
		}
		out[i] = View{Summary: rec.Summarize(), Runtime: state}
	}
	return out, nil
}

// Get returns one saved connection with its runtime state.
func (m *Manager) Get(ctx context.Context, id string) (View, error) {
	rec, err := m.record(ctx, id)
	if err != nil {
		return View{}, err
	}
	return m.view(rec), nil
}

// Create saves a new connection and seals its secret.
func (m *Manager) Create(ctx context.Context, in connections.Input) (View, error) {
	if err := m.requireUnlocked(); err != nil {
		return View{}, err
	}
	in.Normalize()
	if err := in.Validate(); err != nil {
		return View{}, err
	}

	rec := connections.Record{Config: in.ToConfig(m.newID())}
	rec.CreatedAt = m.now()

	sealed, err := m.sealFor(rec.Config, in.Secret, nil)
	if err != nil {
		return View{}, err
	}
	rec.SealedSecret = sealed

	if err := m.store.CreateConnection(ctx, rec); err != nil {
		return View{}, err
	}
	return m.view(rec), nil
}

// Update replaces a connection's fields. The stored secret survives unless the
// caller explicitly supplies a replacement.
func (m *Manager) Update(ctx context.Context, id string, in connections.Input) (View, error) {
	existing, err := m.record(ctx, id)
	if err != nil {
		return View{}, err
	}
	in.Normalize()
	if err := in.Validate(); err != nil {
		return View{}, err
	}

	updated := connections.Record{Config: in.ToConfig(id)}
	updated.CreatedAt = existing.CreatedAt

	sealed, err := m.sealFor(updated.Config, in.Secret, &existing)
	if err != nil {
		return View{}, err
	}
	updated.SealedSecret = sealed

	if err := m.store.UpdateConnection(ctx, updated); err != nil {
		return View{}, err
	}

	// An open client built from the old settings must not survive them.
	if password, err := m.password(updated); err == nil {
		m.registry.InvalidateIfChanged(updated.Config, password)
	} else {
		// The new secret cannot be read back, so no client can be trusted.
		m.registry.Close(id)
	}
	return m.view(updated), nil
}

// Delete closes a connection's client and removes it. The client is released
// before the record so no runtime entry outlives its configuration.
func (m *Manager) Delete(ctx context.Context, id string) error {
	if err := m.requireUnlocked(); err != nil {
		return err
	}
	if _, err := m.store.GetConnection(ctx, id); err != nil {
		return err
	}
	m.registry.Forget(id)
	return m.store.DeleteConnection(ctx, id)
}

// Test dials, authenticates, and disconnects without touching the registry.
func (m *Manager) Test(ctx context.Context, id string) (TestResult, error) {
	rec, err := m.record(ctx, id)
	if err != nil {
		return TestResult{}, err
	}
	password, err := m.password(rec)
	if err != nil {
		return TestResult{}, err
	}

	switch rec.Engine {
	case connections.EnginePostgres:
		info, err := postgres.Test(ctx, rec.Config, password)
		if err != nil {
			f := postgres.Classify(err)
			return TestResult{}, OperationFailure{Code: f.Code, Message: f.Message}
		}
		return TestResult{OK: true, Engine: info.Engine, ServerVersion: info.ServerVersion, LatencyMs: info.LatencyMs}, nil
	case connections.EngineRedis:
		info, err := redisx.Test(ctx, rec.Config, password)
		if err != nil {
			f := redisx.Classify(err)
			return TestResult{}, OperationFailure{Code: f.Code, Message: f.Message}
		}
		return TestResult{OK: true, Engine: info.Engine, ServerVersion: info.ServerVersion, LatencyMs: info.LatencyMs}, nil
	default:
		return TestResult{}, OperationFailure{
			Code:    "unsupported_configuration",
			Message: "This connection configuration is not supported.",
		}
	}
}

// Open establishes a live client. It is idempotent for a healthy connection.
func (m *Manager) Open(ctx context.Context, id string) (View, error) {
	rec, err := m.record(ctx, id)
	if err != nil {
		return View{}, err
	}
	password, err := m.password(rec)
	if err != nil {
		return View{}, err
	}
	if err := m.registry.Open(ctx, rec.Config, password); err != nil {
		return m.view(rec), classify(rec.Engine, err)
	}
	return m.view(rec), nil
}

// Close releases a connection's client. It is idempotent.
func (m *Manager) Close(ctx context.Context, id string) (View, error) {
	rec, err := m.record(ctx, id)
	if err != nil {
		return View{}, err
	}
	m.registry.Close(id)
	return m.view(rec), nil
}

// Shutdown releases every client. Called during application shutdown.
func (m *Manager) Shutdown() { m.registry.CloseAll() }

func (m *Manager) requireUnlocked() error {
	if !m.vault.Unlocked() {
		return ErrLocked
	}
	return nil
}

func (m *Manager) record(ctx context.Context, id string) (connections.Record, error) {
	if err := m.requireUnlocked(); err != nil {
		return connections.Record{}, err
	}
	return m.store.GetConnection(ctx, id)
}

func (m *Manager) view(rec connections.Record) View {
	return View{Summary: rec.Summarize(), Runtime: m.registry.State(rec.ID)}
}

// password unseals a stored credential. A connection with no stored secret
// dials with an empty password, which is what Redis and trust-authenticated
// PostgreSQL expect.
func (m *Manager) password(rec connections.Record) (string, error) {
	if len(rec.SealedSecret) == 0 {
		return "", nil
	}
	pw, err := m.vault.Open(identity(rec.Config), rec.SealedSecret)
	if err != nil {
		return "", err
	}
	return pw, nil
}

// sealFor resolves the secret for a create or update.
//
// Contract:
//   - a nil input leaves the stored secret unchanged;
//   - an explicit change with an empty value clears the stored secret;
//   - an explicit change with a value reseals it.
//
// When nothing changes but the connection's authenticated identity does, the
// existing secret is opened and resealed so it stays bound to its record.
func (m *Manager) sealFor(cfg connections.Config, in *connections.SecretInput, existing *connections.Record) ([]byte, error) {
	if in != nil && in.Changed {
		if in.Value == "" {
			return nil, nil
		}
		return m.vault.Seal(identity(cfg), in.Value)
	}
	if existing == nil || len(existing.SealedSecret) == 0 {
		return nil, nil
	}
	if identity(existing.Config) == identity(cfg) {
		return existing.SealedSecret, nil
	}
	// The engine changed, so the authenticated data changed with it.
	password, err := m.vault.Open(identity(existing.Config), existing.SealedSecret)
	if err != nil {
		return nil, err
	}
	return m.vault.Seal(identity(cfg), password)
}

func identity(cfg connections.Config) secrets.Identity {
	return secrets.Identity{ConnectionID: cfg.ID, Engine: string(cfg.Engine)}
}

func classify(engine connections.Engine, err error) error {
	if err == nil {
		return nil
	}
	var f OperationFailure
	if errors.As(err, &f) {
		return f
	}
	switch engine {
	case connections.EngineRedis:
		c := redisx.Classify(err)
		return OperationFailure{Code: c.Code, Message: c.Message}
	case connections.EnginePostgres:
		c := postgres.Classify(err)
		return OperationFailure{Code: c.Code, Message: c.Message}
	default:
		return OperationFailure{
			Code:    "unsupported_configuration",
			Message: fmt.Sprintf("The engine %q is not supported.", engine),
		}
	}
}

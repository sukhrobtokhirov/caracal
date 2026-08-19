package secrets

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"
)

// Metadata keys owned by the vault.
const (
	MetaSalt     = "kdf_salt"
	MetaParams   = "kdf_params"
	MetaVerifier = "master_verifier"
)

// MinPasswordLen guards against an accidental empty or trivial password. There
// are deliberately no composition rules: against an attacker who can read the
// config file, the Argon2id cost and the password's length do the work, and
// character-class rules mostly produce passwords people write down.
const MinPasswordLen = 8

// Unlock rate limiting. Argon2id already makes each guess expensive; this stops
// a script from spending the whole CPU budget on attempts.
const (
	maxFailuresBeforeCooldown = 5
	baseCooldown              = 30 * time.Second
	maxCooldown               = 15 * time.Minute
)

// State is the vault's lifecycle position.
type State string

const (
	// StateSetupRequired means no master password has been chosen yet.
	StateSetupRequired State = "setup_required"
	// StateLocked means a master password exists but the key is not in memory.
	StateLocked State = "locked"
	// StateUnlocked means the derived key is held for this process.
	StateUnlocked State = "unlocked"
)

// Vault errors.
var (
	// ErrLocked reports an operation that needs the master key while locked.
	ErrLocked = errors.New("the application is locked")
	// ErrAlreadySetUp reports a second attempt to choose a master password.
	ErrAlreadySetUp = errors.New("a master password has already been set")
	// ErrNotSetUp reports an unlock attempt before setup.
	ErrNotSetUp = errors.New("no master password has been set")
	// ErrWrongPassword is the single answer to every failed unlock, so an
	// attacker learns nothing beyond "not this one".
	ErrWrongPassword = errors.New("the master password is incorrect")
	// ErrWeakPassword reports a password below the minimum length.
	ErrWeakPassword = fmt.Errorf("the master password must be at least %d characters", MinPasswordLen)
)

// ErrTooManyAttempts reports that unlock attempts are in cooldown.
type ErrTooManyAttempts struct {
	RetryAfter time.Duration
}

func (e ErrTooManyAttempts) Error() string {
	return fmt.Sprintf("too many failed attempts; try again in %s", e.RetryAfter.Round(time.Second))
}

// MetaStore is the small slice of the configuration store the vault needs.
type MetaStore interface {
	GetMeta(ctx context.Context, key string) ([]byte, bool, error)
	PutMeta(ctx context.Context, key string, value []byte) error
}

// Vault owns the master key for the process lifetime.
type Vault struct {
	store  MetaStore
	params KDFParams
	now    func() time.Time

	mu          sync.Mutex
	key         []byte
	failures    int
	lockedUntil time.Time
}

// NewVault builds a vault over a metadata store.
func NewVault(store MetaStore) *Vault {
	return &Vault{store: store, params: DefaultParams(), now: time.Now}
}

// SetClock replaces the time source. Tests use it to exercise cooldowns.
func (v *Vault) SetClock(now func() time.Time) { v.now = now }

// SetParams overrides the derivation cost. Tests use it to stay fast.
func (v *Vault) SetParams(p KDFParams) { v.params = p }

// State reports whether setup is needed, and whether the key is held.
func (v *Vault) State(ctx context.Context) (State, error) {
	v.mu.Lock()
	unlocked := v.key != nil
	v.mu.Unlock()
	if unlocked {
		return StateUnlocked, nil
	}
	_, ok, err := v.store.GetMeta(ctx, MetaVerifier)
	if err != nil {
		return "", err
	}
	if !ok {
		return StateSetupRequired, nil
	}
	return StateLocked, nil
}

// Setup chooses the master password on first run and leaves the vault unlocked.
func (v *Vault) Setup(ctx context.Context, password string) error {
	if len([]rune(password)) < MinPasswordLen {
		return ErrWeakPassword
	}
	if _, ok, err := v.store.GetMeta(ctx, MetaVerifier); err != nil {
		return err
	} else if ok {
		return ErrAlreadySetUp
	}

	salt, err := NewSalt()
	if err != nil {
		return err
	}
	key, err := DeriveKey(password, salt, v.params)
	if err != nil {
		return err
	}
	verifier, err := SealVerifier(key)
	if err != nil {
		Zero(key)
		return err
	}
	params, err := v.params.Marshal()
	if err != nil {
		Zero(key)
		return err
	}

	// Order matters: the verifier is written last, so a crash mid-setup leaves
	// the vault in StateSetupRequired rather than permanently unopenable.
	if err := v.store.PutMeta(ctx, MetaSalt, salt); err != nil {
		Zero(key)
		return err
	}
	if err := v.store.PutMeta(ctx, MetaParams, params); err != nil {
		Zero(key)
		return err
	}
	if err := v.store.PutMeta(ctx, MetaVerifier, verifier); err != nil {
		Zero(key)
		return err
	}

	v.mu.Lock()
	v.replaceKeyLocked(key)
	v.failures = 0
	v.lockedUntil = time.Time{}
	v.mu.Unlock()
	return nil
}

// Unlock derives the key and checks it against the stored verifier.
func (v *Vault) Unlock(ctx context.Context, password string) error {
	v.mu.Lock()
	if wait := v.lockedUntil.Sub(v.now()); wait > 0 {
		v.mu.Unlock()
		return ErrTooManyAttempts{RetryAfter: wait}
	}
	v.mu.Unlock()

	salt, okSalt, err := v.store.GetMeta(ctx, MetaSalt)
	if err != nil {
		return err
	}
	rawParams, okParams, err := v.store.GetMeta(ctx, MetaParams)
	if err != nil {
		return err
	}
	verifier, okVerifier, err := v.store.GetMeta(ctx, MetaVerifier)
	if err != nil {
		return err
	}
	if !okSalt || !okParams || !okVerifier {
		return ErrNotSetUp
	}

	params, err := UnmarshalParams(rawParams)
	if err != nil {
		return err
	}
	key, err := DeriveKey(password, salt, params)
	if err != nil {
		return err
	}
	if err := CheckVerifier(key, verifier); err != nil {
		Zero(key)
		v.recordFailure()
		return ErrWrongPassword
	}

	v.mu.Lock()
	v.replaceKeyLocked(key)
	v.failures = 0
	v.lockedUntil = time.Time{}
	v.mu.Unlock()
	return nil
}

// Lock discards the derived key. Runtime clients are the caller's problem.
func (v *Vault) Lock() {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.replaceKeyLocked(nil)
}

// Seal encrypts a password under the master key.
func (v *Vault) Seal(id Identity, password string) ([]byte, error) {
	key, err := v.borrowKey()
	if err != nil {
		return nil, err
	}
	return Seal(key, id, password)
}

// Open decrypts a sealed password.
func (v *Vault) Open(id Identity, envelope []byte) (string, error) {
	key, err := v.borrowKey()
	if err != nil {
		return "", err
	}
	return Open(key, id, envelope)
}

// Unlocked reports whether the key is currently held.
func (v *Vault) Unlocked() bool {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.key != nil
}

// borrowKey returns the live key. Callers must not retain or modify it.
func (v *Vault) borrowKey() ([]byte, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.key == nil {
		return nil, ErrLocked
	}
	return v.key, nil
}

func (v *Vault) replaceKeyLocked(key []byte) {
	if v.key != nil {
		Zero(v.key)
	}
	v.key = key
}

func (v *Vault) recordFailure() {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.failures++
	if v.failures < maxFailuresBeforeCooldown {
		return
	}
	// Each failure past the threshold doubles the wait, up to the cap.
	cooldown := baseCooldown << (v.failures - maxFailuresBeforeCooldown)
	if cooldown > maxCooldown || cooldown <= 0 {
		cooldown = maxCooldown
	}
	v.lockedUntil = v.now().Add(cooldown)
}

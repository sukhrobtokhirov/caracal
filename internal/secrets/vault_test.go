package secrets

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

// memMeta is an in-memory MetaStore.
type memMeta struct {
	mu sync.Mutex
	m  map[string][]byte
}

func newMemMeta() *memMeta { return &memMeta{m: map[string][]byte{}} }

func (s *memMeta) GetMeta(_ context.Context, key string) ([]byte, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v, ok := s.m[key]
	return v, ok, nil
}

func (s *memMeta) PutMeta(_ context.Context, key string, value []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.m[key] = value
	return nil
}

func newTestVault(t *testing.T) (*Vault, *memMeta) {
	t.Helper()
	store := newMemMeta()
	v := NewVault(store)
	v.SetParams(fastParams())
	return v, store
}

const goodPassword = "correct horse battery"

func TestVaultLifecycle(t *testing.T) {
	ctx := context.Background()
	v, _ := newTestVault(t)

	if state, _ := v.State(ctx); state != StateSetupRequired {
		t.Fatalf("state = %q, want %q", state, StateSetupRequired)
	}
	if err := v.Setup(ctx, goodPassword); err != nil {
		t.Fatalf("Setup: %v", err)
	}
	if state, _ := v.State(ctx); state != StateUnlocked {
		t.Fatalf("state after setup = %q, want %q", state, StateUnlocked)
	}

	v.Lock()
	if state, _ := v.State(ctx); state != StateLocked {
		t.Fatalf("state after lock = %q, want %q", state, StateLocked)
	}
	if err := v.Unlock(ctx, goodPassword); err != nil {
		t.Fatalf("Unlock: %v", err)
	}
	if state, _ := v.State(ctx); state != StateUnlocked {
		t.Fatalf("state after unlock = %q, want %q", state, StateUnlocked)
	}
}

func TestVaultPersistsAcrossProcesses(t *testing.T) {
	ctx := context.Background()
	first, store := newTestVault(t)
	if err := first.Setup(ctx, goodPassword); err != nil {
		t.Fatalf("Setup: %v", err)
	}
	sealed, err := first.Seal(pgID, "db-password")
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}

	// A fresh vault over the same metadata is a restarted process.
	second := NewVault(store)
	second.SetParams(fastParams())
	if state, _ := second.State(ctx); state != StateLocked {
		t.Fatalf("restarted state = %q, want %q", state, StateLocked)
	}
	if _, err := second.Open(pgID, sealed); !errors.Is(err, ErrLocked) {
		t.Fatalf("locked Open error = %v, want ErrLocked", err)
	}
	if err := second.Unlock(ctx, goodPassword); err != nil {
		t.Fatalf("Unlock after restart: %v", err)
	}
	got, err := second.Open(pgID, sealed)
	if err != nil {
		t.Fatalf("Open after restart: %v", err)
	}
	if got != "db-password" {
		t.Fatalf("password = %q, want %q", got, "db-password")
	}
}

func TestSetupRejectsShortPasswordsAndRepeatSetup(t *testing.T) {
	ctx := context.Background()
	v, _ := newTestVault(t)

	if err := v.Setup(ctx, "short"); !errors.Is(err, ErrWeakPassword) {
		t.Fatalf("error = %v, want ErrWeakPassword", err)
	}
	if state, _ := v.State(ctx); state != StateSetupRequired {
		t.Fatal("a rejected setup must not change the state")
	}
	if err := v.Setup(ctx, goodPassword); err != nil {
		t.Fatalf("Setup: %v", err)
	}
	if err := v.Setup(ctx, "another password"); !errors.Is(err, ErrAlreadySetUp) {
		t.Fatalf("error = %v, want ErrAlreadySetUp", err)
	}
}

func TestUnlockBeforeSetup(t *testing.T) {
	v, _ := newTestVault(t)
	if err := v.Unlock(context.Background(), goodPassword); !errors.Is(err, ErrNotSetUp) {
		t.Fatalf("error = %v, want ErrNotSetUp", err)
	}
}

func TestWrongPasswordLeavesTheVaultLocked(t *testing.T) {
	ctx := context.Background()
	v, _ := newTestVault(t)
	if err := v.Setup(ctx, goodPassword); err != nil {
		t.Fatalf("Setup: %v", err)
	}
	sealed, _ := v.Seal(pgID, "db-password")
	v.Lock()

	err := v.Unlock(ctx, "wrong password entirely")
	if !errors.Is(err, ErrWrongPassword) {
		t.Fatalf("error = %v, want ErrWrongPassword", err)
	}
	if v.Unlocked() {
		t.Fatal("a failed unlock must leave the vault locked")
	}
	if _, err := v.Open(pgID, sealed); !errors.Is(err, ErrLocked) {
		t.Fatalf("Open error = %v, want ErrLocked", err)
	}
}

func TestOperationsRequiringSecretsFailWhileLocked(t *testing.T) {
	v, _ := newTestVault(t)
	if _, err := v.Seal(pgID, "pw"); !errors.Is(err, ErrLocked) {
		t.Fatalf("Seal error = %v, want ErrLocked", err)
	}
	if _, err := v.Open(pgID, []byte("anything")); !errors.Is(err, ErrLocked) {
		t.Fatalf("Open error = %v, want ErrLocked", err)
	}
}

func TestRepeatedFailuresEnterCooldown(t *testing.T) {
	ctx := context.Background()
	v, _ := newTestVault(t)
	now := time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)
	v.SetClock(func() time.Time { return now })

	if err := v.Setup(ctx, goodPassword); err != nil {
		t.Fatalf("Setup: %v", err)
	}
	v.Lock()

	for i := 0; i < maxFailuresBeforeCooldown; i++ {
		if err := v.Unlock(ctx, "wrong"); !errors.Is(err, ErrWrongPassword) {
			t.Fatalf("attempt %d error = %v, want ErrWrongPassword", i+1, err)
		}
	}

	// Now in cooldown: even the correct password must wait.
	var tooMany ErrTooManyAttempts
	if err := v.Unlock(ctx, goodPassword); !errors.As(err, &tooMany) {
		t.Fatalf("error = %v, want ErrTooManyAttempts", err)
	}
	if tooMany.RetryAfter <= 0 || tooMany.RetryAfter > maxCooldown {
		t.Fatalf("retry after = %v", tooMany.RetryAfter)
	}

	// After the cooldown expires the correct password works and resets state.
	now = now.Add(tooMany.RetryAfter + time.Second)
	if err := v.Unlock(ctx, goodPassword); err != nil {
		t.Fatalf("Unlock after cooldown: %v", err)
	}
	if !v.Unlocked() {
		t.Fatal("vault should be unlocked")
	}
}

func TestCooldownIsCapped(t *testing.T) {
	ctx := context.Background()
	v, _ := newTestVault(t)
	now := time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)
	v.SetClock(func() time.Time { return now })
	if err := v.Setup(ctx, goodPassword); err != nil {
		t.Fatalf("Setup: %v", err)
	}
	v.Lock()

	for i := 0; i < 40; i++ {
		err := v.Unlock(ctx, "wrong")
		var tooMany ErrTooManyAttempts
		if errors.As(err, &tooMany) {
			if tooMany.RetryAfter > maxCooldown {
				t.Fatalf("cooldown %v exceeds the cap %v", tooMany.RetryAfter, maxCooldown)
			}
			now = now.Add(tooMany.RetryAfter + time.Second)
		}
	}
}

func TestVaultIsConcurrencySafe(t *testing.T) {
	ctx := context.Background()
	v, _ := newTestVault(t)
	if err := v.Setup(ctx, goodPassword); err != nil {
		t.Fatalf("Setup: %v", err)
	}

	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			sealed, err := v.Seal(pgID, "pw")
			if err != nil {
				return // a concurrent Lock is a legitimate outcome
			}
			_, _ = v.Open(pgID, sealed)
			_, _ = v.State(ctx)
			v.Unlocked()
		}()
	}
	wg.Wait()
}

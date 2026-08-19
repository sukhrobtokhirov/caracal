package manager

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/stohirov/database-ide/internal/connections"
	"github.com/stohirov/database-ide/internal/registry"
	"github.com/stohirov/database-ide/internal/secrets"
	"github.com/stohirov/database-ide/internal/store"
)

const masterPassword = "correct horse battery"

type fixture struct {
	mgr   *Manager
	store *store.Store
	vault *secrets.Vault
	reg   *registry.Registry
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	ctx := context.Background()

	st, err := store.Open(ctx, filepath.Join(t.TempDir(), "dbide.db"))
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	vault := secrets.NewVault(st)
	// Keep derivation cheap; the cost parameters are covered in the secrets tests.
	params := secrets.DefaultParams()
	params.Time, params.MemoryKiB = 1, 8*1024
	vault.SetParams(params)

	reg := registry.New()
	mgr := New(st, vault, reg)
	mgr.SetClock(func() time.Time { return time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC) })

	ids := 0
	mgr.SetIDSource(func() string {
		ids++
		return "conn-" + string(rune('0'+ids))
	})
	t.Cleanup(reg.CloseAll)
	return &fixture{mgr: mgr, store: st, vault: vault, reg: reg}
}

func (f *fixture) unlock(t *testing.T) {
	t.Helper()
	if err := f.mgr.Setup(context.Background(), masterPassword); err != nil {
		t.Fatalf("Setup: %v", err)
	}
}

func pgInput(name string) connections.Input {
	port := 5432
	env := connections.EnvProd
	tls := connections.TLSRequire
	return connections.Input{
		Name: name, Engine: connections.EnginePostgres, Host: "db.internal",
		Port: &port, Database: "app", Username: "app_ro", TLSMode: &tls,
		Environment: &env, ReadOnly: true, Color: "#ff5555",
		Secret: &connections.SecretInput{Changed: true, Value: "hunter2"},
	}
}

func redisInput(name string) connections.Input {
	return connections.Input{
		Name: name, Engine: connections.EngineRedis, Host: "127.0.0.1",
		Secret: &connections.SecretInput{Changed: true, Value: "cachepass"},
	}
}

func TestEveryConnectionOperationRequiresUnlocking(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()

	ops := map[string]func() error{
		"list":   func() error { _, err := f.mgr.List(ctx); return err },
		"get":    func() error { _, err := f.mgr.Get(ctx, "conn-1"); return err },
		"create": func() error { _, err := f.mgr.Create(ctx, pgInput("x")); return err },
		"update": func() error { _, err := f.mgr.Update(ctx, "conn-1", pgInput("x")); return err },
		"delete": func() error { return f.mgr.Delete(ctx, "conn-1") },
		"test":   func() error { _, err := f.mgr.Test(ctx, "conn-1"); return err },
		"open":   func() error { _, err := f.mgr.Open(ctx, "conn-1"); return err },
		"close":  func() error { _, err := f.mgr.Close(ctx, "conn-1"); return err },
	}
	for name, op := range ops {
		t.Run(name, func(t *testing.T) {
			if err := op(); !errors.Is(err, ErrLocked) {
				t.Fatalf("error = %v, want ErrLocked", err)
			}
		})
	}
}

func TestCreateAndList(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	view, err := f.mgr.Create(ctx, pgInput("prod db"))
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	if view.ID != "conn-1" || view.Name != "prod db" {
		t.Fatalf("view = %+v", view.Summary)
	}
	if !view.HasSecret {
		t.Fatal("a connection created with a password must report HasSecret")
	}
	if view.Runtime.Status != registry.StatusClosed {
		t.Fatalf("runtime status = %q, want closed", view.Runtime.Status)
	}
	if !view.CreatedAt.Equal(time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)) {
		t.Fatalf("createdAt = %v", view.CreatedAt)
	}

	list, err := f.mgr.List(ctx)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(list) != 1 || list[0].ID != "conn-1" {
		t.Fatalf("list = %+v", list)
	}
}

func TestNoResponseCarriesASecret(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	view, err := f.mgr.Create(ctx, pgInput("prod db"))
	if err != nil {
		t.Fatalf("Create: %v", err)
	}

	// Serialize exactly as the API does and search the bytes.
	encoded, err := json.Marshal(view)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	body := string(encoded)
	for _, forbidden := range []string{"hunter2", "sealed", "secretSealed", "SealedSecret"} {
		if strings.Contains(body, forbidden) {
			t.Fatalf("the serialized view contains %q: %s", forbidden, body)
		}
	}
	if !strings.Contains(body, `"hasSecret":true`) {
		t.Fatalf("the view should report that a secret exists: %s", body)
	}

	// The same must hold for the list projection.
	list, _ := f.mgr.List(ctx)
	encodedList, _ := json.Marshal(list)
	if strings.Contains(string(encodedList), "hunter2") {
		t.Fatalf("the list leaked a password: %s", encodedList)
	}
}

func TestSecretsAreStoredSealed(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	if _, err := f.mgr.Create(ctx, pgInput("prod db")); err != nil {
		t.Fatalf("Create: %v", err)
	}
	rec, err := f.store.GetConnection(ctx, "conn-1")
	if err != nil {
		t.Fatalf("GetConnection: %v", err)
	}
	if len(rec.SealedSecret) == 0 {
		t.Fatal("no sealed secret was stored")
	}
	if strings.Contains(string(rec.SealedSecret), "hunter2") {
		t.Fatal("the password was stored in the clear")
	}
}

func TestValidationErrorsAreReportedPerField(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)

	in := pgInput("")
	in.Host = "postgres://db.internal"
	badPort := 70000
	in.Port = &badPort
	in.Database = ""

	_, err := f.mgr.Create(context.Background(), in)
	var verrs connections.ValidationErrors
	if !errors.As(err, &verrs) {
		t.Fatalf("error = %v, want ValidationErrors", err)
	}
	fields := map[string]bool{}
	for _, v := range verrs {
		fields[v.Field] = true
	}
	for _, want := range []string{"name", "host", "port", "database"} {
		if !fields[want] {
			t.Errorf("no validation error for %q (got %v)", want, verrs)
		}
	}
}

func TestEngineDefaultsAreApplied(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	view, err := f.mgr.Create(ctx, connections.Input{
		Name: "cache", Engine: connections.EngineRedis, Host: "127.0.0.1",
	})
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	if view.Port != connections.DefaultRedisPort {
		t.Fatalf("port = %d, want %d", view.Port, connections.DefaultRedisPort)
	}
	if view.Database != "0" {
		t.Fatalf("database = %q, want the default index 0", view.Database)
	}
	if view.Environment != connections.EnvDev {
		t.Fatalf("environment = %q, want dev", view.Environment)
	}
	if view.TLSMode != connections.TLSDisable {
		t.Fatalf("tls mode = %q, want disable", view.TLSMode)
	}
	if view.HasSecret {
		t.Fatal("a connection created without a password must not report one")
	}
}

func TestDuplicateNames(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	if _, err := f.mgr.Create(ctx, pgInput("same")); err != nil {
		t.Fatalf("Create: %v", err)
	}
	if _, err := f.mgr.Create(ctx, pgInput("same")); !errors.Is(err, ErrDuplicateName) {
		t.Fatalf("error = %v, want ErrDuplicateName", err)
	}
}

func TestUpdateWithoutASecretKeepsTheStoredOne(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	if _, err := f.mgr.Create(ctx, pgInput("prod db")); err != nil {
		t.Fatalf("Create: %v", err)
	}
	before, _ := f.store.GetConnection(ctx, "conn-1")

	// A cosmetic edit with no secret supplied.
	in := pgInput("prod db")
	in.Secret = nil
	in.Color = "#00ff00"
	view, err := f.mgr.Update(ctx, "conn-1", in)
	if err != nil {
		t.Fatalf("Update: %v", err)
	}
	if view.Color != "#00ff00" || !view.HasSecret {
		t.Fatalf("view = %+v", view.Summary)
	}

	after, _ := f.store.GetConnection(ctx, "conn-1")
	if string(after.SealedSecret) != string(before.SealedSecret) {
		t.Fatal("an update without a secret must not reseal the stored one")
	}
	// The stored password must still be usable.
	if got := f.password(t, after); got != "hunter2" {
		t.Fatalf("password after update = %q", got)
	}
	if !after.CreatedAt.Equal(before.CreatedAt) {
		t.Fatal("createdAt must be immutable")
	}
}

func TestUpdateWithANewSecretReplacesIt(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	if _, err := f.mgr.Create(ctx, pgInput("prod db")); err != nil {
		t.Fatalf("Create: %v", err)
	}
	in := pgInput("prod db")
	in.Secret = &connections.SecretInput{Changed: true, Value: "new-password"}
	if _, err := f.mgr.Update(ctx, "conn-1", in); err != nil {
		t.Fatalf("Update: %v", err)
	}

	rec, _ := f.store.GetConnection(ctx, "conn-1")
	if got := f.password(t, rec); got != "new-password" {
		t.Fatalf("password = %q, want the replacement", got)
	}
}

func TestUpdateWithAnEmptySecretClearsIt(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	if _, err := f.mgr.Create(ctx, pgInput("prod db")); err != nil {
		t.Fatalf("Create: %v", err)
	}
	in := pgInput("prod db")
	in.Secret = &connections.SecretInput{Changed: true, Value: ""}
	view, err := f.mgr.Update(ctx, "conn-1", in)
	if err != nil {
		t.Fatalf("Update: %v", err)
	}
	if view.HasSecret {
		t.Fatal("clearing the password must clear HasSecret")
	}
	rec, _ := f.store.GetConnection(ctx, "conn-1")
	if len(rec.SealedSecret) != 0 {
		t.Fatal("the sealed secret should have been removed")
	}
}

func TestUnchangedFlagIgnoresASuppliedValue(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	if _, err := f.mgr.Create(ctx, pgInput("prod db")); err != nil {
		t.Fatalf("Create: %v", err)
	}
	in := pgInput("prod db")
	// A form that posts its (empty) password field without the change flag must
	// not silently wipe the stored credential.
	in.Secret = &connections.SecretInput{Changed: false, Value: ""}
	if _, err := f.mgr.Update(ctx, "conn-1", in); err != nil {
		t.Fatalf("Update: %v", err)
	}
	rec, _ := f.store.GetConnection(ctx, "conn-1")
	if got := f.password(t, rec); got != "hunter2" {
		t.Fatalf("password = %q, want the original", got)
	}
}

func TestChangingTheEngineResealsTheSecret(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	if _, err := f.mgr.Create(ctx, pgInput("was postgres")); err != nil {
		t.Fatalf("Create: %v", err)
	}
	before, _ := f.store.GetConnection(ctx, "conn-1")

	in := redisInput("was postgres")
	in.Secret = nil // keep the stored password, but the engine changes
	if _, err := f.mgr.Update(ctx, "conn-1", in); err != nil {
		t.Fatalf("Update: %v", err)
	}

	after, _ := f.store.GetConnection(ctx, "conn-1")
	if string(after.SealedSecret) == string(before.SealedSecret) {
		t.Fatal("the secret must be resealed when its authenticated identity changes")
	}
	if got := f.password(t, after); got != "hunter2" {
		t.Fatalf("password after reseal = %q, want the original", got)
	}
}

func TestUpdateAndDeleteOfAMissingConnection(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	if _, err := f.mgr.Update(ctx, "ghost", pgInput("x")); !errors.Is(err, ErrNotFound) {
		t.Fatalf("update error = %v, want ErrNotFound", err)
	}
	if err := f.mgr.Delete(ctx, "ghost"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("delete error = %v, want ErrNotFound", err)
	}
}

func TestDeleteRemovesTheRecordAndItsRuntimeEntry(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	in := pgInput("doomed")
	in.Host, in.TLSMode = "127.0.0.1", ptr(connections.TLSDisable)
	port := 1
	in.Port = &port
	if _, err := f.mgr.Create(ctx, in); err != nil {
		t.Fatalf("Create: %v", err)
	}
	// A failed open still creates a tracked runtime entry.
	_, _ = f.mgr.Open(ctx, "conn-1")
	if _, tracked := f.reg.States()["conn-1"]; !tracked {
		t.Skip("the open attempt unexpectedly succeeded; cannot test this path")
	}

	if err := f.mgr.Delete(ctx, "conn-1"); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if _, tracked := f.reg.States()["conn-1"]; tracked {
		t.Fatal("deleting a connection must leave no runtime entry behind")
	}
	if _, err := f.mgr.Get(ctx, "conn-1"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("get after delete = %v, want ErrNotFound", err)
	}
}

func TestOpenAndTestReportSafeFailures(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	in := pgInput("unreachable")
	in.Host, in.TLSMode = "127.0.0.1", ptr(connections.TLSDisable)
	port := 1
	in.Port = &port
	if _, err := f.mgr.Create(ctx, in); err != nil {
		t.Fatalf("Create: %v", err)
	}

	_, openErr := f.mgr.Open(ctx, "conn-1")
	if openErr == nil {
		t.Skip("something is listening on port 1")
	}
	var failure OperationFailure
	if !errors.As(openErr, &failure) {
		t.Fatalf("open error = %v, want OperationFailure", openErr)
	}
	if strings.Contains(failure.Message, "hunter2") || strings.Contains(failure.Message, "dial tcp") {
		t.Fatalf("the failure message leaks detail: %q", failure.Message)
	}

	_, testErr := f.mgr.Test(ctx, "conn-1")
	if !errors.As(testErr, &failure) {
		t.Fatalf("test error = %v, want OperationFailure", testErr)
	}

	// The runtime state records the failure for the UI.
	view, err := f.mgr.Get(ctx, "conn-1")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if view.Runtime.Status != registry.StatusError || view.Runtime.LastError == "" {
		t.Fatalf("runtime = %+v", view.Runtime)
	}
}

func TestCloseIsIdempotent(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	if _, err := f.mgr.Create(ctx, pgInput("never opened")); err != nil {
		t.Fatalf("Create: %v", err)
	}
	for i := 0; i < 2; i++ {
		view, err := f.mgr.Close(ctx, "conn-1")
		if err != nil {
			t.Fatalf("Close: %v", err)
		}
		if view.Runtime.Status != registry.StatusClosed {
			t.Fatalf("status = %q, want closed", view.Runtime.Status)
		}
	}
}

func TestLockClosesEverythingAndBlocksAccess(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	if _, err := f.mgr.Create(ctx, pgInput("prod db")); err != nil {
		t.Fatalf("Create: %v", err)
	}
	f.mgr.Lock()

	if _, err := f.mgr.List(ctx); !errors.Is(err, ErrLocked) {
		t.Fatalf("list after lock = %v, want ErrLocked", err)
	}
	state, err := f.mgr.AuthState(ctx)
	if err != nil {
		t.Fatalf("AuthState: %v", err)
	}
	if state != secrets.StateLocked {
		t.Fatalf("state = %q, want locked", state)
	}
}

func TestConnectionsSurviveARestart(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	if _, err := f.mgr.Create(ctx, pgInput("prod db")); err != nil {
		t.Fatalf("Create: %v", err)
	}

	// A fresh manager over the same store is a restarted process.
	// The restarted vault is given no parameters: it must read the ones stored
	// at setup, or the derived key would not match.
	restarted := New(f.store, secrets.NewVault(f.store), registry.New())

	if state, _ := restarted.AuthState(ctx); state != secrets.StateLocked {
		t.Fatalf("restarted state = %q, want locked", state)
	}
	if _, err := restarted.List(ctx); !errors.Is(err, ErrLocked) {
		t.Fatalf("list before unlock = %v, want ErrLocked", err)
	}
	if err := restarted.Unlock(ctx, "the wrong password"); !errors.Is(err, secrets.ErrWrongPassword) {
		t.Fatalf("wrong password error = %v", err)
	}
	if err := restarted.Unlock(ctx, masterPassword); err != nil {
		t.Fatalf("Unlock: %v", err)
	}

	list, err := restarted.List(ctx)
	if err != nil {
		t.Fatalf("List after restart: %v", err)
	}
	if len(list) != 1 || list[0].Name != "prod db" || !list[0].HasSecret {
		t.Fatalf("list after restart = %+v", list)
	}

	rec, _ := f.store.GetConnection(ctx, "conn-1")
	password, err := restarted.password(rec)
	if err != nil {
		t.Fatalf("unseal after restart: %v", err)
	}
	if password != "hunter2" {
		t.Fatalf("password after restart = %q", password)
	}
}

func (f *fixture) password(t *testing.T, rec connections.Record) string {
	t.Helper()
	pw, err := f.mgr.password(rec)
	if err != nil {
		t.Fatalf("unseal: %v", err)
	}
	return pw
}

func ptr[T any](v T) *T { return &v }

func TestListReportsClosedForConnectionsTheRegistryHasNeverSeen(t *testing.T) {
	f := newFixture(t)
	f.unlock(t)
	ctx := context.Background()

	if _, err := f.mgr.Create(ctx, pgInput("prod db")); err != nil {
		t.Fatalf("Create: %v", err)
	}

	// A fresh registry is what every restart starts with.
	restarted := New(f.store, f.vault, registry.New())
	list, err := restarted.List(ctx)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("got %d connections", len(list))
	}
	if got := list[0].Runtime.Status; got != registry.StatusClosed {
		t.Fatalf("runtime status = %q, want %q", got, registry.StatusClosed)
	}
}

package httpapp

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/stohirov/database-ide/internal/manager"
	"github.com/stohirov/database-ide/internal/registry"
	"github.com/stohirov/database-ide/internal/secrets"
	"github.com/stohirov/database-ide/internal/store"
)

const (
	testToken      = "test-token-value"
	testOrigin     = "http://127.0.0.1:43127"
	masterPassword = "correct horse battery"
)

// newTestManager builds a manager over a temporary database with cheap key
// derivation. The cost parameters are covered by the secrets package tests.
func newTestManager(t *testing.T) *manager.Manager {
	t.Helper()
	st, err := store.Open(context.Background(), filepath.Join(t.TempDir(), "dbide.db"))
	if err != nil {
		t.Fatalf("store.Open: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })

	vault := secrets.NewVault(st)
	params := secrets.DefaultParams()
	params.Time, params.MemoryKiB = 1, 8*1024
	vault.SetParams(params)

	reg := registry.New()
	t.Cleanup(reg.CloseAll)
	return manager.New(st, vault, reg)
}

func newTestConfig(t *testing.T) Config {
	t.Helper()
	return Config{Version: "test", Token: testToken, Manager: newTestManager(t)}
}

func testHandler(t *testing.T) http.Handler {
	t.Helper()
	return NewHandler(newTestConfig(t), []string{testOrigin})
}

// unlockedHandler returns a handler whose vault is already open.
func unlockedHandler(t *testing.T) http.Handler {
	t.Helper()
	cfg := newTestConfig(t)
	if err := cfg.Manager.Setup(context.Background(), masterPassword); err != nil {
		t.Fatalf("Setup: %v", err)
	}
	return NewHandler(cfg, []string{testOrigin})
}

func decodeError(t *testing.T, body string) APIError {
	t.Helper()
	var env errorEnvelope
	if err := json.Unmarshal([]byte(body), &env); err != nil {
		t.Fatalf("response is not a JSON error envelope: %v (body=%q)", err, body)
	}
	return env.Error
}

// do issues an authenticated request with the permitted origin.
func do(t *testing.T, h http.Handler, method, target string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var reader *bytes.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			t.Fatalf("marshal request body: %v", err)
		}
		reader = bytes.NewReader(encoded)
	} else {
		reader = bytes.NewReader(nil)
	}
	req := httptest.NewRequest(method, target, reader)
	req.Header.Set("Authorization", "Bearer "+testToken)
	req.Header.Set("Origin", testOrigin)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

// decodeBody parses a successful JSON response into dst.
func decodeBody(t *testing.T, rec *httptest.ResponseRecorder, dst any) {
	t.Helper()
	if err := json.Unmarshal(rec.Body.Bytes(), dst); err != nil {
		t.Fatalf("response body is not the expected JSON: %v (body=%s)", err, rec.Body.String())
	}
}

// newRawRequest builds an authenticated request with a literal body, for cases
// where the body is deliberately not valid JSON.
func newRawRequest(method, target, body string) *http.Request {
	req := httptest.NewRequest(method, target, bytes.NewReader([]byte(body)))
	req.Header.Set("Authorization", "Bearer "+testToken)
	req.Header.Set("Origin", testOrigin)
	req.Header.Set("Content-Type", "application/json")
	return req
}

func serve(h http.Handler, req *http.Request) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

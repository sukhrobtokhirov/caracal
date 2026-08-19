package httpapp

import (
	"net/http"
	"testing"

	"github.com/stohirov/database-ide/internal/secrets"
)

func TestAuthStatusReportsSetupThenLockedThenUnlocked(t *testing.T) {
	h := testHandler(t)

	var status authStatusResponse
	decodeBody(t, do(t, h, http.MethodGet, "/api/auth/status", nil), &status)
	if status.State != secrets.StateSetupRequired {
		t.Fatalf("state = %q, want %q", status.State, secrets.StateSetupRequired)
	}
	if status.MinPasswordLength != secrets.MinPasswordLen {
		t.Fatalf("minPasswordLength = %d, want %d", status.MinPasswordLength, secrets.MinPasswordLen)
	}

	rec := do(t, h, http.MethodPost, "/api/auth/setup", map[string]string{"password": masterPassword})
	if rec.Code != http.StatusOK {
		t.Fatalf("setup status = %d (body=%s)", rec.Code, rec.Body.String())
	}
	decodeBody(t, do(t, h, http.MethodGet, "/api/auth/status", nil), &status)
	if status.State != secrets.StateUnlocked {
		t.Fatalf("state after setup = %q", status.State)
	}

	if rec := do(t, h, http.MethodPost, "/api/auth/lock", nil); rec.Code != http.StatusOK {
		t.Fatalf("lock status = %d", rec.Code)
	}
	decodeBody(t, do(t, h, http.MethodGet, "/api/auth/status", nil), &status)
	if status.State != secrets.StateLocked {
		t.Fatalf("state after lock = %q", status.State)
	}

	if rec := do(t, h, http.MethodPost, "/api/auth/unlock", map[string]string{"password": masterPassword}); rec.Code != http.StatusOK {
		t.Fatalf("unlock status = %d (body=%s)", rec.Code, rec.Body.String())
	}
}

func TestSetupRejectsAShortPassword(t *testing.T) {
	rec := do(t, testHandler(t), http.MethodPost, "/api/auth/setup", map[string]string{"password": "short"})
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want 422 (body=%s)", rec.Code, rec.Body.String())
	}
	if got := decodeError(t, rec.Body.String()).Code; got != CodeWeakPassword {
		t.Fatalf("code = %q, want %q", got, CodeWeakPassword)
	}
}

func TestSetupTwiceIsRejected(t *testing.T) {
	h := testHandler(t)
	if rec := do(t, h, http.MethodPost, "/api/auth/setup", map[string]string{"password": masterPassword}); rec.Code != http.StatusOK {
		t.Fatalf("first setup status = %d", rec.Code)
	}
	rec := do(t, h, http.MethodPost, "/api/auth/setup", map[string]string{"password": "another password"})
	if rec.Code != http.StatusConflict {
		t.Fatalf("status = %d, want 409", rec.Code)
	}
	if got := decodeError(t, rec.Body.String()).Code; got != CodeAlreadySetUp {
		t.Fatalf("code = %q, want %q", got, CodeAlreadySetUp)
	}
}

func TestUnlockWithTheWrongPassword(t *testing.T) {
	h := testHandler(t)
	if rec := do(t, h, http.MethodPost, "/api/auth/setup", map[string]string{"password": masterPassword}); rec.Code != http.StatusOK {
		t.Fatalf("setup status = %d", rec.Code)
	}
	do(t, h, http.MethodPost, "/api/auth/lock", nil)

	rec := do(t, h, http.MethodPost, "/api/auth/unlock", map[string]string{"password": "not it"})
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
	apiErr := decodeError(t, rec.Body.String())
	if apiErr.Code != CodeWrongPassword {
		t.Fatalf("code = %q, want %q", apiErr.Code, CodeWrongPassword)
	}
	// The message must not hint at how close the guess was.
	if apiErr.Message != "The master password is incorrect." {
		t.Fatalf("message = %q", apiErr.Message)
	}
}

func TestUnlockBeforeSetup(t *testing.T) {
	rec := do(t, testHandler(t), http.MethodPost, "/api/auth/unlock", map[string]string{"password": masterPassword})
	if rec.Code != http.StatusConflict {
		t.Fatalf("status = %d, want 409 (body=%s)", rec.Code, rec.Body.String())
	}
	if got := decodeError(t, rec.Body.String()).Code; got != CodeNotSetUp {
		t.Fatalf("code = %q, want %q", got, CodeNotSetUp)
	}
}

func TestMalformedAndUnknownFieldsAreRejected(t *testing.T) {
	h := testHandler(t)
	cases := map[string]string{
		"not json":      `{`,
		"unknown field": `{"password":"correct horse battery","admin":true}`,
		"wrong type":    `{"password":123}`,
	}
	for name, body := range cases {
		t.Run(name, func(t *testing.T) {
			req := newRawRequest(http.MethodPost, "/api/auth/setup", body)
			rec := serve(h, req)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want 400 (body=%s)", rec.Code, rec.Body.String())
			}
			if got := decodeError(t, rec.Body.String()).Code; got != CodeBadRequest {
				t.Fatalf("code = %q, want %q", got, CodeBadRequest)
			}
		})
	}
}

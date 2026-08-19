package httpapp

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestTokenAuth(t *testing.T) {
	cases := []struct {
		name       string
		header     string
		query      string
		wantStatus int
	}{
		{"valid bearer token", "Bearer " + testToken, "", http.StatusOK},
		{"valid bearer token, odd case", "bearer " + testToken, "", http.StatusOK},
		{"valid query token", "", "?token=" + testToken, http.StatusOK},
		{"missing token", "", "", http.StatusUnauthorized},
		{"invalid token", "Bearer wrong", "", http.StatusUnauthorized},
		{"prefix of the token", "Bearer " + testToken[:5], "", http.StatusUnauthorized},
		{"token with trailing data", "Bearer " + testToken + "x", "", http.StatusUnauthorized},
		{"raw token without scheme", testToken, "", http.StatusUnauthorized},
	}
	h := testHandler(t)
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/api/health"+tc.query, nil)
			if tc.header != "" {
				req.Header.Set("Authorization", tc.header)
			}
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, req)
			if rec.Code != tc.wantStatus {
				t.Fatalf("status = %d, want %d (body=%s)", rec.Code, tc.wantStatus, rec.Body.String())
			}
			if tc.wantStatus == http.StatusUnauthorized {
				if got := decodeError(t, rec.Body.String()).Code; got != CodeUnauthorized {
					t.Fatalf("error code = %q, want %q", got, CodeUnauthorized)
				}
			}
		})
	}
}

func TestOriginGuard(t *testing.T) {
	cases := []struct {
		name       string
		method     string
		origin     string
		wantStatus int
	}{
		{"permitted origin on post", http.MethodPost, testOrigin, http.StatusBadRequest},
		{"hostile origin on post", http.MethodPost, "https://evil.example", http.StatusForbidden},
		{"missing origin on post", http.MethodPost, "", http.StatusForbidden},
		{"origin differing only by port", http.MethodPost, "http://127.0.0.1:1", http.StatusForbidden},
		{"null origin on post", http.MethodPost, "null", http.StatusForbidden},
		{"hostile origin on get", http.MethodGet, "https://evil.example", http.StatusForbidden},
		{"missing origin on get", http.MethodGet, "", http.StatusOK},
	}
	h := testHandler(t)
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			target := "/api/health"
			if tc.method == http.MethodPost {
				target = "/api/auth/unlock"
			}
			req := httptest.NewRequest(tc.method, target, nil)
			req.Header.Set("Authorization", "Bearer "+testToken)
			if tc.origin != "" {
				req.Header.Set("Origin", tc.origin)
			}
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, req)
			if rec.Code != tc.wantStatus {
				t.Fatalf("status = %d, want %d (body=%s)", rec.Code, tc.wantStatus, rec.Body.String())
			}
			if tc.wantStatus == http.StatusForbidden {
				if got := decodeError(t, rec.Body.String()).Code; got != CodeForbidden {
					t.Fatalf("error code = %q, want %q", got, CodeForbidden)
				}
			}
		})
	}
}

func TestAuthRunsBeforeOriginCheck(t *testing.T) {
	// An unauthenticated hostile request must not learn which origins pass.
	req := httptest.NewRequest(http.MethodPost, "/api/auth/unlock", nil)
	req.Header.Set("Origin", "https://evil.example")
	rec := httptest.NewRecorder()
	testHandler(t).ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnauthorized)
	}
}

func TestSecurityHeaders(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/api/health", nil)
	req.Header.Set("Authorization", "Bearer "+testToken)
	rec := httptest.NewRecorder()
	testHandler(t).ServeHTTP(rec, req)

	want := map[string]string{
		"X-Content-Type-Options": "nosniff",
		"X-Frame-Options":        "DENY",
	}
	for k, v := range want {
		if got := rec.Header().Get(k); got != v {
			t.Errorf("header %s = %q, want %q", k, got, v)
		}
	}
	csp := rec.Header().Get("Content-Security-Policy")
	for _, directive := range []string{"default-src 'none'", "script-src 'self'", "frame-ancestors 'none'"} {
		if !strings.Contains(csp, directive) {
			t.Errorf("CSP %q is missing %q", csp, directive)
		}
	}
	if rec.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Error("CORS must not be enabled")
	}
}

package httpapp

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"testing/fstest"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/stohirov/database-ide/internal/postgres"
)

type fakePG struct {
	res *postgres.Result
	err error
}

func (f fakePG) SelectOne(context.Context) (*postgres.Result, error) { return f.res, f.err }

func authedGet(h http.Handler, target string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, target, nil)
	req.Header.Set("Authorization", "Bearer "+testToken)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func authedPost(h http.Handler, target string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, target, nil)
	req.Header.Set("Authorization", "Bearer "+testToken)
	req.Header.Set("Origin", testOrigin)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func indexFS() fstest.MapFS {
	return fstest.MapFS{
		"index.html":         {Data: []byte("<!doctype html><title>dbide</title>")},
		"assets/app-abc.js":  {Data: []byte("console.log(1)")},
		"assets/app-abc.css": {Data: []byte("body{}")},
		"favicon.svg":        {Data: []byte("<svg/>")},
	}
}

func TestUnknownAPIRouteReturnsJSONNotIndex(t *testing.T) {
	h := NewHandler(Config{Token: testToken, Assets: indexFS()}, []string{testOrigin})
	rec := authedGet(h, "/api/does-not-exist")

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		t.Fatalf("content type = %q, want JSON", ct)
	}
	if strings.Contains(rec.Body.String(), "<!doctype") {
		t.Fatal("unknown API route fell through to index.html")
	}
	if got := decodeError(t, rec.Body.String()).Code; got != CodeNotFound {
		t.Fatalf("error code = %q, want %q", got, CodeNotFound)
	}
}

func TestWrongMethodOnAPIRoute(t *testing.T) {
	h := NewHandler(Config{Token: testToken, Assets: indexFS()}, []string{testOrigin})
	rec := authedGet(h, "/api/bootstrap/select-one")
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405 (body=%s)", rec.Code, rec.Body.String())
	}
}

func TestServesEmbeddedEntryPage(t *testing.T) {
	h := NewHandler(Config{Token: testToken, Assets: indexFS()}, []string{testOrigin})

	// The document load is unauthenticated: the bundle is public, the API is not.
	for _, path := range []string{"/", "/some/client/route"} {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		if rec.Code != http.StatusOK {
			t.Fatalf("GET %s status = %d, want 200", path, rec.Code)
		}
		if !strings.Contains(rec.Body.String(), "<!doctype") {
			t.Fatalf("GET %s did not serve index.html: %q", path, rec.Body.String())
		}
	}

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/assets/app-abc.js", nil))
	if rec.Code != http.StatusOK || rec.Body.String() != "console.log(1)" {
		t.Fatalf("asset request: status=%d body=%q", rec.Code, rec.Body.String())
	}
	if cc := rec.Header().Get("Cache-Control"); !strings.Contains(cc, "immutable") {
		t.Errorf("hashed asset Cache-Control = %q, want immutable", cc)
	}
}

func TestHealth(t *testing.T) {
	h := NewHandler(Config{Token: testToken, Version: "1.2.3"}, []string{testOrigin})
	rec := authedGet(h, "/api/health")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	var got healthResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("bad health body: %v", err)
	}
	if got.Status != "ok" || got.Version != "1.2.3" || got.Database != "not_configured" {
		t.Fatalf("health = %+v", got)
	}
}

func TestSelectOneSuccess(t *testing.T) {
	res := &postgres.Result{
		Columns:    []postgres.Column{{Name: "value", TypeOID: 23, TypeName: "int4"}},
		Rows:       [][]any{{"1"}},
		RowCount:   1,
		DurationMs: 3,
	}
	h := NewHandler(Config{Token: testToken, PG: fakePG{res: res}}, []string{testOrigin})
	rec := authedPost(h, "/api/bootstrap/select-one")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body=%s)", rec.Code, rec.Body.String())
	}
	var got postgres.Result
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("bad result body: %v", err)
	}
	if got.RowCount != 1 || len(got.Rows) != 1 || got.Rows[0][0] != "1" {
		t.Fatalf("result = %+v", got)
	}
}

func TestSelectOneWithoutDatabase(t *testing.T) {
	h := NewHandler(Config{Token: testToken}, []string{testOrigin})
	rec := authedPost(h, "/api/bootstrap/select-one")
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", rec.Code)
	}
	if got := decodeError(t, rec.Body.String()).Code; got != CodeDBUnavailable {
		t.Fatalf("error code = %q, want %q", got, CodeDBUnavailable)
	}
}

func TestSelectOneFailureMapping(t *testing.T) {
	cases := []struct {
		name       string
		err        error
		wantStatus int
		wantCode   string
	}{
		{"unreachable server", errors.New("dial tcp: connection refused"), http.StatusBadGateway, "database_unavailable"},
		{"timeout", context.DeadlineExceeded, http.StatusGatewayTimeout, "query_timeout"},
		{"cancelled", context.Canceled, http.StatusGatewayTimeout, "query_cancelled"},
		{"sql error", &pgconn.PgError{Severity: "ERROR", Code: "42601", Message: "syntax error"}, http.StatusUnprocessableEntity, "query_failed"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			h := NewHandler(Config{Token: testToken, PG: fakePG{err: tc.err}}, []string{testOrigin})
			rec := authedPost(h, "/api/bootstrap/select-one")
			if rec.Code != tc.wantStatus {
				t.Fatalf("status = %d, want %d", rec.Code, tc.wantStatus)
			}
			apiErr := decodeError(t, rec.Body.String())
			if apiErr.Code != tc.wantCode {
				t.Fatalf("code = %q, want %q", apiErr.Code, tc.wantCode)
			}
			if strings.Contains(apiErr.Message, "dial tcp") {
				t.Fatalf("driver internals leaked to the client: %q", apiErr.Message)
			}
		})
	}
}

func TestListenBindsLoopbackOnly(t *testing.T) {
	ln, err := Listen(0)
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	defer ln.Close()

	addr, ok := ln.Addr().(*net.TCPAddr)
	if !ok {
		t.Fatalf("unexpected address type %T", ln.Addr())
	}
	if !addr.IP.IsLoopback() {
		t.Fatalf("bound to %s, want a loopback address", addr.IP)
	}
	if addr.Port == 0 {
		t.Fatal("port 0 did not resolve to a concrete port")
	}
}

func TestListenRejectsInvalidPort(t *testing.T) {
	if _, err := Listen(70000); err == nil {
		t.Fatal("expected an error for an out-of-range port")
	}
}

func TestLaunchURLTargetsLoopback(t *testing.T) {
	ln, err := Listen(0)
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	defer ln.Close()

	srv := New(Config{Token: testToken}, ln)
	url := srv.LaunchURL()
	if !strings.HasPrefix(url, "http://127.0.0.1:") {
		t.Fatalf("launch URL = %q, want a 127.0.0.1 URL", url)
	}
	if !strings.HasSuffix(url, "?token="+testToken) {
		t.Fatalf("launch URL = %q, want the session token", url)
	}
}

func TestServerAllowsItsOwnOrigin(t *testing.T) {
	ln, err := Listen(0)
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	srv := New(Config{Token: testToken, PG: fakePG{res: &postgres.Result{Rows: [][]any{}}}}, ln)
	go func() { _ = srv.Serve() }()
	defer srv.Shutdown(context.Background())

	base := "http://127.0.0.1:" + strconv.Itoa(srv.Port())
	req, _ := http.NewRequest(http.MethodPost, base+"/api/bootstrap/select-one", nil)
	req.Header.Set("Authorization", "Bearer "+testToken)
	req.Header.Set("Origin", base)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
}

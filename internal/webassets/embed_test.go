package webassets

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"
)

func fixture() fstest.MapFS {
	return fstest.MapFS{
		"index.html":       {Data: []byte("<!doctype html>index")},
		"assets/app-a1.js": {Data: []byte("app")},
		"secret/inner.txt": {Data: []byte("inner")},
	}
}

func get(t *testing.T, path string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	Handler(fixture()).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	return rec
}

func TestServesIndexForRootAndClientRoutes(t *testing.T) {
	for _, p := range []string{"/", "/connections/42", "/unknown"} {
		rec := get(t, p)
		if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "index") {
			t.Fatalf("GET %s: status=%d body=%q", p, rec.Code, rec.Body.String())
		}
		if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/html") {
			t.Fatalf("GET %s content type = %q", p, ct)
		}
	}
}

func TestServesHashedAsset(t *testing.T) {
	rec := get(t, "/assets/app-a1.js")
	if rec.Code != http.StatusOK || rec.Body.String() != "app" {
		t.Fatalf("status=%d body=%q", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Header().Get("Cache-Control"), "immutable") {
		t.Errorf("Cache-Control = %q", rec.Header().Get("Cache-Control"))
	}
}

func TestIndexIsNotCached(t *testing.T) {
	if cc := get(t, "/").Header().Get("Cache-Control"); cc != "no-store" {
		t.Fatalf("index Cache-Control = %q, want no-store", cc)
	}
}

func TestTraversalCannotEscapeTheBundle(t *testing.T) {
	// A traversal attempt must resolve to index.html, never to a host file.
	rec := get(t, "/../../etc/passwd")
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "index") {
		t.Fatalf("status=%d body=%q", rec.Code, rec.Body.String())
	}
}

func TestNonGetIsRejected(t *testing.T) {
	rec := httptest.NewRecorder()
	Handler(fixture()).ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
}

func TestFSReportsMissingBundle(t *testing.T) {
	// The checked-in dist directory holds only .gitkeep until `make build-frontend`
	// runs, so FS must say so rather than serving a broken page.
	if _, err := FS(); err != nil && err != ErrNotBuilt {
		t.Fatalf("FS() = %v, want nil or ErrNotBuilt", err)
	}
}

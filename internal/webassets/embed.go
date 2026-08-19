// Package webassets embeds the production frontend bundle.
//
// Vite writes its build into ./dist (see web/vite.config.ts). Go's embed
// directive cannot reach outside the package directory, so the build output
// lives next to this file rather than in web/dist.
package webassets

import (
	"embed"
	"errors"
	"io/fs"
	"net/http"
	"path"
	"strings"
)

//go:embed all:dist
var embedded embed.FS

// ErrNotBuilt reports that the binary was compiled without a frontend bundle.
var ErrNotBuilt = errors.New("frontend bundle is not built; run `make build-frontend`")

// FS returns the embedded bundle rooted at the directory holding index.html.
func FS() (fs.FS, error) {
	sub, err := fs.Sub(embedded, "dist")
	if err != nil {
		return nil, err
	}
	if _, err := fs.Stat(sub, "index.html"); err != nil {
		return nil, ErrNotBuilt
	}
	return sub, nil
}

// Handler serves the SPA: hashed assets with long-lived caching, and
// index.html as the fallback for client-side routes.
//
// The caller must not mount this on /api; unknown API routes must return JSON,
// never index.html.
func Handler(assets fs.FS) http.Handler {
	files := http.FileServer(http.FS(assets))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		name := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
		if name == "" || name == "." {
			serveIndex(w, r, assets)
			return
		}
		f, err := assets.Open(name)
		if err != nil {
			serveIndex(w, r, assets)
			return
		}
		info, statErr := f.Stat()
		_ = f.Close()
		if statErr != nil || info.IsDir() {
			serveIndex(w, r, assets)
			return
		}
		if strings.HasPrefix(name, "assets/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		}
		files.ServeHTTP(w, r)
	})
}

func serveIndex(w http.ResponseWriter, r *http.Request, assets fs.FS) {
	data, err := fs.ReadFile(assets, "index.html")
	if err != nil {
		http.Error(w, "frontend bundle is missing", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	if r.Method == http.MethodHead {
		w.WriteHeader(http.StatusOK)
		return
	}
	_, _ = w.Write(data)
}

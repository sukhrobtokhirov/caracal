package httpapp

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"net"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"

	"github.com/stohirov/database-ide/internal/manager"
	"github.com/stohirov/database-ide/internal/webassets"
)

// loopbackHost is the only address this server is ever allowed to bind. It is a
// constant on purpose: no flag or environment variable may widen it in v0.1.
const loopbackHost = "127.0.0.1"

const (
	readHeaderTimeout = 5 * time.Second
	writeTimeout      = 60 * time.Second
	idleTimeout       = 120 * time.Second
	maxHeaderBytes    = 1 << 16 // 64 KiB
)

// Config carries everything the HTTP layer needs. A nil Assets is a documented,
// user-visible degraded state rather than a startup failure.
type Config struct {
	Version string
	Token   string
	Assets  fs.FS
	Manager *manager.Manager
	// ExtraOrigins allows the Vite dev server origin during frontend work.
	ExtraOrigins []string
}

// Server owns the listener and the HTTP server.
type Server struct {
	cfg  Config
	ln   net.Listener
	http *http.Server
}

// Listen binds to the loopback interface only. Port 0 selects a free port.
func Listen(port int) (net.Listener, error) {
	if port < 0 || port > 65535 {
		return nil, fmt.Errorf("port %d is out of range", port)
	}
	return net.Listen("tcp", net.JoinHostPort(loopbackHost, fmt.Sprint(port)))
}

// New builds a server around an already-bound listener.
func New(cfg Config, ln net.Listener) *Server {
	origins := localOrigins(ln.Addr())
	origins = append(origins, cfg.ExtraOrigins...)
	return &Server{
		cfg: cfg,
		ln:  ln,
		http: &http.Server{
			Handler:           NewHandler(cfg, origins),
			ReadHeaderTimeout: readHeaderTimeout,
			WriteTimeout:      writeTimeout,
			IdleTimeout:       idleTimeout,
			MaxHeaderBytes:    maxHeaderBytes,
		},
	}
}

// Port reports the bound TCP port.
func (s *Server) Port() int {
	if a, ok := s.ln.Addr().(*net.TCPAddr); ok {
		return a.Port
	}
	return 0
}

// LaunchURL is the one place the session token appears in a log line.
func (s *Server) LaunchURL() string {
	return fmt.Sprintf("http://%s:%d/?token=%s", loopbackHost, s.Port(), s.cfg.Token)
}

// Serve blocks until the server is shut down.
func (s *Server) Serve() error {
	err := s.http.Serve(s.ln)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

// Shutdown stops accepting requests and waits for in-flight work.
func (s *Server) Shutdown(ctx context.Context) error {
	return s.http.Shutdown(ctx)
}

func localOrigins(addr net.Addr) []string {
	port := 0
	if a, ok := addr.(*net.TCPAddr); ok {
		port = a.Port
	}
	return []string{
		fmt.Sprintf("http://%s:%d", loopbackHost, port),
		fmt.Sprintf("http://localhost:%d", port),
	}
}

// NewHandler assembles the route tree. Exported so tests can exercise the full
// middleware chain without binding a port.
func NewHandler(cfg Config, origins []string) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.Recoverer)
	r.Use(securityHeaders)

	api := chi.NewRouter()
	api.Use(tokenAuth(cfg.Token))
	api.Use(originGuard(origins))
	api.NotFound(func(w http.ResponseWriter, _ *http.Request) {
		writeError(w, http.StatusNotFound, CodeNotFound, "No such API route.")
	})
	api.MethodNotAllowed(func(w http.ResponseWriter, _ *http.Request) {
		writeError(w, http.StatusMethodNotAllowed, CodeMethod, "That method is not allowed on this route.")
	})
	api.Get("/health", cfg.handleHealth)

	api.Get("/auth/status", cfg.handleAuthStatus)
	api.Post("/auth/setup", cfg.handleAuthSetup)
	api.Post("/auth/unlock", cfg.handleAuthUnlock)
	api.Post("/auth/lock", cfg.handleAuthLock)

	// Registered flat rather than nested so /api/connections resolves without
	// depending on a trailing-slash redirect.
	api.Get("/connections", cfg.handleListConnections)
	api.Post("/connections", cfg.handleCreateConnection)
	api.Put("/connections/{id}", cfg.handleUpdateConnection)
	api.Delete("/connections/{id}", cfg.handleDeleteConnection)
	api.Post("/connections/{id}/test", cfg.handleTestConnection)
	api.Post("/connections/{id}/open", cfg.handleOpenConnection)
	api.Post("/connections/{id}/close", cfg.handleCloseConnection)

	r.Mount("/api", api)

	// The SPA fallback lives outside /api, so an unknown API path can never
	// resolve to index.html.
	if cfg.Assets != nil {
		r.NotFound(webassets.Handler(cfg.Assets).ServeHTTP)
	} else {
		r.NotFound(notBuiltHandler)
	}
	return r
}

type healthResponse struct {
	Status  string `json:"status"`
	Version string `json:"version"`
}

func (c Config) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, healthResponse{Status: "ok", Version: c.Version})
}

func notBuiltHandler(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusServiceUnavailable)
	fmt.Fprintln(w, "The frontend bundle is not embedded in this binary.")
	fmt.Fprintln(w, "Run `make build` for a production binary, or `make dev-frontend` and use the Vite URL.")
}

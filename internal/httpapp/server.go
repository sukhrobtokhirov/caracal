package httpapp

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"net"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"

	"github.com/stohirov/database-ide/internal/postgres"
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

// PGQuerier is the M0 slice of the PostgreSQL adapter the API depends on.
type PGQuerier interface {
	SelectOne(ctx context.Context) (*postgres.Result, error)
}

// Config carries everything the HTTP layer needs. A nil PG or Assets is a
// documented, user-visible degraded state rather than a startup failure.
type Config struct {
	Version string
	Token   string
	Assets  fs.FS
	PG      PGQuerier
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
	api.Post("/bootstrap/select-one", cfg.handleSelectOne)

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
	Status   string `json:"status"`
	Version  string `json:"version"`
	Database string `json:"database"` // "configured" | "not_configured"
}

func (c Config) handleHealth(w http.ResponseWriter, _ *http.Request) {
	db := "not_configured"
	if c.PG != nil {
		db = "configured"
	}
	writeJSON(w, http.StatusOK, healthResponse{Status: "ok", Version: c.Version, Database: db})
}

// handleSelectOne is M0 scaffolding: it ignores any request body and runs one
// fixed statement. M2 replaces it with the real query endpoint.
func (c Config) handleSelectOne(w http.ResponseWriter, r *http.Request) {
	if c.PG == nil {
		writeError(w, http.StatusServiceUnavailable, CodeDBUnavailable,
			"No PostgreSQL connection is configured. Set DBIDE_DEV_POSTGRES_DSN and restart.")
		return
	}
	res, err := c.PG.SelectOne(r.Context())
	if err != nil {
		f := postgres.Classify(err)
		slog.Warn("bootstrap query failed", "component", "httpapp", "code", f.Code)
		status := http.StatusBadGateway
		switch f.Code {
		case "query_failed":
			status = http.StatusUnprocessableEntity
		case "query_timeout", "query_cancelled":
			status = http.StatusGatewayTimeout
		}
		writeError(w, status, f.Code, f.Message)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func notBuiltHandler(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusServiceUnavailable)
	fmt.Fprintln(w, "The frontend bundle is not embedded in this binary.")
	fmt.Fprintln(w, "Run `make build` for a production binary, or `make dev-frontend` and use the Vite URL.")
}

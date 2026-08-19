// Command dbide runs the Database IDE server on the loopback interface and
// serves the embedded single-page application.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/stohirov/database-ide/internal/appdata"
	"github.com/stohirov/database-ide/internal/browser"
	"github.com/stohirov/database-ide/internal/httpapp"
	"github.com/stohirov/database-ide/internal/manager"
	"github.com/stohirov/database-ide/internal/registry"
	"github.com/stohirov/database-ide/internal/secrets"
	"github.com/stohirov/database-ide/internal/store"
	"github.com/stohirov/database-ide/internal/webassets"
)

// version is overridden at build time with -ldflags "-X main.version=...".
var version = "dev"

const shutdownGrace = 10 * time.Second

func main() {
	if err := run(); err != nil {
		slog.Error("fatal", "component", "main", "error", err.Error())
		os.Exit(1)
	}
}

func run() error {
	var (
		port      = flag.Int("port", 0, "TCP port on 127.0.0.1; 0 selects a free port")
		noOpen    = flag.Bool("no-open", false, "do not launch a browser; print the URL only")
		devOrigin = flag.String("dev-origin", "", "additional allowed origin for the Vite dev server, e.g. http://localhost:5173")
		dataDir   = flag.String("data-dir", "", "directory holding the configuration database; defaults to the platform application data directory")
		verbose   = flag.Bool("verbose", false, "enable debug logging")
	)
	flag.Parse()

	setupLogging(*verbose)

	assets, err := webassets.FS()
	if err != nil {
		if !errors.Is(err, webassets.ErrNotBuilt) {
			return err
		}
		slog.Warn("frontend bundle is not embedded; serving API only",
			"component", "main", "hint", "run `make build` or use `make dev-frontend`")
		assets = nil
	}

	dbPath, err := appdata.DatabasePath(*dataDir)
	if err != nil {
		return err
	}
	ctx, cancelStartup := context.WithTimeout(context.Background(), 15*time.Second)
	st, err := store.Open(ctx, dbPath)
	cancelStartup()
	if err != nil {
		return err
	}
	defer func() { _ = st.Close() }()
	slog.Info("configuration store ready", "component", "main", "path", dbPath)

	vault := secrets.NewVault(st)
	reg := registry.New()
	mgr := manager.New(st, vault, reg)
	defer mgr.Shutdown()

	ln, err := httpapp.Listen(*port)
	if err != nil {
		return fmt.Errorf("cannot bind loopback port: %w", err)
	}

	cfg := httpapp.Config{
		Version: version,
		Token:   httpapp.NewToken(),
		Assets:  assets,
		Manager: mgr,
	}
	if *devOrigin != "" {
		cfg.ExtraOrigins = []string{strings.TrimRight(*devOrigin, "/")}
		slog.Info("allowing additional development origin", "component", "main", "origin", *devOrigin)
	}

	srv := httpapp.New(cfg, ln)
	url := srv.LaunchURL()

	slog.Info("listening", "component", "main", "address", ln.Addr().String(), "version", version)
	// The launch URL is the only place the token is printed. It is not logged
	// as an independent field.
	fmt.Fprintf(os.Stdout, "\n  Database IDE is running.\n  Open: %s\n\n", url)

	errCh := make(chan error, 1)
	go func() { errCh <- srv.Serve() }()

	if !*noOpen {
		if err := browser.Open(url); err != nil {
			slog.Warn("could not open a browser automatically; use the URL above",
				"component", "main", "error", err.Error())
		}
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case err := <-errCh:
		return err
	case sig := <-sigCh:
		slog.Info("shutting down", "component", "main", "signal", sig.String())
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownGrace)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		return fmt.Errorf("shutdown did not complete cleanly: %w", err)
	}
	slog.Info("stopped", "component", "main")
	return nil
}

func setupLogging(verbose bool) {
	level := slog.LevelInfo
	if verbose {
		level = slog.LevelDebug
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level})))
}

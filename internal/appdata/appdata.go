// Package appdata locates the per-user configuration directory.
package appdata

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
)

// AppName is the directory name used under the platform's data location.
const AppName = "dbide"

// DatabaseFile is the configuration database's file name.
const DatabaseFile = "dbide.db"

// Dir returns the directory holding this user's configuration database:
//
//	macOS    ~/Library/Application Support/dbide
//	Windows  %AppData%\dbide
//	Linux    $XDG_DATA_HOME/dbide, or ~/.local/share/dbide
//
// The directory is not created here; the store creates it with owner-only
// permissions when it opens the database.
func Dir() (string, error) {
	switch runtime.GOOS {
	case "darwin":
		home, err := os.UserHomeDir()
		if err != nil {
			return "", fmt.Errorf("locate the home directory: %w", err)
		}
		return filepath.Join(home, "Library", "Application Support", AppName), nil
	case "windows":
		dir, err := os.UserConfigDir()
		if err != nil {
			return "", fmt.Errorf("locate the application data directory: %w", err)
		}
		return filepath.Join(dir, AppName), nil
	default:
		if xdg := os.Getenv("XDG_DATA_HOME"); xdg != "" {
			return filepath.Join(xdg, AppName), nil
		}
		home, err := os.UserHomeDir()
		if err != nil {
			return "", fmt.Errorf("locate the home directory: %w", err)
		}
		return filepath.Join(home, ".local", "share", AppName), nil
	}
}

// DatabasePath returns the configuration database path inside dir. An empty dir
// resolves to the platform default.
func DatabasePath(dir string) (string, error) {
	if dir == "" {
		var err error
		if dir, err = Dir(); err != nil {
			return "", err
		}
	}
	return filepath.Join(dir, DatabaseFile), nil
}

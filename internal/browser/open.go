// Package browser opens a URL in the user's default browser. Opening is a
// convenience only: the caller always prints the URL as a fallback.
package browser

import (
	"fmt"
	"os/exec"
	"runtime"
)

// Open launches the platform browser opener for url.
func Open(url string) error {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "darwin":
		cmd = exec.Command("open", url)
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "linux":
		cmd = exec.Command("xdg-open", url)
	default:
		return fmt.Errorf("opening a browser is not supported on %s", runtime.GOOS)
	}
	return cmd.Start()
}

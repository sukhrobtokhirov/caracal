package httpapp

import (
	"encoding/base64"
	"testing"
)

func TestNewTokenIsUsableAndUnique(t *testing.T) {
	seen := make(map[string]struct{}, 256)
	for i := 0; i < 256; i++ {
		tok := NewToken()
		raw, err := base64.RawURLEncoding.DecodeString(tok)
		if err != nil {
			t.Fatalf("token %q is not URL-safe base64: %v", tok, err)
		}
		if len(raw) != tokenBytes {
			t.Fatalf("token decoded to %d bytes, want %d", len(raw), tokenBytes)
		}
		if _, dup := seen[tok]; dup {
			t.Fatalf("token repeated after %d draws", i)
		}
		seen[tok] = struct{}{}
	}
}

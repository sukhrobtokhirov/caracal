package httpapp

import (
	"crypto/rand"
	"encoding/base64"
)

// tokenBytes is the entropy of the per-process session token.
const tokenBytes = 32

// NewToken returns a URL-safe random session token. It panics only if the
// system CSPRNG fails, which is not a recoverable condition for this process.
func NewToken() string {
	b := make([]byte, tokenBytes)
	if _, err := rand.Read(b); err != nil {
		panic("dbide: no source of randomness: " + err.Error())
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

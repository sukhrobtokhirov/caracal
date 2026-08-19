// Package secrets derives the master key and seals connection credentials.
//
// Threat model: an attacker with read access to the configuration file on this
// machine. Argon2id makes an offline guess expensive; AES-GCM makes a silently
// modified record impossible. Neither defends against an attacker who already
// controls the running process.
package secrets

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"

	"golang.org/x/crypto/argon2"
)

// KDFVersion identifies the derivation scheme. Bump it if the algorithm, not
// just its cost, changes.
const KDFVersion = 1

// SaltLen is the length of the per-installation Argon2id salt.
const SaltLen = 16

// KeyLen is the derived key length: AES-256.
const KeyLen = 32

// KDFParams are stored alongside the salt so a future cost increase can still
// open old data.
type KDFParams struct {
	Version uint32 `json:"version"`
	// Time is the number of Argon2id passes.
	Time uint32 `json:"time"`
	// MemoryKiB is the memory cost in kibibytes.
	MemoryKiB uint32 `json:"memoryKiB"`
	// Threads is the parallelism degree.
	Threads uint8 `json:"threads"`
	// KeyLen is the derived key length in bytes.
	KeyLen uint32 `json:"keyLen"`
}

// DefaultParams targets roughly a tenth of a second on a modern laptop while
// costing an attacker 64 MiB per guess.
func DefaultParams() KDFParams {
	return KDFParams{Version: KDFVersion, Time: 3, MemoryKiB: 64 * 1024, Threads: 4, KeyLen: KeyLen}
}

// ErrUnsupportedKDF reports parameters this build cannot use.
var ErrUnsupportedKDF = errors.New("unsupported key derivation parameters")

// Validate rejects stored parameters that are unusable or dangerously weak.
func (p KDFParams) Validate() error {
	switch {
	case p.Version != KDFVersion:
		return fmt.Errorf("%w: version %d", ErrUnsupportedKDF, p.Version)
	case p.Time < 1:
		return fmt.Errorf("%w: time cost must be at least 1", ErrUnsupportedKDF)
	case p.MemoryKiB < 8*1024:
		return fmt.Errorf("%w: memory cost must be at least 8 MiB", ErrUnsupportedKDF)
	case p.Threads < 1:
		return fmt.Errorf("%w: parallelism must be at least 1", ErrUnsupportedKDF)
	case p.KeyLen != KeyLen:
		return fmt.Errorf("%w: key length must be %d bytes", ErrUnsupportedKDF, KeyLen)
	}
	return nil
}

// Marshal encodes the parameters for storage.
func (p KDFParams) Marshal() ([]byte, error) { return json.Marshal(p) }

// UnmarshalParams decodes stored parameters and validates them.
func UnmarshalParams(data []byte) (KDFParams, error) {
	var p KDFParams
	if err := json.Unmarshal(data, &p); err != nil {
		return KDFParams{}, fmt.Errorf("%w: parameters are unreadable", ErrUnsupportedKDF)
	}
	if err := p.Validate(); err != nil {
		return KDFParams{}, err
	}
	return p, nil
}

// DeriveKey turns a master password into the AES key. The returned slice is the
// process's most sensitive value; callers keep exactly one copy.
func DeriveKey(password string, salt []byte, p KDFParams) ([]byte, error) {
	if err := p.Validate(); err != nil {
		return nil, err
	}
	if len(salt) < SaltLen {
		return nil, fmt.Errorf("%w: salt is too short", ErrUnsupportedKDF)
	}
	return argon2.IDKey([]byte(password), salt, p.Time, p.MemoryKiB, p.Threads, p.KeyLen), nil
}

// NewSalt returns a fresh random salt.
func NewSalt() ([]byte, error) {
	salt := make([]byte, SaltLen)
	if _, err := rand.Read(salt); err != nil {
		return nil, fmt.Errorf("no source of randomness: %w", err)
	}
	return salt, nil
}

// Zero overwrites a sensitive slice. Go cannot guarantee the bytes never
// reached another page, but clearing the copy we control is still worth doing.
func Zero(b []byte) {
	for i := range b {
		b[i] = 0
	}
}

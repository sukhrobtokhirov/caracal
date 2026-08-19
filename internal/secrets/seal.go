package secrets

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
)

// EnvelopeVersion identifies the sealed-secret format on disk.
const EnvelopeVersion byte = 1

// NonceLen is the AES-GCM nonce length: 96 bits, the size GCM is defined for.
const NonceLen = 12

// PayloadVersion identifies the plaintext structure inside the envelope, so a
// second sensitive field can be added later without a new crypto format.
const PayloadVersion = 1

// ErrCannotDecrypt reports a sealed value that will not open: a wrong key,
// tampering, or a record copied from another connection. The three are
// deliberately indistinguishable to the caller.
var ErrCannotDecrypt = errors.New("this saved credential cannot be decrypted")

// ErrMalformedEnvelope reports bytes that are not a sealed envelope at all.
var ErrMalformedEnvelope = errors.New("this saved credential is not readable")

// Payload is the plaintext inside an envelope.
type Payload struct {
	Version  int    `json:"v"`
	Password string `json:"password"`
}

// Identity is the connection this secret belongs to. It is authenticated but
// not encrypted, so a ciphertext moved to a different record fails to open
// rather than silently decrypting under the wrong connection.
type Identity struct {
	ConnectionID string
	Engine       string
}

func (i Identity) aad() []byte {
	// The version prefix keeps the AAD unambiguous if the format ever changes.
	return append([]byte{EnvelopeVersion}, []byte(i.ConnectionID+"\x00"+i.Engine)...)
}

// Seal encrypts a password for one connection identity.
//
// Envelope layout: version(1) || nonce(12) || ciphertext+tag.
func Seal(key []byte, id Identity, password string) ([]byte, error) {
	gcm, err := newGCM(key)
	if err != nil {
		return nil, err
	}
	plaintext, err := json.Marshal(Payload{Version: PayloadVersion, Password: password})
	if err != nil {
		return nil, fmt.Errorf("could not encode the credential: %w", err)
	}
	nonce := make([]byte, NonceLen)
	if _, err := rand.Read(nonce); err != nil {
		return nil, fmt.Errorf("no source of randomness: %w", err)
	}

	out := make([]byte, 0, 1+NonceLen+len(plaintext)+gcm.Overhead())
	out = append(out, EnvelopeVersion)
	out = append(out, nonce...)
	out = gcm.Seal(out, nonce, plaintext, id.aad())
	Zero(plaintext)
	return out, nil
}

// Open decrypts an envelope. Every failure mode returns ErrCannotDecrypt or
// ErrMalformedEnvelope; the bytes are never reinterpreted as plaintext.
func Open(key []byte, id Identity, envelope []byte) (string, error) {
	if len(envelope) < 1+NonceLen+1 {
		return "", ErrMalformedEnvelope
	}
	if envelope[0] != EnvelopeVersion {
		return "", fmt.Errorf("%w: unknown format version %d", ErrMalformedEnvelope, envelope[0])
	}
	gcm, err := newGCM(key)
	if err != nil {
		return "", err
	}
	nonce := envelope[1 : 1+NonceLen]
	ciphertext := envelope[1+NonceLen:]

	plaintext, err := gcm.Open(nil, nonce, ciphertext, id.aad())
	if err != nil {
		return "", ErrCannotDecrypt
	}
	defer Zero(plaintext)

	var payload Payload
	if err := json.Unmarshal(plaintext, &payload); err != nil {
		return "", ErrCannotDecrypt
	}
	if payload.Version != PayloadVersion {
		return "", fmt.Errorf("%w: unknown payload version %d", ErrMalformedEnvelope, payload.Version)
	}
	return payload.Password, nil
}

// verifierIdentity is the fixed identity used for the unlock verifier.
var verifierIdentity = Identity{ConnectionID: "master", Engine: "verifier"}

// SealVerifier encrypts a random probe so a later unlock can tell a wrong
// master password from corrupted connection data.
func SealVerifier(key []byte) ([]byte, error) {
	probe := make([]byte, 32)
	if _, err := rand.Read(probe); err != nil {
		return nil, fmt.Errorf("no source of randomness: %w", err)
	}
	return Seal(key, verifierIdentity, string(probe))
}

// CheckVerifier reports whether key opens the stored verifier.
func CheckVerifier(key, verifier []byte) error {
	_, err := Open(key, verifierIdentity, verifier)
	return err
}

func newGCM(key []byte) (cipher.AEAD, error) {
	if len(key) != KeyLen {
		return nil, fmt.Errorf("%w: key length %d", ErrUnsupportedKDF, len(key))
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("cipher setup failed: %w", err)
	}
	return cipher.NewGCM(block)
}

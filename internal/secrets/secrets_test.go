package secrets

import (
	"bytes"
	"errors"
	"strings"
	"testing"
)

// fastParams keep the tests quick. Production uses DefaultParams.
func fastParams() KDFParams {
	p := DefaultParams()
	p.Time = 1
	p.MemoryKiB = 8 * 1024
	return p
}

func testKey(t *testing.T, password string) []byte {
	t.Helper()
	salt := bytes.Repeat([]byte{7}, SaltLen)
	key, err := DeriveKey(password, salt, fastParams())
	if err != nil {
		t.Fatalf("DeriveKey: %v", err)
	}
	return key
}

var pgID = Identity{ConnectionID: "11111111-1111-1111-1111-111111111111", Engine: "postgres"}

func TestDeriveKeyIsDeterministicAndSaltDependent(t *testing.T) {
	saltA := bytes.Repeat([]byte{1}, SaltLen)
	saltB := bytes.Repeat([]byte{2}, SaltLen)

	k1, _ := DeriveKey("correct horse", saltA, fastParams())
	k2, _ := DeriveKey("correct horse", saltA, fastParams())
	k3, _ := DeriveKey("correct horse", saltB, fastParams())
	k4, _ := DeriveKey("wrong horse", saltA, fastParams())

	if !bytes.Equal(k1, k2) {
		t.Fatal("the same password and salt must derive the same key")
	}
	if len(k1) != KeyLen {
		t.Fatalf("key length = %d, want %d", len(k1), KeyLen)
	}
	if bytes.Equal(k1, k3) {
		t.Fatal("a different salt must derive a different key")
	}
	if bytes.Equal(k1, k4) {
		t.Fatal("a different password must derive a different key")
	}
}

func TestDeriveKeyRejectsWeakOrUnknownParameters(t *testing.T) {
	salt := bytes.Repeat([]byte{1}, SaltLen)
	cases := map[string]func(p *KDFParams){
		"unknown version":   func(p *KDFParams) { p.Version = 99 },
		"no passes":         func(p *KDFParams) { p.Time = 0 },
		"too little memory": func(p *KDFParams) { p.MemoryKiB = 64 },
		"no parallelism":    func(p *KDFParams) { p.Threads = 0 },
		"short key":         func(p *KDFParams) { p.KeyLen = 16 },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			p := fastParams()
			mutate(&p)
			if _, err := DeriveKey("pw", salt, p); !errors.Is(err, ErrUnsupportedKDF) {
				t.Fatalf("error = %v, want ErrUnsupportedKDF", err)
			}
		})
	}
}

func TestParamsRoundTrip(t *testing.T) {
	want := DefaultParams()
	data, err := want.Marshal()
	if err != nil {
		t.Fatalf("Marshal: %v", err)
	}
	got, err := UnmarshalParams(data)
	if err != nil {
		t.Fatalf("UnmarshalParams: %v", err)
	}
	if got != want {
		t.Fatalf("params = %+v, want %+v", got, want)
	}
	if _, err := UnmarshalParams([]byte("not json")); !errors.Is(err, ErrUnsupportedKDF) {
		t.Fatalf("error = %v, want ErrUnsupportedKDF", err)
	}
}

func TestSealOpenRoundTrip(t *testing.T) {
	key := testKey(t, "master")
	for _, password := range []string{"hunter2", "", "üñïçødé 🔐", strings.Repeat("x", 4096)} {
		sealed, err := Seal(key, pgID, password)
		if err != nil {
			t.Fatalf("Seal: %v", err)
		}
		got, err := Open(key, pgID, sealed)
		if err != nil {
			t.Fatalf("Open: %v", err)
		}
		if got != password {
			t.Fatalf("password round trip = %q, want %q", got, password)
		}
	}
}

func TestSealedBytesNeverContainThePlaintext(t *testing.T) {
	key := testKey(t, "master")
	sealed, err := Seal(key, pgID, "distinctive-password")
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	if bytes.Contains(sealed, []byte("distinctive-password")) {
		t.Fatal("the plaintext password appears in the sealed envelope")
	}
	if sealed[0] != EnvelopeVersion {
		t.Fatalf("envelope version byte = %d, want %d", sealed[0], EnvelopeVersion)
	}
}

func TestEverySealUsesAFreshNonce(t *testing.T) {
	key := testKey(t, "master")
	seen := make(map[string]struct{}, 64)
	for i := 0; i < 64; i++ {
		sealed, err := Seal(key, pgID, "same password every time")
		if err != nil {
			t.Fatalf("Seal: %v", err)
		}
		nonce := string(sealed[1 : 1+NonceLen])
		if _, dup := seen[nonce]; dup {
			t.Fatalf("nonce reused after %d seals", i)
		}
		seen[nonce] = struct{}{}
		if _, dup := seen[string(sealed)]; dup {
			t.Fatal("two seals of the same password produced identical ciphertext")
		}
	}
}

func TestOpenRejectsTampering(t *testing.T) {
	key := testKey(t, "master")
	sealed, err := Seal(key, pgID, "hunter2")
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}

	cases := map[string]func([]byte) []byte{
		"flipped nonce bit":      func(b []byte) []byte { c := clone(b); c[2] ^= 0x01; return c },
		"flipped ciphertext bit": func(b []byte) []byte { c := clone(b); c[len(c)-2] ^= 0x01; return c },
		"truncated tag":          func(b []byte) []byte { return clone(b)[:len(b)-1] },
		"appended byte":          func(b []byte) []byte { return append(clone(b), 0) },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := Open(key, pgID, mutate(sealed)); !errors.Is(err, ErrCannotDecrypt) {
				t.Fatalf("error = %v, want ErrCannotDecrypt", err)
			}
		})
	}
}

func TestOpenRejectsWrongKey(t *testing.T) {
	sealed, err := Seal(testKey(t, "master"), pgID, "hunter2")
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	if _, err := Open(testKey(t, "not the master"), pgID, sealed); !errors.Is(err, ErrCannotDecrypt) {
		t.Fatalf("error = %v, want ErrCannotDecrypt", err)
	}
}

func TestOpenRejectsACiphertextCopiedToAnotherConnection(t *testing.T) {
	key := testKey(t, "master")
	sealed, err := Seal(key, pgID, "hunter2")
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}

	otherID := Identity{ConnectionID: "22222222-2222-2222-2222-222222222222", Engine: "postgres"}
	if _, err := Open(key, otherID, sealed); !errors.Is(err, ErrCannotDecrypt) {
		t.Fatalf("a ciphertext moved to another connection opened: %v", err)
	}

	otherEngine := Identity{ConnectionID: pgID.ConnectionID, Engine: "redis"}
	if _, err := Open(key, otherEngine, sealed); !errors.Is(err, ErrCannotDecrypt) {
		t.Fatalf("a ciphertext reused under another engine opened: %v", err)
	}
}

func TestOpenRejectsMalformedEnvelopes(t *testing.T) {
	key := testKey(t, "master")
	cases := map[string][]byte{
		"empty":           {},
		"too short":       {EnvelopeVersion, 1, 2, 3},
		"unknown version": append([]byte{99}, bytes.Repeat([]byte{0}, 40)...),
	}
	for name, envelope := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := Open(key, pgID, envelope); !errors.Is(err, ErrMalformedEnvelope) {
				t.Fatalf("error = %v, want ErrMalformedEnvelope", err)
			}
		})
	}
}

func TestVerifier(t *testing.T) {
	key := testKey(t, "master")
	verifier, err := SealVerifier(key)
	if err != nil {
		t.Fatalf("SealVerifier: %v", err)
	}
	if err := CheckVerifier(key, verifier); err != nil {
		t.Fatalf("the correct key failed its own verifier: %v", err)
	}
	if err := CheckVerifier(testKey(t, "wrong"), verifier); !errors.Is(err, ErrCannotDecrypt) {
		t.Fatalf("error = %v, want ErrCannotDecrypt", err)
	}

	// Two installations must not share a verifier value.
	other, _ := SealVerifier(key)
	if bytes.Equal(verifier, other) {
		t.Fatal("verifiers must be random per installation")
	}
}

func TestSealRejectsAWrongLengthKey(t *testing.T) {
	if _, err := Seal(make([]byte, 16), pgID, "pw"); !errors.Is(err, ErrUnsupportedKDF) {
		t.Fatalf("error = %v, want ErrUnsupportedKDF", err)
	}
}

func TestZero(t *testing.T) {
	b := []byte("sensitive")
	Zero(b)
	if !bytes.Equal(b, make([]byte, len("sensitive"))) {
		t.Fatalf("Zero left %q", b)
	}
}

func clone(b []byte) []byte { return append([]byte(nil), b...) }

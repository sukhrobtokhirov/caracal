package postgres

import (
	"context"
	"crypto/x509"
	"errors"
	"fmt"
	"math"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

func TestEncodePreservesExactValues(t *testing.T) {
	ts := time.Date(2026, 8, 20, 12, 0, 0, 123456789, time.UTC)
	cases := []struct {
		name string
		in   any
		want any
	}{
		{"null stays null", nil, nil},
		{"bool stays bool", true, true},
		{"text stays text", "hello", "hello"},
		{"empty string is not null", "", ""},
		{"int8 beyond float64 precision", int64(math.MaxInt64), "9223372036854775807"},
		{"negative int8", int64(-9007199254740993), "-9007199254740993"},
		{"int4", int32(42), "42"},
		{"float8", float64(1.5), "1.5"},
		{"bytea is hex", []byte{0xde, 0xad, 0xbe, 0xef}, "\\xdeadbeef"},
		{"timestamp is rfc3339", ts, "2026-08-20T12:00:00.123456789Z"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := encode(tc.in); got != tc.want {
				t.Fatalf("encode(%#v) = %#v, want %#v", tc.in, got, tc.want)
			}
		})
	}
}

func TestEncodeNullIsDistinctFromEmptyString(t *testing.T) {
	if encode(nil) == encode("") {
		t.Fatal("NULL and the empty string must not encode identically")
	}
}

func TestTypeName(t *testing.T) {
	cases := map[uint32]string{16: "bool", 20: "int8", 23: "int4", 1700: "numeric", 999999: "oid:999999"}
	for oid, want := range cases {
		if got := typeName(oid); got != want {
			t.Errorf("typeName(%d) = %q, want %q", oid, got, want)
		}
	}
}

func TestClassify(t *testing.T) {
	cases := []struct {
		name     string
		err      error
		wantCode string
	}{
		{"nil", nil, ""},
		{"deadline", context.DeadlineExceeded, CodeQueryTimeout},
		{"cancel", context.Canceled, CodeCancelled},
		{"config", ErrConfig, CodeUnsupported},
		{"wrapped config", fmt.Errorf("building the pool: %w", ErrConfig), CodeUnsupported},
		{"sql error", &pgconn.PgError{Severity: "ERROR", Code: "42P01", Message: "relation does not exist"}, CodeQueryFailed},
		{"unknown", errors.New("some driver internal"), CodeUnavailable},
		{"bad password", &pgconn.PgError{Severity: "FATAL", Code: "28P01", Message: "password authentication failed"}, CodeAuthFailed},
		{"unknown authority", x509.UnknownAuthorityError{}, CodeTLSFailed},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := Classify(tc.err)
			if got.Code != tc.wantCode {
				t.Fatalf("code = %q, want %q", got.Code, tc.wantCode)
			}
			if strings.Contains(got.Message, "driver internal") {
				t.Fatalf("internal error text leaked: %q", got.Message)
			}
		})
	}
}

func TestClassifySurfacesServerErrorDetail(t *testing.T) {
	f := Classify(&pgconn.PgError{Severity: "ERROR", Code: "42601", Message: "syntax error at or near \"slect\""})
	for _, want := range []string{"ERROR", "42601", "syntax error"} {
		if !strings.Contains(f.Message, want) {
			t.Fatalf("message %q is missing %q", f.Message, want)
		}
	}
}

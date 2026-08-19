// Package connections holds the connection domain: value types, engine-aware
// defaults, and validation. It deliberately knows nothing about SQL storage,
// encryption, or live database clients.
package connections

import "time"

// Engine identifies the database a connection talks to.
type Engine string

const (
	EnginePostgres Engine = "postgres"
	EngineRedis    Engine = "redis"
)

// Environment tags how dangerous a connection is. It drives the UI's
// production treatment, so the set is closed.
type Environment string

const (
	EnvDev     Environment = "dev"
	EnvStaging Environment = "staging"
	EnvProd    Environment = "prod"
)

// TLSMode is the transport security requested for a connection.
type TLSMode string

const (
	TLSDisable    TLSMode = "disable"
	TLSRequire    TLSMode = "require"
	TLSVerifyFull TLSMode = "verify-full"
)

// Default ports per engine.
const (
	DefaultPostgresPort = 5432
	DefaultRedisPort    = 6379
)

// Config is the non-secret part of a connection: everything needed to dial a
// server except the password.
type Config struct {
	ID          string      `json:"id"`
	Name        string      `json:"name"`
	Engine      Engine      `json:"engine"`
	Host        string      `json:"host"`
	Port        int         `json:"port"`
	Database    string      `json:"database"`
	Username    string      `json:"username"`
	TLSMode     TLSMode     `json:"tlsMode"`
	Environment Environment `json:"environment"`
	ReadOnly    bool        `json:"readOnly"`
	Color       string      `json:"color"`
	CreatedAt   time.Time   `json:"createdAt"`
}

// Record is the stored form: a Config plus the sealed secret. It must never be
// serialized into an HTTP response.
type Record struct {
	Config
	SealedSecret []byte
}

// Summary is the safe API projection of a connection. It carries no secret, no
// sealed bytes, and no encryption metadata.
type Summary struct {
	Config
	// HasSecret reports whether a password is stored, without revealing it or
	// its length.
	HasSecret bool `json:"hasSecret"`
}

// Summarize projects a stored record into its safe API form.
func (r Record) Summarize() Summary {
	return Summary{Config: r.Config, HasSecret: len(r.SealedSecret) > 0}
}

// SecretInput carries an optional replacement password. A nil *SecretInput
// means "leave the stored secret unchanged"; Changed with an empty Value means
// "store an empty password", which is a different thing.
type SecretInput struct {
	Changed bool   `json:"changed"`
	Value   string `json:"value"`
}

// Input is the accepted request body for create and update.
//
// Port and TLSMode are pointers so the engine default can be applied when the
// caller omits them, without treating an explicit 0 or "" as absent.
type Input struct {
	Name        string       `json:"name"`
	Engine      Engine       `json:"engine"`
	Host        string       `json:"host"`
	Port        *int         `json:"port"`
	Database    string       `json:"database"`
	Username    string       `json:"username"`
	TLSMode     *TLSMode     `json:"tlsMode"`
	Environment *Environment `json:"environment"`
	ReadOnly    bool         `json:"readOnly"`
	Color       string       `json:"color"`
	Secret      *SecretInput `json:"secret"`
}

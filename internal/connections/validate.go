package connections

import (
	"fmt"
	"net"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

// ValidationError names the offending field so the UI can focus it.
type ValidationError struct {
	Field   string `json:"field"`
	Message string `json:"message"`
}

func (e ValidationError) Error() string { return e.Field + ": " + e.Message }

// ValidationErrors is the full set of problems with one input.
type ValidationErrors []ValidationError

func (e ValidationErrors) Error() string {
	parts := make([]string, len(e))
	for i, v := range e {
		parts[i] = v.Error()
	}
	return strings.Join(parts, "; ")
}

const (
	maxNameLen     = 100
	maxHostLen     = 255
	maxUsernameLen = 100
	maxDatabaseLen = 100
	maxRedisDBs    = 15 // Redis ships with databases 0..15
)

var (
	hostPattern  = regexp.MustCompile(`^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$`)
	colorPattern = regexp.MustCompile(`^#[0-9a-fA-F]{6}$`)
)

// Normalize applies engine defaults and trims whitespace. It runs before
// Validate so defaults are themselves validated.
func (in *Input) Normalize() {
	in.Name = strings.TrimSpace(in.Name)
	in.Host = strings.TrimSpace(in.Host)
	in.Database = strings.TrimSpace(in.Database)
	in.Username = strings.TrimSpace(in.Username)
	in.Color = strings.TrimSpace(in.Color)

	if in.Port == nil {
		p := DefaultPostgresPort
		if in.Engine == EngineRedis {
			p = DefaultRedisPort
		}
		in.Port = &p
	}
	if in.Environment == nil {
		e := EnvDev
		in.Environment = &e
	}
	if in.TLSMode == nil {
		m := TLSDisable
		in.TLSMode = &m
	}
	if in.Engine == EngineRedis && in.Database == "" {
		in.Database = "0"
	}
}

// Validate reports every problem with the input at once, so the form can show
// them together rather than one per round trip.
func (in Input) Validate() error {
	var errs ValidationErrors
	add := func(field, msg string) { errs = append(errs, ValidationError{Field: field, Message: msg}) }

	switch {
	case in.Name == "":
		add("name", "A name is required.")
	case utf8.RuneCountInString(in.Name) > maxNameLen:
		add("name", fmt.Sprintf("A name may be at most %d characters.", maxNameLen))
	}

	switch in.Engine {
	case EnginePostgres, EngineRedis:
	case "":
		add("engine", "An engine is required.")
	default:
		add("engine", "Only postgres and redis are supported.")
	}

	switch {
	case in.Host == "":
		add("host", "A host is required.")
	case len(in.Host) > maxHostLen:
		add("host", fmt.Sprintf("A host may be at most %d characters.", maxHostLen))
	case strings.Contains(in.Host, "://"):
		add("host", "Enter a host name or IP address without a URL scheme.")
	case strings.ContainsAny(in.Host, " \t/@?#"):
		add("host", "Enter a host name or IP address only.")
	case net.ParseIP(in.Host) == nil && !hostPattern.MatchString(in.Host):
		add("host", "This is not a valid host name or IP address.")
	}

	if in.Port == nil {
		add("port", "A port is required.")
	} else if *in.Port < 1 || *in.Port > 65535 {
		add("port", "A port must be between 1 and 65535.")
	}

	if in.Environment == nil {
		add("environment", "An environment is required.")
	} else {
		switch *in.Environment {
		case EnvDev, EnvStaging, EnvProd:
		default:
			add("environment", "An environment must be dev, staging, or prod.")
		}
	}

	if in.Color != "" && !colorPattern.MatchString(in.Color) {
		add("color", "A color must be a hex value such as #4c8dff.")
	}
	if utf8.RuneCountInString(in.Username) > maxUsernameLen {
		add("username", fmt.Sprintf("A username may be at most %d characters.", maxUsernameLen))
	}

	errs = append(errs, in.validateEngineFields()...)

	if len(errs) > 0 {
		return errs
	}
	return nil
}

func (in Input) validateEngineFields() ValidationErrors {
	var errs ValidationErrors
	add := func(field, msg string) { errs = append(errs, ValidationError{Field: field, Message: msg}) }

	switch in.Engine {
	case EnginePostgres:
		switch {
		case in.Database == "":
			add("database", "A database name is required.")
		case utf8.RuneCountInString(in.Database) > maxDatabaseLen:
			add("database", fmt.Sprintf("A database name may be at most %d characters.", maxDatabaseLen))
		}
		// Username is usually required, but some servers authenticate by
		// certificate or peer identity, so this stays a warning-free allowance.
		if in.TLSMode != nil {
			switch *in.TLSMode {
			case TLSDisable, TLSRequire, TLSVerifyFull:
			default:
				add("tlsMode", "A TLS mode must be disable, require, or verify-full.")
			}
		}
	case EngineRedis:
		if in.Database == "" {
			add("database", "A database index is required.")
		} else if idx, err := strconv.Atoi(in.Database); err != nil {
			add("database", "A Redis database must be an index such as 0.")
		} else if idx < 0 || idx > maxRedisDBs {
			add("database", fmt.Sprintf("A Redis database index must be between 0 and %d.", maxRedisDBs))
		}
		if in.TLSMode != nil {
			switch *in.TLSMode {
			case TLSDisable, TLSRequire:
			case TLSVerifyFull:
				add("tlsMode", "Redis supports disable and require in this version.")
			default:
				add("tlsMode", "A TLS mode must be disable or require.")
			}
		}
	}
	return errs
}

// ToConfig builds the stored config for an input that has already been
// normalized and validated. The caller supplies the identity and creation time.
func (in Input) ToConfig(id string) Config {
	return Config{
		ID:          id,
		Name:        in.Name,
		Engine:      in.Engine,
		Host:        in.Host,
		Port:        *in.Port,
		Database:    in.Database,
		Username:    in.Username,
		TLSMode:     *in.TLSMode,
		Environment: *in.Environment,
		ReadOnly:    in.ReadOnly,
		Color:       in.Color,
	}
}

// RedisDBIndex returns the numeric database index for a Redis connection.
func (c Config) RedisDBIndex() int {
	idx, err := strconv.Atoi(c.Database)
	if err != nil {
		return 0
	}
	return idx
}

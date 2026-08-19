package connections

import (
	"errors"
	"strings"
	"testing"
)

func pgInput() Input {
	return Input{
		Name: "prod db", Engine: EnginePostgres, Host: "db.internal",
		Database: "app", Username: "app_ro",
	}
}

func redisInput() Input {
	return Input{Name: "cache", Engine: EngineRedis, Host: "127.0.0.1"}
}

func fields(err error) map[string]string {
	var errs ValidationErrors
	if !errors.As(err, &errs) {
		return nil
	}
	out := make(map[string]string, len(errs))
	for _, e := range errs {
		out[e.Field] = e.Message
	}
	return out
}

func TestNormalizeAppliesEngineDefaults(t *testing.T) {
	pg := pgInput()
	pg.Normalize()
	if *pg.Port != DefaultPostgresPort {
		t.Errorf("postgres port = %d, want %d", *pg.Port, DefaultPostgresPort)
	}
	if pg.Database != "app" {
		t.Errorf("postgres database was rewritten to %q", pg.Database)
	}

	rd := redisInput()
	rd.Normalize()
	if *rd.Port != DefaultRedisPort {
		t.Errorf("redis port = %d, want %d", *rd.Port, DefaultRedisPort)
	}
	if rd.Database != "0" {
		t.Errorf("redis database = %q, want the default index 0", rd.Database)
	}
	if *rd.Environment != EnvDev {
		t.Errorf("environment = %q, want dev", *rd.Environment)
	}
	if *rd.TLSMode != TLSDisable {
		t.Errorf("tls mode = %q, want disable", *rd.TLSMode)
	}
}

func TestNormalizeTrimsWhitespaceButDoesNotInventValues(t *testing.T) {
	in := Input{Name: "  prod db  ", Engine: EnginePostgres, Host: " db.internal ",
		Database: " app ", Username: " app_ro ", Color: " #ff0000 "}
	in.Normalize()
	if in.Name != "prod db" || in.Host != "db.internal" || in.Database != "app" ||
		in.Username != "app_ro" || in.Color != "#ff0000" {
		t.Fatalf("trimming failed: %+v", in)
	}

	explicit := 15432
	withPort := pgInput()
	withPort.Port = &explicit
	withPort.Normalize()
	if *withPort.Port != explicit {
		t.Fatalf("an explicit port was overwritten with %d", *withPort.Port)
	}
}

func TestValidateAcceptsGoodInput(t *testing.T) {
	for name, in := range map[string]Input{"postgres": pgInput(), "redis": redisInput()} {
		t.Run(name, func(t *testing.T) {
			in.Normalize()
			if err := in.Validate(); err != nil {
				t.Fatalf("Validate: %v", err)
			}
		})
	}
}

func TestValidateRejectsBadHosts(t *testing.T) {
	cases := map[string]string{
		"empty":            "",
		"url scheme":       "postgres://db.internal",
		"with a path":      "db.internal/app",
		"with credentials": "user@db.internal",
		"with a space":     "db internal",
		"query string":     "db.internal?x=1",
	}
	for name, host := range cases {
		t.Run(name, func(t *testing.T) {
			in := pgInput()
			in.Host = host
			in.Normalize()
			if _, bad := fields(in.Validate())["host"]; !bad {
				t.Fatalf("host %q was accepted", host)
			}
		})
	}

	for _, host := range []string{"db.internal", "localhost", "127.0.0.1", "::1", "db-1.eu-west-1.rds.amazonaws.com"} {
		t.Run("accepts "+host, func(t *testing.T) {
			in := pgInput()
			in.Host = host
			in.Normalize()
			if msg, bad := fields(in.Validate())["host"]; bad {
				t.Fatalf("host %q was rejected: %s", host, msg)
			}
		})
	}
}

func TestValidatePortRange(t *testing.T) {
	for _, port := range []int{0, -1, 65536, 100000} {
		in := pgInput()
		in.Port = &port
		in.Normalize()
		if _, bad := fields(in.Validate())["port"]; !bad {
			t.Errorf("port %d was accepted", port)
		}
	}
	for _, port := range []int{1, 5432, 65535} {
		in := pgInput()
		in.Port = &port
		in.Normalize()
		if msg, bad := fields(in.Validate())["port"]; bad {
			t.Errorf("port %d was rejected: %s", port, msg)
		}
	}
}

func TestValidateEngine(t *testing.T) {
	for _, engine := range []Engine{"", "mysql", "MongoDB", "POSTGRES"} {
		in := pgInput()
		in.Engine = engine
		in.Normalize()
		if _, bad := fields(in.Validate())["engine"]; !bad {
			t.Errorf("engine %q was accepted", engine)
		}
	}
}

func TestValidateEnvironmentIsAClosedSet(t *testing.T) {
	for _, env := range []Environment{"production", "PROD", "test", ""} {
		in := pgInput()
		in.Environment = &env
		in.Normalize()
		if _, bad := fields(in.Validate())["environment"]; !bad {
			t.Errorf("environment %q was accepted", env)
		}
	}
	for _, env := range []Environment{EnvDev, EnvStaging, EnvProd} {
		in := pgInput()
		e := env
		in.Environment = &e
		in.Normalize()
		if msg, bad := fields(in.Validate())["environment"]; bad {
			t.Errorf("environment %q was rejected: %s", env, msg)
		}
	}
}

func TestPostgresRequiresADatabaseName(t *testing.T) {
	in := pgInput()
	in.Database = ""
	in.Normalize()
	if _, bad := fields(in.Validate())["database"]; !bad {
		t.Fatal("an empty PostgreSQL database name was accepted")
	}
}

func TestRedisDatabaseIsAnIndex(t *testing.T) {
	for _, db := range []string{"app", "-1", "16", "1.5", "0x0"} {
		in := redisInput()
		in.Database = db
		in.Normalize()
		if _, bad := fields(in.Validate())["database"]; !bad {
			t.Errorf("Redis database %q was accepted", db)
		}
	}
	for _, db := range []string{"0", "1", "15"} {
		in := redisInput()
		in.Database = db
		in.Normalize()
		if msg, bad := fields(in.Validate())["database"]; bad {
			t.Errorf("Redis database %q was rejected: %s", db, msg)
		}
	}
}

func TestTLSModesPerEngine(t *testing.T) {
	cases := []struct {
		engine  Engine
		mode    TLSMode
		wantBad bool
	}{
		{EnginePostgres, TLSDisable, false},
		{EnginePostgres, TLSRequire, false},
		{EnginePostgres, TLSVerifyFull, false},
		{EnginePostgres, TLSMode("prefer"), true},
		{EngineRedis, TLSDisable, false},
		{EngineRedis, TLSRequire, false},
		// verify-full is deferred for Redis in the MVP.
		{EngineRedis, TLSVerifyFull, true},
	}
	for _, tc := range cases {
		t.Run(string(tc.engine)+"/"+string(tc.mode), func(t *testing.T) {
			in := pgInput()
			if tc.engine == EngineRedis {
				in = redisInput()
			}
			mode := tc.mode
			in.TLSMode = &mode
			in.Normalize()
			_, bad := fields(in.Validate())["tlsMode"]
			if bad != tc.wantBad {
				t.Fatalf("rejected = %v, want %v", bad, tc.wantBad)
			}
		})
	}
}

func TestValidateColor(t *testing.T) {
	for _, color := range []string{"red", "#fff", "#GGGGGG", "ff0000"} {
		in := pgInput()
		in.Color = color
		in.Normalize()
		if _, bad := fields(in.Validate())["color"]; !bad {
			t.Errorf("color %q was accepted", color)
		}
	}
	for _, color := range []string{"", "#ff0000", "#4C8DFF"} {
		in := pgInput()
		in.Color = color
		in.Normalize()
		if msg, bad := fields(in.Validate())["color"]; bad {
			t.Errorf("color %q was rejected: %s", color, msg)
		}
	}
}

func TestValidateReportsEveryProblemAtOnce(t *testing.T) {
	in := Input{Engine: "mysql", Host: "http://db", Color: "red"}
	in.Normalize()
	got := fields(in.Validate())
	for _, want := range []string{"name", "engine", "host", "color"} {
		if _, ok := got[want]; !ok {
			t.Errorf("no error for %q (got %v)", want, got)
		}
	}
}

func TestValidationErrorMessagesAreUserFacing(t *testing.T) {
	in := Input{}
	in.Normalize()
	var errs ValidationErrors
	if !errors.As(in.Validate(), &errs) {
		t.Fatal("expected validation errors")
	}
	for _, e := range errs {
		if e.Message == "" || !strings.HasSuffix(e.Message, ".") {
			t.Errorf("field %q has an unpolished message: %q", e.Field, e.Message)
		}
		if strings.Contains(strings.ToLower(e.Message), "nil") {
			t.Errorf("field %q leaks implementation detail: %q", e.Field, e.Message)
		}
	}
}

func TestToConfigCarriesEveryField(t *testing.T) {
	in := pgInput()
	env := EnvProd
	tls := TLSVerifyFull
	in.Environment, in.TLSMode = &env, &tls
	in.ReadOnly, in.Color = true, "#ff0000"
	in.Normalize()

	cfg := in.ToConfig("id-1")
	if cfg.ID != "id-1" || cfg.Name != "prod db" || cfg.Engine != EnginePostgres ||
		cfg.Host != "db.internal" || cfg.Port != DefaultPostgresPort || cfg.Database != "app" ||
		cfg.Username != "app_ro" || cfg.TLSMode != TLSVerifyFull || cfg.Environment != EnvProd ||
		!cfg.ReadOnly || cfg.Color != "#ff0000" {
		t.Fatalf("config = %+v", cfg)
	}
}

func TestRedisDBIndex(t *testing.T) {
	cases := map[string]int{"0": 0, "7": 7, "": 0, "not a number": 0}
	for db, want := range cases {
		cfg := Config{Database: db}
		if got := cfg.RedisDBIndex(); got != want {
			t.Errorf("RedisDBIndex(%q) = %d, want %d", db, got, want)
		}
	}
}

func TestSummarizeNeverCarriesTheSealedSecret(t *testing.T) {
	rec := Record{Config: Config{ID: "id-1"}, SealedSecret: []byte{1, 2, 3}}
	summary := rec.Summarize()
	if !summary.HasSecret {
		t.Fatal("HasSecret should be true when a sealed secret exists")
	}
	empty := Record{Config: Config{ID: "id-2"}}
	if empty.Summarize().HasSecret {
		t.Fatal("HasSecret should be false with no sealed secret")
	}
}

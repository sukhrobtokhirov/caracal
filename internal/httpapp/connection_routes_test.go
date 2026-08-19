package httpapp

import (
	"net/http"
	"strings"
	"testing"

	"github.com/stohirov/database-ide/internal/manager"
)

func pgBody(name string) map[string]any {
	return map[string]any{
		"name": name, "engine": "postgres", "host": "db.internal", "port": 5432,
		"database": "app", "username": "app_ro", "tlsMode": "require",
		"environment": "prod", "readOnly": true, "color": "#ff5555",
		"secret": map[string]any{"changed": true, "value": "hunter2"},
	}
}

func createConnection(t *testing.T, h http.Handler, body map[string]any) manager.View {
	t.Helper()
	rec := do(t, h, http.MethodPost, "/api/connections", body)
	if rec.Code != http.StatusCreated {
		t.Fatalf("create status = %d, want 201 (body=%s)", rec.Code, rec.Body.String())
	}
	var view manager.View
	decodeBody(t, rec, &view)
	return view
}

func TestConnectionRoutesRequireUnlocking(t *testing.T) {
	h := testHandler(t) // set up, but never unlocked

	cases := []struct {
		method string
		target string
		body   map[string]any
	}{
		{http.MethodGet, "/api/connections", nil},
		{http.MethodPost, "/api/connections", pgBody("x")},
		{http.MethodPut, "/api/connections/abc", pgBody("x")},
		{http.MethodDelete, "/api/connections/abc", nil},
		{http.MethodPost, "/api/connections/abc/test", nil},
		{http.MethodPost, "/api/connections/abc/open", nil},
		{http.MethodPost, "/api/connections/abc/close", nil},
	}
	for _, tc := range cases {
		t.Run(tc.method+" "+tc.target, func(t *testing.T) {
			rec := do(t, h, tc.method, tc.target, tc.body)
			if rec.Code != http.StatusLocked {
				t.Fatalf("status = %d, want 423 (body=%s)", rec.Code, rec.Body.String())
			}
			if got := decodeError(t, rec.Body.String()).Code; got != CodeLocked {
				t.Fatalf("code = %q, want %q", got, CodeLocked)
			}
		})
	}
}

func TestCreateListAndDelete(t *testing.T) {
	h := unlockedHandler(t)

	created := createConnection(t, h, pgBody("prod db"))
	if created.Name != "prod db" || created.Engine != "postgres" || !created.ReadOnly {
		t.Fatalf("created = %+v", created.Summary)
	}
	if created.Environment != "prod" {
		t.Fatalf("environment = %q", created.Environment)
	}
	if created.Runtime.Status != "closed" {
		t.Fatalf("runtime status = %q, want closed", created.Runtime.Status)
	}

	var list struct {
		Connections []manager.View `json:"connections"`
	}
	decodeBody(t, do(t, h, http.MethodGet, "/api/connections", nil), &list)
	if len(list.Connections) != 1 || list.Connections[0].ID != created.ID {
		t.Fatalf("list = %+v", list.Connections)
	}

	rec := do(t, h, http.MethodDelete, "/api/connections/"+created.ID, nil)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("delete status = %d, want 204", rec.Code)
	}
	if rec.Body.Len() != 0 {
		t.Fatalf("delete returned a body: %s", rec.Body.String())
	}

	decodeBody(t, do(t, h, http.MethodGet, "/api/connections", nil), &list)
	if len(list.Connections) != 0 {
		t.Fatalf("connections after delete = %+v", list.Connections)
	}
}

func TestNoConnectionResponseContainsASecret(t *testing.T) {
	h := unlockedHandler(t)

	rec := do(t, h, http.MethodPost, "/api/connections", pgBody("prod db"))
	assertNoSecret(t, rec.Body.String())

	var created manager.View
	decodeBody(t, rec, &created)

	for _, target := range []string{"/api/connections"} {
		assertNoSecret(t, do(t, h, http.MethodGet, target, nil).Body.String())
	}
	assertNoSecret(t, do(t, h, http.MethodPut, "/api/connections/"+created.ID, pgBody("prod db")).Body.String())
}

func assertNoSecret(t *testing.T, body string) {
	t.Helper()
	for _, forbidden := range []string{"hunter2", "secretSealed", "sealed", "\"secret\""} {
		if strings.Contains(body, forbidden) {
			t.Fatalf("the response contains %q: %s", forbidden, body)
		}
	}
}

func TestValidationErrorsNameTheirFields(t *testing.T) {
	h := unlockedHandler(t)
	body := pgBody("")
	body["host"] = "postgres://db.internal"
	body["port"] = 0
	body["database"] = ""

	rec := do(t, h, http.MethodPost, "/api/connections", body)
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want 422 (body=%s)", rec.Code, rec.Body.String())
	}
	apiErr := decodeError(t, rec.Body.String())
	if apiErr.Code != CodeValidation {
		t.Fatalf("code = %q, want %q", apiErr.Code, CodeValidation)
	}
	fields, ok := apiErr.Details["fields"].([]any)
	if !ok || len(fields) == 0 {
		t.Fatalf("details = %+v, want a fields array", apiErr.Details)
	}
	named := map[string]bool{}
	for _, raw := range fields {
		entry, ok := raw.(map[string]any)
		if !ok {
			t.Fatalf("field entry = %#v", raw)
		}
		named[entry["field"].(string)] = true
		if entry["message"] == "" {
			t.Fatalf("field %v has no message", entry["field"])
		}
	}
	for _, want := range []string{"name", "host", "port", "database"} {
		if !named[want] {
			t.Errorf("no validation error for %q (got %v)", want, named)
		}
	}
}

func TestDuplicateNameIsAConflict(t *testing.T) {
	h := unlockedHandler(t)
	createConnection(t, h, pgBody("same"))

	rec := do(t, h, http.MethodPost, "/api/connections", pgBody("same"))
	if rec.Code != http.StatusConflict {
		t.Fatalf("status = %d, want 409", rec.Code)
	}
	if got := decodeError(t, rec.Body.String()).Code; got != CodeDuplicateName {
		t.Fatalf("code = %q, want %q", got, CodeDuplicateName)
	}
}

func TestUpdateWithoutASecretPreservesIt(t *testing.T) {
	h := unlockedHandler(t)
	created := createConnection(t, h, pgBody("prod db"))

	body := pgBody("prod db")
	delete(body, "secret")
	body["color"] = "#00ff00"

	rec := do(t, h, http.MethodPut, "/api/connections/"+created.ID, body)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body=%s)", rec.Code, rec.Body.String())
	}
	var updated manager.View
	decodeBody(t, rec, &updated)
	if updated.Color != "#00ff00" {
		t.Fatalf("color = %q", updated.Color)
	}
	if !updated.HasSecret {
		t.Fatal("the stored secret must survive an update that omits it")
	}
}

func TestOperationsOnAMissingConnection(t *testing.T) {
	h := unlockedHandler(t)
	cases := []struct {
		method string
		target string
		body   map[string]any
	}{
		{http.MethodPut, "/api/connections/ghost", pgBody("x")},
		{http.MethodDelete, "/api/connections/ghost", nil},
		{http.MethodPost, "/api/connections/ghost/test", nil},
		{http.MethodPost, "/api/connections/ghost/open", nil},
		{http.MethodPost, "/api/connections/ghost/close", nil},
	}
	for _, tc := range cases {
		t.Run(tc.method+" "+tc.target, func(t *testing.T) {
			rec := do(t, h, tc.method, tc.target, tc.body)
			if rec.Code != http.StatusNotFound {
				t.Fatalf("status = %d, want 404 (body=%s)", rec.Code, rec.Body.String())
			}
		})
	}
}

func TestTestAndOpenReportSafeFailures(t *testing.T) {
	h := unlockedHandler(t)
	body := pgBody("unreachable")
	body["host"], body["port"], body["tlsMode"] = "127.0.0.1", 1, "disable"
	created := createConnection(t, h, body)

	for _, action := range []string{"test", "open"} {
		t.Run(action, func(t *testing.T) {
			rec := do(t, h, http.MethodPost, "/api/connections/"+created.ID+"/"+action, nil)
			if rec.Code == http.StatusOK {
				t.Skip("something is listening on port 1")
			}
			if rec.Code != http.StatusBadGateway {
				t.Fatalf("status = %d, want 502 (body=%s)", rec.Code, rec.Body.String())
			}
			apiErr := decodeError(t, rec.Body.String())
			if apiErr.Message == "" {
				t.Fatal("a failure must carry a user-facing message")
			}
			for _, leak := range []string{"hunter2", "dial tcp", "127.0.0.1:1"} {
				if strings.Contains(apiErr.Message, leak) {
					t.Fatalf("the message leaks %q: %q", leak, apiErr.Message)
				}
			}
		})
	}
}

func TestCloseIsIdempotent(t *testing.T) {
	h := unlockedHandler(t)
	created := createConnection(t, h, pgBody("never opened"))

	for i := 0; i < 2; i++ {
		rec := do(t, h, http.MethodPost, "/api/connections/"+created.ID+"/close", nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("close status = %d (body=%s)", rec.Code, rec.Body.String())
		}
		var view manager.View
		decodeBody(t, rec, &view)
		if view.Runtime.Status != "closed" {
			t.Fatalf("status = %q, want closed", view.Runtime.Status)
		}
	}
}

func TestRedisDefaultsAreAppliedOnCreate(t *testing.T) {
	h := unlockedHandler(t)
	created := createConnection(t, h, map[string]any{
		"name": "cache", "engine": "redis", "host": "127.0.0.1",
	})
	if created.Port != 6379 || created.Database != "0" || created.Environment != "dev" {
		t.Fatalf("defaults not applied: %+v", created.Summary)
	}
	if created.HasSecret {
		t.Fatal("no password was supplied, so none should be stored")
	}
}

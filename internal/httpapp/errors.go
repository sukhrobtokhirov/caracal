package httpapp

import (
	"encoding/json"
	"log/slog"
	"net/http"
)

// APIError is the stable machine-readable error envelope used by every API
// route. See docs/mvp-steps/README.md ("Suggested error envelope").
type APIError struct {
	Code    string         `json:"code"`
	Message string         `json:"message"`
	Details map[string]any `json:"details,omitempty"`
}

type errorEnvelope struct {
	Error APIError `json:"error"`
}

// Error codes. Keep these stable: the frontend switches on them.
const (
	CodeUnauthorized  = "unauthorized"
	CodeForbidden     = "forbidden_origin"
	CodeNotFound      = "not_found"
	CodeMethod        = "method_not_allowed"
	CodeBadRequest    = "bad_request"
	CodeDBUnavailable = "database_unavailable"
	CodeQueryFailed   = "query_failed"
	CodeQueryTimeout  = "query_timeout"
	CodeInternal      = "internal_error"
)

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		slog.Error("failed to write json response", "component", "httpapp", "error", err.Error())
	}
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, errorEnvelope{Error: APIError{Code: code, Message: message}})
}

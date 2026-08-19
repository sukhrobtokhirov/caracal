package httpapp

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"github.com/stohirov/database-ide/internal/connections"
	"github.com/stohirov/database-ide/internal/manager"
	"github.com/stohirov/database-ide/internal/registry"
	"github.com/stohirov/database-ide/internal/secrets"
)

// Additional error codes returned by the connection API.
const (
	CodeLocked         = "locked"
	CodeAlreadySetUp   = "already_set_up"
	CodeNotSetUp       = "not_set_up"
	CodeWrongPassword  = "wrong_master_password"
	CodeWeakPassword   = "weak_master_password"
	CodeTooManyTries   = "too_many_attempts"
	CodeValidation     = "validation_failed"
	CodeDuplicateName  = "duplicate_name"
	CodeSecretSealed   = "secret_unreadable"
	CodeNotOpen        = "connection_unavailable"
	CodeWrongEngine    = "wrong_engine"
	CodeUnsupportedCfg = "unsupported_configuration"
)

// writeAPIError converts a domain error into the JSON envelope, choosing a
// status and a message that is safe to display.
//
// Anything unrecognized becomes a generic 500: an unexpected error's text may
// contain storage paths or driver internals, so it is logged, not returned.
func writeAPIError(w http.ResponseWriter, err error) {
	var validation connections.ValidationErrors
	var tooMany secrets.ErrTooManyAttempts
	var failure manager.OperationFailure

	switch {
	case errors.As(err, &validation):
		writeJSON(w, http.StatusUnprocessableEntity, errorEnvelope{Error: APIError{
			Code:    CodeValidation,
			Message: "Some fields need attention.",
			Details: map[string]any{"fields": validation},
		}})

	case errors.Is(err, manager.ErrLocked):
		writeError(w, http.StatusLocked, CodeLocked,
			"The application is locked. Enter the master password to continue.")

	case errors.Is(err, secrets.ErrAlreadySetUp):
		writeError(w, http.StatusConflict, CodeAlreadySetUp, "A master password has already been set.")

	case errors.Is(err, secrets.ErrNotSetUp):
		writeError(w, http.StatusConflict, CodeNotSetUp, "No master password has been set yet.")

	case errors.Is(err, secrets.ErrWrongPassword):
		writeError(w, http.StatusUnauthorized, CodeWrongPassword, "The master password is incorrect.")

	case errors.Is(err, secrets.ErrWeakPassword):
		writeError(w, http.StatusUnprocessableEntity, CodeWeakPassword, secrets.ErrWeakPassword.Error()+".")

	case errors.As(err, &tooMany):
		writeJSON(w, http.StatusTooManyRequests, errorEnvelope{Error: APIError{
			Code:    CodeTooManyTries,
			Message: "Too many failed attempts. Wait before trying again.",
			Details: map[string]any{"retryAfterSeconds": int(tooMany.RetryAfter.Round(1e9).Seconds())},
		}})

	case errors.Is(err, manager.ErrNotFound):
		writeError(w, http.StatusNotFound, CodeNotFound, "That connection does not exist.")

	case errors.Is(err, manager.ErrDuplicateName):
		writeError(w, http.StatusConflict, CodeDuplicateName, "A connection with that name already exists.")

	case errors.Is(err, manager.ErrSecretUnreadable):
		writeError(w, http.StatusConflict, CodeSecretSealed,
			"This saved credential cannot be decrypted. Enter the password again to replace it.")

	case errors.Is(err, registry.ErrNotOpen):
		writeError(w, http.StatusConflict, CodeNotOpen, "The connection is not open.")

	case errors.Is(err, registry.ErrWrongEngine):
		writeError(w, http.StatusConflict, CodeWrongEngine,
			"That operation does not apply to this connection's engine.")

	case errors.As(err, &failure):
		writeError(w, http.StatusBadGateway, failure.Code, failure.Message)

	default:
		slog.Error("unhandled API error", "component", "httpapp", "error", err.Error())
		writeError(w, http.StatusInternalServerError, CodeInternal, "Something went wrong.")
	}
}

// decodeJSON reads a bounded JSON body and rejects unknown fields, so a typo in
// a client request fails loudly instead of silently doing nothing.
func decodeJSON(w http.ResponseWriter, r *http.Request, dst any) bool {
	const maxBody = 1 << 20 // 1 MiB
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		writeError(w, http.StatusBadRequest, CodeBadRequest, "The request body could not be read.")
		return false
	}
	return true
}

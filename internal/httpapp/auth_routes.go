package httpapp

import (
	"net/http"

	"github.com/stohirov/database-ide/internal/secrets"
)

type authStatusResponse struct {
	State secrets.State `json:"state"`
	// MinPasswordLength lets the setup form state the rule before submitting.
	MinPasswordLength int `json:"minPasswordLength"`
}

type passwordRequest struct {
	Password string `json:"password"`
}

func (c Config) handleAuthStatus(w http.ResponseWriter, r *http.Request) {
	state, err := c.Manager.AuthState(r.Context())
	if err != nil {
		writeAPIError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, authStatusResponse{State: state, MinPasswordLength: secrets.MinPasswordLen})
}

func (c Config) handleAuthSetup(w http.ResponseWriter, r *http.Request) {
	var req passwordRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if err := c.Manager.Setup(r.Context(), req.Password); err != nil {
		writeAPIError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, authStatusResponse{
		State:             secrets.StateUnlocked,
		MinPasswordLength: secrets.MinPasswordLen,
	})
}

func (c Config) handleAuthUnlock(w http.ResponseWriter, r *http.Request) {
	var req passwordRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if err := c.Manager.Unlock(r.Context(), req.Password); err != nil {
		writeAPIError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, authStatusResponse{
		State:             secrets.StateUnlocked,
		MinPasswordLength: secrets.MinPasswordLen,
	})
}

func (c Config) handleAuthLock(w http.ResponseWriter, _ *http.Request) {
	c.Manager.Lock()
	writeJSON(w, http.StatusOK, authStatusResponse{
		State:             secrets.StateLocked,
		MinPasswordLength: secrets.MinPasswordLen,
	})
}

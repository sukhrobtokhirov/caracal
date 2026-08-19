package httpapp

import (
	"net/http"

	"github.com/go-chi/chi/v5"

	"github.com/stohirov/database-ide/internal/connections"
)

func (c Config) handleListConnections(w http.ResponseWriter, r *http.Request) {
	views, err := c.Manager.List(r.Context())
	if err != nil {
		writeAPIError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"connections": views})
}

func (c Config) handleCreateConnection(w http.ResponseWriter, r *http.Request) {
	var in connections.Input
	if !decodeJSON(w, r, &in) {
		return
	}
	view, err := c.Manager.Create(r.Context(), in)
	if err != nil {
		writeAPIError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, view)
}

func (c Config) handleUpdateConnection(w http.ResponseWriter, r *http.Request) {
	var in connections.Input
	if !decodeJSON(w, r, &in) {
		return
	}
	view, err := c.Manager.Update(r.Context(), chi.URLParam(r, "id"), in)
	if err != nil {
		writeAPIError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, view)
}

func (c Config) handleDeleteConnection(w http.ResponseWriter, r *http.Request) {
	if err := c.Manager.Delete(r.Context(), chi.URLParam(r, "id")); err != nil {
		writeAPIError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (c Config) handleTestConnection(w http.ResponseWriter, r *http.Request) {
	result, err := c.Manager.Test(r.Context(), chi.URLParam(r, "id"))
	if err != nil {
		writeAPIError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (c Config) handleOpenConnection(w http.ResponseWriter, r *http.Request) {
	view, err := c.Manager.Open(r.Context(), chi.URLParam(r, "id"))
	if err != nil {
		writeAPIError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, view)
}

func (c Config) handleCloseConnection(w http.ResponseWriter, r *http.Request) {
	view, err := c.Manager.Close(r.Context(), chi.URLParam(r, "id"))
	if err != nil {
		writeAPIError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, view)
}
